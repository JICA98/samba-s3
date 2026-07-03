package net.rpcsx

import androidx.annotation.Keep

@Keep
object ProgressRepository {
    @Keep
    @JvmStatic
    fun onProgressEvent(id: Long, value: Long, max: Long, message: String?): Boolean {
        return com.zenithblue.sambas3.ProgressRepository.onProgressEvent(id, value, max, message)
    }
}
