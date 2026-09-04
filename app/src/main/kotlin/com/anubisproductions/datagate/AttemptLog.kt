package com.anubisproductions.datagate

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-app counters for blocked connection attempts.
 *
 * This is the measurement instrument for M0: the shape of a game's attempts over time is
 * what distinguishes "fell into offline mode" from "stuck in a retry loop". A single
 * burst that stops means the app accepted the failure; attempts still arriving at second
 * 40 mean it is looping.
 */
object AttemptLog {

    const val TAG = "DataGate"

    class Stats {
        val total = AtomicLong()
        val tcp = AtomicLong()
        val udp = AtomicLong()
        val dns = AtomicLong()
        val other = AtomicLong()

        @Volatile var firstAtMs: Long = 0L
        @Volatile var lastAtMs: Long = 0L

        /** Attempt counts bucketed into 5-second slots since the window opened. */
        val buckets = ConcurrentHashMap<Int, AtomicLong>()

        /** Distinct destinations seen, capped so a chatty app cannot exhaust memory. */
        val destinations: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }

    private const val MAX_DESTINATIONS = 60
    private const val BUCKET_MS = 5_000L

    private val byApp = ConcurrentHashMap<String, Stats>()

    @Volatile private var windowStartMs: Long = 0L

    fun reset() {
        byApp.clear()
        windowStartMs = System.currentTimeMillis()
        Log.i(TAG, "attempt log reset")
    }

    fun record(app: String, protocol: Int, isDns: Boolean, destination: String) {
        val now = System.currentTimeMillis()
        if (windowStartMs == 0L) windowStartMs = now

        val s = byApp.getOrPut(app) { Stats() }
        s.total.incrementAndGet()
        when {
            isDns -> s.dns.incrementAndGet()
            protocol == com.anubisproductions.datagate.net.Proto.TCP -> s.tcp.incrementAndGet()
            protocol == com.anubisproductions.datagate.net.Proto.UDP -> s.udp.incrementAndGet()
            else -> s.other.incrementAndGet()
        }
        if (s.firstAtMs == 0L) s.firstAtMs = now
        s.lastAtMs = now

        val bucket = ((now - windowStartMs) / BUCKET_MS).toInt()
        s.buckets.getOrPut(bucket) { AtomicLong() }.incrementAndGet()

        if (s.destinations.size < MAX_DESTINATIONS) s.destinations.add(destination)
    }

    /**
     * Machine-readable dump, one line per app, emitted to logcat under [TAG].
     * `adb logcat -s DataGate` picks it up; the M0 harness parses these lines.
     */
    fun dump() {
        val now = System.currentTimeMillis()
        Log.i(TAG, "DUMP-BEGIN window_ms=${now - windowStartMs} apps=${byApp.size}")
        if (byApp.isEmpty()) {
            Log.i(TAG, "DUMP-EMPTY no blocked connection attempts recorded")
        }
        for ((app, s) in byApp.entries.sortedByDescending { it.value.total.get() }) {
            val spanMs = if (s.firstAtMs == 0L) 0 else s.lastAtMs - s.firstAtMs
            val sinceLastMs = if (s.lastAtMs == 0L) -1 else now - s.lastAtMs
            val timeline = s.buckets.entries
                .sortedBy { it.key }
                .joinToString(",") { "${it.key * 5}s:${it.value.get()}" }
            Log.i(
                TAG,
                "DUMP app=$app total=${s.total.get()} tcp=${s.tcp.get()} udp=${s.udp.get()} " +
                    "dns=${s.dns.get()} other=${s.other.get()} " +
                    "span_ms=$spanMs quiet_ms=$sinceLastMs timeline=[$timeline]"
            )
            Log.i(TAG, "DUMP-DEST app=$app n=${s.destinations.size} " +
                s.destinations.take(20).joinToString(" "))
        }
        Log.i(TAG, "DUMP-END")
    }
}
