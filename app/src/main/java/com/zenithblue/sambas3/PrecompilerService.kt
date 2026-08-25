package com.zenithblue.sambas3

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import com.zenithblue.sambas3.utils.Telemetry
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
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, PrecompilerService::class.java)
            intent.putExtra(PrecompilerServiceLogic.EXTRA_ACTION, action.ordinal)
            if (uri != null) {
                intent.putExtra(PrecompilerServiceLogic.EXTRA_URI, uri)
                intent.data = uri
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    appCtx.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "takePersistableUriPermission skipped: ${e.message}")
                }
            }

            try {
                ContextCompat.startForegroundService(appCtx, intent)
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService failed: ${e.message}", e)
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

            val appCtx = context.applicationContext
            val intent = Intent(appCtx, PrecompilerService::class.java)
            intent.putExtra(PrecompilerServiceLogic.EXTRA_ACTION, action.ordinal)
            intent.putParcelableArrayListExtra(PrecompilerServiceLogic.EXTRA_BATCH, batch)
            batch.firstOrNull()?.let { first ->
                intent.data = first
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            try {
                ContextCompat.startForegroundService(appCtx, intent)
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService failed: ${e.message}", e)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collectJob: Job? = null
    private var isForeground = false
    private var installPpuSeen = false
    private var currentInstallIsFirmware = false
    @Volatile private var jobStartId: Int? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureCreated(this)
        collectJob = CompileProgressBridge.installState
            .onEach { st -> onInstallPpuState(st) }
            .launchIn(serviceScope)
    }

    private class OpenedFd(
        val fd: Int,
        private val closeables: List<java.io.Closeable>,
        val debugLabel: String
    ) : java.io.Closeable {
        override fun close() {
            closeables.asReversed().forEach {
                try {
                    it.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun openSeekableFd(uri: Uri): OpenedFd? {
        try {
            val pfd = contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                Log.i(TAG, "openFileDescriptor ok uri=$uri statSize=${pfd.statSize}")
                return OpenedFd(pfd.fd, listOf(pfd), "pfd statSize=${pfd.statSize}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "openFileDescriptor failed uri=$uri: ${e.message}")
        }
        try {
            val afd = contentResolver.openAssetFileDescriptor(uri, "r")
            val pfd = afd?.parcelFileDescriptor
            if (pfd != null) {
                Log.i(
                    TAG,
                    "openAssetFileDescriptor ok uri=$uri length=${afd.declaredLength} statSize=${pfd.statSize}"
                )
                return OpenedFd(
                    pfd.fd,
                    listOf(pfd, afd),
                    "afd length=${afd.declaredLength} statSize=${pfd.statSize}"
                )
            }
            afd?.close()
        } catch (e: Exception) {
            Log.w(TAG, "openAssetFileDescriptor failed uri=$uri: ${e.message}")
        }
        return null
    }

    fun install(isFw: Boolean, uri: Uri, installProgress: Long): Boolean {
        val opened = openSeekableFd(uri)
        if (opened == null) {
            Log.e(TAG, "Failed to open seekable fd for $uri")
            try {
                ProgressRepository.onProgressEvent(
                    installProgress,
                    -1,
                    0,
                    "Could not open selected file"
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return false
        }

        Log.i(TAG, "install isFw=$isFw ${opened.debugLabel} uri=$uri")
        val installResult = try {
            if (isFw) {
                RPCSX.instance.installFw(opened.fd, installProgress)
            } else {
                RPCSX.instance.install(opened.fd, installProgress)
            }
        } catch (e: Exception) {
            Log.e(TAG, "native install threw: ${e.message}", e)
            try {
                ProgressRepository.onProgressEvent(
                    installProgress,
                    -1,
                    0,
                    e.message ?: "Install failed"
                )
            } catch (_: Exception) {
            }
            false
        } finally {
            try {
                opened.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return installResult
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val batch = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableArrayListExtra(PrecompilerServiceLogic.EXTRA_BATCH, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableArrayListExtra<Uri>(PrecompilerServiceLogic.EXTRA_BATCH)
        }
        val extraUri = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(PrecompilerServiceLogic.EXTRA_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Uri>(PrecompilerServiceLogic.EXTRA_URI)
        }
        val extraString = if (extraUri == null) {
            intent?.getStringExtra(PrecompilerServiceLogic.EXTRA_URI)
        } else {
            null
        }
        val uri = PrecompilerServiceLogic.extractUri(
            intent?.data,
            extraUri,
            extraString
        )
        val action = intent?.getIntExtra(PrecompilerServiceLogic.EXTRA_ACTION, 0)
        val isFwInstall = action == PrecompilerServiceAction.InstallFirmware.ordinal
        val title = if (isFwInstall) {
            getString(R.string.firmware_installation)
        } else {
            getString(R.string.package_installation)
        }

        Log.i(
            TAG,
            "onStartCommand startId=$startId jobStartId=$jobStartId uri=$uri batch=${batch?.size ?: 0} action=$action"
        )

        // Install-origin PPU compilation owns the shared compiler lifecycle.
        // Do not leave the runtime monitor (2000) beside the install card
        // notification (3000) for the same compilation session.
        stopService(Intent(this, CompilationMonitorService::class.java))
        NotificationManagerCompat.from(this).cancel(CompilationMonitorService.NOTIF_FGS)

        // Always promote first — startForegroundService requires startForeground within ~5s,
        // including empty follow-up intents the platform may deliver.
        if (!isForeground) {
            promoteForeground(title)
        }

        if (uri == null && batch == null) {
            if (PrecompilerServiceLogic.shouldStopEmptyStart(jobStartId != null)) {
                Log.w(TAG, "empty start with no running job — stopping startId=$startId")
                stopForegroundAndSelf(startId)
            } else {
                Log.w(TAG, "empty start ignored; install already running jobStartId=$jobStartId")
            }
            return START_NOT_STICKY
        }

        if (jobStartId != null) {
            Log.w(TAG, "Install already running (jobStartId=$jobStartId), ignoring startId=$startId")
            return START_NOT_STICKY
        }
        jobStartId = startId
        installPpuSeen = false
        currentInstallIsFirmware = isFwInstall

        val created = try {
            ProgressRepository.createForeground(this, NOTIF_INSTALL, title) { entry ->
                // Game installs reuse this request for staged extraction,
                // verification, commit, and install-origin PPU compilation.
                // A game install returns before its queued PPU work runs. The
                // native queue sends a distinct terminal message only after
                // that work and teardown are complete, so the notification
                // cannot disappear while compilation is still active.
                val ppuComplete = !isFwInstall && entry.isComplete() &&
                    entry.message == "PPU compilation complete"
                if (entry.isFailed() || (isFwInstall && entry.isComplete()) || ppuComplete) {
                    if (isFwInstall) {
                        FirmwareRepository.progressChannel.value = null
                    } else {
                        GameRepository.activeInstallProgress.value = null
                    }
                    stopForegroundAndSelf(startId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "createForeground threw: ${e.message}", e)
            ForegroundCreateResult(NOTIF_INSTALL.toLong(), false)
        }
        isForeground = isForeground || created.promoted
        if (!created.promoted) {
            Log.e(TAG, "FGS helper promotion failed — falling back, install still proceeds")
            promoteForeground(title)
        }

        val installProgress = NOTIF_INSTALL.toLong()

        if (isFwInstall) {
            FirmwareRepository.progressChannel.value = installProgress
        } else {
            GameRepository.activeInstallProgress.value = installProgress
            GameRepository.createGameInstallEntry(installProgress)
        }

        thread(name = "sambas3-install") {
            var installResult = false
            try {
                if (uri != null) {
                    installResult = install(isFwInstall, uri, installProgress)
                } else batch?.forEach { item ->
                    if (install(isFwInstall, item, installProgress)) {
                        installResult = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "install thread crashed: ${e.message}", e)
                installResult = false
            }

            if (!installResult) {
                Log.e(TAG, "install returned false isFw=$isFwInstall")
                try {
                    val handled = ProgressRepository.onProgressEvent(installProgress, -1, 0)
                    Log.e(TAG, "fallback failure event handled=$handled id=$installProgress")
                } catch (_: Exception) {
                }
                mainHandler.post { stopForegroundAndSelf(startId) }
            }
        }

        return START_NOT_STICKY
    }

    private fun promoteForeground(title: String) {
        NotificationChannels.ensureCreated(this)
        val notification = NotificationCompat.Builder(this, NotificationChannels.RPCSX_PROGRESS)
            .setContentTitle(title)
            .setSmallIcon(R.mipmap.ic_sambas3_foreground)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .build()
        val types = intArrayOf(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        )
        for (type in types) {
            try {
                ServiceCompat.startForeground(this, NOTIF_INSTALL, notification, type)
                isForeground = true
                Log.i(TAG, "startForeground type=$type")
                return
            } catch (e: Exception) {
                Log.e(TAG, "startForeground type=$type failed: ${e.message}", e)
            }
        }
    }

    private fun onInstallPpuState(st: CompileProgressBridge.CompileState) {
        if (!st.ppuActive) {
            if (installPpuSeen) {
                installPpuSeen = false
                if (currentInstallIsFirmware) {
                    FirmwareRepository.progressChannel.value = null
                } else {
                    GameRepository.activeInstallProgress.value = null
                }
                jobStartId?.let { stopForegroundAndSelf(it) }
            }
            return
        }
        // PPU active - ensure FGS is present
        if (!isForeground) {
            promoteForeground(getString(R.string.compiling_ppu_title))
        }
        installPpuSeen = true
        val title = getString(R.string.compiling_ppu_title)
        val msg = st.ppuMsg ?: title
        ProgressRepository.updateForeground(
            this,
            NOTIF_INSTALL,
            title,
            msg,
            st.ppuPercent.toLong(),
            st.ppuMax.toLong()
        )
        if (Telemetry.isEnabled) {
            Telemetry.logS3Ppu("event=install_ppu_active progress_id=${NOTIF_INSTALL} msg=${msg.take(40)} percent=${st.ppuPercent} max=${st.ppuMax} session=${Telemetry.sessionId}")
        }
    }

    private fun stopForegroundAndSelf(startId: Int) {
        if (currentInstallIsFirmware) {
            FirmwareRepository.progressChannel.value = null
        } else if (jobStartId != null) {
            GameRepository.activeInstallProgress.value = null
        }
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            NotificationManagerCompat.from(this).cancel(NOTIF_INSTALL)
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground failed: ${e.message}")
        }
        try {
            ProgressRepository.cancel(NOTIF_INSTALL.toLong())
        } catch (_: Exception) {
        }
        isForeground = false
        jobStartId = null
        currentInstallIsFirmware = false
        stopSelf(startId)
    }

    override fun onDestroy() {
        collectJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "onTimeout startId=$startId fgsType=$fgsType — stopping promptly (dataSync quota)")
        try {
            NotificationManagerCompat.from(this).cancel(NOTIF_INSTALL)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        jobStartId = null
        isForeground = false
        stopSelf(startId)
    }
}
