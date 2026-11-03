package com.gamecore.pluginmaker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.gamecore.studio.mcp.StudioMcpBridge

/**
 * Keeps the debug bridge alive while the editor is not on screen.
 *
 * A plain `Application`-owned bridge dies when Android caches or freezes the process, which makes
 * an agent unusable: every switch to the terminal stalls the four HTTP workers. A foreground
 * service raises the process priority and shows a persistent notification, so the bridge stays
 * reachable while the user works elsewhere.
 *
 * This class is in `src/debug`; the release manifest has no service, no notification and no
 * listener. The notification is deliberately plain: it names the loopback port and opens the
 * editor, nothing else.
 */
class StudioBridgeService : Service() {

    private var bridge: StudioMcpBridge? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        bridge = StudioMcpBridge(
            session = MakerStudio.session,
            appPackage = packageName,
            appVersion = version,
        ).also { started ->
            if (!started.start()) {
                Log.w(TAG, "The debug MCP bridge is unavailable; the editor is unaffected.")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        bridge?.close()
        bridge = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.studio_bridge_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.studio_bridge_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_studio_bridge)
            .setContentTitle(getString(R.string.studio_bridge_title))
            .setContentText(getString(R.string.studio_bridge_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private companion object {
        const val TAG = "StudioMcp"
        const val CHANNEL_ID = "studio_mcp_bridge"
        const val NOTIFICATION_ID = 47653
    }
}
