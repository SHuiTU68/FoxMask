package com.topjohnwu.magisk.ui.home

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import com.topjohnwu.magisk.arch.AsyncLoadViewModel
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.BuildConfig
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.ktx.await
import com.topjohnwu.magisk.core.ktx.toast
import com.topjohnwu.magisk.core.repository.NetworkService
import com.topjohnwu.magisk.ui.kpatch.KpatchShell
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import com.topjohnwu.magisk.core.R as CoreR

class HomeViewModel(
    private val svc: NetworkService
) : AsyncLoadViewModel() {

    enum class State {
        LOADING, INVALID, OUTDATED, UP_TO_DATE
    }

    data class UiState(
        val isNoticeVisible: Boolean = Config.safetyNotice,
        val appState: State = State.LOADING,
        val managerRemoteVersion: String = "",
        val managerProgress: Int = 0,
        val showUninstall: Boolean = false,
        val showManagerInstall: Boolean = false,
        val showHideRestore: Boolean = false,
        val envFixCode: Int = 0,
        val kpatchVersion: String? = null,
        val kpatchInstalled: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val magiskState
        get() = when {
            Info.isRooted && Info.env.isUnsupported -> State.OUTDATED
            !Info.env.isActive -> State.INVALID
            Info.env.versionCode < BuildConfig.APP_VERSION_CODE -> State.OUTDATED
            else -> State.UP_TO_DATE
        }

    val magiskInstalledVersion: String
        get() = Info.env.run {
            if (isActive)
                "$versionString ($versionCode)" + if (isDebug) " (D)" else ""
            else
                ""
        }

    val managerInstalledVersion: String
        get() = "${BuildConfig.APP_VERSION_NAME} (${BuildConfig.APP_VERSION_CODE})" +
            if (BuildConfig.DEBUG) " (D)" else ""

    val kpatchVersion: String?
        get() = _uiState.value.kpatchVersion

    companion object {
        private var checkedEnv = false
    }

    override suspend fun doLoadWork() {
        _uiState.update { it.copy(appState = State.LOADING) }
        Info.fetchUpdate(svc)?.apply {
            val isDebug = Config.updateChannel == Config.Value.DEBUG_CHANNEL
            _uiState.update {
                it.copy(
                    appState = if (BuildConfig.APP_VERSION_CODE < versionCode) State.OUTDATED else State.UP_TO_DATE,
                    managerRemoteVersion = "$version ($versionCode)" + if (isDebug) " (D)" else ""
                )
            }
        } ?: run {
            _uiState.update { it.copy(appState = State.INVALID, managerRemoteVersion = "") }
        }
        ensureEnv()
        detectKpatch()
    }

    /**
     * 检测 KernelPatch 是否已安装并获取版本。
     * superkey 已剥离：固定 root-skey 模式，无需用户配置。
     * 版本号总会显示：已安装用 kpcall 获取实际版本，否则回退到内置打包版本。
     */
    private suspend fun detectKpatch() {
        if (!Info.isRooted) {
            // 无 root：仍展示内置打包版本，标记为未安装
            _uiState.update {
                it.copy(kpatchVersion = KpatchShell.PACKED_KP_VERSION, kpatchInstalled = false)
            }
            return
        }
        val installed = withContext(Dispatchers.IO) {
            KpatchShell.isKpatchInstalled()
        }
        val version = if (installed) {
            withContext(Dispatchers.IO) { KpatchShell.getKpatchVersion() }
                ?: KpatchShell.PACKED_KP_VERSION
        } else {
            KpatchShell.PACKED_KP_VERSION
        }
        _uiState.update {
            it.copy(kpatchVersion = version, kpatchInstalled = installed)
        }
    }

    private val networkObserver: (Boolean) -> Unit = { startLoading() }

    init {
        Info.isConnected.observeForever(networkObserver)
    }

    override fun onCleared() {
        super.onCleared()
        Info.isConnected.removeObserver(networkObserver)
    }

    fun onLinkPressed(link: String) {
        val intent = Intent(Intent.ACTION_VIEW, link.toUri())
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            AppContext.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            AppContext.toast(CoreR.string.open_link_failed_toast, Toast.LENGTH_SHORT)
        }
    }

    fun onDeletePressed() {
        _uiState.update { it.copy(showUninstall = true) }
    }

    fun onUninstallConsumed() {
        _uiState.update { it.copy(showUninstall = false) }
    }

    fun onManagerPressed() {
        when (_uiState.value.appState) {
            State.LOADING -> showSnackbar(CoreR.string.loading)
            State.INVALID -> showSnackbar(CoreR.string.no_connection)
            else -> _uiState.update { it.copy(showManagerInstall = true) }
        }
    }

    fun onManagerInstallConsumed() {
        _uiState.update { it.copy(showManagerInstall = false) }
    }

    fun onHideRestorePressed() {
        _uiState.update { it.copy(showHideRestore = true) }
    }

    fun onHideRestoreConsumed() {
        _uiState.update { it.copy(showHideRestore = false) }
    }

    fun onEnvFixConsumed() {
        _uiState.update { it.copy(envFixCode = 0) }
    }

    fun hideNotice() {
        Config.safetyNotice = false
        _uiState.update { it.copy(isNoticeVisible = false) }
    }

    private suspend fun ensureEnv() {
        if (magiskState == State.INVALID || checkedEnv) return
        val cmd = "env_check ${Info.env.versionString} ${Info.env.versionCode}"
        val code = Shell.cmd(cmd).await().code
        if (code != 0) {
            _uiState.update { it.copy(envFixCode = code) }
        }
        checkedEnv = true
    }
}
