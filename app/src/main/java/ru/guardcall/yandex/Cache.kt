package ru.guardcall.yandex

import java.util.concurrent.ConcurrentHashMap

/** Кэш вердиктов только в памяти процесса. На диск ничего не пишется. */
object Cache {

    private const val MAX = 300
    private const val TTL_KNOWN = 6 * 60 * 60 * 1000L
    private const val TTL_UNKNOWN = 15 * 60 * 1000L

    private data class Entry(val result: CheckResult, val expiresAt: Long)

    private val map = ConcurrentHashMap<String, Entry>()

    fun get(number: String): CheckResult? {
        val entry = map[number] ?: return null
        if (entry.expiresAt < System.currentTimeMillis()) {
            map.remove(number)
            return null
        }
        return entry.result
    }

    fun put(number: String, result: CheckResult) {
        if (map.size >= MAX) map.clear()
        val ttl = if (result.verdict == Verdict.UNKNOWN) TTL_UNKNOWN else TTL_KNOWN
        map[number] = Entry(result, System.currentTimeMillis() + ttl)
    }
}
