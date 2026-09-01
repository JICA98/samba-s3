package com.zenithblue.sambas3.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.util.Log

data class RoutedInputMapper(
    val device: ConnectedInputDevice,
    val mapper: GamepadMapper,
)

/** Caches one mapper per stable physical device identity for gameplay routing. */
class DeviceInputMapperRegistry(
    private val profileLoader: (ConnectedInputDevice) -> ControllerProfile = ControllerProfileRepository::loadForDevice,
) {
    private val entries = mutableMapOf<String, RoutedInputMapper>()

    fun mapperFor(event: KeyEvent): GamepadMapper? = resolve(event)?.mapper
    fun mapperFor(event: MotionEvent): GamepadMapper? = resolve(event)?.mapper

    fun resolve(event: KeyEvent): RoutedInputMapper? = resolve(event.device)
    fun resolve(event: MotionEvent): RoutedInputMapper? = resolve(event.device)

    fun invalidate(deviceKey: String) {
        entries.remove(deviceKey)
        Log.i("S3PADMAP", "mapper invalidated deviceKey=$deviceKey")
    }

    fun invalidateAll() {
        entries.clear()
        Log.i("S3PADMAP", "all gameplay mappers invalidated")
    }

    private fun resolve(device: InputDevice?): RoutedInputMapper? {
        val connected = device?.let(ControllerDeviceRepository::toConnected) ?: return null
        if (connected.type != InputDeviceType.GAMEPAD && connected.type != InputDeviceType.KEYBOARD) return null
        return entries.getOrPut(connected.deviceKey) {
            val profile = profileLoader(connected)
            Log.i("S3PADMAP", "gameplay mapper device=${connected.name} key=${connected.deviceKey} profile=${profile.id}")
            RoutedInputMapper(connected, GamepadMapper(profile))
        }
    }
}
