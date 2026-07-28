package io.github.xororz.localdream.mcp

import io.github.xororz.localdream.data.InferenceJobStatus

/**
 * Process-local task-state fan-out. It contains only opaque Job identifiers and
 * task state, never prompts, paths or image bytes.
 */
object McpTaskEventBus {
    data class Event(val clientId: String, val jobId: String, val status: InferenceJobStatus)

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
