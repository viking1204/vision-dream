package io.github.xororz.localdream.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.xororz.localdream.data.DeviceMemory
import io.github.xororz.localdream.data.HtpDynamicPartitioning
import io.github.xororz.localdream.data.HtpPowerMode
import io.github.xororz.localdream.data.PerformancePreset
import io.github.xororz.localdream.data.PerformancePresetBinding
import io.github.xororz.localdream.data.PerformancePresetConfig
import io.github.xororz.localdream.data.PerformancePresetEngineConfig
import io.github.xororz.localdream.data.PerformancePresetRepository
import io.github.xororz.localdream.mcp.AndroidMcpPresetStore
import io.github.xororz.localdream.mcp.McpPresetStore
import io.github.xororz.localdream.navigation.popBackStackIfResumed
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DEFAULT_ENGINE_CONFIG = PerformancePresetEngineConfig(
    sdxlLowRam = false,
    animaLowRam = false,
    animaSequentialDit = false,
    cpuClipThreads = 4,
    htpPowerMode = HtpPowerMode.ADJUST_UP_DOWN,
    htpDynamicPartitioning = HtpDynamicPartitioning.AUTO,
)

/**
 * Performance profiles are a product-facing control surface, not a JSON CRUD
 * list. Built-in profiles are immutable but fully inspectable; user profiles
 * remain versioned and editable through the same store as MCP.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformancePresetScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
    presetStore: McpPresetStore? = null,
    snackbarHostState: SnackbarHostState? = null,
) {
    val context = LocalContext.current
    val store = presetStore ?: remember { AndroidMcpPresetStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val snackbar = snackbarHostState ?: remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var presets by remember { mutableStateOf(emptyList<PerformancePreset>()) }
    var defaultBinding by remember { mutableStateOf<PerformancePresetBinding?>(null) }
    var editing by remember { mutableStateOf<PerformancePreset?>(null) }
    var creating by remember { mutableStateOf(false) }
    var createTemplate by remember { mutableStateOf<PerformancePreset?>(null) }
    var deleting by remember { mutableStateOf<PerformancePreset?>(null) }
    var viewing by remember { mutableStateOf<PerformancePreset?>(null) }
    var exportEnvelope by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    fun refresh() = scope.launch {
        val (updatedPresets, updatedBinding) = withContext(Dispatchers.IO) {
            store.list() to store.binding(PerformancePresetBinding.DEFAULT)
        }
        presets = updatedPresets
        defaultBinding = updatedBinding
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        val (initialPresets, initialBinding) = withContext(Dispatchers.IO) {
            store.list() to store.binding(PerformancePresetBinding.DEFAULT)
        }
        presets = initialPresets
        defaultBinding = initialBinding
    }
    val builtIns = presets.filter { it.isBuiltIn && !it.isFallback }
    val customPresets = presets.filter { !it.isBuiltIn && !it.isFallback }
    val recommendedId = if (DeviceMemory.isHighMemoryDevice(context)) {
        PerformancePresetRepository.EXTREME_PERFORMANCE_PRESET_ID
    } else {
        PerformancePresetRepository.RECOMMENDED_DEFAULT_PRESET_ID
    }
    val recommended = presets.firstOrNull { it.id == recommendedId }
    val effectivePreset = defaultBinding?.let { binding ->
        presets.firstOrNull { it.id == binding.presetId }
    } ?: recommended
    val overrideEnabled = defaultBinding != null

    fun setOverrideEnabled(enabled: Boolean) = scope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                if (enabled) {
                    val target = effectivePreset ?: recommended ?: error("推荐预设不可用")
                    store.bind(PerformancePresetBinding.DEFAULT, target.id)
                } else {
                    store.unbind(PerformancePresetBinding.DEFAULT)
                }
            }
        }.onSuccess {
            refresh()
            snackbar.showSnackbar(
                if (enabled) {
                    "已启用自选预设"
                } else {
                    "已切换为推荐预设：${recommended?.let(::builtInDisplayName) ?: "推荐预设"}"
                },
            )
        }.onFailure { snackbar.showSnackbar("切换失败：${it.message}") }
    }

    fun activatePreset(preset: PerformancePreset) = scope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                store.bind(PerformancePresetBinding.DEFAULT, preset.id)
            }
        }.onSuccess {
            refresh()
            snackbar.showSnackbar("已启用：${if (preset.isBuiltIn) builtInDisplayName(preset) else preset.name}")
        }.onFailure { snackbar.showSnackbar("启用失败：${it.message}") }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("性能控制台", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("选择配置，查看它会怎样影响下一次推理", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStackIfResumed() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { exportEnvelope = withContext(Dispatchers.IO) { store.exportEnvelope() } } }) {
                        Icon(Icons.Default.Download, "导出预设")
                    }
                    IconButton(onClick = { importing = true }) {
                        Icon(Icons.Default.Upload, "导入预设")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    createTemplate = null
                    creating = true
                },
                modifier = Modifier.testTag("performance-preset-create"),
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("新建预设") },
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PerformanceConsoleHero(
                    overrideEnabled = overrideEnabled,
                    effectivePresetName = effectivePreset?.let {
                        if (it.isBuiltIn) builtInDisplayName(it) else it.name
                    } ?: recommended?.let(::builtInDisplayName) ?: "推荐预设",
                    onOverrideEnabledChange = ::setOverrideEnabled,
                )
            }
            item { SectionHeading("内置基准", "系统维护 · 只读 · 点开查看完整参数") }
            if (builtIns.isEmpty()) {
                item { EmptyBuiltInState() }
            } else {
                items(builtIns, key = PerformancePreset::id) { preset ->
                    BuiltInPresetCard(
                        preset = preset,
                        isEffective = effectivePreset?.id == preset.id,
                        isRecommended = preset.id == recommendedId,
                        onView = { viewing = preset },
                        onActivate = { activatePreset(preset) },
                        onCopy = {
                            createTemplate = preset
                            creating = true
                        },
                    )
                }
            }
            item {
                SectionHeading(
                    title = "我的预设",
                    subtitle = if (customPresets.isEmpty()) "把验证过的组合保存成自己的模板" else "可编辑、可删除，也可以绑定为默认",
                )
            }
            if (customPresets.isEmpty()) {
                item { EmptyCustomPresetCard(onCreate = { creating = true }) }
            } else {
                items(customPresets, key = PerformancePreset::id) { preset ->
                    CustomPresetCard(
                        preset = preset,
                        isEffective = effectivePreset?.id == preset.id,
                        onView = { viewing = preset },
                        onEdit = { editing = preset },
                        onDelete = { deleting = preset },
                        onBindDefault = { activatePreset(preset) },
                    )
                }
            }
            item {
                Text(
                    "关闭自选后统一使用「持续性能」；模型专属覆盖会保留但暂停生效。排队中的任务仍固定受理时的预设快照。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }

    viewing?.let { preset ->
        PresetDetailDialog(
            preset = preset,
            onDismiss = { viewing = null },
            onCreateCopy = if (preset.isBuiltIn && !preset.isFallback) {
                {
                    viewing = null
                    createTemplate = preset
                    creating = true
                }
            } else {
                null
            },
        )
    }
    if (creating) {
        PresetEditorDialog(
            preset = null,
            template = createTemplate,
            onDismiss = {
                creating = false
                createTemplate = null
            },
        ) { name, config ->
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        store.create(name, newCustomSelector(), config)
                    }
                }.onSuccess {
                    creating = false
                    createTemplate = null
                    refresh()
                }.onFailure { snackbar.showSnackbar("保存失败：${it.message}") }
            }
        }
    }
    editing?.let { preset ->
        PresetEditorDialog(
            preset = preset,
            onDismiss = { editing = null },
        ) { name, config ->
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        store.update(preset.id, preset.revision, name, preset.selector, config)
                    }
                }.onSuccess {
                    editing = null
                    refresh()
                }.onFailure { snackbar.showSnackbar("保存失败：${it.message}") }
            }
        }
    }
    deleting?.let { preset ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除 ${preset.name}？") },
            text = { Text("关联的默认或模型绑定会原子回退到 Compatibility fallback。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { store.delete(preset.id) } }
                            .onSuccess { result ->
                                val suffix = result.reboundBindingKeys.takeIf(List<String>::isNotEmpty)
                                    ?.joinToString(prefix = "；已回退：") ?: ""
                                deleting = null
                                refresh()
                                snackbar.showSnackbar(if (result.deleted) "已删除$suffix" else "内置预设不能删除")
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
                            }.onFailure { snackbar.showSnackbar("导入失败：${it.message}") }
                    }
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { importing = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun PerformanceConsoleHero(
    overrideEnabled: Boolean,
    effectivePresetName: String,
    onOverrideEnabledChange: (Boolean) -> Unit,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(28.dp),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("当前生效：$effectivePresetName", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (overrideEnabled) "自选预设已启用" else "自动推荐模式 · 持续性能",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = .78f),
                    )
                }
                Switch(
                    checked = overrideEnabled,
                    onCheckedChange = onOverrideEnabledChange,
                    modifier = Modifier.testTag("performance-preset-override-switch"),
                )
            }
            Text(
                "低内存、CLIP 线程和 HTP 策略会随下次模型启动一起注入。关闭自选不会退回低质量兼容模式。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = .78f),
            )
        }
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(top = 8.dp, start = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BuiltInPresetCard(
    preset: PerformancePreset,
    isEffective: Boolean,
    isRecommended: Boolean,
    onView: () -> Unit,
    onActivate: () -> Unit,
    onCopy: () -> Unit,
) {
    val engine = PerformancePresetConfig.parse(preset.configJson).engine
    val accent = when (preset.selector) {
        "memory_saver" -> MaterialTheme.colorScheme.tertiary
        "extreme_performance", "sustained_performance" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
    }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onView)
            .testTag("performance-preset-view-${preset.id}"),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Memory, null, tint = accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    builtInDisplayName(preset),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                AssistChip(
                    onClick = onView,
                    label = {
                        Text(
                            when {
                                isEffective -> "当前生效"
                                isRecommended -> "推荐"
                                else -> "只读"
                            },
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(containerColor = accent.copy(alpha = .13f), labelColor = accent),
                    border = null,
                )
            }
            Text(
                builtInSummary(preset.selector),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PresetTraits(engine)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = onActivate,
                    enabled = !isEffective,
                    modifier = Modifier.testTag("performance-preset-activate-${preset.id}"),
                ) {
                    if (isEffective) {
                        Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (isEffective) "使用中" else "启用")
                }
                TextButton(onClick = onView) {
                    Text("查看参数")
                    Icon(Icons.Default.ChevronRight, null)
                }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(
                    onClick = onCopy,
                    modifier = Modifier.testTag("performance-preset-copy-${preset.id}"),
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("基于此创建")
                }
            }
        }
    }
}

@Composable
private fun CustomPresetCard(
    preset: PerformancePreset,
    isEffective: Boolean,
    onView: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onBindDefault: () -> Unit,
) {
    val engine = PerformancePresetConfig.parse(preset.configJson).engine
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onView)
            .testTag("performance-preset-view-${preset.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(preset.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                AssistChip(
                    onClick = onView,
                    label = { Text(if (isEffective) "当前生效" else "自定义 · r${preset.revision}") },
                    border = null,
                )
            }
            PresetTraits(engine)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    onClick = onBindDefault,
                    enabled = !isEffective,
                    modifier = Modifier.testTag("performance-preset-activate-${preset.id}"),
                ) {
                    if (isEffective) {
                        Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (isEffective) "使用中" else "启用")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "编辑") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除") }
                IconButton(onClick = onView) { Icon(Icons.Default.MoreVert, "查看详情") }
            }
        }
    }
}

@Composable
private fun PresetTraits(engine: PerformancePresetEngineConfig?) {
    if (engine == null) {
        Text("保守兼容参数 · 不覆盖模型原设置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MetricPill("CLIP", "${engine.cpuClipThreads ?: "默认"} 线程")
        MetricPill("内存", if (engine.sdxlLowRam || engine.animaLowRam) "优先节省" else "优先速度")
        MetricPill("HTP", htpLabel(engine))
    }
}

@Composable
private fun MetricPill(label: String, value: String) {
    AssistChip(
        onClick = {},
        label = {
            Text(
                "$label $value",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
            )
        },
        border = null,
        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

@Composable
private fun EmptyBuiltInState() {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Text("内置基准将在数据库升级后自动出现。", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EmptyCustomPresetCard(onCreate: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("还没有自定义预设", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("先复制一个适合自己模型的组合，再通过验证结果迭代。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onCreate) { Text("创建") }
        }
    }
}

/** Built-in presets are immutable, not opaque. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetDetailDialog(
    preset: PerformancePreset,
    onDismiss: () -> Unit,
    onCreateCopy: (() -> Unit)? = null,
) {
    val engine = PerformancePresetConfig.parse(preset.configJson).engine
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Expanded,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    var showRawConfig by remember(preset.id) { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (preset.isBuiltIn) builtInDisplayName(preset) else preset.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (preset.isBuiltIn) "内置预设 · 只读" else "自定义预设 · r${preset.revision}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                AssistChip(onClick = {}, label = { Text("${preset.selector}") }, border = null)
            }
            Text(
                if (preset.isBuiltIn) builtInSummary(preset.selector) else "这是你的可编辑版本；已受理任务仍保留原始快照。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Text("运行参数", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (engine == null) {
                Text("兼容模式", style = MaterialTheme.typography.titleSmall)
                Text("保留旧版保守运行参数，不覆盖模型原有设置。")
            } else {
                DetailMetric("SDXL 低内存", enabledText(engine.sdxlLowRam))
                DetailMetric("Anima 低内存", enabledText(engine.animaLowRam))
                DetailMetric("Anima 顺序 DiT", enabledText(engine.animaSequentialDit))
                DetailMetric("CLIP CPU 线程", engine.cpuClipThreads?.toString() ?: "沿用默认")
                DetailMetric("HTP 电源策略", powerModeDisplay(engine.htpPowerMode?.name))
                DetailMetric("HTP 动态分区", partitionDisplay(engine.htpDynamicPartitioning?.name))
            }
            HorizontalDivider()
            TextButton(onClick = { showRawConfig = !showRawConfig }) {
                Text(if (showRawConfig) "收起原始 JSON" else "查看原始 JSON")
            }
            if (showRawConfig) {
                Text(
                    preset.configJson,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                onCreateCopy?.let { createCopy ->
                    FilledTonalButton(
                        onClick = createCopy,
                        modifier = Modifier.testTag("performance-preset-detail-copy"),
                    ) {
                        Text("基于此创建")
                    }
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}

@Composable
private fun DetailMetric(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
    }
}

private fun builtInSummary(selector: String): String = when (selector) {
    "memory_saver" -> "内存紧张或需要留出后台余量时使用，牺牲部分吞吐换稳定。"
    "extreme_performance" -> "单张峰值速度优先；会长期驻留模型，不适合连续后台生成。"
    "sustained_performance" -> "保持 HTP 性能状态，同时逐阶段释放模型，适合连续和后台生成。"
    else -> "日常生成的折中起点，在速度、温度和内存之间保持平衡。"
}

/**
 * 旧版本已将 extreme 名称持久化为“极致性能”。展示层统一迁移为更准确的
 * “单张极速”，避免修改不可变预设和已受理任务快照。
 */
