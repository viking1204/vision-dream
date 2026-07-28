package io.github.xororz.localdream.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.xororz.localdream.data.PerformancePreset
import io.github.xororz.localdream.data.PerformancePresetBinding
import io.github.xororz.localdream.mcp.AndroidMcpPresetStore
import io.github.xororz.localdream.mcp.McpPresetStore
import io.github.xororz.localdream.navigation.popBackStackIfResumed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DEFAULT_CONFIG_JSON =
    "{\"schemaVersion\":1,\"engine\":{\"sdxlLowRam\":false,\"animaLowRam\":false,\"animaSequentialDit\":false}}"

/**
 * Local, authenticated editor for the versioned performance presets.  The UI
 * calls the same repository adapter as MCP so name, revision and fallback
 * rules cannot diverge by entry point.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformancePresetScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
    presetStore: McpPresetStore? = null,
) {
    val context = LocalContext.current
    val store = presetStore ?: remember { AndroidMcpPresetStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var presets by remember { mutableStateOf(emptyList<PerformancePreset>()) }
    var editing by remember { mutableStateOf<PerformancePreset?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<PerformancePreset?>(null) }
    var exportEnvelope by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    fun refresh() = scope.launch {
        presets = withContext(Dispatchers.IO) { store.list() }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { presets = withContext(Dispatchers.IO) { store.list() } }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("性能预设") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStackIfResumed() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { scope.launch { exportEnvelope = withContext(Dispatchers.IO) { store.exportEnvelope() } } }) {
                        Text("导出")
                    }
                    TextButton(onClick = { importing = true }) { Text("导入") }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { creating = true },
                modifier = Modifier.testTag("performance-preset-create"),
            ) {
                Icon(Icons.Default.Add, "新建预设")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "已受理任务使用当时的不可变快照；编辑或删除不会改变排队任务。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(presets, key = PerformancePreset::id) { preset ->
                PresetCard(
                    preset = preset,
                    onEdit = { editing = preset },
                    onDelete = { deleting = preset },
                    onBindDefault = {
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) { store.bind(PerformancePresetBinding.DEFAULT, preset.id) }
                            }.onSuccess { snackbar.showSnackbar("已绑定为默认预设") }
                                .onFailure { snackbar.showSnackbar("绑定失败：${it.message}") }
                        }
                    },
                )
            }
        }
    }
    if (creating) {
        PresetEditorDialog(null, onDismiss = { creating = false }) { name, selector, config, modelId ->
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        store.create(name, selector, config).also { created ->
                            modelId.takeIf(String::isNotBlank)?.let { store.bind(PerformancePresetBinding.model(it), created.id) }
                        }
                    }
                }.onSuccess {
                    creating = false
                    refresh()
                }
                    .onFailure { snackbar.showSnackbar("保存失败：${it.message}") }
            }
        }
    }
    editing?.let { preset ->
        PresetEditorDialog(preset, onDismiss = { editing = null }) { name, selector, config, modelId ->
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        store.update(preset.id, preset.revision, name, selector, config).also { updated ->
                            modelId.takeIf(String::isNotBlank)?.let { store.bind(PerformancePresetBinding.model(it), updated.id) }
                        }
                    }
                }.onSuccess {
                    editing = null
                    refresh()
                }
                    .onFailure { snackbar.showSnackbar("保存失败：${it.message}") }
            }
        }
    }
    deleting?.let { preset ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除 ${preset.name}？") },
            text = { Text("未来默认或模型绑定会原子回退到 Compatibility fallback。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { store.delete(preset.id) } }
                            .onSuccess { result ->
                                val suffix = result.reboundBindingKeys.takeIf(List<String>::isNotEmpty)
                                    ?.joinToString(prefix = "；已回退：") ?: ""
                                snackbar.showSnackbar(if (result.deleted) "已删除$suffix" else "Compatibility fallback 不能删除")
                                deleting = null
                                refresh()
                            }.onFailure { snackbar.showSnackbar("删除失败：${it.message}") }
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
    exportEnvelope?.let { envelope ->
        AlertDialog(
            onDismissRequest = { exportEnvelope = null },
            title = { Text("导出预设") },
            text = { Text(envelope) },
            confirmButton = { TextButton(onClick = { exportEnvelope = null }) { Text("完成") } },
        )
    }
    if (importing) {
        var envelope by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importing = false },
            title = { Text("导入预设") },
            text = { OutlinedTextField(envelope, { envelope = it }, label = { Text("导出 JSON") }) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { store.importEnvelope(envelope) } }
                            .onSuccess {
                                importing = false
                                refresh()
                                snackbar.showSnackbar("导入完成，同名项已自动编号")
                            }
                            .onFailure { snackbar.showSnackbar("导入失败：${it.message}") }
                    }
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { importing = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun PresetCard(
    preset: PerformancePreset,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onBindDefault: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(preset.name, style = MaterialTheme.typography.titleMedium)
                Text("r${preset.revision}", style = MaterialTheme.typography.labelMedium)
            }
            Text(preset.selector, style = MaterialTheme.typography.bodySmall)
            if (preset.isFallback) {
                Text("Compatibility fallback：只读、不可删除", style = MaterialTheme.typography.bodySmall)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onBindDefault) { Text("设为默认") }
                    IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "编辑") }
                    IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除") }
                }
            }
        }
    }
}

@Composable
private fun PresetEditorDialog(
    preset: PerformancePreset?,
    onDismiss: () -> Unit,
    onSave: (name: String, selector: String, config: String, modelId: String) -> Unit,
) {
    var name by remember(preset) { mutableStateOf(preset?.name.orEmpty()) }
    var selector by remember(preset) { mutableStateOf(preset?.selector.orEmpty()) }
    var config by remember(preset) { mutableStateOf(preset?.configJson ?: DEFAULT_CONFIG_JSON) }
    var modelId by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (preset == null) "新建性能预设" else "编辑性能预设") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    modifier = Modifier.testTag("performance-preset-name"),
                )
                OutlinedTextField(
                    value = selector,
                    onValueChange = { selector = it },
                    label = { Text("选择标识") },
                    modifier = Modifier.testTag("performance-preset-selector"),
                )
                OutlinedTextField(
                    value = config,
                    onValueChange = { config = it },
                    label = { Text("v1 配置 JSON") },
                    modifier = Modifier.testTag("performance-preset-config"),
                )
                OutlinedTextField(modelId, { modelId = it }, label = { Text("可选模型绑定") })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, selector, config, modelId) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
