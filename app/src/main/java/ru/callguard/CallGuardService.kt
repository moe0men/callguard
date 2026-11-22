package ru.callguard

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Перехват входящего звонка до гудка.
 * Контакт -> обычный звонок. Метка спама -> беззвучно. Всё остальное -> обычный звонок.
 */
class CallGuardService : CallScreeningService() {

    private val responded = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    override fun onScreenCall(callDetails: Call.Details) {
        responded.set(false)

        val number = callDetails.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() }
        val incoming = callDetails.callDirection == Call.Details.DIRECTION_INCOMING

        if (!incoming || number == null || !Prefs.isEnabled(this)) {
            allow(callDetails)
            return
        }

        if (ContactsChecker.isKnown(this, number)) {
            AppLog.add(number, Verdict.CLEAN, "номер в контактах")
            allow(callDetails)
            return
        }

        val key = PhoneNumbers.normalize(number)
        Cache.get(key)?.let { cached ->
            AppLog.add(number, cached.verdict, cached.reason + " · кэш")
            respond(callDetails, cached.verdict)
            return
        }

        // Android ждёт respondToCall не больше 5 секунд.
        main.postDelayed({ timeout(callDetails, number) }, 4300)

        Thread({
            val started = System.currentTimeMillis()
            val result = try {
                YandexChecker.check(number, 2500)
            } catch (t: Throwable) {
                CheckResult(Verdict.UNKNOWN, "ошибка: ${t.javaClass.simpleName}")
            }
            Cache.put(key, result)
            val spent = System.currentTimeMillis() - started
            AppLog.add(number, result.verdict, "${result.reason} · $spent мс")
            respond(callDetails, result.verdict)
        }, "yandex-check").apply { isDaemon = true }.start()
    }

    private fun timeout(details: Call.Details, number: String) {
        if (responded.get()) return
        AppLog.add(number, Verdict.UNKNOWN, "таймаут проверки")
        respond(details, Verdict.UNKNOWN)
    }

    private fun allow(details: Call.Details) = respond(details, Verdict.CLEAN)

    private fun respond(details: Call.Details, verdict: Verdict) {
        if (!responded.compareAndSet(false, true)) return
        val silence = verdict == Verdict.SPAM && Prefs.isSilence(this)
        val builder = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setSilenceCall(silence)
        }
        try {
            respondToCall(details, builder.build())
        } catch (t: Throwable) {
            Log.w("CallGuard", "respondToCall failed", t)
        }
    }
}
