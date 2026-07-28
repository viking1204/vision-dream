package io.github.xororz.localdream.inference

/**
 * Tracks every service and accepted inference that keeps the native backend
 * alive. A lease is released only by its holder and is safe to close twice.
 */
class BackendRuntimeLeaseManager {
    enum class Kind {
        SERVICE,
        JOB,
    }

    data class Snapshot(
        val total: Int,
        val services: Int,
        val jobs: Int,
        val owners: Set<String>,
    )

    private val monitor = Any()
    private val leases = linkedMapOf<Long, Entry>()
    private var nextId = 1L

    fun acquire(ownerId: String, kind: Kind): Lease {
        require(ownerId.isNotBlank()) { "ownerId must not be blank" }
        val id = synchronized(monitor) {
            nextId++.also { leases[it] = Entry(ownerId, kind) }
        }
        return Lease { release(id) }
    }

    fun snapshot(): Snapshot = synchronized(monitor) {
        val values = leases.values
        Snapshot(
            total = values.size,
            services = values.count { it.kind == Kind.SERVICE },
            jobs = values.count { it.kind == Kind.JOB },
            owners = values.mapTo(linkedSetOf()) { it.ownerId },
        )
    }

    fun canStopBackend(): Boolean = synchronized(monitor) { leases.isEmpty() }

    private fun release(id: Long) = synchronized(monitor) { leases.remove(id) }

    private data class Entry(val ownerId: String, val kind: Kind)

    class Lease internal constructor(private val releaseAction: () -> Unit) : AutoCloseable {
        @Volatile
        private var released = false

        override fun close() {
            if (!released) {
                synchronized(this) {
                    if (!released) {
                        released = true
                        releaseAction()
                    }
                }
            }
        }
    }
}
