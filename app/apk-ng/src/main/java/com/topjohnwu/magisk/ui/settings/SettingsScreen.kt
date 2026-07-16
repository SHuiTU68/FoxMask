package com.topjohnwu.magisk.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutManagerCompat
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.isRunningAsStub
import com.topjohnwu.magisk.core.utils.LocaleSetting
import com.topjohnwu.magisk.core.utils.MediaStoreUtils
import com.topjohnwu.magisk.ui.BlurState
import com.topjohnwu.magisk.ui.ThemeState
import com.topjohnwu.magisk.ui.component.SettingsArrow
import com.topjohnwu.magisk.ui.component.SettingsDropdown
import com.topjohnwu.magisk.ui.component.SettingsSectionCard
import com.topjohnwu.magisk.ui.component.SettingsSlider
import com.topjohnwu.magisk.ui.component.SettingsSwitch
import com.topjohnwu.magisk.ui.component.AdaptiveSmallTitle
import com.topjohnwu.magisk.ui.component.SmallTitle
import com.topjohnwu.magisk.core.R as CoreR

/// 从 Context 递归解包出 Activity（LocalContext 返回的可能是 ContextWrapper）
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(CoreR.string.settings)) },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 12.dp)
                .padding(bottom = 88.dp)
        ) {
            CustomizationSection(viewModel)
            Spacer(Modifier.height(12.dp))
            AppSettingsSection()
            if (Info.env.isActive) {
                Spacer(Modifier.height(12.dp))
                MagiskSection(viewModel)
            }
            if (Info.showSuperUser) {
                Spacer(Modifier.height(12.dp))
                SuperuserSection(viewModel)
            }
        }
    }
}

// --- Customization ---

