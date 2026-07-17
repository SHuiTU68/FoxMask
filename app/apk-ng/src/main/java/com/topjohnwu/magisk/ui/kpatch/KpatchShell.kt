package com.topjohnwu.magisk.ui.kpatch

import android.content.Context
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.utils.MediaStoreUtils.getFile
import com.topjohnwu.magisk.core.utils.MediaStoreUtils.inputStream
import com.topjohnwu.magisk.core.utils.MediaStoreUtils.outputStream
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.FileInputStream

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
     * 取 kptools 可执行文件路径（shell 表达式，供拼接到命令行）。
     * - 无 root：直接用 nativeLibraryDir/libkptools.so（apk_data_file 上下文，
     *   APK 自带 native 库可执行，不依赖 root）。
     * - 有 root 且 noDataExec（Samsung 等）：复制到 $MAGISKTMP（root tmpfs）再用。
     * - 有 root 且可执行：直接用 nativeLibraryDir 路径。
     */
    private fun kptoolsExec(context: Context): String {
        val libPath = File(context.applicationInfo.nativeLibraryDir, KPTOOLS_LIB_NAME).absolutePath
        // 无 root 时直接执行 nativeLibraryDir 下的二进制（不需要 MAGISKTMP）
        if (!Info.isRooted) return "'$libPath'"
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
     * 准备 kpatch 数据工作目录，复制 kpimg 和额外输入文件进去。
     * - 有 root：用 [Const.TMPDIR] (/dev/tmp，root tmpfs)，确保 root shell 可读写。
     * - 无 root：用 app cacheDir（app 私有目录，app 自身可直接读写执行）。
     *
     * @return 工作目录绝对路径
     */
    private fun prepareWorkspace(context: Context, extraFiles: Map<String, String>): String {
        val workDir = if (Info.isRooted) {
            // root 模式：用 /dev/tmp，通过 shell 命令复制
            val wd = "${Const.TMPDIR}/kpatch_work"
            val kpimgSrc = kpimgPath(context)
            val cmds = mutableListOf(
                "rm -rf '$wd'",
                "mkdir -p '$wd'",
                "cp '$kpimgSrc' '$wd/kpimg'",
            )
            extraFiles.forEach { (destName, srcPath) ->
                cmds.add("cp '$srcPath' '$wd/$destName'")
            }
            Shell.cmd(*cmds.toTypedArray()).exec()
            wd
        } else {
            // 无 root 模式：用 app cacheDir，直接用 Java IO 复制
            val wd = File(context.cacheDir, "kpatch_work").apply { mkdirs() }
            File(kpimgPath(context)).copyTo(File(wd, "kpimg"), overwrite = true)
            extraFiles.forEach { (destName, srcPath) ->
                File(srcPath).copyTo(File(wd, destName), overwrite = true)
            }
            wd.absolutePath
        }
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
     * 离线操作，不需要 root：kptools 从 nativeLibraryDir 执行，工作目录用 app cacheDir，
     * 输出通过 MediaStore 写到 /storage/emulated/0/Download/。
     *
     * @param context 上下文
     * @param bootImgPath 原始 boot.img 路径（app cache 中的本地路径）
     * @param onLog 实时日志回调，每输出一行调用一次（用于 UI 可视化）
     * @return 修补结果（success=true 时 log 字段是输出文件的 Uri 字符串）
     */
    fun patchBoot(
        context: Context,
        bootImgPath: String,
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
        val workDir = prepareWorkspace(context, mapOf("boot.img" to bootImgPath))
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

        val newBootFile = File(workDir, "new-boot.img")
        val patchOk = result.isSuccess && newBootFile.exists() && newBootFile.length() > 0

        // 通过 MediaStore 把 new-boot.img 写到 Downloads 目录（无需 root）
        var outUri: String? = null
        if (patchOk) {
            try {
                val outFile = getFile("patched_boot.img")
                outFile.uri.outputStream().use { out ->
                    FileInputStream(newBootFile).use { input -> input.copyTo(out) }
                }
                outUri = outFile.uri.toString()
                onLog("- Successfully Patched!")
                onLog(" Output file is written to $outFile")
                onLog("****************************")
            } catch (e: Exception) {
                onLog("- Failed to write output: ${e.message}")
                onLog("****************************")
            }
        } else {
            onLog("- Patch failed! (code=${result.code})")
            onLog("****************************")
        }

        // 清理工作目录
        if (Info.isRooted) {
            Shell.cmd("rm -rf '$workDir'").submit()
        } else {
            File(workDir).deleteRecursively()
        }
        return if (outUri != null) PatchResult(true, outUri) else PatchResult(false, "patch failed")
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
        val workDir = prepareWorkspace(context, mapOf("boot.img" to bootImgPath))
        val result = Shell.cmd(
            "cd '$workDir'",
            "$kptools -l -i 'boot.img'",
        ).exec()
        if (Info.isRooted) {
            Shell.cmd("rm -rf '$workDir'").submit()
        } else {
            File(workDir).deleteRecursively()
        }
        return result.isSuccess && result.out.any { it.contains("patched=") }
    }

    /**
     * 嵌入 KPM 模块到已修补 kpatch 的 boot 镜像。
     *
     * 同样遵循 unpack → patch（-M 嵌入）→ repack 流程，因为 kptools -p 只接受裸内核。
     * 离线操作，不需要 root；输出通过 MediaStore 写到 Downloads。
     *
     * @param context 上下文
     * @param bootImgPath 已修补 kpatch 的 boot.img 路径（app cache 中的本地路径）
     * @param kpmPath .kpm 文件路径（app cache 中的本地路径）
     * @param kpmName 模块名称
     * @param onLog 实时日志回调，每输出一行调用一次（用于 UI 可视化）
     * @return 嵌入结果（success=true 时 log 字段是输出文件的 Uri 字符串）
     */
    fun embedKpm(
        context: Context,
        bootImgPath: String,
        kpmPath: String,
        kpmName: String,
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
        val workDir = prepareWorkspace(
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

        val newBootFile = File(workDir, "new-boot.img")
        val patchOk = result.isSuccess && newBootFile.exists() && newBootFile.length() > 0

        // 通过 MediaStore 把 new-boot.img 写到 Downloads 目录（无需 root）
        var outUri: String? = null
        if (patchOk) {
            try {
                val outFile = getFile("embedded_boot.img")
                outFile.uri.outputStream().use { out ->
                    FileInputStream(newBootFile).use { input -> input.copyTo(out) }
                }
                outUri = outFile.uri.toString()
                onLog("- Successfully Embedded!")
                onLog(" Output file is written to $outFile")
                onLog("****************************")
            } catch (e: Exception) {
                onLog("- Failed to write output: ${e.message}")
                onLog("****************************")
            }
        } else {
            onLog("- Embed failed! (code=${result.code})")
            onLog("****************************")
        }

        // 清理工作目录
        if (Info.isRooted) {
            Shell.cmd("rm -rf '$workDir'").submit()
        } else {
            File(workDir).deleteRecursively()
        }
        return if (outUri != null) PatchResult(true, outUri) else PatchResult(false, "embed failed")
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
