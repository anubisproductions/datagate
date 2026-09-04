package com.anubisproductions.datagate

import android.content.Context

/**
 * The bundle, the cycle, and the savings ledger.
 *
 * The savings figure is the one thing that proves the app did anything. Every competitor
 * shows what an app *used*; none shows what blocking it *saved*, because none of them
 * records the before. We record the app's own recent daily average at the moment it is
 * blocked, and compare.
 */
object Budget {

    private const val PREFS = "datagate_budget"
    private const val KEY_BUNDLE_MB = "bundle_mb"
    private const val KEY_RESET_DAY = "reset_day"
    private const val PREFIX_BLOCKED_AT = "blocked_at_"
    private const val PREFIX_DAILY_AVG = "daily_avg_"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Monthly mobile allowance in MB. 0 means "not set", and the UI hides the gauge. */
    fun bundleMb(ctx: Context): Int = prefs(ctx).getInt(KEY_BUNDLE_MB, 0)

    fun setBundleMb(ctx: Context, mb: Int) = prefs(ctx).edit().putInt(KEY_BUNDLE_MB, mb).apply()

    /** Day of the month the bundle renews. */
    fun resetDay(ctx: Context): Int = prefs(ctx).getInt(KEY_RESET_DAY, 1)

    fun setResetDay(ctx: Context, day: Int) =
        prefs(ctx).edit().putInt(KEY_RESET_DAY, day.coerceIn(1, 28)).apply()

    /**
     * Records the baseline at the moment an app is restricted: when, and what it had been
     * spending per day. Without this there is nothing to compare against later.
     */
    fun recordBlock(ctx: Context, pkg: String, bytesPerDayBefore: Long) {
        prefs(ctx).edit()
            .putLong(PREFIX_BLOCKED_AT + pkg, System.currentTimeMillis())
            .putLong(PREFIX_DAILY_AVG + pkg, bytesPerDayBefore)
            .apply()
    }

    fun clearBlock(ctx: Context, pkg: String) {
        prefs(ctx).edit()
            .remove(PREFIX_BLOCKED_AT + pkg)
            .remove(PREFIX_DAILY_AVG + pkg)
            .apply()
    }

    fun blockedAt(ctx: Context, pkg: String): Long = prefs(ctx).getLong(PREFIX_BLOCKED_AT + pkg, 0L)

    private fun dailyAvgBefore(ctx: Context, pkg: String): Long =
        prefs(ctx).getLong(PREFIX_DAILY_AVG + pkg, 0L)

    /**
     * Estimated bytes not spent because [pkg] has been restricted.
     *
     * Deliberately an estimate and labelled as one in the UI: it assumes the app would have
     * carried on at its prior daily rate. [actualSince] is subtracted so an app that still
     * leaks some traffic is not credited with saving it.
     */
    fun estimatedSaved(ctx: Context, pkg: String, actualSince: Long): Long {
        val since = blockedAt(ctx, pkg)
        if (since == 0L) return 0L
        val avg = dailyAvgBefore(ctx, pkg)
        if (avg == 0L) return 0L
        val days = (System.currentTimeMillis() - since).toDouble() / 86_400_000.0
        if (days <= 0) return 0L
        return ((avg * days).toLong() - actualSince).coerceAtLeast(0L)
    }

    /**
     * Estimated saving for one app, measuring its actual usage since it was restricted.
     *
     * Callers used to pass the app's cycle total here, which made the subtraction
     * meaningless and pinned every result to zero.
     */
    fun estimatedSavedFor(ctx: Context, pkg: String): Long {
        val since = blockedAt(ctx, pkg)
        if (since == 0L) return 0L
        return estimatedSaved(ctx, pkg, UsageRepository.bytesSince(ctx, pkg, since))
    }

    fun totalEstimatedSaved(ctx: Context, blocked: Set<String>): Long =
        blocked.sumOf { estimatedSavedFor(ctx, it) }
}
