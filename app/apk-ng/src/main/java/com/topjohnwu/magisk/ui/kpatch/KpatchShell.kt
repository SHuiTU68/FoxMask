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
 */
object KpatchShell {

    private const val KP_VERSION = "0.13.1"
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

        // 设置可执行权限
        Shell.cmd("chmod 755 '${kptools.absolutePath}'").exec()
        return kptools.exists() && kpimg.exists()
    }

    /**
     * 检测 KernelPatch 是否已安装（内核已修补）。
     * 通过 kpcall hello 检测。
     * @param superkey 修补时设置的 superkey
     * @return true 如果 kpatch 已安装且 key 有效
     */
    fun isKpatchInstalled(superkey: String): Boolean {
        if (superkey.isEmpty()) return false
        val result = Shell.cmd("magisk kpcall hello '$superkey'").exec()
        return result.isSuccess && result.out.any { it.contains("installed") }
    }

    /**
     * 获取 KernelPatch 版本。
     * @param superkey 修补时设置的 superkey
     * @return 版本字符串（如 "0.13.1"），失败返回 null
     */
    fun getKpatchVersion(superkey: String): String? {
        if (superkey.isEmpty()) return null
        val result = Shell.cmd("magisk kpcall kp-version '$superkey'").exec()
        if (result.isSuccess && result.out.isNotEmpty()) {
            return result.out[0].trim()
        }
        return null
    }

    /**
     * 修补 boot 镜像，嵌入 KernelPatch。
     * @param context 上下文
     * @param bootImgPath 原始 boot.img 路径
     * @param superkey 修补时设置的 superkey
     * @param outputPath 修补后输出路径
     * @return 修补是否成功
     */
    fun patchBoot(
        context: Context,
        bootImgPath: String,
        superkey: String,
        outputPath: String,
    ): Boolean {
        if (!ensureBinaries(context)) return false
        val kptools = kptoolsPath(context)
        val kpimg = kpimgPath(context)

        val result = Shell.cmd(
            "$kptools -p -i '$bootImgPath' -k '$kpimg' -s '$superkey' -o '$outputPath'"
        ).exec()
        return result.isSuccess && result.out.any { it.contains("patch done") }
    }

    /**
     * 检查 boot 镜像是否已修补 kpatch。
     * @param context 上下文
     * @param bootImgPath boot.img 路径
     * @return true 如果已修补
     */
    fun isBootPatched(context: Context, bootImgPath: String): Boolean {
        if (!ensureBinaries(context)) return false
        val kptools = kptoolsPath(context)
        val result = Shell.cmd("$kptools -l -i '$bootImgPath'").exec()
        return result.isSuccess && result.out.any { it.contains("patched=") }
    }

    /**
     * 嵌入 KPM 模块到 boot 镜像。
     * @param context 上下文
     * @param bootImgPath 已修补 kpatch 的 boot.img 路径
     * @param kpmPath .kpm 文件路径
     * @param kpmName 模块名称
     * @param outputPath 输出路径
     * @return 嵌入是否成功
     */
    fun embedKpm(
        context: Context,
        bootImgPath: String,
        kpmPath: String,
        kpmName: String,
        outputPath: String,
    ): Boolean {
        if (!ensureBinaries(context)) return false
        val kptools = kptoolsPath(context)

        val result = Shell.cmd(
            "$kptools -p -i '$bootImgPath' -M '$kpmPath' -T kpm -N '$kpmName' -o '$outputPath'"
        ).exec()
        return result.isSuccess
    }

    // ===== KPM 运行时管理（通过 kpcall supercall）=====

    /**
     * 列出已加载的 KPM 模块。
     * @param superkey superkey
     * @return 模块名称列表，失败返回空列表
     */
    fun listKpms(superkey: String): List<String> {
        if (superkey.isEmpty()) return emptyList()
        val result = Shell.cmd("magisk kpcall kpm-list '$superkey'").exec()
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
     * @param superkey superkey
     * @param name 模块名称
     * @return 模块信息 JSON 字符串，失败返回 null
     */
    fun getKpmInfo(superkey: String, name: String): String? {
        if (superkey.isEmpty()) return null
        val result = Shell.cmd("magisk kpcall kpm-info '$superkey' '$name'").exec()
        if (result.isSuccess && result.out.isNotEmpty()) {
            return result.out.joinToString("\n")
        }
        return null
    }

    /**
     * 加载 KPM 模块。
     * @param superkey superkey
     * @param path .kpm 文件路径
     * @param args 模块参数（可选）
     * @return 加载是否成功
     */
    fun loadKpm(superkey: String, path: String, args: String = ""): Boolean {
        if (superkey.isEmpty()) return false
        val cmd = if (args.isEmpty()) {
            "magisk kpcall kpm-load '$superkey' '$path'"
        } else {
            "magisk kpcall kpm-load '$superkey' '$path' '$args'"
        }
        return Shell.cmd(cmd).exec().isSuccess
    }

    /**
     * 卸载 KPM 模块。
     * @param superkey superkey
     * @param name 模块名称
     * @return 卸载是否成功
     */
    fun unloadKpm(superkey: String, name: String): Boolean {
        if (superkey.isEmpty()) return false
        return Shell.cmd("magisk kpcall kpm-unload '$superkey' '$name'").exec().isSuccess
    }

    /**
     * 控制 KPM 模块。
     * @param superkey superkey
     * @param name 模块名称
     * @param ctlArgs 控制参数
     * @return 控制结果字符串，失败返回 null
     */
    fun controlKpm(superkey: String, name: String, ctlArgs: String): String? {
        if (superkey.isEmpty()) return null
        val result = Shell.cmd(
            "magisk kpcall kpm-control '$superkey' '$name' '$ctlArgs'"
        ).exec()
        if (result.isSuccess) {
            return result.out.joinToString("\n")
        }
        return null
    }
}
