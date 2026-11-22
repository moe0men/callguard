package ru.guardcall.yandex

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var logView: TextView

    private val roleManager by lazy { getSystemService(RoleManager::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        column.addView(title("Yandex Call Guard"))
        column.addView(hint("Проверка номера обычным поиском Яндекса. Без баз и без API-ключей."))

        statusView = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(16), 0, dp(8))
        }
        column.addView(statusView)

        column.addView(primaryButton("Дать роль определителя номера") { requestRole() })
        column.addView(secondaryButton("Разрешить доступ к контактам") { requestContacts() })

        val enabledCheck = CheckBox(this).apply {
            text = "Включить защиту"
            isChecked = Prefs.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, value -> Prefs.setEnabled(this@MainActivity, value) }
        }
        column.addView(enabledCheck)

        val silenceCheck = CheckBox(this).apply {
            text = "Заглушать спам-звонки (не блокировать)"
            isChecked = Prefs.isSilence(this@MainActivity)
            setOnCheckedChangeListener { _, value -> Prefs.setSilence(this@MainActivity, value) }
        }
        column.addView(silenceCheck)

        val yandexCheck = CheckBox(this).apply {
            text = "Искать номер в Яндексе"
            isChecked = Prefs.useYandex(this@MainActivity)
            setOnCheckedChangeListener { _, value -> Prefs.setUseYandex(this@MainActivity, value) }
        }
        column.addView(yandexCheck)

        column.addView(section("Проверить номер вручную"))

        val input = EditText(this).apply {
            hint = "+7 900 123-45-67"
            inputType = InputType.TYPE_CLASS_PHONE
            setSingleLine()
        }
        column.addView(input)

        resultView = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, dp(4))
        }
        column.addView(resultView)

        column.addView(primaryButton("Проверить сейчас") { runManualCheck(input.text.toString()) })

        column.addView(section("Журнал"))
        column.addView(secondaryButton("Очистить журнал") {
            AppLog.clear()
            refreshLog()
        })

        logView = TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        column.addView(logView)

        val scroll = ScrollView(this).apply { addView(column) }
        setContentView(scroll)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        refresh()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun requestRole() {
        val rm = roleManager ?: return
        if (rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            AppLog.add("Роль определителя уже выдана")
            refresh()
            return
        }
        startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), REQ_ROLE)
    }

    private fun requestContacts() {
        if (!ContactsChecker.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
        }
    }

    private fun runManualCheck(raw: String) {
        val number = PhoneNumbers.normalize(raw)
        if (number.length < 10) {
            resultView.text = "Номер слишком короткий"
            return
        }
        val cached = Cache.get(number)
        if (cached != null) {
            resultView.text = "${PhoneNumbers.pretty(number)}: ${cached.verdict} (кэш), ${cached.reason}"
            return
        }
        resultView.text = "Проверяю ${PhoneNumbers.pretty(number)}…"
        Thread({
            val started = System.currentTimeMillis()
            val result = try {
                YandexChecker.check(this@MainActivity, number, 8000)
            } catch (t: Throwable) {
                CheckResult(Verdict.UNKNOWN, "ошибка: ${t.javaClass.simpleName}")
            }
            Cache.put(number, result)
            val spent = System.currentTimeMillis() - started
            AppLog.add("ручная проверка ${PhoneNumbers.pretty(number)}: ${result.verdict} за $spent мс, ${result.reason}")
            runOnUiThread {
                resultView.text = "${result.verdict}: ${result.reason} (${spent} мс)"
                refreshLog()
            }
        }, "manual-check").apply { isDaemon = true }.start()
    }

    private fun refresh() {
        val role = roleManager?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
        val contacts = ContactsChecker.hasPermission(this)
        statusView.text = buildString {
            append(if (role) "Роль определителя: есть" else "Роль определителя: НЕТ")
            append("\n")
            append(if (contacts) "Доступ к контактам: есть" else "Доступ к контактам: НЕТ")
            append("\n")
            append("Защита: ${if (Prefs.isEnabled(this@MainActivity)) "включена" else "выключена"}")
        }
        refreshLog()
    }

    private fun refreshLog() {
        logView.text = AppLog.snapshot().joinToString("\n") { "${it.time}  ${it.text}" }
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 22f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, dp(6), 0, 0)
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun primaryButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
        gravity = Gravity.CENTER
    }

    private fun secondaryButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
        gravity = Gravity.CENTER
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_ROLE = 101
        private const val REQ_CONTACTS = 102
    }
}
