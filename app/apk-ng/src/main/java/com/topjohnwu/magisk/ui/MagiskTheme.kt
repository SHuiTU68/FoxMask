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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.ui.theme.LocalIsMonetTheme
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

/// 是否使用 Magisk 原始 md2 主题样式（已弃用，保留兼容旧引用，恒为 false）。
/// Original 模式现在直接用上游纯 Material3 视觉，不再有 md2 分支。
val LocalMd2Style = staticCompositionLocalOf { false }

/// Magisk 原始 md2 主题的视觉常量（已弃用，保留兼容旧引用）。
object MagiskMd2 {
    val cardCornerRadius = 8.dp
    val cardElevation = 0.dp
}

/// 主题入口。
///
/// 双模式架构:
/// - uiStyle=0 (Original): 直接用上游 Magisk 的纯 Material3 主题逻辑，
///   Android 12+ 走系统 Material You (dynamicLightColorScheme/dynamicDarkColorScheme)，
///   低版本/非 Monet 用 M3 默认 scheme。完全绕开 miuix，与上游 UI 视觉一致。
/// - uiStyle=1 (MIUI): WeaveMagiskTheme (MiuixTheme + ThemeController) 为唯一真相源，
///   通过 miuixColorsToM3() 把 miuix Colors 映射回 M3 ColorScheme。
///
/// colorMode 取值:
/// 0 = 跟随系统 / 1 = 亮色 / 2 = 暗色
/// 3 = Monet 跟随系统 / 4 = Monet 亮色 / 5 = Monet 暗色
///
/// uiStyle: 0 = Original (上游纯 M3) / 1 = MIUI (miuix)
/// keyColor: Monet 种子色，0 表示使用系统壁纸色（仅 MIUI 模式生效）
@Composable
fun MagiskTheme(
    content: @Composable () -> Unit
) {
    val mode = ThemeState.colorMode
    val useMiuix = ThemeState.uiStyle == 1
    // 底栏: MIUI 模式按用户 floatingNav 设置(默认悬浮);
    // Original 模式始终用标准底栏(贴底无圆角，对应上游 app/apk 老版观感)。
    val useFloatingNav = useMiuix && ThemeState.floatingNav

    // keyColor: 0 表示使用系统壁纸色（传 null 给 WeaveMagiskTheme），仅 MIUI 模式用
    val keyColorInt = ThemeState.keyColor
    val keyColor = if (keyColorInt == 0) null else Color(keyColorInt)

    if (!useMiuix) {
        // ============ Original 模式：上游纯 Material3 主题 ============
        // 直接复刻上游 app/apk-ng/MagiskTheme.kt 逻辑，绕开 miuix。
        // Original 模式不使用 keyColor，把 Monet 模式归并到对应非 Monet 模式：
        //   1(亮色)/4(Monet亮色) -> 1(亮色)
        //   2(暗色)/5(Monet暗色) -> 2(暗色)
        //   0(系统)/3(Monet系统) -> 0(系统)
        val originalMode = when (mode) {
            1, 4 -> 1
            2, 5 -> 2
            else -> 0
        }
        val isDark = isSystemInDarkTheme()
        val context = LocalContext.current
        val isDarkTheme = when (originalMode) {
            1 -> false
            2 -> true
            else -> isDark
        }
        // Original 模式走系统 Material You (Android 12+)，不用 keyColor 种子色
        val useDynamicColor = originalMode == 3 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        // originalMode 已归并掉 3/4/5，实际不会进 useDynamicColor 分支；
        // 保留逻辑与上游一致，若未来放开 Monet 归并则自动生效。
        val colorScheme = when {
            useDynamicColor && isDarkTheme -> dynamicDarkColorScheme(context)
            useDynamicColor && !isDarkTheme -> dynamicLightColorScheme(context)
            isDarkTheme -> darkColorScheme()
            else -> lightColorScheme()
        }
        CompositionLocalProvider(
            LocalFloatingNav provides useFloatingNav,
            LocalMd2Style provides false,
            LocalIsMonetTheme provides false,
        ) {
            MaterialTheme(
                colorScheme = colorScheme,
                content = content
            )
        }
        return
    }

    // ============ MIUI 模式：miuix 主题 ============
    val wrapped = @Composable {
        CompositionLocalProvider(
            LocalFloatingNav provides useFloatingNav,
            LocalMd2Style provides false,
        ) {
            // MaterialTheme 用 MiuixTheme 当前的 colors 映射，保证 M3 组件颜色一致
            val miuixColors = MiuixTheme.colorScheme
            // 仅在 Monet 模式且用户选了种子色时，把种子色作为 seedColor 传入，
            // 用于在 surface 上叠加可见的色调（Material Kolor 默认 surface tone 太接近白/黑，
            // 种子色在背景上几乎不可见）。
            val isMonet = LocalIsMonetTheme.current
            val seedColor = if (isMonet) keyColor else null
            val m3ColorScheme = miuixColorsToM3(
                miuixColors,
                isOriginal = false,
                seedColor = seedColor
            )
            MaterialTheme(
                colorScheme = m3ColorScheme,
                content = content
            )
        }
    }

    WeaveMagiskTheme(
        colorMode = mode,
        keyColor = keyColor,
        content = wrapped
    )
}

