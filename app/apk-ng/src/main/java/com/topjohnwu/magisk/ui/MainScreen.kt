package com.topjohnwu.magisk.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.arch.VMFactory
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.model.module.LocalModule
import com.topjohnwu.magisk.ui.home.HomeScreen
import com.topjohnwu.magisk.ui.home.HomeViewModel
import com.topjohnwu.magisk.ui.install.InstallViewModel
import com.topjohnwu.magisk.ui.log.LogScreen
import com.topjohnwu.magisk.ui.log.LogViewModel
import com.topjohnwu.magisk.ui.module.ModuleScreen
import com.topjohnwu.magisk.ui.module.ModuleViewModel
import com.topjohnwu.magisk.ui.navigation.CollectNavEvents
import com.topjohnwu.magisk.ui.navigation.LocalNavigator
import com.topjohnwu.magisk.ui.settings.SettingsScreen
import com.topjohnwu.magisk.ui.settings.SettingsViewModel
import com.topjohnwu.magisk.ui.superuser.SuperuserScreen
import com.topjohnwu.magisk.ui.superuser.SuperuserViewModel
import kotlinx.coroutines.launch
import com.topjohnwu.magisk.core.R as CoreR

enum class Tab(val titleRes: Int, val iconRes: Int) {
    MODULES(CoreR.string.modules, R.drawable.ic_module),
    SUPERUSER(CoreR.string.superuser, CoreR.drawable.ic_superuser),
    HOME(CoreR.string.section_home, R.drawable.ic_home),
    LOG(CoreR.string.logs, R.drawable.ic_bug),
    SETTINGS(CoreR.string.settings, R.drawable.ic_settings);
}

