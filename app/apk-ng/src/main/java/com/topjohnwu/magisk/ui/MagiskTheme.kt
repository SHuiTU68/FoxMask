package com.topjohnwu.magisk.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.ui.theme.WeaveMagiskTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme

object ThemeState {
    var colorMode by mutableIntStateOf(Config.colorMode)
    var uiStyle by mutableIntStateOf(Config.uiStyle)
    var floatingNav by mutableStateOf(Config.floatingNav)
    var colorTheme by mutableIntStateOf(Config.colorTheme)
    var keyColor by mutableIntStateOf(Config.keyColor)
}

/// 通过 CompositionLocal 向下层组件暴露悬浮底栏开关，
/// 避免到处直接读 ThemeState，也便于在 Preview 中覆盖。
val LocalFloatingNav = staticCompositionLocalOf { true }

/// 是否使用 Magisk 原始 md2 主题样式（Original 模式下为 true）。
/// 下层组件据此切换卡片圆角/阴影/背景色等 md2 视觉特征。
val LocalMd2Style = staticCompositionLocalOf { false }

/// Magisk 原始 md2 主题的视觉常量。
/// 直接取自上游 app/apk 模块的 styles_md2_impl.xml / dimens.xml：
///   - WidgetFoundation.Card: cardCornerRadius=@dimen/l_50(8dp), cardElevation=0dp,
///     cardBackgroundColor=?colorSurfaceVariant
///   - WidgetFoundation.Card.Primary: cardBackgroundColor=?colorPrimary
///   - 首页 Magisk 卡标题: textColor=?colorPrimary
/// 这些常量仅在 Original 模式下被组件引用。
object MagiskMd2 {
    val cardCornerRadius = 8.dp
    val cardElevation = 0.dp
}

/// 主题入口 — 移植自 WeaveMask (github.com/Seyud/WeaveMask) 的主题架构。
///
/// 以 MiuixTheme 为唯一真相源（通过 WeaveMagiskTheme/ThemeController 控制），
/// 同时把 MiuixTheme 的 Colors 映射到 Material3 ColorScheme，
/// 供现有 Material3 组件使用（MaterialTheme.colorScheme.xxx）。
///
/// colorMode 取值:
/// 0 = 跟随系统
/// 1 = 亮色
/// 2 = 暗色
/// 3 = Monet 跟随系统
/// 4 = Monet 亮色
/// 5 = Monet 暗色
///
/// uiStyle:
/// 0 = Original (Magisk md2 风格)
/// 1 = MIUI (Miuix 风格)
///
/// keyColor: Monet 种子色，0 表示使用系统壁纸色
@Composable
fun MagiskTheme(
    content: @Composable () -> Unit
) {
    val mode = ThemeState.colorMode
    val useMiuix = ThemeState.uiStyle == 1
    // 悬浮底栏是 MIUI 模式专属特性，
    // Original 模式始终用标准 M3 底栏，保持 Magisk 原始观感。
    val useFloatingNav = ThemeState.floatingNav && useMiuix
    // Original 模式启用 md2 样式（上游 app/apk 的 WidgetFoundation.Card 风格）
    val useMd2Style = !useMiuix

    // keyColor: 0 表示使用系统壁纸色（传 null 给 WeaveMagiskTheme）
    val keyColorInt = ThemeState.keyColor
    val keyColor = if (keyColorInt == 0) null else Color(keyColorInt)

    val wrapped = @Composable {
        CompositionLocalProvider(
            LocalFloatingNav provides useFloatingNav,
            LocalMd2Style provides useMd2Style,
        ) {
            // MaterialTheme 用 MiuixTheme 当前的 colors 映射，保证 M3 组件颜色一致
            val miuixColors = MiuixTheme.colorScheme
            val m3ColorScheme = miuixColorsToM3(miuixColors)
            MaterialTheme(
                colorScheme = m3ColorScheme,
                content = content
            )
        }
    }

    if (useMiuix) {
        // MIUI 模式：WeaveMagiskTheme (MiuixTheme + ThemeController) 为唯一真相源
        WeaveMagiskTheme(
            colorMode = mode,
            keyColor = keyColor,
            content = wrapped
        )
    } else {
        // Original 模式：也用 WeaveMagiskTheme（colorMode 0=跟随系统，用 miuix 默认配色），
        // 但通过 LocalMd2Style 让组件切换为 md2 视觉风格。
        // Original 模式不使用 keyColor/Monet，强制 colorMode 为系统/亮/暗之一。
        val originalMode = when (mode) {
            1 -> 1
            2 -> 2
            else -> 0
        }
        WeaveMagiskTheme(
            colorMode = originalMode,
            keyColor = null,
            content = wrapped
        )
    }
}