/// 把 miuix Colors 映射到 Material3 ColorScheme，
/// 让 Material3 组件（MaterialTheme.colorScheme.xxx）与 MiuixTheme 颜色一致。
///
/// Surface 层级映射要点（miuix 实际颜色值参考）：
///   浅色: surface=0xFFF7F7F7, surfaceContainer=White, surfaceContainerHigh=0xFFE8E8E8
///   暗色: surface=Black, surfaceContainer=0xFF242424, surfaceContainerHighest=0xFF2D2D2D
///
/// seedColor（Monet 种子色）:
///   Material Kolor 生成的 surface tone 太接近白(98)/黑(10)，用户选的种子色
///   (如橘子色)在背景上几乎不可见。当 seedColor != null 时，把种子色按一定比例
///   混入 surface 各级，让用户选的颜色在底栏/主页/功能背景上能被肉眼看到。
///
/// 所有背景统一(unified)，不做分层：
///   - 顶栏/底栏/卡片/功能背景全部用同一个 unified 颜色，消除分割。
///   - 暗色用 surfaceContainer(0xFF242424 深灰，避免纯黑刺眼)。
///   - 浅色：Original 用 surface(0xFFF7F7F7 浅白)，MIUI 用 surfaceContainerHigh(0xFFE8E8E8 浅灰)。
private fun miuixColorsToM3(
    c: top.yukonga.miuix.kmp.theme.Colors,
    isOriginal: Boolean = false,
    seedColor: Color? = null
): androidx.compose.material3.ColorScheme {
    val isDark = c.background.luminance() < 0.5f

    // Monet 种子色混入比例。
    // Material Kolor 生成的 surface 在浅色下是 tone 98(几乎纯白)、暗色下 tone 10(几乎纯黑)，
    // 种子色被稀释到肉眼几乎不可见。需要把纯种子色按较高比例混入 surface 各级，
    // 用户才能在底栏/主页/功能背景上看到自己选的颜色。
    // 浅色比例更高(0.4)：tone 98 的底色太亮，需要更多种子色才能显色；
    // 暗色比例稍低(0.25)：暗色底本身吸光，种子色更容易显现，太高会过暗偏色。
    // 用 seedColor != null 直接判断，保证 lerp() 拿到非空 Color（智能转换生效）。
    val tintAmount = if (seedColor == null) 0f else if (isDark) 0.25f else 0.4f
    val surface = if (seedColor != null) lerp(c.surface, seedColor, tintAmount) else c.surface
    val surfaceContainer =
        if (seedColor != null) lerp(c.surfaceContainer, seedColor, tintAmount) else c.surfaceContainer
    val surfaceContainerHigh =
        if (seedColor != null) lerp(c.surfaceContainerHigh, seedColor, tintAmount) else c.surfaceContainerHigh

    // 所有 surface 角色统一为单个 unified 值，消除顶栏/底栏/卡片/背景的分割。
    // - 暗色: surfaceContainer(深灰 0xFF242424，避免纯黑)
    // - Original 浅色: surface(浅白 0xFFF7F7F7)
    // - MIUI 浅色: surfaceContainerHigh(浅灰 0xFFE8E8E8，保留 MIUI 灰底基调)
    val unified = when {
        isDark -> surfaceContainer
        isOriginal -> surface
        else -> surfaceContainerHigh
    }

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
            background = unified,
            onBackground = c.onBackground,
            surface = unified,
            onSurface = c.onSurface,
            surfaceVariant = unified,
            onSurfaceVariant = c.onSurfaceVariantSummary,
            surfaceTint = c.primary,
            outline = c.outline,
            outlineVariant = c.dividerLine,
            error = c.error,
            onError = c.onError,
            errorContainer = c.errorContainer,
            onErrorContainer = c.onErrorContainer,
            surfaceContainer = unified,
            surfaceContainerHigh = unified,
            surfaceContainerHighest = unified,
            surfaceContainerLow = unified,
            surfaceContainerLowest = unified,
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
            background = unified,
            onBackground = c.onBackground,
            surface = unified,
            onSurface = c.onSurface,
            surfaceVariant = unified,
            onSurfaceVariant = c.onSurfaceVariantSummary,
            surfaceTint = c.primary,
            outline = c.outline,
            outlineVariant = c.dividerLine,
            error = c.error,
            onError = c.onError,
            errorContainer = c.errorContainer,
            onErrorContainer = c.onErrorContainer,
            surfaceContainer = unified,
            surfaceContainerHigh = unified,
            surfaceContainerHighest = unified,
            surfaceContainerLow = unified,
            surfaceContainerLowest = unified,
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
