package com.zenithblue.sambas3

import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import androidx.annotation.Keep
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.zenithblue.sambas3.dialogs.AlertDialogQueue
import java.util.concurrent.ConcurrentHashMap

data class ProgressEntry(
    val value: MutableLongState = mutableLongStateOf(0),
    val max: MutableLongState = mutableLongStateOf(0),
    val message: MutableState<String?> = mutableStateOf(null)
) {
    fun isComplete() = value.longValue == max.longValue && !isIndeterminate()
    fun isFailed() = value.longValue < 0
    fun isFinished() = isFailed() || isComplete()
    fun isIndeterminate() = max.longValue == 0L
}

data class ProgressUpdateEntry(val value: Long, val max: Long, val message: String?) {
    fun isComplete() = value == max && !isIndeterminate()
    fun isFailed() = value < 0
    fun isFinished() = isFailed() || isComplete()
    fun isIndeterminate() = max == 0L
}

data class ForegroundCreateResult(val requestId: Long, val promoted: Boolean)

private data class ProgressWithHandler(
    var handler: (ProgressUpdateEntry) -> Unit,
    val progressEntry: MutableState<ProgressEntry>,
    val cancelOnComplete: Boolean = true
)

class ProgressRepository {
    private var progressHandlers = ConcurrentHashMap<Long, ProgressWithHandler>()
    private var nextRequestId = 1L

