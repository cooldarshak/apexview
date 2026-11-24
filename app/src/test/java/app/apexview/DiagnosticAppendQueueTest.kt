package app.apexview

import org.junit.Assert.*
import org.junit.Test

class DiagnosticAppendQueueTest {
    @Test fun boundsCountAndPayloadAndReportsRejectionWithoutDroppingAcceptedRecords() {
        val queue = DiagnosticAppendQueue(2, 8)
        assertFalse(queue.offer("off"))
        queue.start(1)
        assertTrue(queue.offer("abc"))
        assertTrue(queue.offer("def"))
        assertFalse(queue.offer("g"))
        assertEquals(DiagnosticAppendQueue.Drain(listOf("abc", "def"), 1), queue.drain(1))
        assertFalse(queue.offer("123456789"))
        assertEquals(DiagnosticAppendQueue.Drain(emptyList(), 1), queue.drain(1))
        assertTrue(queue.offer("last"))
        assertEquals(listOf("last"), queue.drain(1, close = true).lines)
        assertFalse(queue.offer("closed"))
    }
    @Test fun retiredCaptureCannotDrainNewCapture() {
        val queue = DiagnosticAppendQueue()
        queue.start(1); queue.offer("old")
        queue.start(2); queue.offer("new")
        assertTrue(queue.drain(1, close = true).lines.isEmpty())
        assertEquals(listOf("new"), queue.drain(2).lines)
    }
}
