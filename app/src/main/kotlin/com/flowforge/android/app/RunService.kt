package com.flowforge.android.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.flowforge.android.MainActivity
import com.flowforge.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and visible while a run is active (Android foreground service, type dataSync).
 * Android can still end it: Force Stop, or the system time limit for dataSync services on Android 15.
 */
class RunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = (application as FlowForgeApp).container.runManager
        if (intent?.action == ACTION_STOP) manager.stop()
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, build(manager.state.value, ongoing = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (job?.isActive != true) {
            job = scope.launch {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                while (true) {
                    val s = manager.state.value
                    if (!s.active) {
                        ServiceCompat.stopForeground(this@RunService, ServiceCompat.STOP_FOREGROUND_DETACH)
                        nm.notify(NOTIFICATION_ID, build(s, ongoing = false))
                        stopSelf()
                        break
                    }
                    nm.notify(NOTIFICATION_ID, build(s, ongoing = true))
                    delay(1000)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun build(s: RunState, ongoing: Boolean) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle(s.workflowName.ifBlank { getString(R.string.app_name) })
        .setContentText(
            if (ongoing) {
                getString(
                    if (s.paused) R.string.notif_paused else R.string.notif_running,
                    s.processed, s.total, s.success, s.failed,
                )
            } else {
                getString(R.string.notif_finished, s.finalStatus ?: "-", s.success, s.failed)
            },
        )
        .setOngoing(ongoing)
        .setOnlyAlertOnce(true)
        .setProgress(s.total, s.processed.coerceAtMost(s.total), ongoing && s.total == 0)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .apply {
            if (ongoing) {
                addAction(
                    0, getString(R.string.rn_stop),
                    PendingIntent.getService(
                        this@RunService, 1,
                        Intent(this@RunService, RunService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
        }
        .build()

    companion object {
        const val ACTION_STOP = "com.flowforge.android.STOP_RUN"
        private const val CHANNEL_ID = "runs"
        private const val NOTIFICATION_ID = 1001
    }
}
