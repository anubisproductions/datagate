package com.anubisproductions.datagate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log

/**
 * Restores blocking after a reboot.
 *
 * Without this the engine stops silently on restart and every restricted app quietly
 * goes back online - the user is not told, and would only notice by seeing an ad.
 *
 * VPN consent survives a reboot, so prepare() normally returns null here. If the user
 * revoked it, or another VPN app took over, we log and stay off rather than pestering
 * them with an Activity at boot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        val rules = Rules.blockedAny(context)
        if (rules.isEmpty()) {
            Log.i(AttemptLog.TAG, "BOOT no rules; staying off")
            return
        }

        if (VpnService.prepare(context) != null) {
            Log.w(AttemptLog.TAG, "BOOT consent missing; blocking stays off until opened")
            return
        }

        Log.i(AttemptLog.TAG, "BOOT restoring ${rules.size} rule(s)")
        BlockVpnService.start(context)
    }
}
