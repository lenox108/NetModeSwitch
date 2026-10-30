package com.netmode.switcher

/**
 * The newest [max] lines, newest first. Lines come from many threads and storing is a read of
 * the whole list and a write of it, so each add holds the lock across both.
 */
internal class EventLog(
    private val max: Int,
    private val read: () -> List<String>,
    private val write: (List<String>) -> Unit,
) {
    private val lock = Any()

    fun lines(): List<String> = synchronized(lock) { read() }

    fun add(line: String) = synchronized(lock) { write((listOf(line) + read()).take(max)) }

    fun clear() = synchronized(lock) { write(emptyList()) }
}
