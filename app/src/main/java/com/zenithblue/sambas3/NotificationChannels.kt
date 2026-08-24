package com.zenithblue.sambas3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object NotificationChannels {
    const val RPCSX_PROGRESS = "rpcsx-progress"

    fun ensureCreated(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Channel id must match the one used in ProgressRepository / services
        // Use IMPORTANCE_LOW (minimum for FGS) but keep DEFAULT compatible — IMPORTANCE_LOW is quiet yet FGS-eligible.
        // Original code used IMPORTANCE_DEFAULT; we keep LOW to reduce noise while remaining FGS-compatible.
        // Both are >= LOW, so notification is FGS-eligible.
        val existing = nm.getNotificationChannel(RPCSX_PROGRESS)
        if (existing != null) return
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
}
