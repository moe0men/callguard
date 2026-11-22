package ru.guardcall.yandex

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/** Проверка "номер есть в контактах". Без разрешения всегда false. */
object ContactsChecker {

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun isKnown(ctx: Context, number: String): Boolean {
        if (!hasPermission(ctx)) return false
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(PhoneNumbers.pretty(number))
        )
        return try {
            ctx.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null,
                null,
                null
            )?.use { it.count > 0 } ?: false
        } catch (t: Throwable) {
            false
        }
    }
}
