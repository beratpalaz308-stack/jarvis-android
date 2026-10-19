package com.jarvis.ev

import android.service.notification.NotificationListenerService

/** Bildirimleri okuyabilmek için. */
class JarvisNotif : NotificationListenerService() {
    companion object {
        @Volatile var inst: JarvisNotif? = null
    }
    override fun onListenerConnected() { inst = this }
    override fun onListenerDisconnected() { inst = null }
}
