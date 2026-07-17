package com.topjohnwu.magisk.ui.webui

import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.arch.BaseViewModel
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class WebUIViewModel : BaseViewModel() {

    var moduleId: String = ""
    var moduleName: String = ""

    private val _localPath = MutableStateFlow<String?>(null)
    val localPath: StateFlow<String?> = _localPath.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    /**
     * 初始化 WebUI：将模块 webroot 目录复制到应用缓存目录，
     * 以便 WebView（应用进程权限）可以读取这些文件。
     */
    fun init(moduleId: String, moduleName: String, cacheDir: String) {
        if (this.moduleId.isNotEmpty()) return
        this.moduleId = moduleId
        this.moduleName = moduleName

        viewModelScope.launch(Dispatchers.IO) {
            val sourceDir = "/data/adb/modules/$moduleId/webroot"
            val targetDir = "$cacheDir/webui/$moduleId"

            // 复制 webroot 到缓存目录并修正权限
            val result = Shell.cmd(
                "rm -rf '$targetDir'",
                "mkdir -p '$targetDir'",
                "cp -r '$sourceDir/.' '$targetDir/'",
                "chmod -R 755 '$targetDir'",
                "chown -R \$(stat -c %u '$cacheDir'):\$(stat -c %g '$cacheDir') '$targetDir'"
            ).exec()

            val indexFile = File("$targetDir/index.html")
            if (result.isSuccess && indexFile.exists()) {
                _localPath.value = "file://$targetDir/index.html"
            } else {
                _loadError.value = "Failed to load WebUI files"
            }
        }
    }
}
