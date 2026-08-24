package net.rpcsx

import android.util.Log
import androidx.annotation.Keep

@Keep
object ProgressRepository {
    @Keep
    @JvmStatic
    fun onProgressEvent(id: Long, value: Long, max: Long, message: String?): Boolean {
        if (value < 0) {
            Log.e("NativeProgressBridge", "native failure id=$id max=$max message=$message")
        }
        return com.zenithblue.sambas3.ProgressRepository.onProgressEvent(id, value, max, message)
    }
}
