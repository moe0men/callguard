package ru.guardcall.yandex

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Журнал событий в памяти, без записи на диск. */
object AppLog {

    private const val MAX = 100
    private val entries = ArrayDeque<Entry>()

    data class Entry(val time: String, val text: String)

    @Synchronized
    fun add(text: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        entries.addFirst(Entry(stamp, text))
        while (entries.size > MAX) entries.removeLast()
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()
}
