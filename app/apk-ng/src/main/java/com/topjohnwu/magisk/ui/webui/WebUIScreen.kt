package com.topjohnwu.magisk.ui.webui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.topjohnwu.magisk.core.R as CoreR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebUIScreen(
    viewModel: WebUIViewModel,
    moduleId: String,
    moduleName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val localPath by viewModel.localPath.collectAsState()
    val loadError by viewModel.loadError.collectAsState()
    var fullScreen by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(moduleId) {
        viewModel.init(moduleId, moduleName, context.cacheDir.absolutePath)
    }

    BackHandler {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            if (!fullScreen) {
                TopAppBar(
                    title = { Text(moduleName) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
    ) { padding ->
        when {
            loadError != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = loadError!!,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            localPath != null -> {
                val path = localPath!!
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(if (fullScreen) PaddingValues(0.dp) else padding),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = true
                            settings.allowFileAccessFromFileURLs = true
                            settings.allowUniversalAccessFromFileURLs = true

                            // 注入 KernelSU 桥接，兼容 KSU 和 APatch 模块
                            addJavascriptInterface(
                                KsuBridge(ctx, moduleId) { enable ->
                                    fullScreen = enable
                                },
                                "KernelSU"
                            )

                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    // 注入 ksu 别名以兼容 APatch 模块
                                    view?.evaluateJavascript(
                                        "if(!window.ksu){window.ksu=window.KernelSU;}", null
                                    )
                                }
                            }

                            loadUrl(path)
                        }
                    },
                    update = { wv -> webView = wv },
                )
            }
            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