private fun builtInDisplayName(preset: PerformancePreset): String = when (preset.selector) {
    "extreme_performance" -> "单张极速"
    else -> preset.name
}

private fun htpLabel(engine: PerformancePresetEngineConfig): String = when (engine.htpPowerMode?.name) {
    "PERFORMANCE" -> "性能"
    "POWER_SAVER" -> "省电"
    "ADJUST_UP_DOWN" -> "自适应"
    else -> "默认"
}

private fun powerModeDisplay(value: String?): String = when (value) {
    "PERFORMANCE" -> "PERFORMANCE（性能优先）"
    "POWER_SAVER" -> "POWER_SAVER（省电）"
    "ADJUST_UP_DOWN" -> "ADJUST_UP_DOWN（自适应）"
    else -> "沿用默认"
}

private fun partitionDisplay(value: String?): String = when (value) {
    "ENABLED" -> "ENABLED（开启）"
    "DISABLED" -> "DISABLED（关闭）"
    "AUTO" -> "AUTO（自动）"
    else -> "沿用默认"
}

private fun enabledText(value: Boolean): String = if (value) "开启" else "关闭"

@Composable
private fun PresetEditorDialog(
    preset: PerformancePreset?,
    template: PerformancePreset? = null,
    onDismiss: () -> Unit,
    onSave: (name: String, config: String) -> Unit,
) {
    val source = preset ?: template
    val initialEngine = remember(source?.id, source?.revision) {
        editableEngineConfig(source?.configJson)
    }
    var name by remember(preset?.id, template?.id) {
        mutableStateOf(
            when {
                preset != null -> preset.name
                template != null -> "${builtInDisplayName(template)} · 自定义"
                else -> ""
            },
        )
    }
    var sdxlLowRam by remember(initialEngine) { mutableStateOf(initialEngine.sdxlLowRam) }
    var animaLowRam by remember(initialEngine) { mutableStateOf(initialEngine.animaLowRam) }
    var animaSequentialDit by remember(initialEngine) { mutableStateOf(initialEngine.animaSequentialDit) }
    var cpuClipThreads by remember(initialEngine) { mutableStateOf(initialEngine.cpuClipThreads ?: 4) }
    var htpPowerMode by remember(initialEngine) {
        mutableStateOf(initialEngine.htpPowerMode ?: HtpPowerMode.ADJUST_UP_DOWN)
    }
    var htpDynamicPartitioning by remember(initialEngine) {
        mutableStateOf(initialEngine.htpDynamicPartitioning ?: HtpDynamicPartitioning.AUTO)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (preset == null) "新建性能预设" else "编辑性能预设")
                template?.let {
                    Text(
                        "基于「${builtInDisplayName(it)}」创建",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 680.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    supportingText = { Text("例如：持续生成、低内存测试") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("performance-preset-name"),
                )
                Text(
                    "内存策略",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                PresetToggleRow(
                    title = "SDXL 低内存",
                    description = "降低 SDXL 峰值内存占用，可能牺牲部分速度。",
                    checked = sdxlLowRam,
                    onCheckedChange = { sdxlLowRam = it },
                    testTag = "performance-preset-sdxl-low-ram",
                )
                PresetToggleRow(
                    title = "Anima 低内存",
                    description = "为 Anima 释放更多系统内存余量。",
                    checked = animaLowRam,
                    onCheckedChange = { animaLowRam = it },
                    testTag = "performance-preset-anima-low-ram",
                )
                PresetToggleRow(
                    title = "Anima 顺序 DiT",
                    description = "按顺序执行 DiT，优先稳定性而不是并行吞吐。",
                    checked = animaSequentialDit,
                    onCheckedChange = { animaSequentialDit = it },
                    testTag = "performance-preset-anima-sequential-dit",
                )
                HorizontalDivider()
                PresetChoiceSection(
                    title = "CLIP CPU 线程",
                    description = "更多线程不一定更快；4 线程适合作为日常起点。",
                    values = listOf(1, 2, 4, 6, 8),
                    selected = cpuClipThreads,
                    label = Int::toString,
                    onSelect = { cpuClipThreads = it },
                    testTagPrefix = "performance-preset-clip",
                )
                PresetChoiceSection(
                    title = "HTP 电源策略",
                    description = "控制 NPU 在性能、温度和功耗之间的取舍。",
                    values = HtpPowerMode.entries,
                    selected = htpPowerMode,
                    label = {
                        when (it) {
                            HtpPowerMode.PERFORMANCE -> "性能优先"
                            HtpPowerMode.ADJUST_UP_DOWN -> "自适应"
                            HtpPowerMode.POWER_SAVER -> "省电"
                        }
                    },
                    onSelect = { htpPowerMode = it },
                    testTagPrefix = "performance-preset-power",
                )
                PresetChoiceSection(
                    title = "HTP 动态分区",
                    description = "不确定时选择自动；强制开关只用于对比验证。",
                    values = HtpDynamicPartitioning.entries,
                    selected = htpDynamicPartitioning,
                    label = {
                        when (it) {
                            HtpDynamicPartitioning.AUTO -> "自动"
                            HtpDynamicPartitioning.ENABLED -> "开启"
                            HtpDynamicPartitioning.DISABLED -> "关闭"
                        }
                    },
                    onSelect = { htpDynamicPartitioning = it },
                    testTagPrefix = "performance-preset-partition",
                )
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    modifier = Modifier.testTag("performance-preset-editor-note"),
                ) {
                    Text(
                        "保存后会生成内部标识和严格 v2 配置；运行时会按模型后端忽略不适用项（例如 CPU 模型不使用 HTP）。自动绑定仍需目标设备性能验收。",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        name.trim(),
                        PerformancePresetConfig.encodeV2(
                            PerformancePresetEngineConfig(
                                sdxlLowRam = sdxlLowRam,
                                animaLowRam = animaLowRam,
                                animaSequentialDit = animaSequentialDit,
                                cpuClipThreads = cpuClipThreads,
                                htpPowerMode = htpPowerMode,
                                htpDynamicPartitioning = htpDynamicPartitioning,
                            ),
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PresetToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
        )
    }
}

@Composable
private fun <T> PresetChoiceSection(
    title: String,
    description: String,
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    testTagPrefix: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            values.forEach { value ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(label(value)) },
                    modifier = Modifier.testTag("$testTagPrefix-${value.toString().lowercase()}"),
                )
            }
        }
    }
}

private fun editableEngineConfig(configJson: String?): PerformancePresetEngineConfig {
    val parsed = configJson?.let(PerformancePresetConfig::parse)?.engine ?: return DEFAULT_ENGINE_CONFIG
    return parsed.copy(
        cpuClipThreads = parsed.cpuClipThreads ?: DEFAULT_ENGINE_CONFIG.cpuClipThreads,
        htpPowerMode = parsed.htpPowerMode ?: DEFAULT_ENGINE_CONFIG.htpPowerMode,
        htpDynamicPartitioning = parsed.htpDynamicPartitioning ?: DEFAULT_ENGINE_CONFIG.htpDynamicPartitioning,
    )
}

private fun newCustomSelector(): String = "custom_${UUID.randomUUID().toString().take(8)}"
