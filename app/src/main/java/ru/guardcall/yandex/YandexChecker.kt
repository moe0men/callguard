package ru.guardcall.yandex

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Прямой запрос к тому же сервису, который использует АОН Яндекс.Браузера:
 * https://cid.yandex.net/v7/info?phonenums=...&verticals=ugc&lang=ru&lr=213
 *
 * Сервис отдаёт ugc.verdict ("bank.telemarketing", "notification", "polarity.positive"),
 * ugc.polarity ("positive" / "negative") и ugc.description ("Предложение услуг банка.").
 *
 * Запрос обязан содержать заголовок Authorization. Сейчас значение не проверяется,
 * но Яндекс может включить проверку в любой момент.
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

    fun check(ctx: Context, rawNumber: String, timeoutMs: Int = 2500): CheckResult {
        val number = PhoneNumbers.normalize(rawNumber)
        if (number.length < 10) {
            return CheckResult(Verdict.UNKNOWN, "короткий номер, не проверяю")
        }

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
        val org = result.optJSONObject("geo")
            ?.optJSONArray("results")
            ?.optJSONObject(0)
            ?.let { str(it, "name") }

        val label = description ?: org
        val verdictText = verdict.orEmpty()

        if (polarity == "negative" || spamVerdictParts.any { verdictText.contains(it) }) {
            return CheckResult(Verdict.SPAM, label?.let { "«$it»" } ?: "негативная метка: $verdictText")
        }

        if (polarity == "positive") {
            return CheckResult(Verdict.CLEAN, label?.let { "«$it»" } ?: "положительные отзывы")
        }

        if (!verdict.isNullOrBlank() && verdict != "null") {
            return CheckResult(Verdict.CLEAN, label?.let { "«$it»" } ?: "метка: $verdict")
        }

        return CheckResult(Verdict.UNKNOWN, "данных о номере нет")
    }

    private fun str(obj: JSONObject?, key: String): String? {
        if (obj == null || obj.isNull(key)) return null
        val value = obj.optString(key, "")
        return value.takeIf { it.isNotBlank() && it != "null" }
    }
}
