package com.meowl.app.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Automatically starts MeowlRelayService on device reboot for 24/7 background LDR connection.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            MeowlRelayService.startService(context)
        }
    }
}
