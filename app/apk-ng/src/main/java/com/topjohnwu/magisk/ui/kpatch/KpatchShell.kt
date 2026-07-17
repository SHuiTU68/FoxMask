package com.topjohnwu.magisk.ui.kpatch

import android.content.Context
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.R as CoreR
import com.topjohnwu.superuser.Shell
import java.io.File

/**
 * KernelPatch Shell 工具类。
 *
 * 负责从 assets 复制 kptools/kpimg 到本地目录并设置可执行权限，
 * 提供执行 kptools（boot 修补/KPM 嵌入）和 kpcall（运行时 KPM 管理）的接口。
 *
 * superkey 已剥离：上游 KernelPatch 不再做强 superkey 校验，root 调用者
 * 固定使用 "su"（root-skey 模式），kptools -p 不传 -s，kpcall 也不传 key 参数。
 */
object KpatchShell {

    /** 修补结果：含成功标志与执行日志，便于失败时排查 */
    data class PatchResult(val success: Boolean, val log: String)

    /** 内置打包的 KernelPatch 版本（与 assets 中的 kpimg 对应） */
    const val PACKED_KP_VERSION = "0.13.2"

    private const val KP_DIR_NAME = "kpatch"

    /** kptools/kpimg 所在的本地目录路径 */
    private fun kpatchDir(context: Context): String =
        "${context.filesDir.absolutePath}/$KP_DIR_NAME"

    /** kptools 可执行文件路径 */
    fun kptoolsPath(context: Context): String = "${kpatchDir(context)}/kptools"

    /** kpimg 内核镜像路径 */
    fun kpimgPath(context: Context): String = "${kpatchDir(context)}/kpimg"

    /**
     * 从 assets 复制 kptools/kpimg 到本地目录并设置可执行权限。
     * 幂等操作，已存在则跳过。
     *
     * 注意：本地目录 (app_data_file) 仅用于存放原始二进制，实际执行时
     * 需要复制到 [Const.TMPDIR] (/dev/tmp，root 上下文) 才能执行——
     * Android 10+ SELinux 在 enforcing 模式下禁止执行 app_data_file。
     */
    fun ensureBinaries(context: Context): Boolean {
        val dir = File(kpatchDir(context))
        if (!dir.exists()) dir.mkdirs()

        val kptools = File(kptoolsPath(context))
        val kpimg = File(kpimgPath(context))

        if (!kptools.exists() || kptools.length() == 0L) {
            context.assets.open("kpatch/kptools").use { input ->
                kptools.outputStream().use { output -> input.copyTo(output) }
            }
        }

        if (!kpimg.exists() || kpimg.length() == 0L) {
            context.assets.open("kpatch/kpimg").use { input ->
                kpimg.outputStream().use { output -> input.copyTo(output) }
            }
        }

        return kptools.exists() && kpimg.exists()
    }

