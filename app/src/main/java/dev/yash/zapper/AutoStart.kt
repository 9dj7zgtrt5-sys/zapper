package dev.yash.zapper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import dev.yash.zapper.data.Repo
import dev.yash.zapper.ui.MainActivity

/** Opens Zapper when the Fire TV finishes booting. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Repo.init(context)
        if (!Repo.autoStart) return
        WakeService.start(context)
        AutoStart.openApp(context)
    }
}

object AutoStart {
    fun openApp(ctx: Context) {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try { ctx.startActivity(i) } catch (_: Exception) {}
    }
}

/**
 * Stays running in the background so Zapper can come back to the front when the TV
 * wakes from sleep (the Fire TV normally returns to its home screen).
 */
class WakeService : Service() {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!Repo.autoStart) return
            // Give the Fire TV launcher a moment to appear, then bring Zapper on top of it.
            Repo.main.postDelayed({ AutoStart.openApp(this@WakeService) }, 2500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        if (Build.VERSION.SDK_INT >= 26) startForeground(1, notification())
        registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Repo.autoStart) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("wake", "Auto-open", NotificationManager.IMPORTANCE_MIN))
            return Notification.Builder(this, "wake")
                .setContentTitle("Zapper")
                .setContentText("Opens TV when the screen turns on")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build()
        }
        @Suppress("DEPRECATION")
        return Notification.Builder(this).setContentTitle("Zapper").setSmallIcon(R.mipmap.ic_launcher).build()
    }

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, WakeService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Exception) {}
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WakeService::class.java))
        }
    }
}
