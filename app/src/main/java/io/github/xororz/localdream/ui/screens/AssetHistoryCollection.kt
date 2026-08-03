package io.github.xororz.localdream.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.AssetLayoutMode
import io.github.xororz.localdream.data.HistoryItem
import io.github.xororz.localdream.ui.components.RevealableImage
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.flow.drop

@Composable
internal fun AssetHistoryCollection(
    pagedItems: LazyPagingItems<HistoryItem>,
    layoutMode: AssetLayoutMode,
    revealAll: Boolean,
    revealRevision: Int,
    itemRevealOverrides: Map<Long, Boolean>? = null,
    onItemRevealChanged: ((Long, Boolean) -> Unit)? = null,
    isSelectionMode: Boolean,
    selectedIds: Set<Long>,
    onPreview: (HistoryItem) -> Unit,
    onShowInfo: ((HistoryItem) -> Unit)?,
    onLongClick: (HistoryItem) -> Unit,
    initialScroll: Pair<Int, Int> = 0 to 0,
    onAssetScroll: ((index: Int, offset: Int) -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val staggeredState = rememberLazyStaggeredGridState()
    var scrollRestored by remember { mutableStateOf(false) }

    LaunchedEffect(initialScroll.first, pagedItems.itemCount) {
        if (!scrollRestored && onAssetScroll != null &&
            initialScroll.first > 0 && pagedItems.itemCount > initialScroll.first
        ) {
            when (layoutMode) {
                AssetLayoutMode.WATERFALL ->
                    staggeredState.scrollToItem(initialScroll.first, initialScroll.second)

                AssetLayoutMode.LIST ->
                    listState.scrollToItem(initialScroll.first, initialScroll.second)

                AssetLayoutMode.GRID ->
                    gridState.scrollToItem(initialScroll.first, initialScroll.second)
            }
            scrollRestored = true
        }
    }

    if (onAssetScroll != null) {
        LaunchedEffect(listState) {
            snapshotFlow {
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            }.drop(1).collect { (index, offset) -> onAssetScroll.invoke(index, offset) }
        }
        LaunchedEffect(gridState) {
            snapshotFlow {
                gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
            }.drop(1).collect { (index, offset) -> onAssetScroll.invoke(index, offset) }
        }
        LaunchedEffect(staggeredState) {
            snapshotFlow {
                staggeredState.firstVisibleItemIndex to staggeredState.firstVisibleItemScrollOffset
            }.drop(1).collect { (index, offset) -> onAssetScroll.invoke(index, offset) }
        }
    }

    val itemContent: @Composable (Int) -> Unit = { index ->
        pagedItems[index]?.let { item ->
            AssetHistoryCard(
                item = item,
                layoutMode = layoutMode,
                revealAll = revealAll,
                revealRevision = revealRevision,
                revealed = if (onItemRevealChanged != null) {
                    itemRevealOverrides?.get(item.id) ?: revealAll
                } else {
                    null
                },
                onRevealedChange = onItemRevealChanged?.let { callback ->
                    { revealed -> callback(item.id, revealed) }
                },
                isSelectionMode = isSelectionMode,
                isSelected = item.id in selectedIds,
                onPreview = { onPreview(item) },
                onShowInfo = onShowInfo?.let { callback -> { callback(item) } },
                onLongClick = { onLongClick(item) },
                onToggleSelection = { onPreview(item) },
            )
        }
    }

    when (layoutMode) {
        AssetLayoutMode.WATERFALL -> {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                state = staggeredState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalItemSpacing = 10.dp,
            ) {
                items(
                    count = pagedItems.itemCount,
                    key = pagedItems.itemKey { it.id },
                ) { index ->
                    itemContent(index)
                }
            }
        }

        AssetLayoutMode.LIST -> {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(
                    count = pagedItems.itemCount,
                    key = pagedItems.itemKey { it.id },
                ) { index ->
                    itemContent(index)
                }
            }
        }

        AssetLayoutMode.GRID -> {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    count = pagedItems.itemCount,
                    key = pagedItems.itemKey { it.id },
                ) { index ->
                    itemContent(index)
                }
            }
        }
    }
}

