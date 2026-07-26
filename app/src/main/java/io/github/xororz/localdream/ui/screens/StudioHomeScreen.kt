package io.github.xororz.localdream.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.HistoryFilter
import io.github.xororz.localdream.data.HistoryManager
import io.github.xororz.localdream.navigation.Screen
import io.github.xororz.localdream.navigation.navigateTopLevel
import io.github.xororz.localdream.openai.InstalledModelCatalog
import io.github.xororz.localdream.service.BackendService
import io.github.xororz.localdream.service.OpenAiApiService
import io.github.xororz.localdream.ui.components.RevealableImage
import io.github.xororz.localdream.ui.design.StudioCoral
import io.github.xororz.localdream.ui.design.StudioCyan
import io.github.xororz.localdream.ui.design.StudioStatusPill
import io.github.xororz.localdream.ui.design.VisionStudioNavigationBar

/**
 * Creation-first product entry point. It summarizes live runtime state without
 * taking ownership of generation, model, asset, or API domain behavior.
 */
@Composable
fun StudioHomeScreen(navController: NavController) {
    val context = LocalContext.current.applicationContext
    val historyManager = remember { HistoryManager(context) }
    val catalog = remember { InstalledModelCatalog(context) }
    val servingModelId by BackendService.servingModelId.collectAsState()
    val apiStatus by OpenAiApiService.status.collectAsState()
    val recentAssets by remember {
        historyManager.observeRecent(HistoryFilter(), limit = 2)
    }.collectAsState(initial = emptyList())
    var loadedModelName by remember { mutableStateOf<String?>(null) }
    var installedModelCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(servingModelId) {
        runCatching { catalog.all() }.onSuccess { models ->
            val generationModels = models.filter {
                it.kind == InstalledModelCatalog.Kind.GENERATION
            }
            installedModelCount = generationModels.size
            loadedModelName = generationModels
                .firstOrNull { it.id == servingModelId }
                ?.name
                ?: servingModelId
        }
    }

    Scaffold(
        bottomBar = { VisionStudioNavigationBar(navController) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(key = "heading") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.studio_brand),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.studio_home_title),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            item(key = "create") {
                StudioCreateHero(
                    loadedModelName = loadedModelName,
                    installedModelCount = installedModelCount,
                    onStart = {
                        navController.navigateTopLevel(Screen.ChatGeneration.route)
                    },
                )
            }
            item(key = "service") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            navController.navigateTopLevel(Screen.RemoteLink.route)
                        },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.studio_service_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            StudioStatusPill(
                                label = stringResource(
                                    if (apiStatus.running) {
                                        R.string.studio_service_running
                                    } else {
                                        R.string.studio_service_stopped
                                    },
                                ),
                                accent = if (apiStatus.running) {
                                    io.github.xororz.localdream.ui.design.StudioSuccess
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.studio_service_queue,
                                apiStatus.queued,
                                apiStatus.queueCapacity,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item(key = "tools") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.studio_quick_tools),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StudioQuickAction(
                            title = stringResource(R.string.studio_prompt_library),
                            icon = Icons.Default.Bookmarks,
                            accent = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                            onClick = { navController.navigate(Screen.PromptManager.route) },
                        )
                        StudioQuickAction(
                            title = stringResource(R.string.studio_upscale),
                            icon = Icons.Default.ZoomOutMap,
                            accent = StudioCyan,
                            modifier = Modifier.weight(1f),
                            onClick = { navController.navigate(Screen.Upscale.route) },
                        )
                        StudioQuickAction(
                            title = stringResource(R.string.studio_api_service),
                            icon = Icons.Default.Dns,
                            accent = StudioCoral,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                navController.navigateTopLevel(Screen.RemoteLink.route)
                            },
                        )
                    }
                }
            }
            item(key = "recent") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.studio_recent_assets),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        TextButton(
                            onClick = {
                                navController.navigateTopLevel(Screen.History.route)
                            },
                        ) {
                            Text(stringResource(R.string.studio_view_assets))
                        }
                    }
                    if (recentAssets.isEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Text(
                                text = stringResource(R.string.studio_empty_assets),
                                modifier = Modifier.padding(18.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            recentAssets.forEach { asset ->
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    RevealableImage(
                                        revealKey = asset.id,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1.45f)
                                            .clip(RoundedCornerShape(18.dp)),
                                    ) {
                                        AsyncImage(
                                            model = asset.imageFile,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop,
                                        )
                                    }
                                    Text(
                                        text = asset.modelId,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioCreateHero(
    loadedModelName: String?,
    installedModelCount: Int,
    onStart: () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.78f),
                    ),
                ),
            )
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        StudioStatusPill(
            label = loadedModelName?.let {
                stringResource(R.string.studio_loaded_model, it)
            } ?: stringResource(R.string.studio_no_model),
            accent = if (loadedModelName == null) {
                MaterialTheme.colorScheme.outline
            } else {
                io.github.xororz.localdream.ui.design.StudioSuccess
            },
        )
        Text(
            text = stringResource(R.string.studio_start_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.studio_start_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.studio_installed_models, installedModelCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(vertical = 15.dp),
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
            )
            Text(
                text = stringResource(R.string.studio_start_action),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun StudioQuickAction(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .aspectRatio(1.05f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(13.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
