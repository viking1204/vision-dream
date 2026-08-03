package io.github.xororz.localdream.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.GenerationTask
import io.github.xororz.localdream.data.GenerationTaskStatus

/**
 * Always-visible summary of the pending queue.
 *
 * Sits directly above the composer so the user can see that extra sends went
 * somewhere without a dialog stealing focus mid-run.
 */
@Composable
fun GenerationQueueBar(
    pendingCount: Int,
    runningModelName: String?,
    onOpenPanel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pendingCount == 0 && runningModelName == null) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        onClick = onOpenPanel,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                contentDescription = stringResource(R.string.generation_queue_title),
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = if (runningModelName != null) {
                    stringResource(
                        R.string.generation_queue_bar_running,
                        runningModelName,
                        pendingCount,
                    )
                } else {
                    stringResource(R.string.generation_queue_bar_waiting, pendingCount)
                },
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

/**
 * Editable view of the queue: reorder by model, drop one, or clear the lot.
 *
 * The running task is rendered but not removable — it already holds the
 * inference lease, and cancelling it belongs to the generation screen's stop
 * affordance, not to queue editing.
 *
 * [onStartQueue] is non-null only while a restored queue is parked: reopening
 * the app must never silently burn battery, so the drain is opt-in.
 * Manual reordering uses explicit up/down buttons rather than a drag handle —
 * same capability, but reachable with TalkBack and switch access.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerationQueueSheet(
    tasks: List<GenerationTask>,
    smartSortEnabled: Boolean,
    onSmartSortChange: (Boolean) -> Unit,
    onRemove: (GenerationTask) -> Unit,
    onMove: (GenerationTask, Int) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    onStartQueue: (() -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.generation_queue_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClear, enabled = tasks.any { it.status != GenerationTaskStatus.RUNNING }) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.generation_queue_clear),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.generation_queue_smart_sort),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.generation_queue_smart_sort_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = smartSortEnabled, onCheckedChange = onSmartSortChange)
            }
            if (onStartQueue != null) {
                Button(
                    onClick = onStartQueue,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.generation_queue_start),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            HorizontalDivider()
            if (tasks.isEmpty()) {
                Text(
                    text = stringResource(R.string.generation_queue_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                val firstMovableIndex = tasks.indexOfFirst {
                    it.status != GenerationTaskStatus.RUNNING
                }
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    itemsIndexed(tasks, key = { _, task -> task.id }) { index, task ->
                        val running = task.status == GenerationTaskStatus.RUNNING
                        val canMoveUp = !running && !smartSortEnabled && index > firstMovableIndex
                        val canMoveDown = !running && !smartSortEnabled && index < tasks.lastIndex
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = task.prompt.ifBlank { task.modelName },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = "${task.modelName} · ${task.width}×${task.height} · " +
                                        if (running) {
                                            stringResource(R.string.generation_queue_status_running)
                                        } else {
                                            stringResource(R.string.generation_queue_status_queued)
                                        },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (running) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            },
                            trailingContent = {
                                if (!running) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = { onMove(task, -1) },
                                            enabled = canMoveUp,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.KeyboardArrowUp,
                                                contentDescription = stringResource(
                                                    R.string.generation_queue_move_up,
                                                ),
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                        IconButton(
                                            onClick = { onMove(task, 1) },
                                            enabled = canMoveDown,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.KeyboardArrowDown,
                                                contentDescription = stringResource(
                                                    R.string.generation_queue_move_down,
                                                ),
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                        IconButton(onClick = { onRemove(task) }) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = stringResource(
                                                    R.string.generation_queue_remove,
                                                ),
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
