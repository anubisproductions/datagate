package com.anubisproductions.datagate

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
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
     * Play Services is not the only push carrier, and assuming it is breaks the guard on
     * exactly the phones the app is aimed at.
     *
     * Xiaomi, OPPO, vivo, Realme and Transsion dominate India, Indonesia, Pakistan, Egypt
     * and Nigeria - every market this app has a listing for. Those phones run their own
     * push service alongside (or instead of) Play Services, and a great many Chinese-market
     * apps deliver notifications only through it. Blocking one of these is the same
     * self-inflicted failure as blocking Play Services, just invisible to anyone testing on
     * a Pixel.
     *
     * Deliberately excluded: vendor app stores, cloud-backup and account-sync services.
     * They are heavy background consumers and blocking them costs the user nothing they
     * will miss - the same reasoning that keeps the Play Store off this list. Only packages
     * that carry push *for other apps* belong here.
     *
     * Protection is by package name and costs nothing when a package is absent:
     * AppRepository skips anything not installed, so a Samsung sees only the Samsung entry.
     */
    private val VENDOR_PUSH = setOf(
        "com.sec.spp.push",             // Samsung Push Service
        "com.xiaomi.xmsf",              // Xiaomi Service Framework - carries MiPush
        "com.huawei.hwid",              // HMS Core - Huawei Push
        "com.hihonor.id",               // Honor's HMS equivalent, post-split
        "com.hihonor.push",             // Honor Push
        "com.heytap.mcs",               // OPPO / Realme / OnePlus push (current)
        "com.coloros.mcs",              // the same service on older ColorOS builds
        "com.vivo.pushservice",         // vivo Push
        "com.meizu.cloud",              // Flyme Push
    )

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
    private val GOOGLE_PUSH = setOf(
        "com.google.android.gms",       // Play Services - carries all FCM push
        "com.google.android.gsf",       // Services Framework - same uid as the above
    )

    private val ALWAYS = GOOGLE_PUSH + VENDOR_PUSH

    private const val PER_USER_RANGE = 100000

    /**
     * True when this package runs on a uid below the first application uid - in practice
     * the shared system uid, 1000.
     *
     * This matters because the tunnel filters by **uid**, not by package.
     * addAllowedApplication("com.android.settings") does not capture Settings; it captures
     * uid 1000, which on the test device is 78 packages including the connectivity checks,
     * telephony components, fused location and tethering. Settings carries a launcher icon,
     * so without this rule it appeared in the list as an ordinary blockable app, and one tap
     * would have pulled the whole Android system into the tunnel.
     *
     * A named list would not do the job: which system components carry a launcher entry
     * differs by OEM. The uid decides what actually gets captured, so the uid is what this
     * checks.
     *
     * The modulo strips the user id - a work-profile app has uid userId * 100000 + appId.
     */
    fun isSystemUid(pm: PackageManager, pkg: String): Boolean = runCatching {
        pm.getApplicationInfo(pkg, 0).uid % PER_USER_RANGE < Process.FIRST_APPLICATION_UID
    }.getOrDefault(false)

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
    fun reasonFor(ctx: Context, pkg: String): String = when {
        pkg == ctx.packageName -> ctx.getString(R.string.protected_reason_self)
        pkg in GOOGLE_PUSH || pkg in VENDOR_PUSH ->
            ctx.getString(R.string.protected_reason_push)
        isSystemUid(ctx.packageManager, pkg) ->
            ctx.getString(R.string.protected_reason_system)
        else -> ctx.getString(R.string.protected_reason_calls)
    }
}
