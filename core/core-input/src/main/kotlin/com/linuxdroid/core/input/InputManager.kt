package com.linuxdroid.core.input

/**
 * InputManager routes Android touch/keyboard/mouse input to the Linux Wayland session.
 *
 * Architecture: Android input → Native bridge → Wayland/Linux input → Linux apps
 */
interface InputManager {
    suspend fun start()
    suspend fun stop()
    fun isActive(): Boolean

    fun sendTouchEvent(action: Int, pointerId: Int, x: Float, y: Float, pressure: Float = 1.0f)
    fun sendMouseEvent(action: Int, buttonState: Int, x: Float, y: Float, scrollX: Float = 0f, scrollY: Float = 0f)
    fun sendKeyEvent(keyCode: Int, isDown: Boolean, metaState: Int = 0, unicodeChar: Int = 0)
    fun resetInput()
    fun setScreenBounds(widthPx: Int, heightPx: Int)
}
