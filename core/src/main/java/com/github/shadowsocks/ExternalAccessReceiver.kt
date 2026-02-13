package com.github.shadowsocks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.shadowsocks.utils.Action

class ExternalAccessReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Action.EXTERNAL_ALLOW_ONCE -> Core.allowExternalAccessOnce()
            Action.EXTERNAL_ALLOW_ALWAYS -> Core.allowExternalAccessAlways()
        }
        Core.notification.cancel(Core.externalAccessNotificationId)
    }
}
