package com.aodcards

/** Called on the main thread. Ordinary events never cancel an in-flight snapshot. */
internal class RefreshQueue(
    private val post: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val start: (Int) -> Unit
) {
    @Volatile var revision = 0
        private set
    var running = false
        private set
    var pending = false
        private set
    private var requested = false
    private val task = Runnable {
        pending = false
        if (requested && !running) {
            requested = false
            running = true
            start(revision)
        }
    }

    fun request(delay: Long = 120L, invalidate: Boolean = false) {
        if (invalidate) revision++
        requested = true
        if (running) return
        // Keep the first deadline: a stream of events cannot push it into the future.
        if (pending && delay > 0) return
        if (pending) remove(task)
        pending = true
        post(task, delay)
    }

    fun complete() {
        running = false
        if (requested) request()
    }

    fun cancel() {
        revision++
        requested = false
        if (pending) remove(task)
        pending = false
        // An already running worker must finish before a new one can start.
    }
}
