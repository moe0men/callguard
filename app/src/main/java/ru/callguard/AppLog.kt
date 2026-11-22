package ru.callguard

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Последние проверки в памяти, без записи на диск. */
object AppLog {

    private const val MAX = 20
    private val entries = ArrayDeque<Entry>()

    data class Entry(val time: String, val number: String, val verdict: Verdict, val detail: String)

    @Synchronized
    fun add(number: String, verdict: Verdict, detail: String) {
        val stamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        entries.addFirst(Entry(stamp, PhoneNumbers.pretty(number), verdict, detail))
        while (entries.size > MAX) entries.removeLast()
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()
}
