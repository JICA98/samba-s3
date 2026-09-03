package com.zenithblue.sambas3.ppu

import android.content.Context
import android.util.Log
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.UserRepository

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
            Log.i(TAG, "Worker native bootstrap succeeded. root=${RPCSX.rootDirectory} user=$user")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Worker native bootstrap threw: ${e.message}", e)
            false
        }
    }
}
