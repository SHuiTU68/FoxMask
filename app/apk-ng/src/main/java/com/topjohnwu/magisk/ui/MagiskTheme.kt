package com.topjohnwu.magisk.ui

import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.materialkolor.dynamicColorScheme
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.ui.component.AppBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ThemeState {
    var colorMode by mutableIntStateOf(Config.colorMode)
    var floatingNav by mutableStateOf(Config.floatingNav)
    var floatingNavGlass by mutableStateOf(Config.floatingNavGlass)
    var keyColor by mutableIntStateOf(Config.keyColor)
    /** 用户选择的应用内背景图 Uri 字符串；空串 = 无背景。变更后主题会重新解码。 */
    var appBackgroundUri by mutableStateOf(Config.appBackgroundUri)
    /** 透明背景开关：开启后主屏幕 Scaffold 背景变透明，露出自定义壁纸。默认关。 */
    var transparentBackground by mutableStateOf(Config.transparentBackground)
}

/// 通过 CompositionLocal 向下层组件暴露悬浮底栏开关，
/// 避免到处直接读 ThemeState，也便于在 Preview 中覆盖。
val LocalFloatingNav = staticCompositionLocalOf { true }

/// 悬浮底栏是否启用液态玻璃效果（玻璃拟态模糊背景）。
/// 仅在 LocalFloatingNav=true 时有意义；用 miuix-blur 的 Backdrop + AGSL RuntimeShader
/// 实现真模糊 + 折射 + 色散。Android 12+ (API 31) 走 RenderEffect 模糊，
/// Android 13+ (API 33) 走 RuntimeShader 折射/色散，低版本自动降级为实色背景。
/// 移植自 KernelSU miuix 主题的 FloatingBottomBar。
val LocalFloatingNavGlass = staticCompositionLocalOf { false }

/// 是否使用 Magisk 原始 md2 主题样式（已弃用，保留兼容旧引用，恒为 false）。
val LocalMd2Style = staticCompositionLocalOf { false }

/// 当前应用内背景图（用户从相册选的图）。null = 无背景，用主题色。
val LocalAppBackground = staticCompositionLocalOf<ImageBitmap?> { null }

/// 是否启用透明背景（让自定义壁纸透出）。默认关。
val LocalTransparentBackground = staticCompositionLocalOf { false }

/// Magisk 原始 md2 主题的视觉常量（已弃用，保留兼容旧引用）。
object MagiskMd2 {
    val cardCornerRadius = 8.dp
    val cardElevation = 0.dp
}

/// 主题入口（纯 Material3，不再依赖 miuix）。
///
/// colorMode 取值:
/// 0 = 跟随系统 / 1 = 亮色 / 2 = 暗色
/// 3 = Monet 跟随系统 / 4 = Monet 亮色 / 5 = Monet 暗色
///
/// Monet 模式（3/4/5）配色来源:
/// - keyColor = 0（系统壁纸色）: Android 12+ 走原生 Material You
///   (dynamicLightColorScheme/dynamicDarkColorScheme)；Android 12 以下回退默认亮/暗色 scheme。
/// - keyColor != 0（自定义种子色）: 用 Material Kolor 从种子色生成整套 Material3 ColorScheme，
///   全 API Level 可用。移植自原 miuix 模式的「Key color」特性。
///
/// 悬浮底栏: 由 ThemeState.floatingNav 控制（默认开启，移植自 KernelSU miuix 主题）。
///   true = 圆角胶囊形悬浮底栏（FloatingBottomBar，可选液态玻璃）；
///   false = 标准 Material3 NavigationBar（贴底无圆角）。
/// 液态玻璃: 由 ThemeState.floatingNavGlass 控制（默认关闭）。
///   仅在悬浮底栏开启时显示开关；用 miuix-blur 的 Backdrop + drawBackdrop + lens 实现。
@Composable
fun MagiskTheme(
    content: @Composable () -> Unit
) {
    val mode = ThemeState.colorMode
    val useFloatingNav = ThemeState.floatingNav
    val useFloatingNavGlass = ThemeState.floatingNavGlass

    val isDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val isMonet = mode in 3..5
    val isDarkTheme = when (mode) {
        1, 4 -> false
        2, 5 -> true
        else -> isDark  // 0, 3
    }
    val keyColorInt = ThemeState.keyColor
    val seedColor = if (keyColorInt == 0) null else Color(keyColorInt)

    val colorScheme = when {
        // Monet + 自定义种子色: 用 Material Kolor 从种子色生成 ColorScheme（全 API 通用）
        isMonet && seedColor != null -> dynamicColorScheme(
            seedColor = seedColor,
            isDark = isDarkTheme
        )
        // Monet + 系统壁纸色: Android 12+ 走原生 Material You，低版本回退默认 scheme
        isMonet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (isDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        isDarkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    // 应用内背景图：从 Config 持久化的 Uri 解码。
    // 解码在 IO 线程，uri 为空时不加载（bg=null，回退主题色背景）。
    val bgUriStr = ThemeState.appBackgroundUri
    var bgBitmap by remember(bgUriStr) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(bgUriStr) {
        if (bgUriStr.isNotBlank()) {
            val appContext = context.applicationContext
            bgBitmap = withContext(Dispatchers.IO) {
                runCatching { Uri.parse(bgUriStr) }
                    .getOrNull()
                    ?.let { AppBackground.decode(appContext, it) }
            }
        } else {
            bgBitmap = null
        }
    }

    CompositionLocalProvider(
        LocalFloatingNav provides useFloatingNav,
        LocalFloatingNavGlass provides useFloatingNavGlass,
        LocalMd2Style provides false,
        LocalAppBackground provides bgBitmap,
        LocalTransparentBackground provides ThemeState.transparentBackground,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 背景图层：填满 + 裁剪，绘在内容之下
            bgBitmap?.let { bmp ->
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.Center
                )
            }
            MaterialTheme(
                colorScheme = colorScheme,
                content = content
            )
        }
    }
}
