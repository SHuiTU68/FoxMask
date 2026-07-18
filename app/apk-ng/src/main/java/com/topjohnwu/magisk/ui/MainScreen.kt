package com.topjohnwu.magisk.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.arch.VMFactory
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.model.module.LocalModule
import com.topjohnwu.magisk.ui.component.FloatingBottomBar
import com.topjohnwu.magisk.ui.component.FloatingBottomBarItem
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
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
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
    val useFloatingNavGlass = LocalFloatingNavGlass.current
    val scope = rememberCoroutineScope()
    // 悬浮底栏 backdrop: 捕获 HorizontalPager 内容作为模糊/折射源。
    // 无条件创建（rememberLayerBackdrop 是 @Composable，不能放在条件分支里），
    // 仅在悬浮底栏开启时挂到 pager 上；液态玻璃关闭时 backdrop 不被使用，无开销。
    val floatingBackdrop = rememberLayerBackdrop()

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .let { if (useFloatingNav) it.layerBackdrop(floatingBackdrop) else it },
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
            // 悬浮底栏（移植自 KernelSU miuix 主题）：圆角胶囊形 + 可选液态玻璃。
            // isBlurEnabled 控制是否启用 miuix-blur 的 Backdrop + AGSL lens 折射/色散。
            FloatingBottomBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        bottom = 12.dp + WindowInsets.navigationBars
                            .asPaddingValues().calculateBottomPadding(),
                        start = 24.dp,
                        end = 24.dp,
                    ),
                selectedIndex = { pagerState.currentPage },
                onSelected = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                backdrop = floatingBackdrop,
                tabsCount = visibleTabs.size,
                isBlurEnabled = useFloatingNavGlass,
            ) {
                visibleTabs.forEachIndexed { index, tab ->
                    FloatingBottomBarItem(
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        modifier = Modifier.defaultMinSize(minWidth = 76.dp)
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(tab.iconRes),
                            contentDescription = stringResource(tab.titleRes),
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = stringResource(tab.titleRes),
                            fontSize = 11.sp,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        } else {
            StandardNavigationBar(
                pagerState = pagerState,
                visibleTabs = visibleTabs,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/// 标准底栏：贴合屏幕底部、无圆角、无悬浮，与原始 Material3 风格一致。
@Composable
private fun StandardNavigationBar(
    pagerState: PagerState,
    visibleTabs: List<Tab>,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    NavigationBar(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer
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
