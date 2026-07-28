package io.github.xororz.localdream.mcp

import io.github.xororz.localdream.data.InferenceJobStatus

/**
 * Process-local task-state fan-out. It contains only opaque Job identifiers and
 * task state, never prompts, paths or image bytes.
 */
object McpTaskEventBus {
    data class Event(
        val clientId: String,
        val jobId: String,
        val status: InferenceJobStatus,
        val diffusionStep: Int? = null,
        val totalDiffusionSteps: Int? = null,
    ) {
        init {
            require((diffusionStep == null) == (totalDiffusionSteps == null))
            if (diffusionStep != null && totalDiffusionSteps != null) {
                require(diffusionStep in 1..totalDiffusionSteps)
            }
        }

        val isDiffusionProgress: Boolean
            get() = diffusionStep != null
    }

    private val listeners = linkedSetOf<(Event) -> Unit>()

    fun subscribe(listener: (Event) -> Unit): AutoCloseable = synchronized(listeners) {
        listeners += listener
        AutoCloseable { synchronized(listeners) { listeners -= listener } }
    }

    fun publish(event: Event) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { listener -> listener(event) }
    }
}
