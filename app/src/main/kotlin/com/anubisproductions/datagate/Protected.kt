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

    /*
     * Both entries share one uid on every device checked (10244 here), so blocking either
     * blocks the socket that carries push for the whole device. The uid is what the tunnel
     * actually filters on, which is why the pair has to be guarded together.
     *
     * com.google.android.gms.location.history was in this set and has been removed. It has
     * its own uid (10251 here), so blocking it cannot affect notifications - it was being
     * refused for no reason, under a label claiming it delivered them. Refusing to let a
     * privacy-minded user block Google's location history is the opposite of the point.
     */
    private val ALWAYS = setOf(
        "com.google.android.gms",       // Play Services - carries all FCM push
        "com.google.android.gsf",       // Services Framework - same uid as the above
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

    /**
     * Why this app cannot be blocked, in the user's own language.
     *
     * Takes a context rather than a bare package name for two reasons: these strings are
     * translated into every locale the app ships in, and our own package name is only
     * knowable at runtime. Without the first case our own row read "Calls and messages",
     * which is simply untrue.
     */
    fun reasonFor(ctx: Context, pkg: String): String = when (pkg) {
        ctx.packageName -> ctx.getString(R.string.protected_reason_self)
        "com.google.android.gms",
        "com.google.android.gsf" ->
            ctx.getString(R.string.protected_reason_push)
        else -> ctx.getString(R.string.protected_reason_calls)
    }
}
