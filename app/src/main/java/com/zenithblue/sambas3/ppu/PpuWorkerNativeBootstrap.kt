package com.zenithblue.sambas3.ppu

import android.content.Context
import android.util.Log
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.UserRepository
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object PpuWorkerNativeBootstrap {
    private const val TAG = "PpuWorkerBootstrap"

    @Synchronized
    fun ensureInitialized(context: Context): Boolean {
        if (RPCSX.initialized) return true
        val appContext = context.applicationContext
        return try {
            var root = appContext.getExternalFilesDir(null)?.toString() ?: ""
            if (root.isNotEmpty() && !root.endsWith("/")) {
                root += "/"
            }
            RPCSX.rootDirectory = root
            val nativeLibDir = appContext.packageManager.getApplicationInfo(appContext.packageName, 0).nativeLibraryDir
            RPCSX.nativeLibDirectory = nativeLibDir

            runCatching { com.zenithblue.sambas3.logging.LogBroker.ensureStarted(appContext) }
            if (!RPCSX.openLibrary()) {
                Log.e(TAG, "Failed to open librpcsx-android.so in worker process")
                return false
            }

            val user = try {
                UserRepository.getUserFromSettings()
            } catch (_: Exception) {
                "00000001"
            }

            if (!RPCSX.instance.initialize(RPCSX.rootDirectory, user)) {
                Log.e(TAG, "RPCSX.initialize failed in worker process")
                return false
            }

            RPCSX.initialized = true
            val pumpEntered = CountDownLatch(1)
            kotlin.concurrent.thread(name = "rpcsx-mtp-worker", isDaemon = true) {
                try {
                    pumpEntered.countDown()
                    RPCSX.instance.startMainThreadProcessor()
                } catch (e: Exception) {
                    Log.e(TAG, "Worker main-thread processor failed: ${e.message}", e)
                    pumpEntered.countDown()
                }
            }
            if (!pumpEntered.await(PpuWorkerControlPolicy.STARTUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "Main-thread processor thread did not start")
                return false
            }
            val readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var pumpReady = false
            while (System.nanoTime() < readyDeadline) {
                pumpReady = runCatching { RPCSX.instance.isMainThreadProcessorReady() }.getOrDefault(false)
                if (pumpReady) break
                Thread.sleep(20)
            }
            PpuDiagnosticLog.emit(
                "bootstrap_ready",
                extras = mapOf(
                    "pumpReady" to pumpReady,
                    "coreId" to runCatching { RPCSX.instance.getCoreBuildId() }.getOrNull(),
                    "user" to user,
                ),
            )
            if (!pumpReady) {
                Log.e(TAG, "Worker native bootstrap failed: main-thread processor not ready")
                return false
            }
            Log.i(TAG, "Worker native bootstrap succeeded. pumpReady=$pumpReady root=${RPCSX.rootDirectory} user=$user")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Worker native bootstrap threw: ${e.message}", e)
            false
        }
    }
}
