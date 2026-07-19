package com.topjohnwu.magisk.webui

import android.webkit.WebView

class WebUIState {
    var webView: WebView? = null
    var webviewInterface: WebViewInterface? = null
    lateinit var moduleDir: String
    var moduleName: String = ""
    var insets: Insets = Insets(0, 0, 0, 0)
    var isEdgeToEdgeEnabled = false
    var filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null
    var onSaveFileRequest: ((ByteArray, String, String) -> Unit)? = null
}
