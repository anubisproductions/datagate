package com.anubisproductions.datagate

import android.content.Context
import android.os.Build
import android.provider.Telephony
import android.telecom.TelecomManager

/*
 * Note vs the firewall build: the Play Store is deliberately NOT protected here. It was
 * the single largest consumer on the test device at 444 MB a cycle, almost all of it
 * background auto-updates - which is exactly what someone on a metered bundle wants to
 * stop. Protecting it would block the app from doing the main thing it exists for.
 */
/**
 * Apps that must never be blocked, because blocking them is how people break their own
 * phone without realising it.
 *
 * Push notifications do not arrive over each app's own socket - they land on one
 * persistent connection held by Google Play Services, which then wakes the target app
 * locally. Block that and every notification on the device stops, including the ones
 * from apps you deliberately left allowed. One competitor's store listing actually tells
 * users to block Play Services; this is the guard against doing that by accident.
 */
object Protected {

    private val ALWAYS = setOf(
        "com.google.android.gms",       // Play Services - carries all FCM push
        "com.google.android.gsf",       // Services Framework
        "com.google.android.gms.location.history",
    )

    /** Resolved lazily: the dialer and SMS handler differ per device. */
    fun setFor(ctx: Context): Set<String> {
        val out = HashSet(ALWAYS)
        out += ctx.packageName

        runCatching {
            Telephony.Sms.getDefaultSmsPackage(ctx)?.let { out += it }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let { out += it }
            }
        }
        return out
    }

    fun reasonFor(pkg: String): String = when (pkg) {
        "com.google.android.gms", "com.google.android.gsf" ->
            "Delivers all your notifications"
        else -> "Calls and messages"
    }
}
