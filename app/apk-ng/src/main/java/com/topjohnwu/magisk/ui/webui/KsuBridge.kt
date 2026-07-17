package com.topjohnwu.magisk.ui.webui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.topjohnwu.superuser.Shell
import org.json.JSONObject

/**
 * KernelSU/APatch 风格的 WebUI JavaScript 桥接。
 *
 * 注入到 WebView 的 window.KernelSU 对象，提供以下 API：
 * - exec(command, options) → JSON {errno, stdout, stderr}
 * - toast(message)
 * - fullScreen(enable)
 * - moduleInfo() → JSON {moduleDir, moduleId}
 *
 * 兼容 KernelSU 和 APatch 模块生态。
 */
class KsuBridge(
    private val context: Context,
    private val moduleId: String,
    private val onFullScreen: (Boolean) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 以 root 权限同步执行命令，返回 JSON 结果字符串 */
    @JavascriptInterface
    fun exec(command: String, options: String?): String {
        var cmd = command
        // 解析 options 中的 cwd，通过 cd 切换工作目录
        if (!options.isNullOrEmpty()) {
            try {
                val opts = JSONObject(options)
                val cwd = opts.optString("cwd", "")
                if (cwd.isNotEmpty()) {
                    cmd = "cd $cwd && $cmd"
                }
            } catch (_: Exception) {
                // options 解析失败时忽略，按原命令执行
            }
        }

        val result = Shell.cmd(cmd).exec()
        return JSONObject().apply {
            put("errno", result.code)
            put("stdout", result.out.joinToString("\n"))
            put("stderr", "")
        }.toString()
    }

    /** 显示 Toast 消息 */
    @JavascriptInterface
    fun toast(message: String) {
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** 切换全屏模式 */
    @JavascriptInterface
    fun fullScreen(enable: Boolean) {
        mainHandler.post { onFullScreen(enable) }
    }

    /** 获取当前模块信息 */
    @JavascriptInterface
    fun moduleInfo(): String {
        return JSONObject().apply {
            put("moduleDir", "/data/adb/modules/$moduleId")
            put("moduleId", moduleId)
        }.toString()
    }
}
