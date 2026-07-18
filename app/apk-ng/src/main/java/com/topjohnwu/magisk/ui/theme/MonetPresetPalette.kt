package com.topjohnwu.magisk.ui.theme

/// Monet 预设种子色调色板 — 移植自 WeaveMask (github.com/Seyud/WeaveMask)
/// 的 MonetPresetPalette.kt。
///
/// 15 个预设 keyColor，选中后由 Material Kolor 库通过
/// HCT (Hue-Chroma-Tone) 算法生成整套 Material3 ColorScheme。
/// keyColor=0 表示使用系统壁纸动态色（Android 12+ 走原生 Material You）。
internal object MonetPresetPalette {
    /// 15 个预设种子色（ARGB），顺序与 UI 下拉一致
    val presetKeyColors: List<Int> = listOf(
        0xFFF44336.toInt(), // Red
        0xFFE91E63.toInt(), // Pink
        0xFF9C27B0.toInt(), // Purple
        0xFF673AB7.toInt(), // DeepPurple
        0xFF3F51B5.toInt(), // Indigo
        0xFF2196F3.toInt(), // Blue
        0xFF00BCD4.toInt(), // Cyan
        0xFF009688.toInt(), // Teal
        0xFF4FAF50.toInt(), // Green
        0xFFFFEB3B.toInt(), // Yellow
        0xFFFFC107.toInt(), // Amber
        0xFFFF9800.toInt(), // Orange
        0xFF795548.toInt(), // Brown
        0xFF607D8F.toInt(), // BlueGrey
        0xFFFF9CA8.toInt(), // Sakura
    )

    /// 预设色显示名称（与 presetKeyColors 一一对应）
    val presetColorNames: List<String> = listOf(
        "Red", "Pink", "Purple", "Deep Purple", "Indigo", "Blue",
        "Cyan", "Teal", "Green", "Yellow", "Amber", "Orange",
        "Brown", "Blue Grey", "Sakura",
    )

    fun contains(keyColor: Int): Boolean = presetKeyColors.contains(keyColor)
}
