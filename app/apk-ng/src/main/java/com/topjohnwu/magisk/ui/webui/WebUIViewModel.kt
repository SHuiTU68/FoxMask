package com.topjohnwu.magisk.ui.webui

import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.arch.BaseViewModel
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class WebUIViewModel : BaseViewModel() {

    var moduleId: String = ""
    var moduleName: String = ""

    private val _localPath = MutableStateFlow<String?>(null)
    val localPath: StateFlow<String?> = _localPath.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    // 防止 LaunchedEffect 重组时重复触发；失败后允许重试
    private var loading = false

    /**
     * 初始化 WebUI：将模块 webroot 目录复制到应用缓存目录，
     * 以便 WebView（应用进程权限）可以读取这些文件。
     *
     * 使用 submit 异步执行 shell，避免阻塞 UI；shell 不可用时立即报错，
     * 不让用户停留在加载圈（黑屏）状态。
     */
    fun init(moduleId: String, moduleName: String, cacheDir: String) {
        if (loading) return
        if (this.moduleId.isNotEmpty() && this.moduleId != moduleId) return
        loading = true
        this.moduleId = moduleId
        this.moduleName = moduleName

        viewModelScope.launch(Dispatchers.IO) {
            val sourceDir = "/data/adb/modules/$moduleId/webroot"
            val targetDir = "$cacheDir/webui/$moduleId"

            // 检查 webroot 是否存在；不存在直接报错，不污染缓存目录
            val checkResult = Shell.cmd("[ -d '$sourceDir' ] && echo OK").exec()
            if (!checkResult.isSuccess || checkResult.out.none { it.contains("OK") }) {
                withContext(Dispatchers.Main) {
                    _loadError.value = "Module has no webroot: $sourceDir"
                    loading = false
                }
                return@launch
            }

            // 异步复制；submit 立即返回，回调在 IO 线程
            Shell.cmd(
                "rm -rf '$targetDir'",
                "mkdir -p '$targetDir'",
                "cp -r '$sourceDir/.' '$targetDir/'",
                "chmod -R 755 '$targetDir'",
                "chown -R \$(stat -c %u '$cacheDir'):\$(stat -c %g '$cacheDir') '$targetDir'"
            ).submit { result ->
                val indexFile = File("$targetDir/index.html")
                viewModelScope.launch(Dispatchers.Main) {
                    if (result.isSuccess && indexFile.exists()) {
                        _localPath.value = "file://$targetDir/index.html"
                    } else {
                        _loadError.value = if (!result.isSuccess)
                            "Shell failed (root not available?)"
                        else
                            "index.html not found in webroot"
                    }
                    loading = false
                }
            }
        }
    }
}
