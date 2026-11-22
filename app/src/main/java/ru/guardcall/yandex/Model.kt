package ru.guardcall.yandex

/** Вердикт по номеру. */
enum class Verdict {
    /** Найдены метки спама, мошенничества или жалоб. */
    SPAM,

    /** Найдено прямое указание, что звонок полезный или данных о спаме нет. */
    CLEAN,

    /** Ответа нет, капча, ошибка сети или непонятная выдача. */
    UNKNOWN
}

data class CheckResult(val verdict: Verdict, val reason: String)

/** Приведение номера к виду 7XXXXXXXXXX. */
object PhoneNumbers {

    fun normalize(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return when {
            digits.length == 11 && digits.startsWith("8") -> "7" + digits.substring(1)
            digits.length == 11 && digits.startsWith("7") -> digits
            digits.length == 10 -> "7$digits"
            else -> digits
        }
    }

    /** Номер в виде +7 900 123-45-67 для поиска и логов. */
    fun pretty(raw: String): String {
        val d = normalize(raw)
        if (d.length != 11) return raw
        return "+${d[0]} ${d.substring(1, 4)} ${d.substring(4, 7)}-${d.substring(7, 9)}-${d.substring(9)}"
    }
}
