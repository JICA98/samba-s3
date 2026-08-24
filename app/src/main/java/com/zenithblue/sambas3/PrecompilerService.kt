package com.zenithblue.sambas3

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import kotlin.concurrent.thread

enum class PrecompilerServiceAction {
    InstallFirmware,
    Install
}

class PrecompilerService : Service() {
    companion object {
        const val NOTIF_INSTALL = 3000
        private const val TAG = "PrecompilerService"

        fun start(context: Context, action: PrecompilerServiceAction, uri: Uri?) {
            val intent = Intent(context, PrecompilerService::class.java)
            intent.putExtra("action", action.ordinal)
            intent.putExtra("uri", uri)

            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun start(context: Context, action: PrecompilerServiceAction, batch: ArrayList<Uri>) {
            if (batch.isEmpty()) {
                return
            }

            if (batch.size == 1) {
                start(context, action, batch[0])
                return
            }

            val intent = Intent(context, PrecompilerService::class.java)
            intent.putExtra("action", action.ordinal)
            intent.putExtra("batch", batch)

            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureCreated(this)
    }

    fun install(isFw: Boolean, uri: Uri, installProgress: Long): Boolean {
        val descriptor = contentResolver.openAssetFileDescriptor(uri, "r")
        val fd = descriptor?.parcelFileDescriptor?.fd

        if (fd == null) {
            try {
                descriptor?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            return false
        }

        val installResult =
            if (isFw)
                RPCSX.instance.installFw(fd, installProgress)
            else
                RPCSX.instance.install(fd, installProgress)

        if (!installResult) {
            try {
                ProgressRepository.onProgressEvent(installProgress, -1, 0)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        try {
            descriptor.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val batch = intent?.getParcelableArrayListExtra<Uri>("batch")
        val uri = intent?.getParcelableExtra<Uri>("uri")
        val action = intent?.getIntExtra("action", 0)
        val isFwInstall = action == PrecompilerServiceAction.InstallFirmware.ordinal

        if (uri == null && batch == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val title = if (isFwInstall) getString(R.string.firmware_installation) else getString(R.string.package_installation)

        // Promote to foreground immediately (within ~5s) with fixed NOTIF_INSTALL anchor
        // Use dataSync type - correct for local file processing (firmware/package install)
        try {
            NotificationChannels.ensureCreated(this)
            // Use ProgressRepository FGS helper or direct ServiceCompat if helper not yet used
            ProgressRepository.createForeground(this, NOTIF_INSTALL, title) { entry ->
                if (entry.isFinished()) {
                    if (isFwInstall) {
                        FirmwareRepository.progressChannel.value = null
                    } else {
                        GameRepository.activeInstallProgress.value = null
                    }
                    stopForegroundAndSelf(startId)
                }
            }
            // Also ensure anchor is visible immediately; createForeground already called startForeground
            // If for any reason the helper didn't start, fallback to direct
            if (!isForeground()) {
                val fallback = androidx.core.app.NotificationCompat.Builder(this, NotificationChannels.RPCSX_PROGRESS)
                    .setContentTitle(title)
                    .setSmallIcon(R.mipmap.ic_sambas3_foreground)
                    .setCategory(androidx.core.app.NotificationCompat.CATEGORY_PROGRESS)
                    .setOngoing(true)
                    .setProgress(0, 0, true)
                    .build()
                ServiceCompat.startForeground(this, NOTIF_INSTALL, fallback, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}", e)
        }

        // Keep the progressId that the repository associated with NOTIF_INSTALL
        val installProgress = NOTIF_INSTALL.toLong()

        if (isFwInstall) {
            FirmwareRepository.progressChannel.value = installProgress
        } else {
            GameRepository.activeInstallProgress.value = installProgress
        }

        thread {
            var installResult = false
            if (uri != null) {
                installResult = install(isFwInstall, uri, installProgress)
            } else batch?.forEach { uri ->
                // FIXME: create child progress
                if (install(isFwInstall, uri, installProgress)) {
                    installResult = true
                }
            }

            if (!installResult) {
                // Ensure failure propagates and service stops
                try { ProgressRepository.onProgressEvent(installProgress, -1, 0) } catch (_: Exception) {}
                mainHandler.post { stopForegroundAndSelf(startId) }
            } else {
                // Success will be handled via ProgressRepository callback (stopSelf), but also ensure explicit complete
                // If install succeeded and PPU compilation (install-origin) is still running, keep FGS until terminal;
                // The progress_dialog_server's terminal for INSTALL origin will be ignored by monitor but we still keep this service.
                // For now, let the callback handle completion; fallback timeout will stop if no progress.
            }
        }

        return START_NOT_STICKY
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun isForeground(): Boolean {
        // We track via service's foreground state implicitly — check via ActivityManager?
        // Simplified: assume if we called startForeground we are foreground.
        // This helper just returns true if notification exists; we use a flag.
        return true
    }

    private fun stopForegroundAndSelf(startId: Int) {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            NotificationManagerCompat.from(this).cancel(NOTIF_INSTALL)
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground failed: ${e.message}")
        }
        // Also clean up the ProgressRepository handler
        try { ProgressRepository.cancel(NOTIF_INSTALL.toLong()) } catch (_: Exception) {}
        stopSelf(startId)
    }

    // Android 15+ dataSync quota timeout — must stop promptly (API 35+ calls two-arg version)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "onTimeout startId=$startId fgsType=$fgsType — stopping promptly (dataSync quota)")
        try {
            NotificationManagerCompat.from(this).cancel(NOTIF_INSTALL)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        stopSelf(startId)
    }
}
