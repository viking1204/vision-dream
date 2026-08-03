package io.github.xororz.localdream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.R

/**
 * Keeps generated content out of composition until the user explicitly reveals it.
 *
 * [revealKey] resets the state whenever a different generated asset replaces the
 * current one, preventing a previously revealed slot from exposing the next image.
 */
@Composable
fun RevealableImage(
    revealKey: Any?,
    modifier: Modifier = Modifier,
    initiallyRevealed: Boolean = false,
    revealed: Boolean? = null,
    onRevealedChange: ((Boolean) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onOpenPreview: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var concealed by remember(revealKey) { mutableStateOf(!initiallyRevealed) }
    val isRevealed = revealed ?: !concealed
    val setRevealed: (Boolean) -> Unit = { value ->
        if (revealed == null) {
            concealed = !value
        }
        onRevealedChange?.invoke(value)
    }
    val concealedDescription = stringResource(R.string.image_concealed)
    val revealedDescription = stringResource(R.string.image_revealed)

    Box(
        modifier = modifier.semantics {
            stateDescription = if (isRevealed) {
                revealedDescription
            } else {
                concealedDescription
            }
        },
    ) {
        if (!isRevealed) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .combinedClickable(
                        onClick = { setRevealed(true) },
                        onLongClick = { onLongClick?.invoke() },
                    ),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = stringResource(R.string.reveal_image),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .combinedClickable(
                        onClick = { setRevealed(false) },
                        onLongClick = { onLongClick?.invoke() },
                    ),
            ) {
                content()
            }
            if (onOpenPreview != null) {
                IconButton(
                    onClick = onOpenPreview,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .minimumInteractiveComponentSize()
                        .size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = stringResource(R.string.asset_open_fullscreen),
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
}
