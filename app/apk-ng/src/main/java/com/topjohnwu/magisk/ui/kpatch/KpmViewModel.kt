package com.topjohnwu.magisk.ui.kpatch

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.arch.BaseViewModel
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.R as CoreR
import com.topjohnwu.magisk.core.ktx.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * KPM 模块项数据。
 *
 * @param name 模块名称
 * @param info 模块信息（kpm-info 返回的原始字符串），可能为空
 */
data class KpmItem(
    val name: String,
    val info: String,
)

/**
 * KernelPatch / KPM 管理 ViewModel。
 *
 * 负责：
 * - 检测 kpatch 是否已安装及版本
 * - 列出 / 加载 / 卸载 / 控制 KPM 模块
 * - 嵌入 KPM 到 boot 镜像
 * - 修补 boot 镜像嵌入 kpatch
 */
class KpmViewModel : BaseViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val kpatchInstalled: Boolean = false,
        val kpatchVersion: String? = null,
        val items: List<KpmItem> = emptyList(),
        val message: String? = null,
        /** 修补/嵌入过程的实时日志（可视化用），非空时 UI 展示日志区 */
        val patchLog: String = "",
        /** 修补中标志，控制日志区显示与按钮禁用 */
        val patching: Boolean = false,
        /** 修补完成（成功）标志，控制结果提示 */
        val patchDone: Boolean = false,
        /** 是否已有 boot 镜像可用（修补成功后置 true，用于 gating 嵌入 KPM 按钮） */
        val hasBoot: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 由 KpmScreen 主动调用启动加载。无 root 时仅展示打包版本，不检测安装状态。 */
    fun startLoading() {
        _uiState.update { it.copy(loading = true) }
        if (!Info.isRooted) {
            // 无 root：kpatch 运行时管理功能不可用，但离线修补 boot 仍可用
            _uiState.update {
                it.copy(
                    loading = false,
                    kpatchInstalled = false,
                    kpatchVersion = KpatchShell.PACKED_KP_VERSION,
                    items = emptyList(),
                )
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val installed = KpatchShell.isKpatchInstalled()
            val version = if (installed) KpatchShell.getKpatchVersion() else null
            val items = if (installed) loadKpmList() else emptyList()
            _uiState.update {
                it.copy(
                    loading = false,
                    kpatchInstalled = installed,
                    kpatchVersion = version,
                    items = items,
                )
            }
        }
    }

    /** 下拉/重试刷新 KPM 列表。无 root 时直接返回。 */
    fun refresh() {
        if (!Info.isRooted) {
            _uiState.update { it.copy(loading = false) }
            return
        }
        _uiState.update { it.copy(loading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val installed = KpatchShell.isKpatchInstalled()
            val items = if (installed) loadKpmList() else emptyList()
            _uiState.update {
                it.copy(
                    loading = false,
                    kpatchInstalled = installed,
                    items = items,
                )
            }
        }
    }

    private fun loadKpmList(): List<KpmItem> {
        val names = KpatchShell.listKpms()
        return names.map { name ->
            KpmItem(name = name, info = KpatchShell.getKpmInfo(name) ?: "")
        }
    }

    /**
     * 加载 KPM 模块。
     * @param kpmUri .kpm 文件 Uri
     * @param args 加载参数（可选）
     */
    fun loadKpm(kpmUri: Uri, args: String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val ctx: Context = AppContext
            // 将 Uri 拷贝到本地缓存目录后传给 kpcall，避免 SELinux/路径问题
            val localPath = copyUriToCache(ctx, kpmUri, "kpm_load.kpm")
            val ok = if (localPath != null) {
                KpatchShell.loadKpm(localPath, args)
            } else false
            withContext(Dispatchers.Main) {
                _busy.value = false
                if (ok) {
                    AppContext.toast(CoreR.string.settings_kpatch_kpm_load_ok, Toast.LENGTH_SHORT)
                    refresh()
                } else {
                    _uiState.update { it.copy(message = "kpm-load failed") }
                    AppContext.toast(CoreR.string.failure, Toast.LENGTH_SHORT)
                }
            }
        }
    }

    /** 卸载 KPM 模块。 */
    fun unloadKpm(name: String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val ok = KpatchShell.unloadKpm(name)
            withContext(Dispatchers.Main) {
                _busy.value = false
                if (ok) {
                    AppContext.toast(CoreR.string.settings_kpatch_kpm_unload_ok, Toast.LENGTH_SHORT)
                    refresh()
                } else {
                    _uiState.update { it.copy(message = "kpm-unload failed") }
                    AppContext.toast(CoreR.string.failure, Toast.LENGTH_SHORT)
                }
            }
        }
    }

    /** 控制 KPM 模块。 */
    fun controlKpm(name: String, ctlArgs: String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val result = KpatchShell.controlKpm(name, ctlArgs)
            withContext(Dispatchers.Main) {
                _busy.value = false
                if (result != null) {
                    _uiState.update { it.copy(message = result) }
                } else {
                    _uiState.update { it.copy(message = "kpm-control failed") }
                    AppContext.toast(CoreR.string.failure, Toast.LENGTH_SHORT)
                }
            }
        }
    }

    /**
     * 嵌入 KPM 模块到 boot 镜像。
     * 注意：嵌入是离线操作，不需要 kpatch 已安装到内核，也不需要 root。
     * 输出文件写入 Downloads 目录。模块名由 kptools 自动从 .kpm.info section 读取。
     * @param bootImgUri 已修补 kpatch 的 boot.img Uri
     * @param kpmUri .kpm 文件 Uri
     * @param onResult 输出文件 Uri（成功时）或 null（失败）
     */
    fun embedKpm(bootImgUri: Uri, kpmUri: Uri, onResult: (String?) -> Unit) {
        if (_busy.value) {
            onResult(null)
            return
        }
        _busy.value = true
        _uiState.update { it.copy(patching = true, patchDone = false, patchLog = "", hasBoot = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val ctx: Context = AppContext
            val bootLocal = copyUriToCache(ctx, bootImgUri, "boot.img")
            val kpmLocal = copyUriToCache(ctx, kpmUri, "embed.kpm")
            val result = if (bootLocal != null && kpmLocal != null) {
                KpatchShell.embedKpm(ctx, bootLocal, kpmLocal) { line ->
                    // 实时追加日志行到 state
                    _uiState.update { it.copy(patchLog = it.patchLog + line + "\n") }
                }
            } else null
            withContext(Dispatchers.Main) {
                _busy.value = false
                _uiState.update { it.copy(patching = false, patchDone = result?.success == true) }
                onResult(result?.takeIf { it.success }?.log)
            }
        }
    }

    /**
     * 修补 boot 镜像，嵌入 kpatch。
     * 离线操作，不需要 root。输出文件写入 Downloads 目录。
     * @param bootImgUri 原始 boot.img Uri
     * @param onResult 输出文件 Uri（成功时）或 null（失败）
     */
    fun patchBoot(bootImgUri: Uri, onResult: (String?) -> Unit) {
        if (_busy.value) {
            onResult(null)
            return
        }
        _busy.value = true
        _uiState.update { it.copy(patching = true, patchDone = false, patchLog = "") }
        viewModelScope.launch(Dispatchers.IO) {
            val ctx: Context = AppContext
            val bootLocal = copyUriToCache(ctx, bootImgUri, "boot.img")
            val result = if (bootLocal != null) {
                KpatchShell.patchBoot(ctx, bootLocal) { line ->
                    // 实时追加日志行到 state
                    _uiState.update { it.copy(patchLog = it.patchLog + line + "\n") }
                }
            } else null
            withContext(Dispatchers.Main) {
                _busy.value = false
                // 修补成功后标记 hasBoot，启用嵌入 KPM 按钮
                _uiState.update {
                    it.copy(
                        patching = false,
                        patchDone = result?.success == true,
                        hasBoot = result?.success == true || it.hasBoot,
                    )
                }
                onResult(result?.takeIf { it.success }?.log)
            }
        }
    }

    /** 清除修补日志与完成状态（开始新一轮修补前调用） */
    fun clearPatchLog() {
        _uiState.update { it.copy(patchLog = "", patchDone = false) }
    }

    /** 清除顶部消息。 */
    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun copyUriToCache(
        ctx: Context,
        uri: Uri,
        fileName: String = "tmp.bin",
    ): String? = try {
        val target = File(ctx.cacheDir, "kpatch_$fileName")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        target.absolutePath
    } catch (e: Exception) {
        null
    }
}
