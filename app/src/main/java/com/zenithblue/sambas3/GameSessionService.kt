package com.zenithblue.sambas3

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import android.content.pm.ServiceInfo
import java.io.File

/** Keeps a user-started emulation process visible while its activity is backgrounded. */
class GameSessionService : Service() {

    companion object {
        const val NOTIF_GAME = 4000
        private const val ACTION_START = "com.zenithblue.sambas3.action.START_GAME_SESSION"
        private const val ACTION_STOP = "com.zenithblue.sambas3.action.STOP_GAME_SESSION"
        private const val EXTRA_GAME_PATH = "gamePath"
        private const val EXTRA_GAME_TITLE = "gameTitle"
        private const val EXTRA_GAME_ICON = "gameIcon"
        private const val TAG = "GameSessionService"

        fun start(context: Context, gamePath: String, gameTitle: String?, gameIconPath: String?) {
            val intent = Intent(context.applicationContext, GameSessionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_GAME_PATH, gamePath)
                putExtra(EXTRA_GAME_TITLE, gameTitle)
                putExtra(EXTRA_GAME_ICON, gameIconPath)
            }
            try {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService failed: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context.applicationContext, GameSessionService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.applicationContext.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "stop request failed: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureCreated(this)
        Log.i(TAG, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        val gamePath = intent?.getStringExtra(EXTRA_GAME_PATH).orEmpty()
        if (gamePath.isBlank()) {
            Log.w(TAG, "missing game path; stopping startId=$startId")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_GAME_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: gamePath.trimEnd('/').substringAfterLast('/').ifBlank { getString(R.string.app_name) }
        val iconPath = intent?.getStringExtra(EXTRA_GAME_ICON)
        val notification = buildNotification(gamePath, title, iconPath)
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIF_GAME,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            Log.i(TAG, "startForeground game=$title path=$gamePath")
            START_NOT_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}", e)
            stopSelf(startId)
            START_NOT_STICKY
        }
    }

    private fun buildNotification(gamePath: String, title: String, iconPath: String?): android.app.Notification {
        val launchIntent = Intent(this, RPCSXActivity::class.java).apply {
            putExtra("path", gamePath)
            putExtra(RPCSXActivity.EXTRA_ORIGINAL_GAME_PATH, gamePath)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            NOTIF_GAME,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, NotificationChannels.RPCSX_GAME)
            .setContentTitle(title)
            .setContentText(getString(R.string.game_session_running))
            .setSubText(getString(R.string.app_name))
            .setSmallIcon(R.mipmap.ic_sambas3_foreground)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)

        loadGameIcon(iconPath)?.let { builder.setLargeIcon(it) }
        return builder.build()
    }

    private fun loadGameIcon(iconPath: String?): Bitmap? {
        if (iconPath.isNullOrBlank()) return null
        val decoded = runCatching {
            when {
                iconPath.startsWith("content://") ->
                    contentResolver.openInputStream(Uri.parse(iconPath))?.use(BitmapFactory::decodeStream)
                iconPath.startsWith("file://") ->
                    contentResolver.openInputStream(Uri.parse(iconPath))?.use(BitmapFactory::decodeStream)
                else -> BitmapFactory.decodeFile(File(iconPath).absolutePath)
            }
        }.onFailure { Log.w(TAG, "game icon load failed path=$iconPath: ${it.message}") }.getOrNull()
            ?: return null

        val maxDimension = 256
        if (decoded.width <= maxDimension && decoded.height <= maxDimension) return decoded
        val scale = minOf(maxDimension.toFloat() / decoded.width, maxDimension.toFloat() / decoded.height)
        val resized = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (resized !== decoded) decoded.recycle()
        return resized
    }

    private fun stopForegroundAndSelf() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            .onFailure { Log.w(TAG, "stopForeground failed: ${it.message}") }
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIF_GAME) }
        stopSelf()
        Log.i(TAG, "stopped")
    }

    override fun onDestroy() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIF_GAME) }
        Log.i(TAG, "onDestroy")
        super.onDestroy()
    }
}
