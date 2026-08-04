package io.github.xororz.localdream.ui.screens

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.NavController
import io.github.xororz.localdream.MainActivity
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.*
import io.github.xororz.localdream.data.DarkModePreference
import io.github.xororz.localdream.ui.design.VisionStudioNavigationBar
import io.github.xororz.localdream.ui.theme.LocalThemeController
import io.github.xororz.localdream.ui.theme.ThemePreset
import io.github.xororz.localdream.ui.theme.scheme
import io.github.xororz.localdream.utils.LogCapture
import io.github.xororz.localdream.utils.TempCleaner
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = { VisionStudioNavigationBar(navController) },
    ) { paddingValues ->
        SettingsContent(paddingValues)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsContent(paddingValues: PaddingValues) {
    val context = LocalContext.current
    val resources = context.resources
    val scope = rememberCoroutineScope()
    val preferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    val mainActivity = context as? MainActivity
    val generationPreferences = remember { GenerationPreferences(context) }
    val modelRepository = remember { ModelRepository.getInstance(context) }
    val upscalerRepository = remember { UpscalerRepository.getInstance(context) }
    var tempBaseUrl by remember { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf("huggingface") }
    var currentBaseUrl by remember { mutableStateOf("https://huggingface.co/") }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showCleanTempDialog by remember { mutableStateOf(false) }
    var showFileManagerDialog by remember { mutableStateOf(false) }
    var showEmbeddingManagerDialog by remember { mutableStateOf(false) }
    var tempScanBytes by remember { mutableLongStateOf(0L) }
    val msgTagImportFailed = stringResource(R.string.tag_import_failed)
    val msgCleanTempNone = stringResource(R.string.clean_temp_none)
    val msgLogSaved = stringResource(R.string.log_saved)
    val msgLogSaveFailed = stringResource(R.string.log_save_failed)
    val msgCleanTempDone = stringResource(R.string.clean_temp_done)
    val msgFileDeleted = stringResource(R.string.file_deleted)
    val msgEmbeddingDeleted = stringResource(R.string.embedding_deleted)
    val msgEmbeddingImported = stringResource(R.string.embedding_imported)
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        currentBaseUrl = generationPreferences.getBaseUrl()
        selectedSource = generationPreferences.getSelectedSource()
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        // Download source settings section
        item {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        stringResource(R.string.download_source),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(
                    stringResource(R.string.download_settings_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                var expanded by remember { mutableStateOf(false) }
                val focusRequester = remember { FocusRequester() }

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded },
                ) {
                    OutlinedTextField(
                        value = when (selectedSource) {
                            "huggingface" -> "https://huggingface.co/"
                            "hf-mirror" -> "https://hf-mirror.com/"
                            else -> tempBaseUrl
                        },
                        onValueChange = {
                            if (selectedSource == "custom") tempBaseUrl = it
                        },
                        label = { Text(stringResource(R.string.download_from)) },
                        readOnly = selectedSource != "custom",
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(
                                if (selectedSource == "custom") {
                                    ExposedDropdownMenuAnchorType.PrimaryEditable
                                } else {
                                    ExposedDropdownMenuAnchorType.PrimaryNotEditable
                                },
                                enabled = true,
                            )
                            .focusRequester(focusRequester)
                            .onFocusChanged { focusState ->
                                if (!focusState.isFocused && selectedSource == "custom") {
                                    scope.launch {
                                        if (tempBaseUrl.isNotEmpty() && tempBaseUrl != currentBaseUrl) {
                                            generationPreferences.saveBaseUrl(
                                                tempBaseUrl,
                                            )
                                            currentBaseUrl = tempBaseUrl
                                            modelRepository.refreshAllModels()
                                            upscalerRepository.refreshBaseUrl()
                                        }
                                    }
                                }
                            },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(
                                expanded = expanded,
                            )
                        },
                        singleLine = true,
                    )

                    LaunchedEffect(selectedSource) {
                        if (selectedSource == "custom") {
                            focusRequester.requestFocus()
                        }
                    }
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.source_huggingface)) },
                            onClick = {
                                selectedSource = "huggingface"
                                val newUrl = "https://huggingface.co/"
                                tempBaseUrl = newUrl
                                expanded = false
                                scope.launch {
                                    generationPreferences.saveSelectedSource("huggingface")
                                    generationPreferences.saveBaseUrl(newUrl)
                                    if (currentBaseUrl != newUrl) {
                                        currentBaseUrl = newUrl
                                        modelRepository.refreshAllModels()
                                        upscalerRepository.refreshBaseUrl()
                                    }
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.source_hf_mirror)) },
                            onClick = {
                                selectedSource = "hf-mirror"
                                val newUrl = "https://hf-mirror.com/"
                                tempBaseUrl = newUrl
                                expanded = false
                                scope.launch {
                                    generationPreferences.saveSelectedSource("hf-mirror")
                                    generationPreferences.saveBaseUrl(newUrl)
                                    if (currentBaseUrl != newUrl) {
                                        currentBaseUrl = newUrl
                                        modelRepository.refreshAllModels()
                                        upscalerRepository.refreshBaseUrl()
                                    }
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.source_custom)) },
                            onClick = {
                                selectedSource = "custom"
                                tempBaseUrl = "https://"
                                expanded = false
                                scope.launch {
                                    generationPreferences.saveSelectedSource("custom")
                                }
                            },
                        )
                    }
                }
            }
        }
        // Appearance (theme) section
        item { AppearanceSection() }
        // Feature settings section
        item {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        stringResource(R.string.feature_settings),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    val preferences = LocalContext.current.getSharedPreferences(
                        "app_prefs",
                        Context.MODE_PRIVATE,
                    )
                    var useImg2img by remember {
                        mutableStateOf(
                            preferences.getBoolean("use_img2img", true).also {
                                if (!preferences.contains("use_img2img")) {
                                    preferences.edit {
                                        putBoolean(
                                            "use_img2img",
                                            true,
                                        )
                                    }
                                }
                            },
                        )
                    }
                    var showProcess by remember {
                        mutableStateOf(
                            preferences.getBoolean("show_diffusion_process", false),
                        )
                    }
                    var captureLogs by remember {
                        mutableStateOf(
                            preferences.getBoolean("enable_log_capture", false),
                        )
                    }
                    val mainActivity = context as? MainActivity
                    val biometricLockEnabled =
                        mainActivity?.biometricLockEnabled ?: false
                    var listenOnAllAddresses by remember {
                        mutableStateOf(
                            preferences.getBoolean("listen_on_all_addresses", false),
                        )
                    }
                    var enableTagAutocomplete by remember {
                        mutableStateOf(
                            preferences.getBoolean("enable_tag_autocomplete", true)
                                .also {
                                    if (!preferences.contains("enable_tag_autocomplete")) {
                                        preferences.edit {
                                            putBoolean("enable_tag_autocomplete", true)
                                        }
                                    }
                                },
                        )
                    }
                    val tagRepository =
                        remember { TagAutocompleteRepository.getInstance(context) }
                    val tagDictState by tagRepository.state.collectAsState()
                    var tagImportInProgress by remember { mutableStateOf(false) }
                    val mainCsvPickerLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.GetContent(),
                    ) { uri ->
                        if (uri == null) return@rememberLauncherForActivityResult
                        val displayName = getFileNameFromUri(context, uri)
                        tagImportInProgress = true
                        scope.launch {
                            val result = tagRepository.importMainCsv(uri, displayName)
                            tagImportInProgress = false
                            val message = when (result) {
                                is ImportResult.Success ->
                                    resources.getQuantityString(
                                        R.plurals.tag_import_success,
                                        result.lineCount,
                                        result.lineCount,
                                    )

                                is ImportResult.Error -> msgTagImportFailed
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    }
                    val translationCsvPickerLauncher =
                        rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.GetContent(),
                        ) { uri ->
                            if (uri == null) return@rememberLauncherForActivityResult
                            val displayName = getFileNameFromUri(context, uri)
                            tagImportInProgress = true
                            scope.launch {
                                val result =
                                    tagRepository.importTranslationCsv(uri, displayName)
                                tagImportInProgress = false
                                val message = when (result) {
                                    is ImportResult.Success ->
                                        resources.getQuantityString(
                                            R.plurals.tag_import_success,
                                            result.lineCount,
                                            result.lineCount,
                                        )

                                    is ImportResult.Error -> msgTagImportFailed
                                }
                                Toast.makeText(context, message, Toast.LENGTH_SHORT)
                                    .show()
                            }
                        }
                    var sdxlLowRam by remember {
                        mutableStateOf(
                            preferences.getBoolean("sdxl_lowram", true).also {
                                if (!preferences.contains("sdxl_lowram")) {
                                    preferences.edit {
                                        putBoolean("sdxl_lowram", true)
                                    }
                                }
                            },
                        )
                    }
                    var animaLowRam by remember {
                        mutableStateOf(
                            preferences.getBoolean("anima_lowram", true).also {
                                if (!preferences.contains("anima_lowram")) {
                                    preferences.edit {
                                        putBoolean("anima_lowram", true)
                                    }
                                }
                            },
                        )
                    }
                    var animaSeqDit by remember {
                        mutableStateOf(
                            preferences.getBoolean("anima_seq_dit", false),
                        )
                    }

                    SwitchSettingRow(
                        title = "img2img",
                        description = stringResource(R.string.img2img_hint),
                        checked = useImg2img,
                        onCheckedChange = {
                            useImg2img = it
                            preferences.edit { putBoolean("use_img2img", it) }
                        },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    SwitchSettingRow(
                        title = stringResource(R.string.show_process),
                        description = stringResource(R.string.show_process_hint),
                        checked = showProcess,
                        onCheckedChange = {
                            showProcess = it
                            preferences.edit {
                                putBoolean("show_diffusion_process", it)
                            }
                        },
                    )
                    AnimatedVisibility(visible = showProcess) {
                        Column {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            ) {
                                var stride by remember {
                                    mutableFloatStateOf(
                                        preferences.getInt("show_diffusion_stride", 1)
                                            .toFloat(),
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.preview_stride),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    pluralStringResource(
                                        R.plurals.preview_stride_hint,
                                        stride.toInt(),
                                        stride.toInt(),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Slider(
                                    value = stride,
                                    onValueChange = {
                                        stride = it
                                        preferences.edit {
                                            putInt("show_diffusion_stride", it.toInt())
                                        }
                                    },
                                    valueRange = 1f..10f,
                                    steps = 8,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    SwitchSettingRow(
                        title = stringResource(R.string.capture_logs),
                        description = stringResource(R.string.capture_logs_hint),
                        checked = captureLogs,
                        onCheckedChange = {
                            captureLogs = it
                            preferences.edit {
                                putBoolean("enable_log_capture", it)
                            }
                        },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    SwitchSettingRow(
                        title = stringResource(R.string.biometric_lock),
                        description = stringResource(R.string.biometric_lock_hint),
                        checked = biometricLockEnabled,
                        onCheckedChange = { enabled ->
                            mainActivity?.setBiometricLockEnabled(enabled)
                        },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    SwitchSettingRow(
                        title = stringResource(R.string.tag_autocomplete),
                        description = stringResource(R.string.tag_autocomplete_hint),
                        checked = enableTagAutocomplete,
                        onCheckedChange = {
                            enableTagAutocomplete = it
                            preferences.edit {
                                putBoolean("enable_tag_autocomplete", it)
                            }
                        },
                    )
                    AnimatedVisibility(visible = enableTagAutocomplete) {
                        Column {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.tag_main_dictionary),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = if (tagDictState.mainImported) {
                                        pluralStringResource(
                                            R.plurals.tag_imported_status,
                                            tagDictState.mainEntryCount,
                                            tagDictState.mainFileName ?: "",
                                            tagDictState.mainEntryCount,
                                        )
                                    } else {
                                        stringResource(R.string.tag_main_dictionary_hint)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = { mainCsvPickerLauncher.launch("*/*") },
                                        enabled = !tagImportInProgress,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(
                                            if (tagDictState.mainImported) {
                                                stringResource(R.string.tag_reimport)
                                            } else {
                                                stringResource(R.string.tag_import)
                                            },
                                        )
                                    }
                                    if (tagDictState.mainImported) {
                                        OutlinedButton(
                                            onClick = { tagRepository.clearMainCsv() },
                                            enabled = !tagImportInProgress,
                                        ) {
                                            Text(stringResource(R.string.tag_clear))
                                        }
                                    }
                                }
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.tag_translation_dictionary),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = if (tagDictState.translationImported) {
                                        pluralStringResource(
                                            R.plurals.tag_imported_status,
                                            tagDictState.translationEntryCount,
                                            tagDictState.translationFileName ?: "",
                                            tagDictState.translationEntryCount,
                                        )
                                    } else {
                                        stringResource(R.string.tag_translation_dictionary_hint)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = {
                                            translationCsvPickerLauncher.launch(
                                                "*/*",
                                            )
                                        },
                                        enabled = !tagImportInProgress,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(
                                            if (tagDictState.translationImported) {
                                                stringResource(R.string.tag_reimport)
                                            } else {
                                                stringResource(R.string.tag_import)
                                            },
                                        )
                                    }
                                    if (tagDictState.translationImported) {
                                        OutlinedButton(
                                            onClick = { tagRepository.clearTranslationCsv() },
                                            enabled = !tagImportInProgress,
                                        ) {
                                            Text(stringResource(R.string.tag_clear))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    SwitchSettingRow(
                        title = stringResource(R.string.sdxl_lowram),
                        description = stringResource(R.string.sdxl_lowram_hint),
                        checked = sdxlLowRam,
                        onCheckedChange = {
                            sdxlLowRam = it
                            preferences.edit { putBoolean("sdxl_lowram", it) }
                        },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    SwitchSettingRow(
                        title = stringResource(R.string.anima_lowram),
                        description = stringResource(R.string.anima_lowram_hint),
                        checked = animaLowRam,
                        onCheckedChange = {
                            animaLowRam = it
                            preferences.edit { putBoolean("anima_lowram", it) }
                        },
                    )
                    AnimatedVisibility(visible = animaLowRam) {
                        Column {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            SwitchSettingRow(
                                title = stringResource(R.string.anima_seq_dit),
                                description = stringResource(R.string.anima_seq_dit_hint),
                                checked = animaSeqDit,
                                onCheckedChange = {
                                    animaSeqDit = it
                                    preferences.edit {
                                        putBoolean("anima_seq_dit", it)
                                    }
                                },
                            )
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    SwitchSettingRow(
                        title = stringResource(R.string.listen_on_all_addresses),
                        description = stringResource(R.string.listen_on_all_addresses_hint),
                        checked = listenOnAllAddresses,
                        onCheckedChange = {
                            listenOnAllAddresses = it
                            preferences.edit {
                                putBoolean("listen_on_all_addresses", it)
                            }
                        },
                    )
                }
            }
        }
        // Embedding management
        item {
            SettingNavCard(
                icon = Icons.Default.Description,
                label = stringResource(R.string.embedding_manager),
                onClick = { showEmbeddingManagerDialog = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // File management
        item {
            SettingNavCard(
                icon = Icons.Default.FolderOpen,
                label = stringResource(R.string.file_manager),
                onClick = { showFileManagerDialog = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // History backup and restore
        item {
            SettingNavCard(
                icon = Icons.Default.SettingsBackupRestore,
                label = stringResource(R.string.backup_restore),
                onClick = { showBackupDialog = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Clean up app scratch / orphaned temp files
        item {
            SettingNavCard(
                icon = Icons.Default.CleaningServices,
                label = stringResource(R.string.clean_temp_files),
                onClick = {
                    scope.launch {
                        val bytes = TempCleaner.scan(context)
                        if (bytes <= 0L) {
                            Toast.makeText(context, msgCleanTempNone, Toast.LENGTH_SHORT)
                                .show()
                        } else {
                            tempScanBytes = bytes
                            showCleanTempDialog = true
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (showBackupDialog) {
        DataBackupDialog(
            installedModelIds = modelRepository.models
                .filter { it.isDownloaded }
                .map { it.id }
                .toSet(),
            onDismiss = { showBackupDialog = false },
        )
    }

    if (showCleanTempDialog) {
        AlertDialog(
            onDismissRequest = { showCleanTempDialog = false },
            title = { Text(stringResource(R.string.clean_temp_files)) },
            text = { Text(stringResource(R.string.clean_temp_confirm, formatBytes(tempScanBytes))) },
            confirmButton = {
                TextButton(onClick = {
                    showCleanTempDialog = false
                    scope.launch {
                        val freed = TempCleaner.clean(context)
                        Toast.makeText(
                            context,
                            msgCleanTempDone.format(formatBytes(freed)),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showCleanTempDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showFileManagerDialog) {
        FileManagerDialog(
            context = context,
            onDismiss = { showFileManagerDialog = false },
            onFileDeleted = {
                scope.launch {
                    modelRepository.refreshAllModels()
                    snackbarHostState.showSnackbar(msgFileDeleted)
                }
            },
        )
    }

    if (showEmbeddingManagerDialog) {
        EmbeddingManagerDialog(
            context = context,
            onDismiss = { showEmbeddingManagerDialog = false },
            onEmbeddingDeleted = {
                scope.launch {
                    snackbarHostState.showSnackbar(msgEmbeddingDeleted)
                }
            },
            onEmbeddingImported = {
                scope.launch {
                    snackbarHostState.showSnackbar(msgEmbeddingImported)
                }
            },
        )
    }

    val capturedLogs = LogCapture.lastCapturedLogs.value
    if (capturedLogs != null) {
        AlertDialog(
            onDismissRequest = { LogCapture.consume() },
            title = { Text(stringResource(R.string.captured_logs_title)) },
            text = {
                if (capturedLogs.isBlank()) {
                    Text(stringResource(R.string.no_logs_captured))
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                MaterialTheme.shapes.extraSmall,
                            )
                            .padding(8.dp),
                    ) {
                        Text(
                            text = capturedLogs,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                        .format(Date())
                    val filename = "local_dream_log_$timestamp.log"
                    scope.launch(Dispatchers.IO) {
                        val savedPath = try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val values = ContentValues().apply {
                                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                                    put(
                                        MediaStore.Downloads.RELATIVE_PATH,
                                        Environment.DIRECTORY_DOWNLOADS + "/VisionDream",
                                    )
                                }
                                val resolver = context.contentResolver
                                val uri = resolver.insert(
                                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                    values,
                                ) ?: throw java.io.IOException("MediaStore insert failed")
                                resolver.openOutputStream(uri)?.use { out ->
                                    out.write(capturedLogs.toByteArray(Charsets.UTF_8))
                                } ?: throw java.io.IOException("openOutputStream failed")
                                "Downloads/VisionDream/$filename"
                            } else {
                                val dir = File(
                                    Environment.getExternalStoragePublicDirectory(
                                        Environment.DIRECTORY_DOWNLOADS,
                                    ),
                                    "VisionDream",
                                )
                                if (!dir.exists()) dir.mkdirs()
                                val file = File(dir, filename)
                                FileOutputStream(file).use { out ->
                                    out.write(capturedLogs.toByteArray(Charsets.UTF_8))
                                }
                                file.absolutePath
                            }
                        } catch (e: Exception) {
                            Log.e("LogCapture", "save failed", e)
                            null
                        }
                        withContext(Dispatchers.Main) {
                            val msg = if (savedPath != null) {
                                msgLogSaved.format(savedPath)
                            } else {
                                msgLogSaveFailed
                            }
                            snackbarHostState.showSnackbar(msg)
                            LogCapture.consume()
                        }
                    }
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { LogCapture.consume() }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
    SnackbarHost(hostState = snackbarHostState)
}

internal fun getFileNameFromUri(context: Context, uri: Uri): String? = try {
    when (uri.scheme) {
        "content" -> {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex != -1) {
                    cursor.getString(nameIndex)
                } else {
                    null
                }
            }
        }

        "file" -> {
            uri.lastPathSegment
        }

        else -> {
            DocumentFile.fromSingleUri(context, uri)?.name
        }
    }
} catch (e: Exception) {
    Log.e("GetFileName", "Get file name from uri failed", e)
    null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingNavCard(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchSettingRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppearanceSection() {
    val themeController = LocalThemeController.current
    val state = themeController.state
    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val isDark = when (state.darkMode) {
        DarkModePreference.SYSTEM -> isSystemInDarkTheme()
        DarkModePreference.LIGHT -> false
        DarkModePreference.DARK -> true
    }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Palette,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                stringResource(R.string.appearance),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            if (dynamicColorSupported) {
                SwitchSettingRow(
                    title = stringResource(R.string.dynamic_color),
                    description = stringResource(R.string.dynamic_color_hint),
                    checked = state.dynamicColor,
                    onCheckedChange = { value ->
                        themeController.update { it.copy(dynamicColor = value) }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.theme_preset),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.theme_preset_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ThemePreset.entries.forEach { preset ->
                        ThemeSwatch(
                            preset = preset,
                            isDark = isDark,
                            selected = preset == state.preset && !state.dynamicColor,
                            enabled = !state.dynamicColor,
                            onClick = {
                                themeController.update { it.copy(preset = preset) }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.dark_mode),
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        ButtonGroupDefaults.ConnectedSpaceBetween,
                    ),
                ) {
                    val modes = DarkModePreference.entries
                    modes.forEachIndexed { index, mode ->
                        val shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            modes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        }
                        ToggleButton(
                            checked = mode == state.darkMode,
                            onCheckedChange = { checked ->
                                if (checked) themeController.update { it.copy(darkMode = mode) }
                            },
                            shapes = shapes,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = stringResource(
                                    when (mode) {
                                        DarkModePreference.SYSTEM -> R.string.dark_mode_system
                                        DarkModePreference.LIGHT -> R.string.dark_mode_light
                                        DarkModePreference.DARK -> R.string.dark_mode_dark
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ThemeSwatch(
    preset: ThemePreset,
    isDark: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = preset.scheme(isDark)
    val alpha = if (enabled) 1f else 0.45f
    val description = stringResource(preset.nameRes)
    val polygon = when (preset) {
        ThemePreset.VISION -> MaterialShapes.SoftBurst
        ThemePreset.TANGERINE -> MaterialShapes.Cookie9Sided
        ThemePreset.FOREST -> MaterialShapes.Clover4Leaf
        ThemePreset.OCEAN -> MaterialShapes.Sunny
        ThemePreset.AMBER -> MaterialShapes.Cookie6Sided
    }
    val shape = polygon.toShape()
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            color = scheme.primary.copy(alpha = alpha),
            border = if (selected) {
                BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
            } else {
                null
            },
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = description },
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = scheme.onPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
