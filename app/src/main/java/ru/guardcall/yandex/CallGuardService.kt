package ru.guardcall.yandex

import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Перехват входящего звонка до гудка.
 * Правила: контакт -> обычный звонок; метка спама -> беззвучно; всё остальное -> обычный звонок.
 */
class CallGuardService : CallScreeningService() {

    private val responded = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    override fun onScreenCall(callDetails: Call.Details) {
        responded.set(false)

        val number = callDetails.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() }
        val incoming = callDetails.callDirection == Call.Details.DIRECTION_INCOMING

        if (!incoming || number == null) {
            allow(callDetails)
            return
        }

        if (!Prefs.isEnabled(this) || !Prefs.useYandex(this)) {
            allow(callDetails)
            return
        }

        if (ContactsChecker.isKnown(this, number)) {
            AppLog.add("${PhoneNumbers.pretty(number)}: в контактах")
            allow(callDetails)
            return
        }

        val key = PhoneNumbers.normalize(number)
        val cached = Cache.get(key)
        if (cached != null) {
            AppLog.add("${PhoneNumbers.pretty(number)}: ${cached.verdict} (кэш), ${cached.reason}")
            respond(callDetails, cached.verdict)
            return
        }

        // Система ждёт ответ 5 секунд. Через 4.3 с отвечаем "обычный звонок".
        main.postDelayed({ timeout(callDetails, number) }, 4300)

        Thread({
            val started = System.currentTimeMillis()
            val result = try {
                YandexChecker.check(this, number, 2500)
            } catch (t: Throwable) {
                CheckResult(Verdict.UNKNOWN, "ошибка: ${t.javaClass.simpleName}")
            }
            Cache.put(key, result)
            val spent = System.currentTimeMillis() - started
            AppLog.add("${PhoneNumbers.pretty(number)}: ${result.verdict} за $spent мс, ${result.reason}")
            respond(callDetails, result.verdict)
        }, "yandex-check").apply { isDaemon = true }.start()
    }

    private fun timeout(details: Call.Details, number: String) {
        if (responded.get()) return
        AppLog.add("${PhoneNumbers.pretty(number)}: таймаут, обычный звонок")
        respond(details, Verdict.UNKNOWN)
    }

    private fun allow(details: Call.Details) {
        respond(details, Verdict.CLEAN)
    }

    private fun respond(details: Call.Details, verdict: Verdict) {
        if (!responded.compareAndSet(false, true)) return
        val silence = verdict == Verdict.SPAM && Prefs.isSilence(this)
        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSilenceCall(silence)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()
        try {
            respondToCall(details, response)
        } catch (t: Throwable) {
            Log.w("CallGuard", "respondToCall failed", t)
        }
    }
}
