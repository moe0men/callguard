package ru.guardcall.yandex

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import org.json.JSONTokener
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Проверка номера через настоящий движок Chromium (WebView).
 * Обычный HTTP-клиент Яндекс режет капчей: нет JavaScript и отпечаток не браузерный.
 * Реальный WebView проходит проверку и получает обычную выдачу.
 */
object YandexChecker {

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val PROXIMITY = 600
    private const val POLL_MS = 200L
    private const val RENDER_DELAY_MS = 350L

    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null

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
        "предложение товаров или услуг",
        "не берите трубку",
        "коллектор"
    )

    private val goodMarkers = listOf(
        "полезный звонок",
        "данных об абоненте нет"
    )

    /** Прогрев движка, чтобы первый звонок не платил за инициализацию WebView. */
    fun warmUp(ctx: Context) {
        main.post { runCatching { obtain(ctx.applicationContext) } }
    }

    fun check(ctx: Context, rawNumber: String, timeoutMs: Long = 3500): CheckResult {
        val number = PhoneNumbers.normalize(rawNumber)
        if (number.length < 10) {
            return CheckResult(Verdict.UNKNOWN, "короткий номер, не проверяю")
        }

        val latch = CountDownLatch(1)
        val done = AtomicBoolean(false)
        val holder = AtomicReference(CheckResult(Verdict.UNKNOWN, "нет ответа WebView"))
        val finish: (CheckResult) -> Unit = { result ->
            if (done.compareAndSet(false, true)) {
                holder.set(result)
                latch.countDown()
            }
        }

        val deadline = SystemClock.uptimeMillis() + timeoutMs
        main.post {
            try {
                val wv = obtain(ctx.applicationContext)
                val url = buildUrl(number)
                wv.loadUrl(url)
                waitReady(wv, url, number, deadline, finish)
            } catch (t: Throwable) {
                finish(CheckResult(Verdict.UNKNOWN, "WebView: ${t.javaClass.simpleName}"))
            }
        }

        if (!latch.await(timeoutMs + 300, TimeUnit.MILLISECONDS)) {
            finish(CheckResult(Verdict.UNKNOWN, "WebView не успел за $timeoutMs мс"))
        }
        return holder.get()
    }

    private fun buildUrl(number: String): String {
        val query = URLEncoder.encode(PhoneNumbers.pretty(number), "UTF-8")
        return "https://yandex.ru/search/?text=$query&lr=213&noreask=1"
    }

    private fun waitReady(
        wv: WebView,
        url: String,
        number: String,
        deadline: Long,
        finish: (CheckResult) -> Unit
    ) {
        if (SystemClock.uptimeMillis() > deadline) {
            finish(CheckResult(Verdict.UNKNOWN, "WebView не успел загрузить страницу"))
            return
        }
        wv.evaluateJavascript("document.readyState") { state ->
            val ready = state?.contains("complete") == true
            if (!ready) {
                main.postDelayed({ waitReady(wv, url, number, deadline, finish) }, POLL_MS)
                return@evaluateJavascript
            }
            // Дать странице дорисовать блоки после load.
            main.postDelayed({
                wv.evaluateJavascript("document.body ? document.body.innerText : ''") { raw ->
                    finish(parse(unquote(raw), wv.url ?: url, number))
                }
            }, RENDER_DELAY_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun obtain(ctx: Context): WebView {
        webView?.let { return it }

        val wv = WebView(ctx)
        wv.setBackgroundColor(Color.TRANSPARENT)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = false
            blockNetworkImage = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = DESKTOP_UA
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        webView = wv
        return wv
    }

    /** evaluateJavascript отдаёт JSON-строку, её надо раскодировать. */
    private fun unquote(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return try {
            JSONTokener(raw).nextValue() as? String ?: raw
        } catch (t: Throwable) {
            raw
        }
    }

    private fun parse(rawText: String, url: String, number: String): CheckResult {
        val text = rawText.replace(Regex("\\s+"), " ").lowercase()
        if (text.isEmpty()) return CheckResult(Verdict.UNKNOWN, "пустая страница")

        if (url.contains("showcaptcha", true) || text.contains("подтвердите, что запросы")) {
            return CheckResult(Verdict.UNKNOWN, "Яндекс показал капчу")
        }

        val positions = numberPositions(text, number)
        if (positions.isEmpty()) {
            return CheckResult(Verdict.UNKNOWN, "номер не найден в выдаче")
        }

        val bad = nearest(text, badMarkers, positions)
        if (bad != null) return CheckResult(Verdict.SPAM, "метка: «${bad.first}»")

        val good = nearest(text, goodMarkers, positions)
        if (good != null) return CheckResult(Verdict.CLEAN, "метка: «${good.first}»")

        return CheckResult(Verdict.UNKNOWN, "меток спама нет")
    }

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
