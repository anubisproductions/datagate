package com.anubisproductions.datagate

import android.content.Context

/**
 * How a restricted app's packets are answered.
 *
 * Measured on real traffic: apps almost never reach a TCP connect, they sit at name
 * resolution. Silent drop leaves them retrying
 * indefinitely; instant refusal made them retry *sooner* and about 40% more often; only a
 * valid negative DNS answer reduced the storm. Hence NXDOMAIN is the default.
 */
enum class BlockMode {
    /** Discard silently. What most competitors do; the app retries until its own timeout. */
    DROP,

    /** TCP RST and ICMP/ICMPv6 port-unreachable — immediate ECONNREFUSED. */
    REFUSE,

    /**
     * As [REFUSE], plus a synthetic NXDOMAIN for DNS. Lowest measured retry traffic.
     *
     * NXDOMAIN rather than SERVFAIL: it surfaces as UnknownHostException, the exception
     * nearly every Android offline path is written to catch, whereas SERVFAIL sends the
     * resolver off to retry other servers first.
     */
    NXDOMAIN;

    companion object {
        fun parse(s: String?): BlockMode =
            entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) } ?: NXDOMAIN
    }
}

/** The transport a rule applies to. */
enum class NetKind { WIFI, MOBILE }

/**
 * Per-app, per-network rules.
 *
 * Stored per transport rather than as one blocked set, because "no mobile data, Wi-Fi is
 * fine" is the core data-saver request — metered bytes are the ones that cost money. The
 * engine enforces it by rebuilding the tunnel with whichever set applies to the network
 * that is currently active; see [BlockVpnService].
 */
object Rules {
    private const val PREFS = "datagate_rules"
    private const val KEY_WIFI = "blocked_wifi"
    private const val KEY_MOBILE = "blocked_mobile"
    private const val KEY_MODE = "mode"

    /** Pre-per-network key. Migrated on first read, then left in place. */
    private const val KEY_LEGACY = "blocked_packages"
    private const val KEY_MIGRATED = "migrated_per_network"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Moves any pre-per-network rule set into both transports, so existing restrictions do
     * not silently stop applying after an update.
     */
    private fun migrate(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean(KEY_MIGRATED, false)) return
        val legacy = p.getStringSet(KEY_LEGACY, null)
        p.edit().apply {
            if (!legacy.isNullOrEmpty()) {
                putStringSet(KEY_WIFI, legacy)
                putStringSet(KEY_MOBILE, legacy)
            }
            putBoolean(KEY_MIGRATED, true)
        }.apply()
    }

    private fun key(kind: NetKind) = if (kind == NetKind.WIFI) KEY_WIFI else KEY_MOBILE

    fun blockedOn(ctx: Context, kind: NetKind): Set<String> {
        migrate(ctx)
        return prefs(ctx).getStringSet(key(kind), emptySet()) ?: emptySet()
    }

    /** Union across transports — what the usage list shows as restricted. */
    fun blockedAny(ctx: Context): Set<String> =
        blockedOn(ctx, NetKind.WIFI) + blockedOn(ctx, NetKind.MOBILE)

    fun isBlockedOn(ctx: Context, pkg: String, kind: NetKind) = pkg in blockedOn(ctx, kind)

    fun setBlockedOn(ctx: Context, kind: NetKind, packages: Set<String>) {
        migrate(ctx)
        prefs(ctx).edit().putStringSet(key(kind), packages).apply()
    }

    /** Restrict or release one app on one transport. */
    fun toggle(ctx: Context, pkg: String, kind: NetKind, blocked: Boolean) {
        val current = blockedOn(ctx, kind).toMutableSet()
        if (blocked) current.add(pkg) else current.remove(pkg)
        setBlockedOn(ctx, kind, current)
    }

    /** Restrict or release on both transports — what the usage list's single switch does. */
    fun setBoth(ctx: Context, pkg: String, blocked: Boolean) {
        toggle(ctx, pkg, NetKind.WIFI, blocked)
        toggle(ctx, pkg, NetKind.MOBILE, blocked)
    }

    fun mode(ctx: Context): BlockMode = BlockMode.parse(prefs(ctx).getString(KEY_MODE, null))

    fun setMode(ctx: Context, mode: BlockMode) =
        prefs(ctx).edit().putString(KEY_MODE, mode.name).apply()
}