    /**
     * 在 [Const.TMPDIR] 下准备 kpatch 工作环境：
     * - 复制 kptools / kpimg / 任意额外输入文件到 tmpfs
     * - chmod +x kptools
     * - 返回工作目录路径（已存在）
     *
     * SELinux 拒绝从 app_data_file 执行二进制（表现为 shell code=126），
     * 因此必须把所有要执行的文件搬到 root 上下文的 tmpfs。
     */
    private fun prepareTmpWorkspace(context: Context, extraFiles: Map<String, String>): String {
        val tmp = Const.TMPDIR
        val workDir = "$tmp/kpatch_work"
        val kptoolsSrc = kptoolsPath(context)
        val kpimgSrc = kpimgPath(context)
        val cmds = mutableListOf(
            "rm -rf '$workDir'",
            "mkdir -p '$workDir'",
            "cp '$kptoolsSrc' '$workDir/kptools'",
            "cp '$kpimgSrc' '$workDir/kpimg'",
            "chmod 755 '$workDir/kptools'",
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
     * @return 修补结果（含成功标志与日志，便于排查失败原因）
     */
    fun patchBoot(
        context: Context,
        bootImgPath: String,
        outputPath: String,
    ): PatchResult {
        if (!ensureBinaries(context)) {
            return PatchResult(false, "ensureBinaries failed")
        }

        // 工作目录用 /dev/tmp（tmpfs，root 上下文，可执行）。
        // 直接在 app data 目录执行 kptools 会因 SELinux 拒绝（code=126）。
        val workDir = prepareTmpWorkspace(context, mapOf("boot.img" to bootImgPath))
        val kptools = "$workDir/kptools"
        val kpimg = "$workDir/kpimg"

        // 依次执行 unpack → patch 裸内核 → repack（不传 -s，走 root-skey 模式）
        val cmds = arrayOf(
            "cd '$workDir'",
            "rm -f kernel kernel.ori new-boot.img",
            // 1. unpack：从 boot.img 抽取裸内核到当前目录 kernel
            "'$kptools' unpack 'boot.img'",
            // 2. 保留原始内核备份
            "mv kernel kernel.ori",
            // 3. patch 裸内核
            "'$kptools' -p -i kernel.ori -k '$kpimg' -o kernel",
            // 4. repack：用修补后的 kernel 重新打包成 new-boot.img
            "'$kptools' repack 'boot.img'",
        )
        val result = Shell.cmd(*cmds).exec()

        val log = buildString {
            appendLine("code=${result.code}")
            result.out.forEach { appendLine("[out] $it") }
            result.err.forEach { appendLine("[err] $it") }
        }

        // 从 tmpfs 把 new-boot.img 拷回 outputPath（app data 或 cache 目录）
        val fetch = Shell.cmd("cp '$workDir/new-boot.img' '$outputPath' && echo OK").exec()
        val ok = result.isSuccess &&
            fetch.out.any { it.contains("OK") } &&
            File(outputPath).exists() &&
            File(outputPath).length() > 0
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
        // 同样需要在 tmpfs 执行 kptools，避免 SELinux 拒绝
        val workDir = prepareTmpWorkspace(context, mapOf("boot.img" to bootImgPath))
        val kptools = "$workDir/kptools"
        val result = Shell.cmd(
            "cd '$workDir'",
            "'$kptools' -l -i 'boot.img'",
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
     * @return 嵌入结果（含日志）
     */
    fun embedKpm(
        context: Context,
        bootImgPath: String,
        kpmPath: String,
        kpmName: String,
        outputPath: String,
    ): PatchResult {
        if (!ensureBinaries(context)) {
            return PatchResult(false, "ensureBinaries failed")
        }

        // 同 patchBoot：用 /dev/tmp 避开 SELinux 对 app_data_file 的 exec 拒绝
        val workDir = prepareTmpWorkspace(
            context,
            mapOf("boot.img" to bootImgPath, "module.kpm" to kpmPath),
        )
        val kptools = "$workDir/kptools"
        val kpm = "$workDir/module.kpm"

        val cmds = arrayOf(
            "cd '$workDir'",
            "rm -f kernel kernel.ori new-boot.img",
            "'$kptools' unpack 'boot.img'",
            "mv kernel kernel.ori",
            // -M 嵌入 KPM 模块，-T kpm 指定类型，-N 指定模块名
            "'$kptools' -p -i kernel.ori -M '$kpm' -T kpm -N '$kpmName' -o kernel",
            "'$kptools' repack 'boot.img'",
        )
        val result = Shell.cmd(*cmds).exec()

        val log = buildString {
            appendLine("code=${result.code}")
            result.out.forEach { appendLine("[out] $it") }
            result.err.forEach { appendLine("[err] $it") }
        }

        val fetch = Shell.cmd("cp '$workDir/new-boot.img' '$outputPath' && echo OK").exec()
        val ok = result.isSuccess &&
            fetch.out.any { it.contains("OK") } &&
            File(outputPath).exists() &&
            File(outputPath).length() > 0
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
