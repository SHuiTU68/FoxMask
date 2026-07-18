package com.topjohnwu.magisk.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.topjohnwu.magisk.core.Config

object ThemeState {
    var colorMode by mutableIntStateOf(Config.colorMode)
    var floatingNav by mutableStateOf(Config.floatingNav)
    var colorTheme by mutableIntStateOf(Config.colorTheme)
    var keyColor by mutableIntStateOf(Config.keyColor)
}

/// 通过 CompositionLocal 向下层组件暴露悬浮底栏开关，
/// 避免到处直接读 ThemeState，也便于在 Preview 中覆盖。
val LocalFloatingNav = staticCompositionLocalOf { true }

/// 是否使用 Magisk 原始 md2 主题样式（已弃用，保留兼容旧引用，恒为 false）。
val LocalMd2Style = staticCompositionLocalOf { false }

/// Magisk 原始 md2 主题的视觉常量（已弃用，保留兼容旧引用）。
object MagiskMd2 {
    val cardCornerRadius = 8.dp
    val cardElevation = 0.dp
}

/// 主题入口（Original 唯一主题）。
///
/// colorMode 取值:
/// 0 = 跟随系统 / 1 = 亮色 / 2 = 暗色
/// 3 = Monet 跟随系统 / 4 = Monet 亮色 / 5 = Monet 暗色
///
/// Monet 模式（3/4/5）:
/// - Android 12+ 走系统 Material You (dynamicLightColorScheme/dynamicDarkColorScheme)
///   读取系统壁纸动态色；若设置了 keyColor 则作为种子色混入 surface 各级，
///   让用户选的颜色在底栏/主页/功能背景上能被肉眼看到。
/// - Android < 12 退化为对应非 Monet 模式（默认 M3 scheme）。
///
/// keyColor: Monet 种子色，0 表示使用系统壁纸色。
@Composable
fun MagiskTheme(
    content: @Composable () -> Unit
) {
    val mode = ThemeState.colorMode
    val useFloatingNav = ThemeState.floatingNav

    val keyColorInt = ThemeState.keyColor
    val seedColor = if (keyColorInt == 0) null else Color(keyColorInt)

    val isDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val isMonet = mode in 3..5

    val isDarkTheme = when (mode) {
        1, 4 -> false
        2, 5 -> true
        else -> isDark
    }

    val useDynamicColor = isMonet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val baseScheme = when {
        useDynamicColor && isDarkTheme -> dynamicDarkColorScheme(context)
        useDynamicColor && !isDarkTheme -> dynamicLightColorScheme(context)
        isDarkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    // 用户选了种子色时，把种子色按比例混入 surface 各级 + 覆盖 primary，
    // 让用户选的颜色在底栏/主页/功能背景上能被肉眼看到。
    // Material You 生成的 surface 在浅色下接近纯白、暗色下接近纯黑，
    // 种子色被稀释到肉眼几乎不可见，需要按较高比例混入。
    // 浅色比例更高(0.4)：底色太亮，需要更多种子色才能显色；
    // 暗色比例稍低(0.25)：暗色底本身吸光，种子色更容易显现，太高会过暗偏色。
    val colorScheme = if (seedColor != null) {
        applySeedColor(baseScheme, seedColor, isDarkTheme)
    } else {
        baseScheme
    }

    CompositionLocalProvider(
        LocalFloatingNav provides useFloatingNav,
        LocalMd2Style provides false,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}

/// 把种子色应用到 M3 ColorScheme：
/// - primary 直接用种子色，保证按钮/选中态显眼；
/// - surface 各级按比例混入种子色，让背景也带色调；
/// - 不动 onPrimary/onSurface，保证文字对比度。
private fun applySeedColor(scheme: ColorScheme, seed: Color, isDark: Boolean): ColorScheme {
    val tintAmount = if (isDark) 0.25f else 0.4f
    val surfaceTinted = lerp(scheme.surface, seed, tintAmount)
    val surfaceContainerTinted = lerp(scheme.surfaceContainer, seed, tintAmount)
    val surfaceContainerHighTinted = lerp(scheme.surfaceContainerHigh, seed, tintAmount)
    val surfaceContainerHighestTinted = lerp(scheme.surfaceContainerHighest, seed, tintAmount)
    val surfaceContainerLowTinted = lerp(scheme.surfaceContainerLow, seed, tintAmount)
    val surfaceContainerLowestTinted = lerp(scheme.surfaceContainerLowest, seed, tintAmount)
    val backgroundTinted = lerp(scheme.background, seed, tintAmount)
    return scheme.copy(
        primary = seed,
        surface = surfaceTinted,
        surfaceContainer = surfaceContainerTinted,
        surfaceContainerHigh = surfaceContainerHighTinted,
        surfaceContainerHighest = surfaceContainerHighestTinted,
        surfaceContainerLow = surfaceContainerLowTinted,
        surfaceContainerLowest = surfaceContainerLowestTinted,
        background = backgroundTinted,
    )
}
