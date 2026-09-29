package org.driftlock.app.sensors

internal data class CaptureItem<T>(val event: T? = null, val discontinuity: Boolean = false)

/** A loss marker is returned before any post-overflow event, even during concurrent draining. */
internal class BoundedCaptureQueue<T : Any>(private val capacity: Int) {
    private val items = ArrayDeque<T>()
    private var pendingGap = false
    var droppedEvents = 0L; private set
    init { require(capacity > 0) }
    @Synchronized fun offer(event: T): Boolean {
        if (items.size >= capacity) {
            droppedEvents += items.size + 1L; items.clear(); pendingGap = true
            return false
        }
        items.addLast(event); return true
    }
    @Synchronized fun poll(): CaptureItem<T>? {
        if (pendingGap) { pendingGap = false; return CaptureItem(discontinuity = true) }
        return items.removeFirstOrNull()?.let { CaptureItem(event = it) }
    }
    @Synchronized fun isEmpty() = items.isEmpty() && !pendingGap
    @Synchronized fun clear(resetCounts: Boolean = false) {
        items.clear(); pendingGap = false
        if (resetCounts) droppedEvents = 0
    }
}
