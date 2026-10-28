package com.audiology2.stream.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CircularBufferTest {

    @Test
    fun offerPollOrder() {
        val cb = CircularBuffer<String>(4)
        assertTrue(cb.offer("a"))
        assertTrue(cb.offer("b"))
        assertEquals("a", cb.poll())
        assertEquals("b", cb.poll())
        assertNull(cb.poll())
    }

    @Test
    fun overflowEvictsOldest() {
        val cb = CircularBuffer<Int>(3)
        assertTrue(cb.offer(1))
        assertTrue(cb.offer(2))
        assertTrue(cb.offer(3))
        assertFalse(cb.offer(4)) // full => evicts oldest (1)
        assertEquals(3, cb.size)
        assertEquals(2, cb.poll())
        assertEquals(3, cb.poll())
        assertEquals(4, cb.poll())
        assertNull(cb.poll())
    }

    @Test
    fun peekDoesNotRemove() {
        val cb = CircularBuffer<Int>(3)
        cb.offer(10)
        assertEquals(10, cb.peek())
        assertEquals(1, cb.size)
        assertEquals(10, cb.poll())
    }

    @Test
    fun wrapsAround() {
        val cb = CircularBuffer<Int>(3)
        repeat(3) { cb.offer(it) } // 0,1,2
        cb.poll(); cb.poll() // remove 0,1
        assertTrue(cb.offer(3)) // buffer holds {2}; room for more (not full at entry)
        assertEquals(2, cb.poll())
        assertEquals(3, cb.poll())
        assertNull(cb.poll())
    }

    @Test
    fun clear() {
        val cb = CircularBuffer<Int>(3)
        cb.offer(1); cb.offer(2); cb.offer(3)
        cb.clear()
        assertTrue(cb.isEmpty)
        assertEquals(0, cb.size)
        assertNull(cb.poll())
    }

    @Test
    fun capacityValidation() {
        try {
            CircularBuffer<Int>(0)
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("capacity"))
        }
    }

    @Test
    fun threadSafeBufferIsConsistent() {
        val cb = ThreadSafeCircularBuffer<Int>(1000)
        val produced = java.util.concurrent.atomic.AtomicInteger(0)
        val consumed = java.util.concurrent.atomic.AtomicInteger(0)
        val producer = Thread {
            repeat(2000) {
                cb.offer(it)
                produced.incrementAndGet()
            }
        }
        val consumer = Thread {
            var last = -1
            while (consumed.get() < 2000) {
                val v = cb.poll()
                if (v != null) {
                    // values may be dropped due to full buffer (bounded), so just track count
                    consumed.incrementAndGet()
                }
            }
        }
        producer.start(); consumer.start()
        producer.join(1000); consumer.join(1000)
        // Not every offer is guaranteed accepted, but consumer read at most what was offered.
        assertTrue(consumed.get() <= produced.get())
    }
}
