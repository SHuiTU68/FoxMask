package com.topjohnwu.magisk.ui.kpatch

import android.content.Context
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import java.io.File

/**
 * KernelPatch Shell 工具类。
 *
 * 负责提供执行 kptools（boot 修补/KPM 嵌入）和 kpcall（运行时 KPM 管理）的接口。
 *
 * superkey 已剥离：上游 KernelPatch 不再做强 superkey 校验，root 调用者
 * 固定使用 "su"（root-skey 模式），kptools -p 不传 -s，kpcall 也不传 key 参数。
 *
 * 二进制执行策略（对齐 Magisk 执行 magiskboot/busybox 的做法）：
 * - kptools 作为 libkptools.so 打包进 jniLibs，安装后位于 nativeLibraryDir，
 *   拥有 apk_data_file 上下文（多数设备可执行）。
 * - 若设备 noDataExec（如 Samsung，/data 不可执行），则把 kptools 复制到
 *   $MAGISKTMP（magisk tmpfs，root 上下文，必可执行）再执行。
 * - kpimg 作为数据文件从 assets 复制到 tmpfs 工作目录。
 */
object KpatchShell {

    /** 修补结果：含成功标志与执行日志，便于失败时排查 */
    data class PatchResult(val success: Boolean, val log: String)

    /** 内置打包的 KernelPatch 版本（与 assets 中的 kpimg 对应） */
    const val PACKED_KP_VERSION = "0.13.2"

    private const val KP_DIR_NAME = "kpatch"
    private const val KPTOOLS_LIB_NAME = "libkptools.so"

    /** kpimg 本地存放目录（app data，仅作为数据文件，不需要可执行） */
    private fun kpatchDir(context: Context): String =
        "${context.filesDir.absolutePath}/$KP_DIR_NAME"

    /** kpimg 本地路径 */
    private fun kpimgPath(context: Context): String = "${kpatchDir(context)}/kpimg"

