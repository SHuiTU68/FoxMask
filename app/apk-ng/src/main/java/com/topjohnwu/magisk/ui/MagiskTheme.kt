package com.topjohnwu.magisk.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.topjohnwu.magisk.core.Config
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme

object ThemeState {
    var colorMode by mutableIntStateOf(Config.colorMode)
    var uiStyle by mutableIntStateOf(Config.uiStyle)
    var floatingNav by mutableStateOf(Config.floatingNav)
    var blurEffect by mutableStateOf(Config.blurEffect)
}

/// 毛玻璃效果开关与可用性检测
object BlurState {
    /// 设备是否支持真正的实时高斯模糊（Android 12+ 才有 RenderEffect/Modifier.blur 硬件加速）
    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /// 当前是否启用毛玻璃（同时满足用户开关和系统支持）
    val enabled: Boolean
        get() = supported && ThemeState.blurEffect
}

/// 通过 CompositionLocal 向下层组件暴露毛玻璃开关与悬浮底栏开关，
/// 避免到处直接读 ThemeState，也便于在 Preview 中覆盖。
val LocalBlurEnabled = staticCompositionLocalOf { false }
val LocalFloatingNav = staticCompositionLocalOf { true }

// MIUI 风格颜色方案 — 让 MIUI 主题视觉上明显区别于原始主题
private val MiuixLightColors = lightColorScheme(
    primary = Color(0xFF2A6CF6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E4FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF565E71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF131C2B),
    tertiary = Color(0xFF705574),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFAD8FB),
    onTertiaryContainer = Color(0xFF29132E),
    background = Color(0xFFF5F6F9),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF43474E),
    surfaceTint = Color(0xFF2A6CF6),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6CF)
)

private val MiuixDarkColors = darkColorScheme(
    primary = Color(0xFF5C8DF6),
    onPrimary = Color(0xFF002B6B),
    primaryContainer = Color(0xFF0E3F9E),
    onPrimaryContainer = Color(0xFFD8E4FF),
    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283041),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFFDDBCE0),
    onTertiary = Color(0xFF3F2844),
    tertiaryContainer = Color(0xFF573E5B),
    onTertiaryContainer = Color(0xFFFAD8FB),
    background = Color(0xFF0D0D0D),
    onBackground = Color(0xFFE3E3E6),
    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFE3E3E6),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC4C6CF),
    surfaceTint = Color(0xFF5C8DF6),
    outline = Color(0xFF8E9199),
    outlineVariant = Color(0xFF43474E)
)

/// Magisk 原始主题颜色（Piplup 主题，蓝色 #4EAFF5）
/// 还原旧版 View/XML 时代 Magisk 自带的蓝色主题观感，
/// 让"原始 UI"模式不再退回到 Material3 默认紫色。
private val MagiskOriginalLightColors = lightColorScheme(
    primary = Color(0xFF4EAFF5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC9E7FB),
    onPrimaryContainer = Color(0xFF001E36),
    secondary = Color(0xFF3E78AF),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E3FF),
    onSecondaryContainer = Color(0xFF0A1F33),
    tertiary = Color(0xFF4A93B8),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCDE8F8),
    onTertiaryContainer = Color(0xFF001E2C),
    background = Color(0xFFF9F9F9),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFF9F9F9),
    onSurface = Color(0xFF444444),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474E),
    surfaceTint = Color(0xFF4EAFF5),
    outline = Color(0xFF747680),
    outlineVariant = Color(0xFFC4C6D0),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF)
)

private val MagiskOriginalDarkColors = darkColorScheme(
    primary = Color(0xFF4EAFF5),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF1E4F75),
    onPrimaryContainer = Color(0xFFC9E7FB),
    secondary = Color(0xFFA6C8E8),
    onSecondary = Color(0xFF103049),
    secondaryContainer = Color(0xFF284761),
    onSecondaryContainer = Color(0xFFC6DBF3),
    tertiary = Color(0xFFA1D0E5),
    onTertiary = Color(0xFF013543),
    tertiaryContainer = Color(0xFF224B5B),
    onTertiaryContainer = Color(0xFFBDEAFB),
    background = Color(0xFF0D0D0D),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF0D0D0D),
    onSurface = Color(0xFFD8D8D8),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC4C6CF),
    surfaceTint = Color(0xFF4EAFF5),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474E),
    error = Color(0xFFEF8282),
    onError = Color(0xFF4E0808)
)

@Composable
fun MagiskTheme(
    content: @Composable () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val mode = ThemeState.colorMode
    val context = LocalContext.current
    val useMiuix = ThemeState.uiStyle == 1
    val useBlur = BlurState.enabled
    val useFloatingNav = ThemeState.floatingNav

    val isDarkTheme = when (mode) {
        1 -> false
        2 -> true
        3 -> isDark
        4 -> false
        5 -> true
        else -> isDark
    }

    val useDynamicColor = mode in listOf(3, 4, 5) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // 原始模式用 Magisk 自带蓝色主题（Piplup #4EAFF5），
    // MIUI 模式用 miuix 风格颜色，动态色由系统取色。
    val colorScheme = when {
        useMiuix && isDarkTheme -> MiuixDarkColors
        useMiuix && !isDarkTheme -> MiuixLightColors
        useDynamicColor && isDarkTheme -> dynamicDarkColorScheme(context)
        useDynamicColor && !isDarkTheme -> dynamicLightColorScheme(context)
        isDarkTheme -> MagiskOriginalDarkColors
        else -> MagiskOriginalLightColors
    }

    val wrapped = @Composable {
        CompositionLocalProvider(
            LocalBlurEnabled provides useBlur,
            LocalFloatingNav provides useFloatingNav,
        ) {
            MaterialTheme(
                colorScheme = colorScheme,
                content = content
            )
        }
    }

    if (useMiuix) {
        // MIUI 模式：MiuixTheme 提供 miuix 组件上下文，MaterialTheme 用 miuix 颜色
        MiuixTheme(
            colors = if (isDarkTheme) miuixDarkColorScheme() else miuixLightColorScheme()
        ) {
            wrapped()
        }
    } else {
        // 原始模式：纯标准 Material3，零 miuix 影响
        wrapped()
    }
}
