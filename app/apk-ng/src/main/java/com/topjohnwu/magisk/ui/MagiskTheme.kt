package com.topjohnwu.magisk.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
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
import androidx.compose.ui.unit.dp
import com.topjohnwu.magisk.core.Config
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme

object ThemeState {
    var colorMode by mutableIntStateOf(Config.colorMode)
    var uiStyle by mutableIntStateOf(Config.uiStyle)
    var floatingNav by mutableStateOf(Config.floatingNav)
    var blurEffect by mutableStateOf(Config.blurEffect)
    var blurIntensity by mutableIntStateOf(Config.blurIntensity)
    var colorTheme by mutableIntStateOf(Config.colorTheme)
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
/// 毛玻璃模糊半径（dp），用户可调，仅在 LocalBlurEnabled=true 时生效。
val LocalBlurIntensity = staticCompositionLocalOf { 24 }

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

// MIUI 风格颜色方案 — 直接取自 Miuix 库（top.yukonga.miuix.kmp 0.9.3）默认配色，
// 与 WeaveMask（github.com/Seyud/WeaveMask）的 MIUI 主题完全一致。
// WeaveMask 自身不定义任何颜色，100% 使用 Miuix 库默认值；此处把 Miuix 的
// Colors 角色映射到 Material3 ColorScheme，供 Material3 组件使用。
// MiuixTheme 本身已用 miuixLightColorScheme()/miuixDarkColorScheme()，
// 这样 Miuix 组件与 Material3 组件颜色完全统一。
private val MiuixLightColors = lightColorScheme(
    primary = Color(0xFF3482FF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF5D9BFF),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFFE6E6E6),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF0F0F0),
    onSecondaryContainer = Color(0xFFA9A9A9),
    tertiary = Color(0xFF3482FF),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEAF2FF),
    onTertiaryContainer = Color(0xFF3482FF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFF7F7F7),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF8C93B0),
    surfaceTint = Color(0xFF3482FF),
    outline = Color(0xFFD9D9D9),
    outlineVariant = Color(0xFFE0E0E0),
    error = Color(0xFFE94634),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDF6F4),
    onErrorContainer = Color(0xFF410002),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE8E8E8),
    surfaceContainerHighest = Color(0xFFE8E8E8),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
)

private val MiuixDarkColors = darkColorScheme(
    primary = Color(0xFF277AF7),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF338FE4),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFF505050),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF434343),
    onSecondaryContainer = Color(0xFF7C7C7C),
    tertiary = Color(0xFF4788FF),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF2B3B54),
    onTertiaryContainer = Color(0xFF4788FF),
    background = Color(0xFF242424),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF242424),
    onSurfaceVariant = Color(0xFF787E96),
    surfaceTint = Color(0xFF277AF7),
    outline = Color(0xFF404040),
    outlineVariant = Color(0xFF393939),
    error = Color(0xFFF12522),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF2E0603),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceContainer = Color(0xFF242424),
    surfaceContainerHigh = Color(0xFF242424),
    surfaceContainerHighest = Color(0xFF2D2D2D),
    surfaceContainerLow = Color(0xFF242424),
    surfaceContainerLowest = Color(0xFF000000),
)

/// Magisk 原始主题颜色 — 直接取自上游 app/apk 模块的 Piplup 主题
/// （app/apk/src/main/res/values/themes_md2.xml 及 values-night/themes_md2.xml）
/// 上游 apk-ng 本身没有自定义颜色（默认 M3 紫），真正的 Magisk 原始蓝色
/// 主题只存在于 apk 模块的 XML 资源里。这里把 Piplup 主题的颜色值映射到
/// Material3 ColorScheme，作为第一套（Original）主题。
///
/// 上游 XML 颜色映射：
///   colorPrimary      -> primary
///   colorOnPrimary    -> onPrimary
///   colorSecondary    -> secondary
///   colorOnSecondary  -> onSecondary
///   colorSurface      -> surface / background
///   colorSurfaceVariant -> surfaceVariant
///   colorOnSurface    -> onSurface / onBackground
///   colorOnSurfaceVariant -> onSurfaceVariant
///   colorError        -> error
///   colorOnError      -> onError
/// 上游未定义的 M3 字段（primaryContainer/tertiary/outline 等）保留 M3 默认值。
private val MagiskOriginalLightColors = lightColorScheme(
    primary = Color(0xFF4EAFF5),
    onPrimary = Color(0xFFF9F9F9),
    secondary = Color(0xFF3E78AF),
    onSecondary = Color(0xFFF9F9F9),
    background = Color(0xFFF9F9F9),
    surface = Color(0xFFF9F9F9),
    surfaceVariant = Color(0xFFEEEEEE),
    onSurface = Color(0xFF444444),
    onBackground = Color(0xFF444444),
    onSurfaceVariant = Color(0xFF444444),
    error = Color(0xFFCC0047),
    onError = Color(0xFFF9F9F9),
)

