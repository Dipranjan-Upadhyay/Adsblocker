package com.adshield

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

/** Restarts blocking after a reboot or an app update, if the user had it switched on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!AdBlockVpnService.isEnabled(ctx)) return
        if (VpnService.prepare(ctx) != null) return // VPN permission was revoked
        ctx.startForegroundService(
            Intent(ctx, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_START)
        )
    }
}
