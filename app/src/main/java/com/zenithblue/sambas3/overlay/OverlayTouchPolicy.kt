package com.zenithblue.sambas3.overlay

object OverlayTouchPolicy {
    const val MENU_DIM_ALPHA = 0.35f

    fun shouldHandleFloatingSticks(isMenuMode: Boolean): Boolean = !isMenuMode
    fun shouldSpawnFloatingStick(isMenuMode: Boolean): Boolean = !isMenuMode

    // Menu-mode modal gate: PadOverlay's touch listener consumes EVERYTHING while
    // a Compose page is open (early-return at PadOverlay.kt setupTouchListener),
    // belt-and-braces beneath the Compose scrim in case a pointer ever misses it.
    fun shouldAcceptOverlayTouch(isMenuMode: Boolean): Boolean = !isMenuMode
}
