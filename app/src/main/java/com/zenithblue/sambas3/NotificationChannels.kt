package com.zenithblue.sambas3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object NotificationChannels {
    const val RPCSX_PROGRESS = "rpcsx-progress"
    const val RPCSX_GAME = "rpcsx-game"

    fun ensureCreated(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Channel id must match the one used in ProgressRepository / services
        // Use IMPORTANCE_LOW (minimum for FGS) but keep DEFAULT compatible — IMPORTANCE_LOW is quiet yet FGS-eligible.
        // Original code used IMPORTANCE_DEFAULT; we keep LOW to reduce noise while remaining FGS-compatible.
        // Both are >= LOW, so notification is FGS-eligible.
        if (nm.getNotificationChannel(RPCSX_PROGRESS) == null) {
            val channel = NotificationChannel(
                RPCSX_PROGRESS,
                ctx.getString(R.string.installation_progress),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(channel)
        }

        if (nm.getNotificationChannel(RPCSX_GAME) == null) {
            val gameChannel = NotificationChannel(
                RPCSX_GAME,
                ctx.getString(R.string.game_session_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(gameChannel)
        }
    }
}