    /**
     * 从 assets 复制 kpimg 到本地目录。幂等。
     * kptools 不再放 assets，改走 jniLibs（libkptools.so）。
     */
    fun ensureBinaries(context: Context): Boolean {
        val dir = File(kpatchDir(context))
        if (!dir.exists()) dir.mkdirs()
        val kpimg = File(kpimgPath(context))
        if (!kpimg.exists() || kpimg.length() == 0L) {
            context.assets.open("kpatch/kpimg").use { input ->
                kpimg.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return kpimg.exists()
    }

    /**
     * 取 kptools 可执行文件路径。
     * - 多数设备：直接用 nativeLibraryDir/libkptools.so（apk_data_file 上下文）。
     * - noDataExec 设备（/data 不可执行，如 Samsung）：复制到 $MAGISKTMP/kptools 再用。
     * 返回值是 shell 表达式（可能含 $MAGISKTMP 变量），供拼接到命令行。
     */
    private fun kptoolsExec(context: Context): String {
        val libPath = File(context.applicationInfo.nativeLibraryDir, KPTOOLS_LIB_NAME).absolutePath
        return if (Info.noDataExec) {
            // 复制到 $MAGISKTMP（root tmpfs，必可执行），用 shell 变量延迟展开
            Shell.cmd(
                "cp -af '$libPath' \"\$MAGISKTMP/kptools\"",
                "chmod 755 \"\$MAGISKTMP/kptools\"",
            ).exec()
            "\"\$MAGISKTMP/kptools\""
        } else {
            "'$libPath'"
        }
    }

    /**
     * 在 [Const.TMPDIR] 下准备 kpatch 数据工作目录：
     * - 复制 kpimg 和任意额外输入文件到 tmpfs
     * - 返回工作目录路径（已存在）
     *
     * 工作目录用 tmpfs 是为了确保 root shell 能读取这些文件
     * （app_data_file 在某些设备上 root 也可能读取受限）。
     */
    private fun prepareTmpWorkspace(context: Context, extraFiles: Map<String, String>): String {
        val tmp = Const.TMPDIR
        val workDir = "$tmp/kpatch_work"
        val kpimgSrc = kpimgPath(context)
        val cmds = mutableListOf(
            "rm -rf '$workDir'",
            "mkdir -p '$workDir'",
            "cp '$kpimgSrc' '$workDir/kpimg'",
        )
        // extraFiles: 目标文件名 → 源路径（app data 中的路径）
        extraFiles.forEach { (destName, srcPath) ->
            cmds.add("cp '$srcPath' '$workDir/$destName'")
        }
        Shell.cmd(*cmds.toTypedArray()).exec()
        return workDir
    }

    /**
     * 检测 KernelPatch 是否已安装（内核已修补）。
     * 通过 kpcall hello 检测（固定 root-skey 模式，无需 superkey）。
     * @return true 如果 kpatch 已安装
     */
    fun isKpatchInstalled(): Boolean {
        val result = Shell.cmd("magisk kpcall hello").exec()
        return result.isSuccess && result.out.any { it.contains("installed") }
    }

    /**
     * 获取 KernelPatch 版本。
     * @return 版本字符串（如 "0.13.2"），失败返回 null
     */
    fun getKpatchVersion(): String? {
        val result = Shell.cmd("magisk kpcall kp-version").exec()
        if (result.isSuccess && result.out.isNotEmpty()) {
            return result.out[0].trim()
        }
        return null
    }

    /**
     * 修补 boot 镜像，嵌入 KernelPatch。
     *
     * 对齐官方 APatch boot_patch.sh 流程：
     *   1. kptools unpack boot.img      → 抽取裸内核 kernel
     *   2. kptools -p -i kernel.ori -k kpimg -o kernel   → 修补裸内核
     *   3. kptools repack boot.img      → 生成 new-boot.img
     *
     * 注意：kptools -p 只接受裸内核（或 UNCOMPRESSED_IMG 头），不能直接处理 Android boot.img。
     * superkey 已剥离：不传 -s，固定走 root-skey 模式。
     *
     * @param context 上下文
     * @param bootImgPath 原始 boot.img 路径
     * @param outputPath 修补后输出路径
     * @param onLog 实时日志回调，每输出一行调用一次（用于 UI 可视化）
     * @return 修补结果（含成功标志与日志，便于排查失败原因）
     */
    fun patchBoot(
        context: Context,
        bootImgPath: String,
        outputPath: String,
        onLog: (String) -> Unit = {},
    ): PatchResult {
        if (!ensureBinaries(context)) {
            onLog("ensureBinaries failed")
            return PatchResult(false, "ensureBinaries failed")
        }

        // 实时日志回调列表（stdout + stderr 都走这里）
        val logCallback = object : CallbackList<String>() {
            override fun onAddElement(e: String?) {
                e?.let { onLog(it) }
            }
        }

        val kptools = kptoolsExec(context)
        // 工作目录用 /dev/tmp（tmpfs，root 上下文），放 kpimg/boot.img 等数据文件
        val workDir = prepareTmpWorkspace(context, mapOf("boot.img" to bootImgPath))
        val kpimg = "$workDir/kpimg"

        // 对齐 APatch boot_patch.sh 的输出风格：步骤标记 + 星号横线
        // 依次执行 unpack → patch 裸内核 → repack（不传 -s，走 root-skey 模式）
        val cmds = arrayOf(
            "echo '****************************'",
            "echo ' FoxMask Boot Image Patcher'",
            "echo '****************************'",
            "cd '$workDir'",
            "rm -f kernel kernel.ori new-boot.img",
            // 1. unpack：从 boot.img 抽取裸内核到当前目录 kernel
            "echo '- Unpacking boot image'",
            "$kptools unpack 'boot.img'",
            // 2. 保留原始内核备份
            "mv kernel kernel.ori",
            // 3. patch 裸内核
            "echo '- Patching kernel'",
            "$kptools -p -i kernel.ori -k '$kpimg' -o kernel",
            // 4. repack：用修补后的 kernel 重新打包成 new-boot.img
            "echo '- Repacking boot image'",
            "$kptools repack 'boot.img'",
        )
        val result = Shell.newJob().add(*cmds).to(logCallback, logCallback).exec()

        val sb = StringBuilder()
        result.out.forEach { sb.appendLine(it) }
        result.err.forEach { sb.appendLine("[err] $it") }
        val log = sb.toString()

        // 从 tmpfs 把 new-boot.img 拷回 outputPath（app data 或 cache 目录）
        val fetch = Shell.cmd("cp '$workDir/new-boot.img' '$outputPath' && echo OK")
            .to(logCallback, logCallback).exec()
        val ok = result.isSuccess &&
            fetch.out.any { it.contains("OK") } &&
            File(outputPath).exists() &&
            File(outputPath).length() > 0
        if (ok) {
            onLog("- Successfully Patched!")
            onLog(" Output file is written to $outputPath")
            onLog("****************************")
        } else {
            onLog("- Patch failed! (code=${result.code})")
            onLog("****************************")
        }
        // 清理 tmpfs 工作目录
        Shell.cmd("rm -rf '$workDir'").submit()
        return if (ok) PatchResult(true, log) else PatchResult(false, "$log\nfetch output failed")
    }

    /**
     * 检查 boot 镜像是否已修补 kpatch。
     * @param context 上下文
     * @param bootImgPath boot.img 路径
     * @return true 如果已修补
     */
    fun isBootPatched(context: Context, bootImgPath: String): Boolean {
        if (!ensureBinaries(context)) return false
        val kptools = kptoolsExec(context)
        val workDir = prepareTmpWorkspace(context, mapOf("boot.img" to bootImgPath))
        val result = Shell.cmd(
            "cd '$workDir'",
            "$kptools -l -i 'boot.img'",
        ).exec()
        Shell.cmd("rm -rf '$workDir'").submit()
        return result.isSuccess && result.out.any { it.contains("patched=") }
    }

    /**
     * 嵌入 KPM 模块到已修补 kpatch 的 boot 镜像。
     *
     * 同样遵循 unpack → patch（-M 嵌入）→ repack 流程，因为 kptools -p 只接受裸内核。
     *
     * @param context 上下文
     * @param bootImgPath 已修补 kpatch 的 boot.img 路径
     * @param kpmPath .kpm 文件路径
     * @param kpmName 模块名称
     * @param outputPath 输出路径
     * @param onLog 实时日志回调，每输出一行调用一次（用于 UI 可视化）
     * @return 嵌入结果（含日志）
     */
    fun embedKpm(
        context: Context,
        bootImgPath: String,
        kpmPath: String,
        kpmName: String,
        outputPath: String,
        onLog: (String) -> Unit = {},
    ): PatchResult {
        if (!ensureBinaries(context)) {
            onLog("ensureBinaries failed")
            return PatchResult(false, "ensureBinaries failed")
        }

        // 实时日志回调列表（stdout + stderr 都走这里）
        val logCallback = object : CallbackList<String>() {
            override fun onAddElement(e: String?) {
                e?.let { onLog(it) }
            }
        }

        val kptools = kptoolsExec(context)
        val workDir = prepareTmpWorkspace(
            context,
            mapOf("boot.img" to bootImgPath, "module.kpm" to kpmPath),
        )
        val kpm = "$workDir/module.kpm"

        val cmds = arrayOf(
            "echo '****************************'",
            "echo ' FoxMask KPM Embedder'",
            "echo '****************************'",
            "cd '$workDir'",
            "rm -f kernel kernel.ori new-boot.img",
            "echo '- Unpacking boot image'",
            "$kptools unpack 'boot.img'",
            "mv kernel kernel.ori",
            // -M 嵌入 KPM 模块，-T kpm 指定类型，-N 指定模块名
            "echo '- Embedding KPM: $kpmName'",
            "$kptools -p -i kernel.ori -M '$kpm' -T kpm -N '$kpmName' -o kernel",
            "echo '- Repacking boot image'",
            "$kptools repack 'boot.img'",
        )
        val result = Shell.newJob().add(*cmds).to(logCallback, logCallback).exec()

        val sb = StringBuilder()
        result.out.forEach { sb.appendLine(it) }
        result.err.forEach { sb.appendLine("[err] $it") }
        val log = sb.toString()

        val fetch = Shell.cmd("cp '$workDir/new-boot.img' '$outputPath' && echo OK")
            .to(logCallback, logCallback).exec()
        val ok = result.isSuccess &&
            fetch.out.any { it.contains("OK") } &&
            File(outputPath).exists() &&
            File(outputPath).length() > 0
        if (ok) {
            onLog("- Successfully Embedded!")
            onLog(" Output file is written to $outputPath")
            onLog("****************************")
        } else {
            onLog("- Embed failed! (code=${result.code})")
            onLog("****************************")
        }
        Shell.cmd("rm -rf '$workDir'").submit()
        return if (ok) PatchResult(true, log) else PatchResult(false, "$log\nfetch output failed")
    }

    // ===== KPM 运行时管理（通过 kpcall supercall，固定 root-skey 模式）=====

    /**
     * 列出已加载的 KPM 模块。
     * @return 模块名称列表，失败返回空列表
     */
    fun listKpms(): List<String> {
        val result = Shell.cmd("magisk kpcall kpm-list").exec()
        if (result.isSuccess) {
            return result.out
                .filter { it.isNotBlank() }
                .flatMap { it.split("\n") }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
        return emptyList()
    }

    /**
     * 获取 KPM 模块信息。
     * @param name 模块名称
     * @return 模块信息 JSON 字符串，失败返回 null
     */
    fun getKpmInfo(name: String): String? {
        val result = Shell.cmd("magisk kpcall kpm-info '$name'").exec()
        if (result.isSuccess && result.out.isNotEmpty()) {
            return result.out.joinToString("\n")
        }
        return null
    }

    /**
     * 加载 KPM 模块。
     * @param path .kpm 文件路径
     * @param args 模块参数（可选）
     * @return 加载是否成功
     */
    fun loadKpm(path: String, args: String = ""): Boolean {
        val cmd = if (args.isEmpty()) {
            "magisk kpcall kpm-load '$path'"
        } else {
            "magisk kpcall kpm-load '$path' '$args'"
        }
        return Shell.cmd(cmd).exec().isSuccess
    }

    /**
     * 卸载 KPM 模块。
     * @param name 模块名称
     * @return 卸载是否成功
     */
    fun unloadKpm(name: String): Boolean {
        return Shell.cmd("magisk kpcall kpm-unload '$name'").exec().isSuccess
    }

    /**
     * 控制 KPM 模块。
     * @param name 模块名称
     * @param ctlArgs 控制参数
     * @return 控制结果字符串，失败返回 null
     */
    fun controlKpm(name: String, ctlArgs: String): String? {
        val result = Shell.cmd(
            "magisk kpcall kpm-control '$name' '$ctlArgs'"
        ).exec()
        if (result.isSuccess) {
            return result.out.joinToString("\n")
        }
        return null
    }
}