@Composable
private fun CustomizationSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current

    AdaptiveSmallTitle(text = stringResource(CoreR.string.settings_customization))
    SettingsSectionCard(modifier = Modifier.fillMaxWidth()) {
        if (LocaleSetting.useLocaleManager) {
            val locale = LocaleSetting.instance.appLocale
            val summary = locale?.getDisplayName(locale) ?: stringResource(CoreR.string.system_default)
            SettingsArrow(
                title = stringResource(CoreR.string.language),
                summary = summary,
                onClick = {
                    context.startActivity(LocaleSetting.localeSettingsIntent)
                }
            )
        } else {
            val names = remember { LocaleSetting.available.names }
            val tags = remember { LocaleSetting.available.tags }
            var selectedIndex by remember {
                mutableIntStateOf(tags.indexOf(Config.locale).coerceAtLeast(0))
            }
            SettingsDropdown(
                title = stringResource(CoreR.string.language),
                items = names.toList(),
                selectedIndex = selectedIndex,
                onSelectedIndexChange = { index ->
                    selectedIndex = index
                    Config.locale = tags[index]
                }
            )
        }

        // Color Mode
        val resources = LocalResources.current
        val colorModeEntries = remember {
            resources.getStringArray(CoreR.array.color_mode).toList()
        }
        var colorMode by remember { mutableIntStateOf(Config.colorMode) }
        SettingsDropdown(
            title = stringResource(CoreR.string.settings_color_mode),
            items = colorModeEntries,
            selectedIndex = colorMode,
            onSelectedIndexChange = { index ->
                colorMode = index
                Config.colorMode = index
                ThemeState.colorMode = index
            }
        )

        // Color Theme — 颜色主题（与 UI Style 正交，覆盖默认色调）
        val colorThemeEntries = remember {
            resources.getStringArray(CoreR.array.color_theme).toList()
        }
        var colorTheme by remember { mutableIntStateOf(Config.colorTheme) }
        SettingsDropdown(
            title = stringResource(CoreR.string.settings_color_theme),
            items = colorThemeEntries,
            selectedIndex = colorTheme,
            onSelectedIndexChange = { index ->
                colorTheme = index
                Config.colorTheme = index
                ThemeState.colorTheme = index
                // 颜色主题切换改变 colorScheme，recreate 确保主题彻底生效
                context.findActivity()?.recreate()
            }
        )

        // UI Style
        val uiStyleOriginal = stringResource(CoreR.string.settings_ui_style_original)
        val uiStyleMiuix = stringResource(CoreR.string.settings_ui_style_miuix)
        val uiStyleEntries = remember {
            listOf(uiStyleOriginal, uiStyleMiuix)
        }
        var uiStyle by remember { mutableIntStateOf(Config.uiStyle) }
        SettingsDropdown(
            title = stringResource(CoreR.string.settings_ui_style_title),
            items = uiStyleEntries,
            selectedIndex = uiStyle,
            onSelectedIndexChange = { index ->
                uiStyle = index
                Config.uiStyle = index
                ThemeState.uiStyle = index
                // UI Style 切换会改变 Compose 树结构（添加/移除 MiuixTheme 包裹），
                // 单纯的 recompose 可能导致内部子树被复用而无法完全切换。
                // recreate Activity 确保整个 Compose 树从零重建，主题彻底生效。
                context.findActivity()?.recreate()
            }
        )

        // 悬浮底栏 & 毛玻璃是 MIUI 模式专属特性，Original 模式不显示这两个开关。
        // Original 模式始终用标准 M3 底栏、无毛玻璃，保持 Magisk 原始观感。
        if (ThemeState.uiStyle == 1) {
            // Floating bottom bar — 悬浮底栏开关（圆角胶囊形，悬浮于内容之上）
            var floatingNav by remember { mutableStateOf(Config.floatingNav) }
            SettingsSwitch(
                title = stringResource(CoreR.string.settings_floating_nav_title),
                summary = stringResource(CoreR.string.settings_floating_nav_summary),
                checked = floatingNav,
                onCheckedChange = {
                    floatingNav = it
                    Config.floatingNav = it
                    ThemeState.floatingNav = it
                }
            )

            // iOS-style blur — 毛玻璃开关（仅 Android 12+ 生效，低版本自动回退）
            val blurSupported = BlurState.supported
            var blurEffect by remember { mutableStateOf(Config.blurEffect) }
            SettingsSwitch(
                title = stringResource(CoreR.string.settings_blur_effect_title),
                summary = if (blurSupported) {
                    stringResource(CoreR.string.settings_blur_effect_summary)
                } else {
                    "${stringResource(CoreR.string.settings_blur_effect_summary)} (Android 12+)"
                },
                checked = blurEffect && blurSupported,
                enabled = blurSupported,
                onCheckedChange = {
                    blurEffect = it
                    Config.blurEffect = it
                    ThemeState.blurEffect = it
                    // 毛玻璃开关影响 Compose 修饰符链结构，recreate 确保底栏背景彻底重建
                    context.findActivity()?.recreate()
                }
            )

            // Blur intensity — 毛玻璃模糊度滑块（仅 blur 开启时可用）
            var blurIntensity by remember { mutableIntStateOf(Config.blurIntensity) }
            SettingsSlider(
                title = stringResource(CoreR.string.settings_blur_intensity_title),
                summary = "${blurIntensity}dp",
                value = blurIntensity.toFloat(),
                valueRange = 4f..64f,
                enabled = blurEffect && blurSupported,
                onValueChange = {
                    val intVal = it.toInt()
                    blurIntensity = intVal
                    Config.blurIntensity = intVal
                    ThemeState.blurIntensity = intVal
                }
            )
        }

        if (isRunningAsStub && ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            SettingsArrow(
                title = stringResource(CoreR.string.add_shortcut_title),
                summary = stringResource(CoreR.string.setting_add_shortcut_summary),
                onClick = { viewModel.requestAddShortcut() }
            )
        }
    }
}

// --- App Settings ---

