package com.topjohnwu.magisk.ui.kpatch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.topjohnwu.magisk.core.R as CoreR

/**
 * KernelPatch / KPM 管理页面。
 *
 * 显示：
 * - kpatch 安装状态与版本
 * - 修补 boot / 嵌入 KPM / 加载 KPM 操作入口
 * - 已加载 KPM 列表（卸载、控制）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KpmScreen(
    viewModel: KpmViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // boot picker：选完 boot 后弹对话框让用户选择是否嵌入 KPM
    var pendingBootUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showEmbedChoiceDialog by remember { mutableStateOf(false) }
    val bootPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingBootUri = uri
        showEmbedChoiceDialog = true
    }

    // 选完 boot 后，如果用户选"嵌入 KPM"，再弹 KPM picker
    val kpmPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val bootUri = pendingBootUri
        pendingBootUri = null
        if (uri == null || bootUri == null) return@rememberLauncherForActivityResult
        viewModel.patchBoot(bootUri, uri) { out ->
            if (out != null) {
                viewModel.showSnackbar(context.getString(CoreR.string.settings_kpatch_patch_done, out))
            } else {
                viewModel.showSnackbar(context.getString(CoreR.string.failure))
            }
        }
    }

    // 加载 KPM picker
    val loadKpmPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.loadKpm(uri, "")
    }

    // 控制 KPM 参数对话框
    var controlTarget by remember { mutableStateOf<KpmItem?>(null) }
    var controlArgs by remember { mutableStateOf("") }

    // 控制结果对话框
    var controlResult by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uiState.message) {
        if (uiState.message != null && controlTarget != null) {
            controlResult = uiState.message
        }
    }

    LaunchedEffect(Unit) {
        viewModel.startLoading()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(CoreR.string.settings_kpatch_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }, enabled = !busy) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { loadKpmPicker.launch("*/*") },
                shape = CircleShape,
                content = {
                    Icon(Icons.Default.Add, contentDescription = stringResource(CoreR.string.settings_kpatch_kpm_load))
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(padding)
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(bottom = 160.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 状态卡片
            item { StatusCard(uiState, busy) }

            // 操作卡片
            item {
                ActionsCard(
                    onPatchBoot = {
                        viewModel.clearPatchLog()
                        bootPicker.launch("*/*")
                    },
                    enabled = !busy,
                )
            }

            // 修补/嵌入过程的实时日志区（APatch 风格：终端日志流，自动滚底，可选中复制）
            if (uiState.patchLog.isNotEmpty()) {
                item { PatchLogCard(uiState) }
            }

            // 已加载 KPM 列表
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(CoreR.string.settings_kpatch_kpm_list_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }

            if (uiState.loading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (uiState.items.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(CoreR.string.settings_kpatch_kpm_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(uiState.items, key = { it.name }) { item ->
                    KpmItemCard(
                        item = item,
                        onUnload = { viewModel.unloadKpm(item.name) },
                        onControl = {
                            controlTarget = item
                            controlArgs = ""
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    // 选完 boot 后的"是否嵌入 KPM"对话框（APatch 风格）
    if (showEmbedChoiceDialog) {
        AlertDialog(
            onDismissRequest = {
                showEmbedChoiceDialog = false
                pendingBootUri = null
            },
            title = { Text(stringResource(CoreR.string.settings_kpatch_embed_choice_title)) },
            text = { Text(stringResource(CoreR.string.settings_kpatch_embed_choice_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showEmbedChoiceDialog = false
                    kpmPicker.launch("*/*")
                }) {
                    Text(stringResource(CoreR.string.settings_kpatch_embed_choice_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showEmbedChoiceDialog = false
                    val bootUri = pendingBootUri
                    pendingBootUri = null
                    if (bootUri == null) return@TextButton
                    viewModel.patchBoot(bootUri, null) { out ->
                        if (out != null) {
                            viewModel.showSnackbar(context.getString(CoreR.string.settings_kpatch_patch_done, out))
                        } else {
                            viewModel.showSnackbar(context.getString(CoreR.string.failure))
                        }
                    }
                }) {
                    Text(stringResource(CoreR.string.settings_kpatch_embed_choice_no))
                }
            },
        )
    }

    // 控制 KPM 参数对话框
    val ctlTarget = controlTarget
    if (ctlTarget != null) {
        AlertDialog(
            onDismissRequest = {
                controlTarget = null
                controlArgs = ""
            },
            title = {
                Text(stringResource(CoreR.string.settings_kpatch_kpm_control_title, ctlTarget.name))
            },
            text = {
                OutlinedTextField(
                    value = controlArgs,
                    onValueChange = { controlArgs = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(CoreR.string.settings_kpatch_kpm_control_hint)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = ctlTarget.name
                        val args = controlArgs
                        controlTarget = null
                        controlArgs = ""
                        viewModel.controlKpm(name, args)
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    controlTarget = null
                    controlArgs = ""
                }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    // 控制结果显示
    val result = controlResult
    if (result != null) {
        AlertDialog(
            onDismissRequest = {
                controlResult = null
                viewModel.clearMessage()
            },
            title = { Text(stringResource(CoreR.string.settings_kpatch_kpm_control_result)) },
            text = {
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall,
                    overflow = TextOverflow.Visible,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    controlResult = null
                    viewModel.clearMessage()
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}

@Composable
private fun StatusCard(state: KpmViewModel.UiState, busy: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(CoreR.string.settings_kpatch_status_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            if (state.kpatchInstalled) {
                Text(
                    text = stringResource(
                        CoreR.string.settings_kpatch_status_installed,
                        state.kpatchVersion ?: "unknown",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    text = stringResource(CoreR.string.settings_kpatch_status_not_installed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(CoreR.string.settings_kpatch_status_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (busy) {
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ActionsCard(
    onPatchBoot: () -> Unit,
    enabled: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(CoreR.string.settings_kpatch_actions_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onPatchBoot,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Build, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(CoreR.string.settings_kpatch_patch_boot))
            }
        }
    }
}

@Composable
private fun KpmItemCard(
    item: KpmItem,
    onUnload: () -> Unit,
    onControl: () -> Unit,
) {
    Column(modifier = Modifier.padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onControl) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(CoreR.string.settings_kpatch_kpm_control))
            }
            IconButton(onClick = onUnload) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(CoreR.string.settings_kpatch_kpm_unload))
            }
        }
        if (item.info.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.info,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 修补/嵌入过程的实时日志卡片（APatch 风格）。
 *
 * - 等宽字体，终端体验
 * - SelectionContainer 包裹，可选中复制
 * - 日志变化时自动滚动到底部
 * - 修补中显示转圈，完成显示成功/失败标识
 */
@Composable
private fun PatchLogCard(state: KpmViewModel.UiState) {
    val scrollState = rememberScrollState()
    // 日志变化时自动滚到底部
    LaunchedEffect(state.patchLog) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.patching) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(16.dp).width(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = "Patching…",
                        style = MaterialTheme.typography.titleSmall,
                    )
                } else if (state.patchDone) {
                    Text(
                        text = "✓ Done",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        text = "✗ Failed",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    text = state.patchLog,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(scrollState),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
        }
    }
}

