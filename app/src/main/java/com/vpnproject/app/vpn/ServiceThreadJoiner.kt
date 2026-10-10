package com.vpnproject.app.vpn

import java.util.concurrent.TimeUnit

/**
 * Bounds the calling thread's wait for a lifecycle worker, so a worker that
 * fails to respond to cancellation cannot hold the service thread forever. A
 * `false` result means the worker may still be unwinding and cleanup is best-effort.
 */
internal object ServiceThreadJoiner {
    const val DEFAULT_TIMEOUT_MS = 2_000L

    fun await(
        thread: Thread?,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Boolean {
        require(timeoutMs >= 0L) { "timeoutMs must not be negative" }
        if (thread == null || thread === Thread.currentThread()) return true

        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val startedAtNanos = System.nanoTime()
        var interrupted = false
        while (thread.isAlive) {
            val elapsedNanos = System.nanoTime() - startedAtNanos
            val remainingNanos = timeoutNanos - elapsedNanos
            if (remainingNanos <= 0L) break

            // Thread.join(0) means wait forever, so round a positive sub-ms
            // remainder up to one millisecond rather than accidentally hanging.
            val waitMs = TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L)
            try {
                thread.join(waitMs)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        return !thread.isAlive
    }
}