@Composable
private fun AppSettingsSection() {
    val context = LocalContext.current
    val resources = LocalResources.current

    AdaptiveSmallTitle(text = stringResource(CoreR.string.home_app_title))
    SettingsSectionCard(modifier = Modifier.fillMaxWidth()) {
        // Update Channel
        val updateChannelEntries = remember {
            resources.getStringArray(CoreR.array.update_channel).toList()
        }
        var updateChannel by remember {
            mutableIntStateOf(Config.updateChannel.coerceIn(0, updateChannelEntries.size - 1))
        }
        var showUrlDialog by remember { mutableStateOf(false) }

        SettingsDropdown(
            title = stringResource(CoreR.string.settings_update_channel_title),
            items = updateChannelEntries,
            selectedIndex = updateChannel,
            onSelectedIndexChange = { index ->
                updateChannel = index
                Config.updateChannel = index
                Info.resetUpdate()
                if (index == Config.Value.CUSTOM_CHANNEL && Config.customChannelUrl.isBlank()) {
                    showUrlDialog = true
                }
            }
        )

        // Update Channel URL (for custom channel)
        if (updateChannel == Config.Value.CUSTOM_CHANNEL) {
            UpdateChannelUrlDialog(
                show = showUrlDialog,
                onDismiss = { showUrlDialog = false }
            )
            SettingsArrow(
                title = stringResource(CoreR.string.settings_update_custom),
                summary = Config.customChannelUrl.ifBlank { null },
                onClick = { showUrlDialog = true }
            )
        }

        // DoH Toggle
        var doh by remember { mutableStateOf(Config.doh) }
        SettingsSwitch(
            title = stringResource(CoreR.string.settings_doh_title),
            summary = stringResource(CoreR.string.settings_doh_description),
            checked = doh,
            onCheckedChange = {
                doh = it
                Config.doh = it
            }
        )

        // Update Checker
        var checkUpdate by remember { mutableStateOf(Config.checkUpdate) }
        SettingsSwitch(
            title = stringResource(CoreR.string.settings_check_update_title),
            summary = stringResource(CoreR.string.settings_check_update_summary),
            checked = checkUpdate,
            onCheckedChange = { newValue ->
                checkUpdate = newValue
                Config.checkUpdate = newValue
            }
        )

        // Download Path
        var showDownloadDialog by remember { mutableStateOf(false) }
        DownloadPathDialog(
            show = showDownloadDialog,
            onDismiss = { showDownloadDialog = false }
        )
        SettingsArrow(
            title = stringResource(CoreR.string.settings_download_path_title),
            summary = MediaStoreUtils.fullPath(Config.downloadDir),
            onClick = {
                showDownloadDialog = true
            }
        )

        // Random Package Name
        var randName by remember { mutableStateOf(Config.randName) }
        SettingsSwitch(
            title = stringResource(CoreR.string.settings_random_name_title),
            summary = stringResource(CoreR.string.settings_random_name_description),
            checked = randName,
            onCheckedChange = {
                randName = it
                Config.randName = it
            }
        )
    }
}

// --- Magisk ---

@Composable
private fun MagiskSection(viewModel: SettingsViewModel) {
    AdaptiveSmallTitle(text = stringResource(CoreR.string.magisk))
    SettingsSectionCard(modifier = Modifier.fillMaxWidth()) {
        // Systemless Hosts
        SettingsArrow(
            title = stringResource(CoreR.string.settings_hosts_title),
            summary = stringResource(CoreR.string.settings_hosts_summary),
            onClick = { viewModel.createHosts() }
        )

        if (Const.Version.atLeast_24_0()) {
            // Zygisk
            var zygisk by remember { mutableStateOf(Config.zygisk) }
            SettingsSwitch(
                title = stringResource(CoreR.string.zygisk),
                summary = stringResource(
                    if (zygisk != Info.isZygiskEnabled) CoreR.string.reboot_apply_change
                    else CoreR.string.settings_zygisk_summary
                ),
                checked = zygisk,
                onCheckedChange = {
                    zygisk = it
                    Config.zygisk = it
                    viewModel.notifyZygiskChange()
                }
            )

            // SuList 白名单模式
            var suList by remember { mutableStateOf(Config.suList) }
            SettingsSwitch(
                title = stringResource(CoreR.string.settings_sulist_title),
                summary = stringResource(
                    if (suList != Info.isSuListEnabled) CoreR.string.reboot_apply_change
                    else CoreR.string.settings_sulist_summary
                ),
                checked = suList,
                onCheckedChange = {
                    suList = it
                    Config.suList = it
                    viewModel.notifySuListChange()
                }
            )

            // 可选模块挂载
            var mountModules by remember { mutableStateOf(Config.mountModules) }
            SettingsSwitch(
                title = stringResource(CoreR.string.settings_mount_modules_title),
                summary = stringResource(
                    if (mountModules != Info.isMountModulesEnabled) CoreR.string.reboot_apply_change
                    else CoreR.string.settings_mount_modules_summary
                ),
                checked = mountModules,
                onCheckedChange = {
                    mountModules = it
                    Config.mountModules = it
                    viewModel.notifyMountModulesChange()
                }
            )

            // DenyList
            val denyListEnabled by viewModel.denyListEnabled.collectAsState()
            SettingsSwitch(
                title = stringResource(CoreR.string.settings_denylist_title),
                summary = stringResource(CoreR.string.settings_denylist_summary),
                checked = denyListEnabled,
                onCheckedChange = { viewModel.toggleDenyList(it) }
            )

            // DenyList Config
            SettingsArrow(
                title = stringResource(CoreR.string.settings_denylist_config_title),
                summary = stringResource(CoreR.string.settings_denylist_config_summary),
                onClick = { viewModel.navigateToDenyList() }
            )
        }
    }
}

