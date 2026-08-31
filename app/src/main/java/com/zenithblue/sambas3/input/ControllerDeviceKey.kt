package com.zenithblue.sambas3.input

import android.util.Log

/**
 * Stable identity for per-device profiles. Prefer descriptor, then VID+PID+name,
 * then transport+sources+normalized name; transient Android deviceId is last resort.
 */
object ControllerDeviceKey {
    private const val TAG = "S3PAD"

    fun stableKey(
        descriptor: String?,
        vendorId: Int,
        productId: Int,
        name: String,
        transport: String? = null,
        sources: Int = 0,
        deviceId: Int = -1,
    ): String {
        val desc = descriptor?.trim().orEmpty()
        if (desc.isNotEmpty()) {
            Log.d(TAG, "deviceKey via descriptor")
            return "desc:$desc"
        }
        val normalized = normalizeName(name)
        if (vendorId != 0 || productId != 0) {
            Log.d(TAG, "deviceKey via vid/pid/name $vendorId:$productId")
            return "vidpid:$vendorId:$productId:$normalized"
        }
        val transportPart = transport?.trim().orEmpty()
        if (transportPart.isNotEmpty() || sources != 0) {
            Log.d(TAG, "deviceKey via transport/sources")
            return "meta:$transportPart:$sources:$normalized"
        }
        Log.d(TAG, "deviceKey fallback transient id=$deviceId")
        return "id:$deviceId:$normalized"
    }

    fun normalizeName(name: String): String =
        name.trim().lowercase().replace(Regex("\\s+"), " ")
}
