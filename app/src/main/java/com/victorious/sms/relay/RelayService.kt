package com.victorious.sms.relay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Foreground service that keeps the relay listener up while "Phone relay" is on. */
class RelayService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("relay_fg", "Phone relay", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(this, "relay_fg").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Phone relay is on").setContentText("Listening for paired phones on port ${RelayNode.DEFAULT_PORT}").build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        Thread { runCatching { RelayNode.get(this).startServer() } }.start()
        return START_STICKY
    }

    override fun onDestroy() { RelayNode.get(this).stopServer(); super.onDestroy() }
}