    companion object {
        private val instance = ProgressRepository()

        fun getItem(id: Long?) =
            if (id != null) instance.progressHandlers[id]?.progressEntry else null

        @Keep
        @JvmStatic
        fun onProgressEvent(id: Long, value: Long, max: Long, message: String? = null): Boolean {
            val item = instance.progressHandlers[id] ?: return false

            item.progressEntry.value.apply {
                this.value.longValue = value
                this.max.longValue = max
                this.message.value = message ?: this.message.value
            }

            item.handler(ProgressUpdateEntry(value, max, item.progressEntry.value.message.value))

            val failed = item.progressEntry.value.isFailed()
            if (failed || (item.progressEntry.value.isComplete() && item.cancelOnComplete)) {
                if (failed) {
                    Log.e("ProgressRepository", "progress failed id=$id message=${item.progressEntry.value.message.value}")
                }
                cancel(id)
            }

            return true
        }

        fun cancel(id: Long) {
            instance.progressHandlers.remove(id)
            GameRepository.clearProgress(id)
        }

        /**
         * FGS-capable helper — builds the same rpcsx-progress notification but promotes via
         * ServiceCompat.startForeground. Uses a fixed notificationId (anchor 2000 or 3000). The caller
         * is responsible for stopForeground/stopSelf when work completes. Unlike [create], this does
         * NOT gate startForeground on POST_NOTIFICATIONS — FGS can start without the permission (notification
         * will be hidden in shade but visible in Task Manager). Updates via [onProgressEvent] with the same id
         * will post through NotificationManagerCompat while the service stays foreground.
         */
        fun createForeground(
            service: Service,
            notificationId: Int,
            title: String,
            silent: Boolean = false,
            handler: (ProgressUpdateEntry) -> Unit = { _ -> }
        ): ForegroundCreateResult {
            val requestId = notificationId.toLong()
            // A foreground install reports several independent determinate phases
            // (extracting, verifying, committing) on one request id. Keep the
            // handler alive when a phase reaches 100%; the owning service decides
            // when the complete job is actually finished.
            val entry = ProgressWithHandler(
                handler,
                mutableStateOf(ProgressEntry()),
                cancelOnComplete = false
            )
            instance.progressHandlers[requestId] = entry

            // Ensure channel exists before startForeground (cold-start safety)
            try { NotificationChannels.ensureCreated(service) } catch (_: Exception) {}

            val builder = NotificationCompat.Builder(service, NotificationChannels.RPCSX_PROGRESS).apply {
                setContentTitle(title)
                setSmallIcon(R.mipmap.ic_sambas3_foreground)
                setCategory(NotificationCompat.CATEGORY_PROGRESS)
                setPriority(NotificationCompat.PRIORITY_DEFAULT)
                setOngoing(true)
                setSilent(true)
                setProgress(0, 0, true)
            }

            // For FGS anchor, always call startForeground even without POST_NOTIFICATIONS permission.
            // Permission only affects whether the notification is visible in the shade; FGS itself is legal.
            val fgsType = if (service is CompilationMonitorService)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

            val promoted = try {
                ServiceCompat.startForeground(service, notificationId, builder.build(), fgsType)
                true
            } catch (e: Exception) {
                Log.e("ProgressRepository", "startForeground failed for id=$notificationId: ${e.message}", e)
                false
            }
            if (!promoted) {
                // Keep the progress handler so install UI still updates if the caller
                // falls back and continues the job. Removing it made ISO import silent.
                Log.e("ProgressRepository", "FGS not promoted for id=$notificationId — handler kept")
            }

            val asyncHandler = Handler.createAsync(Looper.getMainLooper()) { message ->
                val value = message.data.getLong("value")
                val max = message.data.getLong("max")
                val text = message.data.getString("message")

                // For FGS anchor, never auto-cancel on value==max alone — lifecycle is explicit (COMPLETED/FAILED/CANCELED).
                // Only terminal failure (value<0) is handled here; success is left to the owning service to stopForeground.
                val notificationManager = NotificationManagerCompat.from(service)
                if (value < 0) {
                    val contentText = text ?: service.getString(R.string.unexpected_error)
                    builder.setContentText(contentText)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setProgress(0, 0, false)
                        .setOngoing(false)
                    try { notificationManager.notify(notificationId, builder.build()) } catch (_: Exception) {}
                    AlertDialogQueue.showDialog(title, contentText)
                } else {
                    // For PrecompilerService install PPU, switch title to PPU when message indicates file/module progress
                    if (notificationId == PrecompilerService.NOTIF_INSTALL && text != null && (text.contains("file", true) || text.contains("module", true) || text.contains("PPU", true))) {
                        try { builder.setContentTitle(service.getString(R.string.compiling_ppu_title)) } catch (_: Exception) {}
                        builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    } else if (notificationId == PrecompilerService.NOTIF_INSTALL) {
                        try { builder.setContentTitle(title) } catch (_: Exception) {}
                    }
                    if (text != null) builder.setContentText(text)
                    if (max > 0) {
                        builder.setProgress(max.toInt(), value.toInt(), false)
                    } else {
                        // indeterminate
                        builder.setProgress(0, 0, true)
                    }
                    try { notificationManager.notify(notificationId, builder.build()) } catch (_: Exception) {}
                }

                handler(ProgressUpdateEntry(value, max, text))
                true
            }

            entry.handler = { progress: ProgressUpdateEntry ->
                val message = Message()
                val data = Bundle()
                data.putLong("value", progress.value)
                data.putLong("max", progress.max)
                data.putString("message", progress.message)
                message.data = data
                asyncHandler.sendMessage(message)
            }

            return ForegroundCreateResult(requestId, promoted)
        }

        /**
         * Helper to post a one-shot notification update for secondary ongoing notifications (2001/2002)
         * without owning a foreground. Caller must have already ensured channel exists.
         */
        fun notifySecondary(
            context: Context,
            notificationId: Int,
            title: String,
            message: String?,
            value: Long,
            max: Long
        ) {
            val builder = NotificationCompat.Builder(context, NotificationChannels.RPCSX_PROGRESS).apply {
                setContentTitle(title)
                setSmallIcon(R.mipmap.ic_sambas3_foreground)
                setCategory(NotificationCompat.CATEGORY_PROGRESS)
                setPriority(NotificationCompat.PRIORITY_DEFAULT)
                setOngoing(true)
                setSilent(true)
                if (message != null) setContentText(message)
                if (max > 0) setProgress(max.toInt(), value.toInt(), false) else setProgress(0, 0, true)
            }
            try {
                NotificationManagerCompat.from(context).notify(notificationId, builder.build())
            } catch (_: Exception) {}
        }

        fun updateForeground(service: Service, notificationId: Int, title: String, message: String?, value: Long, max: Long) {
            // Convenience: delegate to onProgressEvent if handler exists, otherwise direct notifySecondary
            val id = notificationId.toLong()
            if (instance.progressHandlers.containsKey(id)) {
                onProgressEvent(id, value, max, message)
            } else {
                notifySecondary(service, notificationId, title, message, value, max)
            }
        }

        fun create(
            context: Context,
            title: String,
            silent: Boolean = false,
            handler: (ProgressUpdateEntry) -> Unit = { _ -> }
        ): Long {
            var requestId: Long
            val entry = ProgressWithHandler(handler, mutableStateOf(ProgressEntry()))
            while (true) {
                requestId = instance.nextRequestId++
                if (instance.progressHandlers.put(requestId, entry) == null) {
                    break
                }
            }

            val hasPermission = !silent && Permission.PostNotifications.checkPermission(context)

            val builder = NotificationCompat.Builder(context, "rpcsx-progress").apply {
                setContentTitle(title)
                setSmallIcon(R.mipmap.ic_sambas3_foreground)
                setCategory(NotificationCompat.CATEGORY_SERVICE)
                setPriority(NotificationCompat.PRIORITY_DEFAULT)
                setProgress(0, 0, true)
                setOngoing(true)
                setSilent(true)
            }

            if (hasPermission) {
                with(NotificationManagerCompat.from(context)) {
                    notify(requestId.toInt(), builder.build())
                }
            }

            val asyncHandler = Handler.createAsync(Looper.getMainLooper()) { message ->
                val value = message.data.getLong("value")
                val max = message.data.getLong("max")
                val text = message.data.getString("message")

                if (value < 0) {
                    val contentText = text ?: context.getString(R.string.unexpected_error)
                    if (hasPermission) {
                        val notificationManager = NotificationManagerCompat.from(context)
                        builder.setContentText(contentText)
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setProgress(0, 0, false)
                            .setOngoing(false)
                        notificationManager.notify(requestId.toInt(), builder.build())
                    }
                    AlertDialogQueue.showDialog(title, contentText)
                } else if (hasPermission) {
                    val notificationManager = NotificationManagerCompat.from(context)
                    if (text != null) builder.setContentText(text)

                    if (max > 0) {
                        if (value == max) {
                            notificationManager.cancel(requestId.toInt())
                        } else {
                            builder.setProgress(max.toInt(), value.toInt(), false)
                            notificationManager.notify(requestId.toInt(), builder.build())
                        }
                    } else {
                        builder.setProgress(max.toInt(), value.toInt(), true)
                        notificationManager.notify(requestId.toInt(), builder.build())
                    }
                }

                handler(ProgressUpdateEntry(value, max, text))
                true
            }

            entry.handler = { progress: ProgressUpdateEntry ->
                val message = Message()
                val data = Bundle()
                data.putLong("value", progress.value)
                data.putLong("max", progress.max)
                data.putString("message", progress.message)
                message.data = data
                asyncHandler.sendMessage(message)
            }

            return requestId
        }
    }
}
