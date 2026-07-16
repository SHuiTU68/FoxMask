package com.topjohnwu.magisk.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.drawBehind
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
/// 1. blur 只作用于背景层，不能作用于整个容器，否则图标文字也会被模糊。
/// 2. Modifier.blur() 模糊的是该 composable 的渲染输出。修饰符顺序很关键：
///    必须先 drawBehind 画填充色（产生像素），再 blur 模糊这些像素。
///    之前 blur 在 background 之前，模糊的是空 Box，导致 blur 无效且中间
///    出现透明带（即"空长条" bug）。现在用 drawBehind + blur 保证 blur
///    作用于实际填充色，模糊度通过 LocalBlurIntensity 可调。
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
    val blurRadius = LocalBlurIntensity.current.dp

    // 毛玻璃背景色：半透明 surface 色模拟 frosted glass
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val glassColor = if (isDark) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
    }

    Box(
        modifier = modifier
            .padding(bottom = navBarInset + 12.dp, start = 24.dp, end = 24.dp)
            .shadow(elevation = 6.dp, shape = shape)
            .clip(shape)
            .fillMaxWidth()
            .height(64.dp)
    ) {
        // 背景层：drawBehind 先画填充色，blur 再模糊这些像素。
        // 顺序不能反，否则 blur 模糊的是空内容，出现透明带。
        Box(
            modifier = Modifier
                .matchParentSize()
                .then(
                    if (useBlur && blurRadius > 0.dp) {
                        Modifier
                            .drawBehind { drawRect(glassColor) }
                            .blur(blurRadius)
                    } else {
                        Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
                    }
                )
        )
        // 内容层：图标和文字清晰可见（不受 blur 影响）
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
