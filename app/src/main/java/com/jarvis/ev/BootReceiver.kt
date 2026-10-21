package com.jarvis.ev

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Telefon açılınca Jarvis'i tek dokunuşla başlatmak için bildirim bırakır. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        if (i?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!c.getSharedPreferences("jarvis", 0).getBoolean("autostart", false)) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("jarvis_boot", "Jarvis başlat", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(c, 2, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(2, Notification.Builder(c, "jarvis_boot")
            .setContentTitle("Jarvis hazır")
            .setContentText("Dinlemeyi başlatmak için dokunun")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open).setAutoCancel(true).build())
    }
}
