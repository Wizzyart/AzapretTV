package app.azapret.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import app.azapret.tv.config.AppSettings
import app.azapret.tv.diagnostics.AppLog
import app.azapret.tv.vpn.AzapretVpnService

class AutostartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AppSettings.isAutoStartEnabled(context)) return

        AppLog.append(context, "BOOT autostart requested")
        if (VpnService.prepare(context) != null) {
            AppSettings.setLastStatus(context, "Автозапуск ожидает разрешение VPN. Откройте Azapret TV один раз.")
            AppLog.append(context, "BOOT autostart blocked: VPN permission missing")
            return
        }

        AppSettings.setVpnEnabled(context, true)
        AzapretVpnService.start(context)
    }
}