/// 把 miuix Colors 映射到 Material3 ColorScheme，
/// 让 Material3 组件（MaterialTheme.colorScheme.xxx）与 MiuixTheme 颜色一致。
///
/// Surface 层级映射要点（miuix 实际颜色值参考）：
///   浅色: surface=0xFFF7F7F7, surfaceContainer=White, surfaceContainerHigh=0xFFE8E8E8
///   暗色: surface=Black, surfaceContainer=0xFF242424, surfaceContainerHighest=0xFF2D2D2D
///
/// 1. 顶栏(TopAppBar 未滚动透明)透出 Scaffold 背景 = background，
///    底栏(NavigationBar)用 surfaceContainer。让 background = surfaceContainer，
///    保证顶栏与底栏颜色一致，避免黑色主题下顶栏纯黑、底栏深灰的割裂。
///
/// 2. 浅色模式 surfaceContainer=White(纯白)过亮，改用 surfaceContainerHigh(0xFFE8E8E8)
///    作为 background 和 surfaceContainer，降低整体亮度。
///
/// 3. 卡片用 surfaceVariant，映射为 c.surface（浅色0xFFF7F7F7/暗色Black），
///    与背景(surfaceContainer)形成区分：浅色卡片比背景浅，暗色卡片比背景深(MIUI风格)。
private fun miuixColorsToM3(c: top.yukonga.miuix.kmp.theme.Colors): androidx.compose.material3.ColorScheme {
    val isDark = c.background.luminance() < 0.5f
    return if (isDark) {
        darkColorScheme(
            primary = c.primary,
            onPrimary = c.onPrimary,
            primaryContainer = c.primaryContainer,
            onPrimaryContainer = c.onPrimaryContainer,
            secondary = c.secondary,
            onSecondary = c.onSecondary,
            secondaryContainer = c.secondaryContainer,
            onSecondaryContainer = c.onSecondaryContainer,
            tertiary = c.tertiaryContainer,
            onTertiary = c.onTertiaryContainer,
            tertiaryContainer = c.tertiaryContainer,
            onTertiaryContainer = c.onTertiaryContainer,
            background = c.surfaceContainer,
            onBackground = c.onBackground,
            surface = c.surface,
            onSurface = c.onSurface,
            surfaceVariant = c.surface,
            onSurfaceVariant = c.onSurfaceVariantSummary,
            surfaceTint = c.primary,
            outline = c.outline,
            outlineVariant = c.dividerLine,
            error = c.error,
            onError = c.onError,
            errorContainer = c.errorContainer,
            onErrorContainer = c.onErrorContainer,
            surfaceContainer = c.surfaceContainer,
            surfaceContainerHigh = c.surfaceContainerHigh,
            surfaceContainerHighest = c.surfaceContainerHighest,
            surfaceContainerLow = c.surface,
            surfaceContainerLowest = c.surface,
        )
    } else {
        lightColorScheme(
            primary = c.primary,
            onPrimary = c.onPrimary,
            primaryContainer = c.primaryContainer,
            onPrimaryContainer = c.onPrimaryContainer,
            secondary = c.secondary,
            onSecondary = c.onSecondary,
            secondaryContainer = c.secondaryContainer,
            onSecondaryContainer = c.onSecondaryContainer,
            tertiary = c.tertiaryContainer,
            onTertiary = c.onTertiaryContainer,
            tertiaryContainer = c.tertiaryContainer,
            onTertiaryContainer = c.onTertiaryContainer,
            background = c.surfaceContainerHigh,
            onBackground = c.onBackground,
            surface = c.surface,
            onSurface = c.onSurface,
            surfaceVariant = c.surface,
            onSurfaceVariant = c.onSurfaceVariantSummary,
            surfaceTint = c.primary,
            outline = c.outline,
            outlineVariant = c.dividerLine,
            error = c.error,
            onError = c.onError,
            errorContainer = c.errorContainer,
            onErrorContainer = c.onErrorContainer,
            surfaceContainer = c.surfaceContainerHigh,
            surfaceContainerHigh = c.surfaceContainerHigh,
            surfaceContainerHighest = c.surfaceContainerHighest,
            surfaceContainerLow = c.surface,
            surfaceContainerLowest = c.surfaceContainer,
        )
    }
}

/// 工具：获取颜色亮度（0~1），用于判断深/浅色模式
private fun Color.luminance(): Float {
    val r = red
    val g = green
    val b = blue
    return 0.299f * r + 0.587f * g + 0.114f * b
}
