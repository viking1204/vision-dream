package io.github.xororz.localdream.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.GenerationMode
import io.github.xororz.localdream.ui.screens.GenerationParameters
import io.github.xororz.localdream.utils.schedulerDisplayName

@Composable
fun GenerationParamsDialog(
    title: String,
    params: GenerationParameters,
    modelId: String,
    displayMode: GenerationMode? = null,
    showImg2imgButton: Boolean,
    showShareButton: Boolean = true,
    showReproduceButton: Boolean = true,
    onSavePrompt: (() -> Unit)? = null,
    onCopyPrompts: (() -> Unit)? = null,
    onCopyPrompt: (() -> Unit)? = null,
    onCopyNegativePrompt: (() -> Unit)? = null,
    /** Promotes these parameters to the defaults of the model that made them. */
    onSetAsModelDefaults: (() -> Unit)? = null,
    onShare: () -> Unit,
    onSendToImg2img: () -> Unit,
    onReproduce: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, modifier = Modifier.weight(1f))
                if (onSavePrompt != null && params.prompt.isNotBlank()) {
                    IconButton(onClick = onSavePrompt) {
                        Icon(
                            imageVector = Icons.Default.BookmarkAdd,
                            contentDescription = stringResource(
                                R.string.asset_save_prompt_action,
                            ),
                        )
                    }
                }
                if (onCopyPrompts != null) {
                    IconButton(onClick = onCopyPrompts) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.asset_copy_prompts),
                        )
                    }
                }
                if (showShareButton) {
                    IconButton(onClick = onShare) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = stringResource(R.string.share),
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column {
                    Text(
                        stringResource(R.string.basic_params),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.basic_model, modelId),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.basic_step, params.steps),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "CFG: %.1f".format(params.cfg),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.basic_size, params.width, params.height),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    params.seed?.let {
                        Text(
                            stringResource(R.string.basic_seed, it),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        stringResource(
                            R.string.basic_runtime,
                            if (params.runOnCpu) {
                                if (params.useOpenCL) "GPU" else "CPU"
                            } else {
                                "NPU"
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "${stringResource(R.string.scheduler)}: ${schedulerDisplayName(params.scheduler)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val mode = displayMode ?: params.mode
                    if (mode != GenerationMode.UNKNOWN) {
                        Text(
                            stringResource(R.string.basic_mode, mode.name.lowercase()),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (mode != GenerationMode.TXT2IMG) {
                            Text(
                                stringResource(R.string.basic_denoise, params.denoiseStrength),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.basic_time, params.generationTime ?: "unknown"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.image_prompt),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (onCopyPrompt != null && params.prompt.isNotBlank()) {
                        IconButton(onClick = onCopyPrompt) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.asset_copy_positive_prompt),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(params.prompt, style = MaterialTheme.typography.bodyMedium)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.negative_prompt),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (onCopyNegativePrompt != null && params.negativePrompt.isNotBlank()) {
                        IconButton(onClick = onCopyNegativePrompt) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.asset_copy_negative_prompt),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(params.negativePrompt, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showImg2imgButton) {
                        TextButton(onClick = onSendToImg2img) {
                            Text("img2img")
                        }
                    }
                    if (onSetAsModelDefaults != null) {
                        TextButton(onClick = onSetAsModelDefaults) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(R.string.asset_set_model_defaults),
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                    if (!showImg2imgButton && onSetAsModelDefaults == null) {
                        Spacer(modifier = Modifier.width(0.dp))
                    }
                }
                Row {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.close))
                    }
                    if (showReproduceButton) {
                        TextButton(onClick = onReproduce) {
                            Text(stringResource(R.string.reproduce))
                        }
                    }
                }
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
internal fun GenerationParamsDialogLightPreview() {
    MaterialTheme {
        GenerationParamsDialog(
            title = "Generation parameters",
            params = previewParameters(),
            modelId = "dream-shaper-8",
            displayMode = GenerationMode.TXT2IMG,
            showImg2imgButton = true,
            showShareButton = true,
            onCopyPrompt = {},
            onCopyNegativePrompt = {},
            onSetAsModelDefaults = {},
            onShare = {},
            onSendToImg2img = {},
            onReproduce = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111113)
@Composable
internal fun GenerationParamsDialogDarkPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        GenerationParamsDialog(
            title = "Generation parameters",
            params = previewParameters(),
            modelId = "dream-shaper-8",
            displayMode = GenerationMode.TXT2IMG,
            showImg2imgButton = true,
            showShareButton = true,
            onCopyPrompt = {},
            onCopyNegativePrompt = {},
            onSetAsModelDefaults = {},
            onShare = {},
            onSendToImg2img = {},
            onReproduce = {},
            onDismiss = {},
        )
    }
}

private fun previewParameters() = GenerationParameters(
    steps = 20,
    cfg = 7.5f,
    seed = 1234567890L,
    prompt = "a serene mountain lake at sunrise, highly detailed",
    negativePrompt = "blurry, low quality, watermark",
    generationTime = "3.4s",
    width = 512,
    height = 512,
    runOnCpu = true,
    scheduler = "dpm",
    mode = GenerationMode.TXT2IMG,
)