// --- Superuser ---

@Composable
private fun SuperuserSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val resources = LocalResources.current

    AdaptiveSmallTitle(text = stringResource(CoreR.string.superuser))
    SettingsSectionCard(modifier = Modifier.fillMaxWidth()) {
        // Tapjack (SDK < S)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            var tapjack by remember { mutableStateOf(Config.suTapjack) }
            SettingsSwitch(
                title = stringResource(CoreR.string.settings_su_tapjack_title),
                summary = stringResource(CoreR.string.settings_su_tapjack_summary),
                checked = tapjack,
                onCheckedChange = {
                    tapjack = it
                    Config.suTapjack = it
                }
            )
        }

        // Authentication
        var suAuth by remember { mutableStateOf(Config.suAuth) }
        SettingsSwitch(
            title = stringResource(CoreR.string.settings_su_auth_title),
            summary = stringResource(
                if (Info.isDeviceSecure) CoreR.string.settings_su_auth_summary
                else CoreR.string.settings_su_auth_insecure
            ),
            checked = suAuth,
            enabled = Info.isDeviceSecure,
            onCheckedChange = { newValue ->
                viewModel.withAuth {
                    suAuth = newValue
                    Config.suAuth = newValue
                }
            }
        )

        // Access Mode
        val accessEntries = remember {
            resources.getStringArray(CoreR.array.su_access).toList()
        }
        var accessMode by remember { mutableIntStateOf(Config.rootMode) }
        SettingsDropdown(
            title = stringResource(CoreR.string.superuser_access),
            items = accessEntries,
            selectedIndex = accessMode,
            onSelectedIndexChange = {
                accessMode = it
                Config.rootMode = it
            }
        )

        // Multiuser Mode
        val multiuserEntries = remember {
            resources.getStringArray(CoreR.array.multiuser_mode).toList()
        }
        val multiuserDescriptions = remember {
            resources.getStringArray(CoreR.array.multiuser_summary).toList()
        }
        var multiuserMode by remember { mutableIntStateOf(Config.suMultiuserMode) }
        SettingsDropdown(
            title = stringResource(CoreR.string.multiuser_mode),
            summary = multiuserDescriptions.getOrElse(multiuserMode) { "" },
            items = multiuserEntries,
            selectedIndex = multiuserMode,
            enabled = Const.USER_ID == 0,
            onSelectedIndexChange = {
                multiuserMode = it
                Config.suMultiuserMode = it
            }
        )

        // Mount Namespace Mode
        val namespaceEntries = remember {
            resources.getStringArray(CoreR.array.namespace).toList()
        }
        val namespaceDescriptions = remember {
            resources.getStringArray(CoreR.array.namespace_summary).toList()
        }
        var mntNamespaceMode by remember { mutableIntStateOf(Config.suMntNamespaceMode) }
        SettingsDropdown(
            title = stringResource(CoreR.string.mount_namespace_mode),
            summary = namespaceDescriptions.getOrElse(mntNamespaceMode) { "" },
            items = namespaceEntries,
            selectedIndex = mntNamespaceMode,
            onSelectedIndexChange = {
                mntNamespaceMode = it
                Config.suMntNamespaceMode = it
            }
        )

        // Automatic Response
        val autoResponseEntries = remember {
            resources.getStringArray(CoreR.array.auto_response).toList()
        }
        var autoResponse by remember { mutableIntStateOf(Config.suAutoResponse) }
        SettingsDropdown(
            title = stringResource(CoreR.string.auto_response),
            items = autoResponseEntries,
            selectedIndex = autoResponse,
            onSelectedIndexChange = { newIndex ->
                val doIt = {
                    autoResponse = newIndex
                    Config.suAutoResponse = newIndex
                }
                if (Config.suAuth) viewModel.withAuth(doIt) else doIt()
            }
        )

        // Request Timeout
        val timeoutEntries = remember {
            resources.getStringArray(CoreR.array.request_timeout).toList()
        }
        val timeoutValues = remember { listOf(10, 15, 20, 30, 45, 60) }
        var timeoutIndex by remember {
            mutableIntStateOf(timeoutValues.indexOf(Config.suDefaultTimeout).coerceAtLeast(0))
        }
        SettingsDropdown(
            title = stringResource(CoreR.string.request_timeout),
            items = timeoutEntries,
            selectedIndex = timeoutIndex,
            onSelectedIndexChange = {
                timeoutIndex = it
                Config.suDefaultTimeout = timeoutValues[it]
            }
        )

        // SU Notification
        val notifEntries = remember {
            resources.getStringArray(CoreR.array.su_notification).toList()
        }
        var suNotification by remember { mutableIntStateOf(Config.suNotification) }
        SettingsDropdown(
            title = stringResource(CoreR.string.superuser_notification),
            items = notifEntries,
            selectedIndex = suNotification,
            onSelectedIndexChange = {
                suNotification = it
                Config.suNotification = it
            }
        )

        // Reauthenticate (SDK < O)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            var reAuth by remember { mutableStateOf(Config.suReAuth) }
            SettingsSwitch(
                title = stringResource(CoreR.string.settings_su_reauth_title),
                summary = stringResource(CoreR.string.settings_su_reauth_summary),
                checked = reAuth,
                onCheckedChange = {
                    reAuth = it
                    Config.suReAuth = it
                }
            )
        }

        // Restrict (version >= 30.1)
        if (Const.Version.atLeast_30_1()) {
            var restrict by remember { mutableStateOf(Config.suRestrict) }
            SettingsSwitch(
                title = stringResource(CoreR.string.settings_su_restrict_title),
                summary = stringResource(CoreR.string.settings_su_restrict_summary),
                checked = restrict,
                onCheckedChange = {
                    restrict = it
                    Config.suRestrict = it
                }
            )
        }
    }
}

// --- Dialogs ---

@Composable
private fun UpdateChannelUrlDialog(show: Boolean, onDismiss: () -> Unit) {
    val showState = rememberSaveable { mutableStateOf(show) }
    showState.value = show
    var url by rememberSaveable { mutableStateOf(Config.customChannelUrl) }

    if (showState.value) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(CoreR.string.settings_update_custom_msg)) },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        Config.customChannelUrl = url
                        Info.resetUpdate()
                        onDismiss()
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}

@Composable
private fun DownloadPathDialog(show: Boolean, onDismiss: () -> Unit) {
    val showState = rememberSaveable { mutableStateOf(show) }
    showState.value = show
    var path by rememberSaveable { mutableStateOf(Config.downloadDir) }

    if (showState.value) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(CoreR.string.settings_download_path_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(CoreR.string.settings_download_path_message, MediaStoreUtils.fullPath(path)),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        Config.downloadDir = path
                        onDismiss()
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}