private val MagiskOriginalDarkColors = darkColorScheme(
    primary = Color(0xFF4EAFF5),
    onPrimary = Color(0xFFF9F9F9),
    secondary = Color(0xFF3E78AF),
    onSecondary = Color(0xFFF9F9F9),
    background = Color(0xFF0D0D0D),
    surface = Color(0xFF0D0D0D),
    surfaceVariant = Color(0xFF171717),
    onSurface = Color(0xFFD8D8D8),
    onBackground = Color(0xFFD8D8D8),
    onSurfaceVariant = Color(0xFFBABABA),
    error = Color(0xFFEF8282),
    onError = Color(0xFF0D0D0D),
)

/// 颜色主题（Color Theme）—— 与 UI Style 正交的色调覆盖。
/// colorTheme == 0 时不覆盖，使用各 UI Style 的默认色。
/// 樱花色刻意降低饱和度，避免过于鲜艳：
///   用 #E8A0B8（淡樱粉）而非 #FF69B4（亮粉），配合中性背景。

/// 樱花色 — 柔和淡粉，不鲜艳
private val SakuraLightColors = lightColorScheme(
    primary = Color(0xFFE8A0B8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFAD8E2),
    onPrimaryContainer = Color(0xFF3E1A24),
    secondary = Color(0xFFC4849A),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFFB89A8E),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFDF8F9),
    onBackground = Color(0xFF221A1C),
    surface = Color(0xFFFDF8F9),
    onSurface = Color(0xFF221A1C),
    surfaceVariant = Color(0xFFF3E8EA),
    onSurfaceVariant = Color(0xFF524347),
    surfaceTint = Color(0xFFE8A0B8),
    outline = Color(0xFF847276),
    outlineVariant = Color(0xFFD6C2C5),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

private val SakuraDarkColors = darkColorScheme(
    primary = Color(0xFFE8A0B8),
    onPrimary = Color(0xFF4A1A28),
    primaryContainer = Color(0xFF6B3548),
    onPrimaryContainer = Color(0xFFFAD8E2),
    secondary = Color(0xFFD8A8B8),
    onSecondary = Color(0xFF3E1A24),
    tertiary = Color(0xFFD4B5A8),
    onTertiary = Color(0xFF3A261E),
    background = Color(0xFF140D0F),
    onBackground = Color(0xFFE8DCDE),
    surface = Color(0xFF140D0F),
    onSurface = Color(0xFFE8DCDE),
    surfaceVariant = Color(0xFF4A3A3E),
    onSurfaceVariant = Color(0xFFD6C2C5),
    surfaceTint = Color(0xFFE8A0B8),
    outline = Color(0xFF9E8C90),
    outlineVariant = Color(0xFF4A3A3E),
    error = Color(0xFFEF8282),
    onError = Color(0xFF4E0808),
)

/// 翠绿色
private val EmeraldLightColors = lightColorScheme(
    primary = Color(0xFF2E9E6E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC4EDD6),
    onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF4F6354),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF3B6470),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF6FBF6),
    onBackground = Color(0xFF171D18),
    surface = Color(0xFFF6FBF6),
    onSurface = Color(0xFF171D18),
    surfaceVariant = Color(0xFFDCE5DC),
    onSurfaceVariant = Color(0xFF424942),
    surfaceTint = Color(0xFF2E9E6E),
    outline = Color(0xFF727972),
    outlineVariant = Color(0xFFC2C9C2),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

private val EmeraldDarkColors = darkColorScheme(
    primary = Color(0xFF7AD6A4),
    onPrimary = Color(0xFF003823),
    primaryContainer = Color(0xFF1B5E3F),
    onPrimaryContainer = Color(0xFFC4EDD6),
    secondary = Color(0xFFB4CCB0),
    onSecondary = Color(0xFF1F2A22),
    tertiary = Color(0xFFA2CEDA),
    onTertiary = Color(0xFF013540),
    background = Color(0xFF0E1510),
    onBackground = Color(0xFFE0E8E0),
    surface = Color(0xFF0E1510),
    onSurface = Color(0xFFE0E8E0),
    surfaceVariant = Color(0xFF424942),
    onSurfaceVariant = Color(0xFFC2C9C2),
    surfaceTint = Color(0xFF7AD6A4),
    outline = Color(0xFF8C938C),
    outlineVariant = Color(0xFF424942),
    error = Color(0xFFEF8282),
    onError = Color(0xFF4E0808),
)

