package io.github.xororz.localdream.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.HistoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-screen lightbox that lets the user swipe left/right between generated
 * assets. Each page zooms/pans via [detectTransformGestures]; at scale 1 the
 * page only listens for taps (double-tap to zoom, tap outside to dismiss) so a
 * single-finger drag falls through to the [HorizontalPager] and paging is never
 * blocked by the zoom gesture.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AssetImageLightbox(
    items: List<HistoryItem>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onShowInfo: (HistoryItem) -> Unit,
    onToggleFavorite: (HistoryItem) -> Unit,
    onSave: (HistoryItem, Bitmap?) -> Unit,
    onDelete: (HistoryItem) -> Unit,
) {
    if (items.isEmpty()) return
    val safeInitial = initialIndex.coerceIn(0, items.lastIndex)
    val pagerState = rememberPagerState(initialPage = safeInitial) { items.size }
    val currentItem = items[pagerState.currentPage]
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isZoomed by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.9f)),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = !isZoomed,
            ) { page ->
                val item = items[page]
                ZoomablePage(
                    item = item,
                    isCurrent = page == pagerState.currentPage,
                    onDismiss = onDismiss,
                    onZoomChanged = { zoomed -> isZoomed = zoomed },
                    onBitmapReady = { bmp ->
                        if (page == pagerState.currentPage) currentBitmap = bmp
                    },
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 60.dp, start = 16.dp),
            ) {
                OverlayIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = stringResource(R.string.close),
                    onClick = onDismiss,
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 60.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OverlayIconButton(
                    icon = Icons.Default.Info,
                    contentDescription = "View parameters",
                    onClick = { onShowInfo(currentItem) },
                )
                OverlayIconButton(
                    icon = if (currentItem.favorite) {
                        Icons.Default.Favorite
                    } else {
                        Icons.Default.FavoriteBorder
                    },
                    contentDescription = "toggle favorite",
                    onClick = { onToggleFavorite(currentItem) },
                )
                OverlayIconButton(
                    icon = Icons.Default.Save,
                    contentDescription = "Save to gallery",
                    onClick = { onSave(currentItem, currentBitmap) },
                )
                OverlayIconButton(
                    icon = Icons.Default.Delete,
                    contentDescription = "Delete image",
                    onClick = { onDelete(currentItem) },
                )
            }

            Text(
                text = "${pagerState.currentPage + 1} / ${items.size}",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .background(
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.extraSmall,
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ZoomablePage(
    item: HistoryItem,
    isCurrent: Boolean,
    onDismiss: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    onBitmapReady: (Bitmap?) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val bitmap by produceState<Bitmap?>(null, item.imageFile.absolutePath) {
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(item.imageFile.absolutePath)
        }
    }
    LaunchedEffect(bitmap, isCurrent) {
        if (isCurrent) onBitmapReady(bitmap)
    }

    val zoomed = scale > 1.001f
    val bmpValue = bitmap

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(zoomed) {
                if (zoomed) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offsetX += pan.x
                        offsetY += pan.y
                        onZoomChanged(scale > 1.001f)
                    }
                    detectTapGestures(
                        onDoubleTap = {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                            onZoomChanged(false)
                        },
                    )
                } else {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = 2.5f
                            offsetX = 0f
                            offsetY = 0f
                            onZoomChanged(true)
                        },
                        onTap = { offset ->
                            val bmp = bmpValue
                            if (bmp == null) {
                                onDismiss()
                                return@detectTapGestures
                            }
                            val centerX = size.width / 2f
                            val centerY = size.height / 2f
                            // Mirror the centered, aspect-fit layout used by the
                            // Image so a tap in the letterbox (non-square image)
                            // also dismisses, not only taps outside the square.
                            val square = minOf(size.width, size.height).toFloat()
                            val aspect = bmp.width.toFloat() / bmp.height.toFloat()
                            val baseWidth = if (aspect >= 1f) square else square * aspect
                            val baseHeight = if (aspect >= 1f) square / aspect else square
                            val scaledWidth = baseWidth * scale
                            val scaledHeight = baseHeight * scale
                            val left = centerX + offsetX - scaledWidth / 2f
                            val right = centerX + offsetX + scaledWidth / 2f
                            val top = centerY + offsetY - scaledHeight / 2f
                            val bottom = centerY + offsetY + scaledHeight / 2f
                            if (offset.x < left || offset.x > right ||
                                offset.y < top || offset.y > bottom
                            ) {
                                onDismiss()
                            }
                        },
                    )
                }
            },
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "preview image",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                    ),
            )
        }
    }
}