@Composable
private fun AssetHistoryCard(
    item: HistoryItem,
    layoutMode: AssetLayoutMode,
    revealAll: Boolean,
    revealRevision: Int,
    revealed: Boolean?,
    onRevealedChange: ((Boolean) -> Unit)?,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onPreview: () -> Unit,
    onShowInfo: (() -> Unit)?,
    onLongClick: () -> Unit,
    onToggleSelection: () -> Unit,
) {
    val cardModifier = when (layoutMode) {
        AssetLayoutMode.GRID -> Modifier.aspectRatio(1f)
        AssetLayoutMode.LIST, AssetLayoutMode.WATERFALL -> Modifier.fillMaxWidth()
    }

    Card(
        modifier = cardModifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box {
            when (layoutMode) {
                AssetLayoutMode.WATERFALL -> {
                    Column {
                        AssetImageFrame(
                            item = item,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(assetAspectRatio(item)),
                            revealAll = revealAll,
                            revealRevision = revealRevision,
                            revealed = revealed,
                            onRevealedChange = onRevealedChange,
                            onPreview = onPreview,
                            onLongClick = onLongClick,
                        )
                        AssetMetadata(
                            item = item,
                            onShowInfo = onShowInfo,
                        )
                    }
                }

                AssetLayoutMode.LIST -> {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AssetImageFrame(
                            item = item,
                            modifier = Modifier.size(104.dp),
                            revealAll = revealAll,
                            revealRevision = revealRevision,
                            revealed = revealed,
                            onRevealedChange = onRevealedChange,
                            onPreview = onPreview,
                            onLongClick = onLongClick,
                        )
                        AssetMetadata(
                            item = item,
                            onShowInfo = onShowInfo,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                AssetLayoutMode.GRID -> {
                    AssetImageFrame(
                        item = item,
                        modifier = Modifier.fillMaxSize(),
                        revealAll = revealAll,
                        revealRevision = revealRevision,
                        revealed = revealed,
                        onRevealedChange = onRevealedChange,
                        onPreview = onPreview,
                        onLongClick = onLongClick,
                        onShowInfo = onShowInfo,
                    )
                }
            }

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                )
            }

            if (isSelectionMode) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .combinedClickable(
                            onClick = onToggleSelection,
                            onLongClick = {},
                        ),
                )
                SelectionIndicator(
                    selected = isSelected,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            }
        }
    }
}

@Composable
private fun AssetImageFrame(
    item: HistoryItem,
    modifier: Modifier,
    revealAll: Boolean,
    revealRevision: Int,
    revealed: Boolean?,
    onRevealedChange: ((Boolean) -> Unit)?,
    onPreview: () -> Unit,
    onLongClick: () -> Unit,
    onShowInfo: (() -> Unit)? = null,
) {
    val locale = LocalConfiguration.current.locales[0]
    val timestampFormat = remember(locale) { SimpleDateFormat("MM/dd HH:mm", locale) }
    Box(modifier = modifier.clip(MaterialTheme.shapes.large)) {
        RevealableImage(
            revealKey = item.id to revealRevision,
            initiallyRevealed = revealAll,
            revealed = revealed,
            onRevealedChange = onRevealedChange,
            modifier = Modifier.fillMaxSize(),
            onLongClick = onLongClick,
            onOpenPreview = onPreview,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(item.imageFile)
                    .crossfade(true)
                    .build(),
                contentDescription = stringResource(R.string.asset_generated_image),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (item.favorite) {
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = stringResource(R.string.asset_favorited),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(16.dp),
            )
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomStart),
            shape = RoundedCornerShape(topEnd = 4.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.82f),
        ) {
            Text(
                text = remember(item.timestamp, locale) {
                    timestampFormat.format(Date(item.timestamp))
                },
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }

        if (onShowInfo != null) {
            IconButton(
                onClick = onShowInfo,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .minimumInteractiveComponentSize()
                    .size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = stringResource(R.string.generation_params_title),
                    tint = Color.White,
                    modifier = Modifier
                        .size(24.dp)
                        .background(
                            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.58f),
                            shape = CircleShape,
                        )
                        .padding(4.dp),
                )
            }
        }
    }
}

@Composable
private fun AssetMetadata(
    item: HistoryItem,
    onShowInfo: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Text(
                text = item.modelId,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (onShowInfo != null) {
            IconButton(
                onClick = onShowInfo,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = stringResource(R.string.generation_params_title),
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

@Composable
private fun SelectionIndicator(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(24.dp)
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)
                },
                shape = CircleShape,
            )
            .border(
                width = 2.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.asset_selected),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

internal fun assetAspectRatio(item: HistoryItem): Float {
    val width = item.params.width
    val height = item.params.height
    if (width <= 0 || height <= 0) return 1f
    return (width.toFloat() / height.toFloat()).coerceIn(0.55f, 1.8f)
}
