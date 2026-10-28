package com.audiology2.stream.util

/**
 * Minimal bounded circular buffer (FIFO). When full, [offer] drops the oldest
 * element and returns false, so real-time producers never block indefinitely.
 * Capacity is fixed at construction (>0). Not thread-safe by design; callers
 * that share across threads should synchronize externally or use the
 * [ThreadSafe] variant. Pure Kotlin: unit-testable on the host JVM.
 */
open class CircularBuffer<T>(val capacity: Int) {
    init { require(capacity > 0) { "capacity must be > 0" } }

    @Suppress("UNCHECKED_CAST")
    protected val buf: Array<T?> = arrayOfNulls<Any?>(capacity) as Array<T?>
    protected var head: Int = 0  // index of oldest element
    protected var tail: Int = 0  // index of next write slot
    protected var count: Int = 0

    val size: Int get() = count
    val isEmpty: Boolean get() = count == 0
    val isFull: Boolean get() = count == capacity

    /**
     * Insert [item], overwriting the oldest element if full.
     * @return true if the item was added without dropping anything,
     *         false if the buffer was full and the oldest element was evicted.
     */
    open fun offer(item: T): Boolean {
        val evicted = isFull
        if (evicted) {
            // drop oldest
            buf[head] = null
            head = (head + 1) % capacity
            count--
        }
        buf[tail] = item
        tail = (tail + 1) % capacity
        count++
        return !evicted
    }

    /** Remove and return the oldest element, or null if empty. */
    open fun poll(): T? {
        if (isEmpty) return null
        val item = buf[head]
        buf[head] = null
        head = (head + 1) % capacity
        count--
        @Suppress("UNCHECKED_CAST")
        return item as T
    }

    /** Peek the oldest element without removing, or null if empty. */
    open fun peek(): T? {
        if (isEmpty) return null
        @Suppress("UNCHECKED_CAST")
        return buf[head] as T
    }

    open fun clear() {
        var i = 0
        while (i < count) {
            buf[head] = null
            head = (head + 1) % capacity
            i++
        }
        head = 0; tail = 0; count = 0
    }
}

/** Thread-safe wrapper via synchronized blocks. */
class ThreadSafeCircularBuffer<T>(capacity: Int) : CircularBuffer<T>(capacity) {
    private val lock = Any()
    override fun offer(item: T): Boolean = synchronized(lock) { super.offer(item) }
    override fun poll(): T? = synchronized(lock) { super.poll() }
    override fun peek(): T? = synchronized(lock) { super.peek() }
    override fun clear() = synchronized(lock) { super.clear() }
}
