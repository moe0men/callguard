package ru.guardcall.yandex

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * Мгновенная проверка номера обычным поиском Яндекса. Без API-ключей и без баз.
 * Бесплатный HTML-поиск иногда отдаёт SmartCaptcha: тогда результат UNKNOWN.
 */
object YandexChecker {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private const val BASE = "https://yandex.ru/search/?text="

    /** Насколько близко к номеру должна стоять метка, символов. */
    private const val PROXIMITY = 600

    private val badMarkers = listOf(
        "нежелательный звонок",
        "есть жалобы",
        "много жалоб",
        "подозрение на мошенничество",
        "мошенническ",
        "телефонный спам",
        "спам-звон",
        "спам звон",
        "навязчив",
        "назойлив",
        "рекламный звонок",
        "не берите трубку",
        "коллектор"
    )

    private val goodMarkers = listOf(
        "полезный звонок",
        "данных об абоненте нет",
        "нет информации о номере",
        "не является спамом",
        "проверенный номер"
    )

    private val cookieJar = StringBuilder()

    fun check(rawNumber: String, timeoutMs: Long = 3000): CheckResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        val number = PhoneNumbers.normalize(rawNumber)
        if (number.length < 10) {
            return CheckResult(Verdict.UNKNOWN, "короткий номер, не проверяю")
        }

        val query = URLEncoder.encode(PhoneNumbers.pretty(number), "UTF-8")
        val url = BASE + query + "&lr=213&noreask=1"

        val html = try {
            fetch(url, deadline)
        } catch (t: Throwable) {
            return CheckResult(Verdict.UNKNOWN, "сеть: ${t.javaClass.simpleName}")
        }

        if (isCaptcha(html)) {
            return CheckResult(Verdict.UNKNOWN, "Яндекс показал капчу")
        }

        val text = stripHtml(html)
        val positions = numberPositions(text, number)
        if (positions.isEmpty()) {
            return CheckResult(Verdict.UNKNOWN, "номер не найден в выдаче")
        }

        val bad = nearest(text, badMarkers, positions)
        if (bad != null) {
            return CheckResult(Verdict.SPAM, "метка: «${bad.first}»")
        }

        val good = nearest(text, goodMarkers, positions)
        if (good != null) {
            return CheckResult(Verdict.CLEAN, "метка: «${good.first}»")
        }

        return CheckResult(Verdict.UNKNOWN, "меток спама нет")
    }

    private fun isCaptcha(html: String): Boolean {
        val probes = listOf(
            "SmartCaptcha",
            "showcaptcha",
            "Подтвердите, что запросы отправляли вы",
            "captcha__image"
        )
        return probes.any { html.contains(it, ignoreCase = true) }
    }

    private fun fetch(url: String, deadline: Long): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 1500
        conn.readTimeout = 2200
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.5")
        conn.setRequestProperty("Accept-Encoding", "gzip")
        synchronized(cookieJar) {
            if (cookieJar.isNotEmpty()) conn.setRequestProperty("Cookie", cookieJar.toString())
        }

        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            storeCookies(conn)

            val gzip = conn.contentEncoding?.contains("gzip", ignoreCase = true) == true
            val stream = if (gzip) GZIPInputStream(conn.inputStream) else conn.inputStream
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }

            if (System.currentTimeMillis() > deadline) throw IOException("timeout")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun storeCookies(conn: HttpURLConnection) {
        val setCookies = conn.headerFields["Set-Cookie"] ?: return
        if (setCookies.isEmpty()) return
        synchronized(cookieJar) {
            val fresh = setCookies.mapNotNull { it.substringBefore(';').trim().takeIf(String::isNotEmpty) }
            val merged = (cookieJar.toString().split("; ").filter { it.isNotEmpty() } + fresh)
                .associateBy { it.substringBefore('=') }
                .values
            cookieJar.setLength(0)
            cookieJar.append(merged.joinToString("; "))
        }
    }

    private fun stripHtml(html: String): String {
        var s = html.replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?s)<[^>]+>"), " ")
        s = s
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
        return s.replace(Regex("\\s+"), " ").lowercase()
    }

    /** Все позиции номера в тексте: +7 900 123-45-67, 8 900 123 45 67, 79001234567. */
    private fun numberPositions(text: String, normalized: String): List<Int> {
        val ten = normalized.takeLast(10)
        if (ten.length < 10) return emptyList()
        val body = ten.toCharArray().joinToString("\\D{0,4}") { Regex.escape(it.toString()) }
        val regex = Regex("(?:\\+?7|8)?\\D{0,3}$body")
        return regex.findAll(text).map { it.range.first }.toList()
    }

    private fun nearest(text: String, markers: List<String>, positions: List<Int>): Pair<String, Int>? {
        var best: Pair<String, Int>? = null
        var bestDistance = Int.MAX_VALUE
        for (marker in markers) {
            var idx = text.indexOf(marker)
            while (idx >= 0) {
                for (p in positions) {
                    val d = if (idx > p) idx - p else p - idx
                    if (d <= PROXIMITY && d < bestDistance) {
                        bestDistance = d
                        best = marker to idx
                    }
                }
                idx = text.indexOf(marker, idx + 1)
            }
        }
        return best
    }
}
