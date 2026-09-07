package com.linuxdroid.core.input

import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.native_bridge.NativeBridge
import java.util.concurrent.atomic.AtomicBoolean

class DefaultInputManager : InputManager {

    private val log = LinuxDroidLogger(LogSubsystem.INPUT)
    private val active = AtomicBoolean(false)
    private var screenWidth: Int = 1920
    private var screenHeight: Int = 1080

    override suspend fun start() {
        active.set(true)
        log.info("InputManager started")
    }

    override suspend fun stop() {
        active.set(false)
        log.info("InputManager stopped")
    }

    override fun isActive(): Boolean = active.get()

    override fun sendTouchEvent(action: Int, pointerId: Int, x: Float, y: Float, pressure: Float) {
        if (!active.get()) return
        val clampedX = x.coerceIn(0f, screenWidth.toFloat())
        val clampedY = y.coerceIn(0f, screenHeight.toFloat())
        NativeBridge.sendTouchEvent(action, pointerId, clampedX, clampedY, pressure)
    }

    override fun sendMouseEvent(action: Int, buttonState: Int, x: Float, y: Float, scrollX: Float, scrollY: Float) {
        if (!active.get()) return
        val clampedX = x.coerceIn(0f, screenWidth.toFloat())
        val clampedY = y.coerceIn(0f, screenHeight.toFloat())
        NativeBridge.sendMouseEvent(action, buttonState, clampedX, clampedY, scrollX, scrollY)
    }

    override fun sendKeyEvent(keyCode: Int, isDown: Boolean, metaState: Int, unicodeChar: Int) {
        if (!active.get()) return
        NativeBridge.sendKeyEvent(keyCode, isDown, metaState, unicodeChar)
    }

    override fun resetInput() {
        if (!active.get()) return
        NativeBridge.resetInput()
    }

    override fun setScreenBounds(widthPx: Int, heightPx: Int) {
        this.screenWidth = widthPx
        this.screenHeight = heightPx
    }
}

