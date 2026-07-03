package net.rpcsx

import androidx.annotation.Keep

@Keep
object FirmwareRepository {
    @Keep
    @JvmStatic
    fun onFirmwareInstalled(version: String?) {
        com.zenithblue.sambas3.FirmwareRepository.onFirmwareInstalled(version)
    }
}
