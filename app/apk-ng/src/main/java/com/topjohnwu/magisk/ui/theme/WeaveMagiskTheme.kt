package com.topjohnwu.magisk.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/// 主题相关 CompositionLocal — 移植自 WeaveMask
val LocalEnableBlur = staticCompositionLocalOf { false }
val LocalEnableFloatingBottomBar = staticCompositionLocalOf { false }
val LocalIsMonetTheme = staticCompositionLocalOf { false }

/// WeaveMagisk 主题包装函数 — 移植自 WeaveMask (github.com/Seyud/WeaveMask)
/// 的 WeaveMagiskTheme.kt。
///
/// 根据 colorMode 和 keyColor 创建 ThemeController 并应用 MiuixTheme。
/// 以 MiuixTheme 为唯一真相源，不再用 Material3 ColorScheme 作为主色。
///
/// colorMode 取值:
/// 0 = 跟随系统
/// 1 = 亮色
/// 2 = 暗色
/// 3 = Monet 跟随系统
/// 4 = Monet 亮色
/// 5 = Monet 暗色
///
/// @param colorMode 颜色模式，0-5
/// @param keyColor Monet 种子色，null 表示使用系统壁纸色
/// @param content 子组件
@Composable
fun WeaveMagiskTheme(
    colorMode: Int = 0,
    keyColor: Color? = null,
    content: @Composable () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val isMonetTheme = colorMode in 3..5
    val monetDark = when (colorMode) {
        4 -> false
        5 -> true
        else -> isDark
    }
    // 当 Monet 且无 keyColor（用系统壁纸色）时，直读框架 system_* 颜色资源，
    // 绕过 miuix 的 platformDynamicColors，让 root 模式下伪造的 overlay 也能反映。
    val effectiveSystemMonetColors = if (isMonetTheme && keyColor == null) {
        rememberEffectiveSystemMonetColors(dark = monetDark)
    } else {
        null
    }
    val controller = when (colorMode) {
        1 -> ThemeController(ColorSchemeMode.Light)
        2 -> ThemeController(ColorSchemeMode.Dark)
        3 -> ThemeController(
            ColorSchemeMode.MonetSystem,
            keyColor = keyColor,
            isDark = isDark
        )
        4 -> ThemeController(
            ColorSchemeMode.MonetLight,
            keyColor = keyColor,
        )
        5 -> ThemeController(
            ColorSchemeMode.MonetDark,
            keyColor = keyColor,
        )
        else -> ThemeController(ColorSchemeMode.System)
    }
    CompositionLocalProvider(LocalIsMonetTheme provides isMonetTheme) {
        val rawColors = effectiveSystemMonetColors ?: controller.currentColors()
        MiuixTheme(
            colors = rawColors,
            content = content
        )
    }
}
