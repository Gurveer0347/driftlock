package org.driftlock.app.sensors

import org.junit.Assert.*
import org.junit.Test

class BoundedCaptureQueueTest {
    @Test fun overflowSignalsDiscontinuityBeforeAnyLaterEvent() {
        val q = BoundedCaptureQueue<Int>(2)
        q.offer(1); q.offer(2)
        assertFalse(q.offer(3))
        assertEquals(3L,q.droppedEvents)
        q.offer(4)
        assertTrue(q.poll()!!.discontinuity)
        assertEquals(4,q.poll()!!.event)
        assertNull(q.poll())
        assertTrue(q.isEmpty())
    }
}
