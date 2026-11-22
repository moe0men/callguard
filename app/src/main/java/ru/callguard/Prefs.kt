package ru.callguard

import android.content.Context

object Prefs {

    private const val FILE = "settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SILENCE = "silence"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, value: Boolean) = sp(ctx).edit().putBoolean(KEY_ENABLED, value).apply()

    fun isSilence(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_SILENCE, true)

    fun setSilence(ctx: Context, value: Boolean) = sp(ctx).edit().putBoolean(KEY_SILENCE, value).apply()
}
