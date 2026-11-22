package ru.guardcall.yandex

import android.app.Application

class GuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        YandexChecker.warmUp(this)
    }
}