@Composable
fun MainScreen(initialTab: Int = Tab.HOME.ordinal) {
    val navigator = LocalNavigator.current
    val visibleTabs = remember {
        Tab.entries.filter { tab ->
            when (tab) {
                Tab.SUPERUSER -> Info.showSuperUser
                Tab.MODULES -> Info.env.isActive && LocalModule.loaded()
                else -> true
            }
        }
    }
    val initialPage = visibleTabs.indexOf(Tab.entries[initialTab]).coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { visibleTabs.size })

    val useFloatingNav = LocalFloatingNav.current

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = visibleTabs.size - 1,
            userScrollEnabled = true,
        ) { page ->
            when (visibleTabs[page]) {
                Tab.HOME -> {
                    val vm: HomeViewModel = viewModel(factory = VMFactory)
                    val installVm: InstallViewModel = viewModel(factory = VMFactory)
                    LaunchedEffect(Unit) { vm.startLoading() }
                    CollectNavEvents(vm, navigator)
                    CollectNavEvents(installVm, navigator)
                    HomeScreen(vm, installVm)
                }
                Tab.SUPERUSER -> {
                    val activity = LocalActivity.current as MainActivity
                    val vm: SuperuserViewModel = viewModel(viewModelStoreOwner = activity, factory = VMFactory)
                    LaunchedEffect(Unit) {
                        vm.authenticate = { onSuccess ->
                            activity.extension.withAuthentication { if (it) onSuccess() }
                        }
                        vm.startLoading()
                    }
                    SuperuserScreen(vm)
                }
                Tab.LOG -> {
                    val vm: LogViewModel = viewModel(factory = VMFactory)
                    LaunchedEffect(Unit) { vm.startLoading() }
                    LogScreen(vm)
                }
                Tab.MODULES -> {
                    val vm: ModuleViewModel = viewModel(factory = VMFactory)
                    LaunchedEffect(Unit) { vm.startLoading() }
                    CollectNavEvents(vm, navigator)
                    ModuleScreen(vm)
                }
                Tab.SETTINGS -> {
                    val activity = LocalActivity.current as MainActivity
                    val vm: SettingsViewModel = viewModel(factory = VMFactory)
                    LaunchedEffect(Unit) {
                        vm.authenticate = { onSuccess ->
                            activity.extension.withAuthentication { if (it) onSuccess() }
                        }
                    }
                    CollectNavEvents(vm, navigator)
                    SettingsScreen(vm)
                }
            }
        }

        if (useFloatingNav) {
            FloatingNavigationBar(
                pagerState = pagerState,
                visibleTabs = visibleTabs,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        } else {
            StandardNavigationBar(
                pagerState = pagerState,
                visibleTabs = visibleTabs,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/// 悬浮底栏：圆角胶囊形，带 iOS 风格毛玻璃背景（开启 blur 时），
/// 参考 KernelSU-Next 的 miuix 主题样式。
///
/// 实现要点：
/// 1. 不用 Modifier.blur() —— 它模糊的是当前 composable 自己渲染的像素，
///    无法模糊背后内容，且在纯色填充上会产生边缘透明扩散（即"中间白条"
///    和边缘发暗的伪影）。
/// 2. 毛玻璃质感用「半透明 surface 色 + 细微垂直渐变」模拟：顶部稍亮、
///    底部稍暗，形成 frosted glass 的层次感，颜色与主题背景一致。
/// 3. 模糊度（LocalBlurIntensity）映射为渐变强度与不透明度：
///    模糊度越大 → 背景越不透明（遮住背后内容越多，模拟更强磨砂）。
@Composable
private fun FloatingNavigationBar(
    pagerState: PagerState,
    visibleTabs: List<Tab>,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(28.dp)
    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val useBlur = LocalBlurEnabled.current
    val blurIntensity = LocalBlurIntensity.current

    // 毛玻璃背景色：基于 surface 色，半透明叠加在页面内容之上。
    // 模糊度越大，不透明度越高（更强的磨砂遮盖感）。
    // 模糊度范围 4..64，映射到不透明度 0.55..0.85。
    val baseAlpha = (0.5f + (blurIntensity - 4) * 0.006f).coerceIn(0.5f, 0.9f)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 渐变两端颜色：顶部略亮、底部略暗，模拟玻璃折射光感。
    // 与主题 surface 同色系，保证与背景协调。
    val topColor = surfaceColor.copy(alpha = baseAlpha)
    val bottomColor = if (isDark) {
        surfaceColor.copy(alpha = (baseAlpha + 0.08f).coerceAtMost(0.95f))
    } else {
        surfaceColor.copy(alpha = (baseAlpha - 0.05f).coerceAtLeast(0.4f))
    }
    val glassBrush = remember(topColor, bottomColor) {
        Brush.verticalGradient(
            colors = listOf(topColor, bottomColor),
            tileMode = TileMode.Clamp
        )
    }

    Box(
        modifier = modifier
            .padding(bottom = navBarInset + 12.dp, start = 24.dp, end = 24.dp)
            .shadow(elevation = 6.dp, shape = shape)
            .clip(shape)
            .fillMaxWidth()
            .height(64.dp)
    ) {
        // 背景层：半透明渐变模拟毛玻璃，不用 blur 避免边缘伪影。
        // useBlur=false 时用不透明 surfaceContainer，与标准底栏一致。
        Box(
            modifier = Modifier
                .matchParentSize()
                .then(
                    if (useBlur) {
                        Modifier.background(glassBrush, shape = RectangleShape)
                    } else {
                        Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
                    }
                )
        )
        // 内容层：图标和文字清晰可见（不受背景层影响）
        Row(
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            visibleTabs.forEachIndexed { index, tab ->
                FloatingNavItem(
                    icon = ImageVector.vectorResource(tab.iconRes),
                    label = stringResource(tab.titleRes),
                    selected = pagerState.currentPage == index,
                    enabled = true,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun FloatingNavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(200),
        label = "navItemColor"
    )

    Column(
        modifier = modifier
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                role = Role.Tab,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(24.dp),
            tint = contentColor,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = contentColor,
        )
    }
}

/// 标准底栏：贴合屏幕底部、无圆角、无悬浮，与原始 Material3 风格一致。
/// 开启 blur 时让系统 NavigationBar 自身的背景半透明，底层内容滚动可见。
@Composable
private fun StandardNavigationBar(
    pagerState: PagerState,
    visibleTabs: List<Tab>,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val useBlur = LocalBlurEnabled.current

    NavigationBar(
        modifier = modifier.fillMaxWidth(),
        containerColor = if (useBlur) {
            // 半透明背景，让下方内容透出模拟毛玻璃（NavigationBar 本身不直接 blur 内容）
            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }
    ) {
        visibleTabs.forEachIndexed { index, tab ->
            NavigationBarItem(
                selected = pagerState.currentPage == index,
                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                icon = {
                    Icon(
                        imageVector = ImageVector.vectorResource(tab.iconRes),
                        contentDescription = null
                    )
                },
                label = { Text(stringResource(tab.titleRes)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            )
        }
    }
}

/// 工具：获取颜色亮度（0~1），用于判断深/浅色模式
private fun Color.luminance(): Float {
    val r = red
    val g = green
    val b = blue
    return 0.299f * r + 0.587f * g + 0.114f * b
}