/// 金黄色
private val GoldLightColors = lightColorScheme(
    primary = Color(0xFFD4A028),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFCE4A6),
    onPrimaryContainer = Color(0xFF2A1C00),
    secondary = Color(0xFF6B5D40),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF4B645D),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFCFAF5),
    onBackground = Color(0xFF1D1B16),
    surface = Color(0xFFFCFAF5),
    onSurface = Color(0xFF1D1B16),
    surfaceVariant = Color(0xFFEDE2D0),
    onSurfaceVariant = Color(0xFF4D4639),
    surfaceTint = Color(0xFFD4A028),
    outline = Color(0xFF7F7667),
    outlineVariant = Color(0xFFD0C7B5),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

private val GoldDarkColors = darkColorScheme(
    primary = Color(0xFFE8C26A),
    onPrimary = Color(0xFF402D00),
    primaryContainer = Color(0xFF5D4400),
    onPrimaryContainer = Color(0xFFFCE4A6),
    secondary = Color(0xFFD4C2A0),
    onSecondary = Color(0xFF383020),
    tertiary = Color(0xFFB2CCC2),
    onTertiary = Color(0xFF1E352E),
    background = Color(0xFF15120A),
    onBackground = Color(0xFFE8E2D5),
    surface = Color(0xFF15120A),
    onSurface = Color(0xFFE8E2D5),
    surfaceVariant = Color(0xFF4D4639),
    onSurfaceVariant = Color(0xFFD0C7B5),
    surfaceTint = Color(0xFFE8C26A),
    outline = Color(0xFF99907F),
    outlineVariant = Color(0xFF4D4639),
    error = Color(0xFFEF8282),
    onError = Color(0xFF4E0808),
)

@Composable
fun MagiskTheme(
    content: @Composable () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val mode = ThemeState.colorMode
    val context = LocalContext.current
    val useMiuix = ThemeState.uiStyle == 1
    // 悬浮底栏和毛玻璃是 MIUI 模式专属特性，
    // Original 模式始终用标准 M3 底栏、无毛玻璃，保持 Magisk 原始观感。
    val useBlur = BlurState.enabled && useMiuix
    val useFloatingNav = ThemeState.floatingNav && useMiuix
    // Original 模式启用 md2 样式（上游 app/apk 的 WidgetFoundation.Card 风格）
    val useMd2Style = !useMiuix

    val isDarkTheme = when (mode) {
        1 -> false
        2 -> true
        3 -> isDark
        4 -> false
        5 -> true
        else -> isDark
    }

    val useDynamicColor = mode in listOf(3, 4, 5) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // 颜色主题（colorTheme）—— 与 UI Style 正交的色调覆盖。
    // 0=默认（跟随 UI Style），1=樱花，2=翠绿，3=金黄。
    // colorTheme != 0 时覆盖默认色与动态色，保证用户选择的色调生效。
    val colorTheme = ThemeState.colorTheme

    val colorScheme = when {
        colorTheme == 1 && isDarkTheme -> SakuraDarkColors
        colorTheme == 1 && !isDarkTheme -> SakuraLightColors
        colorTheme == 2 && isDarkTheme -> EmeraldDarkColors
        colorTheme == 2 && !isDarkTheme -> EmeraldLightColors
        colorTheme == 3 && isDarkTheme -> GoldDarkColors
        colorTheme == 3 && !isDarkTheme -> GoldLightColors
        useMiuix && isDarkTheme -> MiuixDarkColors
        useMiuix && !isDarkTheme -> MiuixLightColors
        useDynamicColor && isDarkTheme -> dynamicDarkColorScheme(context)
        useDynamicColor && !isDarkTheme -> dynamicLightColorScheme(context)
        isDarkTheme -> MagiskOriginalDarkColors
        else -> MagiskOriginalLightColors
    }

    // Original 模式用 md2 的 8dp 圆角 Shapes（对应上游 @dimen/l_50），
    // MIUI 模式用 M3 默认 Shapes。
    val shapes = if (useMd2Style) {
        Shapes(
            small = RoundedCornerShape(MagiskMd2.cardCornerRadius),
            medium = RoundedCornerShape(MagiskMd2.cardCornerRadius),
            large = RoundedCornerShape(MagiskMd2.cardCornerRadius),
        )
    } else {
        Shapes()
    }

    val wrapped = @Composable {
        CompositionLocalProvider(
            LocalBlurEnabled provides useBlur,
            LocalFloatingNav provides useFloatingNav,
            LocalMd2Style provides useMd2Style,
            LocalBlurIntensity provides ThemeState.blurIntensity,
        ) {
            MaterialTheme(
                colorScheme = colorScheme,
                shapes = shapes,
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
