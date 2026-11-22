package ru.callguard

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Прямой запрос к сервису определителя номера Яндекса:
 * https://cid.yandex.net/v7/info?phonenums=...&verticals=ugc&lang=ru&lr=213
 *
 * ugc.verdict: bank.telemarketing, notification, polarity.positive и т.д.
 * ugc.polarity: positive / negative.
 * ugc.description: «Предложение услуг банка.»
 *
 * Заголовок Authorization обязателен: без него 400. Значение сейчас не проверяется.
 */
object YandexChecker {

    private const val ENDPOINT = "https://cid.yandex.net/v7/info"
    private const val AUTH = "Bearer android"

    private val spamVerdictParts = listOf(
        "telemarketing",
        "spam",
        "fraud",
        "scam",
        "debt",
        "collect",
        "advert",
        "promo",
        "marketing"
    )

    fun check(rawNumber: String, timeoutMs: Int = 2500): CheckResult {
        val number = PhoneNumbers.normalize(rawNumber)
        if (number.length < 10) return CheckResult(Verdict.UNKNOWN, "короткий номер")

        val url = buildString {
            append(ENDPOINT)
            append("?phonenums=").append(URLEncoder.encode(number, "UTF-8"))
            append("&verticals=ugc&lang=ru&lr=213")
        }

        val body = try {
            fetch(url, timeoutMs)
        } catch (t: Throwable) {
            return CheckResult(Verdict.UNKNOWN, "сеть: ${t.javaClass.simpleName}")
        }

        return parse(body)
    }

    private fun fetch(url: String, timeoutMs: Int): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = minOf(1200, timeoutMs)
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("Authorization", AUTH)
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): CheckResult {
        val root = try {
            JSONTokener(body).nextValue()
        } catch (t: Throwable) {
            return CheckResult(Verdict.UNKNOWN, "ответ не JSON")
        }

        val item = when (root) {
            is JSONArray -> root.optJSONObject(0)
            is JSONObject -> root
            else -> null
        } ?: return CheckResult(Verdict.UNKNOWN, "пустой ответ")

        val result = item.optJSONObject("result")
            ?: return CheckResult(Verdict.UNKNOWN, "нет блока result")

        val ugc = result.optJSONObject("ugc")
        val description = str(ugc, "description")
        val polarity = str(ugc, "polarity")
        val verdict = str(ugc, "verdict")
        val verdictText = verdict.orEmpty()

        val org = result.optJSONObject("geo")
            ?.optJSONArray("results")
            ?.optJSONObject(0)
            ?.let { str(it, "name") }

        val label = description ?: org

        if (polarity == "negative" || spamVerdictParts.any { verdictText.contains(it) }) {
            return CheckResult(Verdict.SPAM, label ?: "метка: $verdictText")
        }

        if (polarity == "positive") {
            return CheckResult(Verdict.CLEAN, label ?: "положительные отзывы")
        }

        if (verdictText.isNotBlank()) {
            return CheckResult(Verdict.CLEAN, label ?: "метка: $verdictText")
        }

        return CheckResult(Verdict.UNKNOWN, "данных о номере нет")
    }

    private fun str(obj: JSONObject?, key: String): String? {
        if (obj == null || obj.isNull(key)) return null
        val value = obj.optString(key, "")
        return value.takeIf { it.isNotBlank() && it != "null" }
    }
}
