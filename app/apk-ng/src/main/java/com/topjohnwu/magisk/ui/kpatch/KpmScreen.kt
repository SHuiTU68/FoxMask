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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.topjohnwu.magisk.core.R as CoreR

/**
 * KernelPatch / KPM 管理页面。
 *
 * 显示：
 * - kpatch 安装状态与版本
 * - superkey 输入与保存
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

    // superkey 输入
    var superkeyInput by remember { mutableStateOf(uiState.superkey) }
    LaunchedEffect(uiState.superkey) {
        if (superkeyInput.isEmpty()) superkeyInput = uiState.superkey
    }

    // 修补 boot picker（superkey 可选，为空时使用默认 "su"）
    val bootPickerSimple = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.patchBoot(uri, superkeyInput) { out ->
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

    // 嵌入 KPM 需要两步：先选 boot，再选 kpm
    var embedBootUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var embedKpmName by remember { mutableStateOf("") }
    var showEmbedNameDialog by remember { mutableStateOf(false) }
    val embedBootPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        embedBootUri = uri
        showEmbedNameDialog = true
    }
    val embedKpmPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val bootUri = embedBootUri
        val name = embedKpmName
        embedBootUri = null
        embedKpmName = ""
        if (uri == null || bootUri == null || name.isEmpty()) return@rememberLauncherForActivityResult
        viewModel.embedKpm(bootUri, uri, name) { out ->
            if (out != null) {
                viewModel.showSnackbar(context.getString(CoreR.string.settings_kpatch_embed_done, out))
            } else {
                viewModel.showSnackbar(context.getString(CoreR.string.failure))
            }
        }
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

            // superkey 设置
            item {
                SuperkeyCard(
                    value = superkeyInput,
                    onValueChange = { superkeyInput = it },
                    onSave = { viewModel.saveSuperkey(superkeyInput) },
                )
            }

            // 操作卡片
            item {
                ActionsCard(
                    onPatchBoot = { bootPickerSimple.launch("*/*") },
                    onEmbedKpm = { embedBootPicker.launch("*/*") },
                    enabled = !busy,
                )
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

    // 嵌入 KPM 名称对话框
    if (showEmbedNameDialog) {
        AlertDialog(
            onDismissRequest = {
                showEmbedNameDialog = false
                embedBootUri = null
            },
            title = { Text(stringResource(CoreR.string.settings_kpatch_embed_kpm_name_title)) },
            text = {
                OutlinedTextField(
                    value = embedKpmName,
                    onValueChange = { embedKpmName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = embedKpmName.isNotEmpty(),
                    onClick = {
                        showEmbedNameDialog = false
                        embedKpmPicker.launch("*/*")
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showEmbedNameDialog = false
                    embedBootUri = null
                }) {
                    Text(stringResource(android.R.string.cancel))
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
private fun SuperkeyCard(
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(CoreR.string.settings_kpatch_superkey_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(CoreR.string.settings_kpatch_superkey_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(CoreR.string.settings_kpatch_superkey_placeholder)) },
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(CoreR.string.settings_kpatch_superkey_save))
            }
        }
    }
}

@Composable
private fun ActionsCard(
    onPatchBoot: () -> Unit,
    onEmbedKpm: () -> Unit,
    enabled: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(CoreR.string.settings_kpatch_actions_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onPatchBoot,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Build, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(CoreR.string.settings_kpatch_patch_boot))
                }
                OutlinedButton(
                    onClick = onEmbedKpm,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(CoreR.string.settings_kpatch_embed_kpm))
                }
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

