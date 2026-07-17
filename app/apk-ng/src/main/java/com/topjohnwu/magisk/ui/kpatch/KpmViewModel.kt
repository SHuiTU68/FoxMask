package com.topjohnwu.magisk.ui.kpatch

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.arch.BaseViewModel
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.Config
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
 *
 * superkey 来自 [Config.kpatchSuperkey]，由用户在设置中或修补时设置。
 */
class KpmViewModel : BaseViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val kpatchInstalled: Boolean = false,
        val kpatchVersion: String? = null,
        val items: List<KpmItem> = emptyList(),
        val superkey: String = "",
        val message: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 由 KpmScreen 主动调用启动加载。 */
    fun startLoading() {
        val key = Config.kpatchSuperkey
        _uiState.update { it.copy(superkey = key) }
        if (key.isEmpty()) {
            _uiState.update { it.copy(loading = false, kpatchInstalled = false) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val installed = KpatchShell.isKpatchInstalled(key)
            val version = if (installed) KpatchShell.getKpatchVersion(key) else null
            val items = if (installed) loadKpmList(key) else emptyList()
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

    /** 下拉/重试刷新 KPM 列表。 */
    fun refresh() {
        val key = _uiState.value.superkey
        if (key.isEmpty()) {
            startLoading()
            return
        }
        _uiState.update { it.copy(loading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val installed = KpatchShell.isKpatchInstalled(key)
            val items = if (installed) loadKpmList(key) else emptyList()
            _uiState.update {
                it.copy(
                    loading = false,
                    kpatchInstalled = installed,
                    items = items,
                )
            }
        }
    }

    private fun loadKpmList(superkey: String): List<KpmItem> {
        val names = KpatchShell.listKpms(superkey)
        return names.map { name ->
            KpmItem(name = name, info = KpatchShell.getKpmInfo(superkey, name) ?: "")
        }
    }

    /**
     * 保存 superkey 并重新检测 kpatch 状态。
     * 通常在用户首次输入或更换 superkey 时调用。
     */
    fun saveSuperkey(key: String) {
        Config.kpatchSuperkey = key
        _uiState.update { it.copy(superkey = key, loading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val installed = KpatchShell.isKpatchInstalled(key)
            val version = if (installed) KpatchShell.getKpatchVersion(key) else null
            val items = if (installed) loadKpmList(key) else emptyList()
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

    /**
     * 加载 KPM 模块。
     * @param kpmUri .kpm 文件 Uri
     * @param args 加载参数（可选）
     */
    fun loadKpm(kpmUri: Uri, args: String) {
        if (_busy.value) return
        val key = _uiState.value.superkey
        if (key.isEmpty()) {
            _uiState.update { it.copy(message = "superkey is empty") }
            return
        }
        _busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val ctx: Context = AppContext
            // 将 Uri 拷贝到本地缓存目录后传给 kpcall，避免 SELinux/路径问题
            val localPath = copyUriToCache(ctx, kpmUri, "kpm_load.kpm")
            val ok = if (localPath != null) {
                KpatchShell.loadKpm(key, localPath, args)
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
        val key = _uiState.value.superkey
        if (key.isEmpty()) {
            _uiState.update { it.copy(message = "superkey is empty") }
            return
        }
        _busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val ok = KpatchShell.unloadKpm(key, name)
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
        val key = _uiState.value.superkey
        if (key.isEmpty()) {
            _uiState.update { it.copy(message = "superkey is empty") }
            return
        }
        _busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val result = KpatchShell.controlKpm(key, name, ctlArgs)
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
     * 注意：嵌入是离线操作，不需要 kpatch 已安装到内核。
     * @param bootImgUri 已修补 kpatch 的 boot.img Uri
     * @param kpmUri .kpm 文件 Uri
     * @param kpmName 模块名称
     * @param onResult 输出路径（成功时）或 null（失败）
     */
    fun embedKpm(bootImgUri: Uri, kpmUri: Uri, kpmName: String, onResult: (String?) -> Unit) {
        if (_busy.value) {
            onResult(null)
            return
        }
        _busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val ctx: Context = AppContext
            val bootLocal = copyUriToCache(ctx, bootImgUri, "boot.img")
            val kpmLocal = copyUriToCache(ctx, kpmUri, "$kpmName.kpm")
            val outputPath = "${ctx.cacheDir.absolutePath}/new_boot.img"
            val ok = if (bootLocal != null && kpmLocal != null) {
                KpatchShell.embedKpm(ctx, bootLocal, kpmLocal, kpmName, outputPath)
            } else false
            withContext(Dispatchers.Main) {
                _busy.value = false
                onResult(if (ok) outputPath else null)
            }
        }
    }

    /**
     * 修补 boot 镜像，嵌入 kpatch。
     * 通常在用户首次启动 kpatch 支持时调用。
     * @param bootImgUri 原始 boot.img Uri
     * @param superkey 用户设置的 superkey
     * @param onResult 输出路径（成功时）或 null（失败）
     */
    fun patchBoot(bootImgUri: Uri, superkey: String, onResult: (String?) -> Unit) {
        if (_busy.value) {
            onResult(null)
            return
        }
        _busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val ctx: Context = AppContext
            val bootLocal = copyUriToCache(ctx, bootImgUri, "boot.img")
            val outputPath = "${ctx.cacheDir.absolutePath}/patched_boot.img"
            val ok = if (bootLocal != null) {
                KpatchShell.patchBoot(ctx, bootLocal, superkey, outputPath)
            } else false
            withContext(Dispatchers.Main) {
                _busy.value = false
                if (ok) {
                    // 修补成功后保存 superkey
                    Config.kpatchSuperkey = superkey
                    _uiState.update { it.copy(superkey = superkey) }
                }
                onResult(if (ok) outputPath else null)
            }
        }
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
