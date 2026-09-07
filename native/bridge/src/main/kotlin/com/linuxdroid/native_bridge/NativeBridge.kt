package com.linuxdroid.native_bridge

import android.view.Surface

/**
 * Kotlin entry point for the LinuxDroid native bridge.
 *
 * This is the centralized JNI gate for LinuxDroid.
 * No other Kotlin class may call System.loadLibrary or declare
 * native methods directly.
 */
object NativeBridge {

    private val isLoaded: Boolean = try {
        System.loadLibrary("linuxdroid_bridge")
        true
    } catch (_: Throwable) {
        false
    }

    // ─── System & Process ──────────────────────────────────────────────────────────

    fun getBridgeVersion(): Int = if (isLoaded) try { nativeGetBridgeVersion() } catch (_: UnsatisfiedLinkError) { 1 } else 1
    fun isExecutable(path: String): Boolean = if (isLoaded) {
        try { nativeIsExecutable(path) } catch (_: UnsatisfiedLinkError) { java.io.File(path).canExecute() }
    } else {
        java.io.File(path).canExecute()
    }
    fun setExecutable(path: String): Int = if (isLoaded) {
        try { nativeSetExecutable(path) } catch (_: UnsatisfiedLinkError) { if (java.io.File(path).setExecutable(true, false)) 0 else -1 }
    } else {
        if (java.io.File(path).setExecutable(true, false)) 0 else -1
    }
    fun getAbi(): String = if (isLoaded) try { nativeGetAbi() } catch (_: UnsatisfiedLinkError) { System.getProperty("os.arch") ?: "unknown" } else (System.getProperty("os.arch") ?: "unknown")
    fun sendSignal(pid: Int, signal: Int): Int = if (isLoaded) try { nativeSendSignal(pid, signal) } catch (_: UnsatisfiedLinkError) { -1 } else -1
    fun getAvailableMemoryBytes(): Long = if (isLoaded) try { nativeGetAvailableMemoryBytes() } catch (_: UnsatisfiedLinkError) { Runtime.getRuntime().freeMemory() } else Runtime.getRuntime().freeMemory()

    // ─── PTY Subprocess ────────────────────────────────────────────────────────────

    fun createPtyProcess(
        cmd: Array<String>,
        cwd: String,
        env: Array<String>?,
        rows: Int,
        cols: Int,
        outPidAndFd: IntArray
    ): Int = nativeCreatePtyProcess(cmd, cwd, env, rows, cols, outPidAndFd)

    fun setPtyWindowSize(fd: Int, rows: Int, cols: Int): Int =
        nativeSetPtyWindowSize(fd, rows, cols)

    fun writeFd(fd: Int, data: ByteArray, offset: Int = 0, length: Int = data.size): Int =
        nativeWriteFd(fd, data, offset, length)

    fun readFd(fd: Int, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Int =
        nativeReadFd(fd, buffer, offset, length)

    fun closeFd(fd: Int) = nativeCloseFd(fd)

    fun waitpid(pid: Int, block: Boolean = false): Int = nativeWaitpid(pid, block)

    // ─── Display & Surface ─────────────────────────────────────────────────────────

    fun onSurfaceCreated(surface: Surface, width: Int, height: Int) {
        if (isLoaded) {
            try { nativeOnSurfaceCreated(surface, width, height) } catch (_: UnsatisfiedLinkError) {}
        }
    }

    fun onSurfaceChanged(surface: Surface, width: Int, height: Int, format: Int) {
        if (isLoaded) {
            try { nativeOnSurfaceChanged(surface, width, height, format) } catch (_: UnsatisfiedLinkError) {}
        }
    }

    fun onSurfaceDestroyed() {
        if (isLoaded) {
            try { nativeOnSurfaceDestroyed() } catch (_: UnsatisfiedLinkError) {}
        }
    }

    // ─── GPU Detection ─────────────────────────────────────────────────────────────

    fun getGpuVendor(): String = nativeGetGpuVendor()
    fun getGpuRenderer(): String = nativeGetGpuRenderer()
    fun getGpuVersion(): String = nativeGetGpuVersion()
    fun isVulkanSupported(): Boolean = nativeIsVulkanSupported()
    fun isHardwareAccelerated(): Boolean = nativeIsHardwareAccelerated()

    // ─── Input Routing ─────────────────────────────────────────────────────────────

    fun sendTouchEvent(action: Int, pointerId: Int, x: Float, y: Float, pressure: Float = 1.0f) =
        nativeSendTouchEvent(action, pointerId, x, y, pressure)

    fun sendMouseEvent(action: Int, buttonState: Int, x: Float, y: Float, scrollX: Float = 0f, scrollY: Float = 0f) =
        nativeSendMouseEvent(action, buttonState, x, y, scrollX, scrollY)

    fun sendKeyEvent(keyCode: Int, isDown: Boolean, metaState: Int = 0, unicodeChar: Int = 0) =
        nativeSendKeyEvent(keyCode, isDown, metaState, unicodeChar)

    fun resetInput() {
        if (isLoaded) {
            try { nativeResetInput() } catch (_: UnsatisfiedLinkError) {}
        }
    }

    // ─── Audio Bridge ──────────────────────────────────────────────────────────────

    fun audioStart(sampleRate: Int, channels: Int, bufferSizeFrames: Int): Boolean =
        nativeAudioStart(sampleRate, channels, bufferSizeFrames)

    fun audioStop() = nativeAudioStop()

    fun audioWritePcm(data: ByteArray, offset: Int, length: Int): Int =
        nativeAudioWritePcm(data, offset, length)

    fun audioGetLatencyMs(): Int = nativeAudioGetLatencyMs()

    // ─── Wayland / Weston Foundation Bridge ──────────────────────────────────────

    /**
     * Verifies the native Wayland / libweston / Pixman / libxkbcommon dependency foundation.
     * Returns true if all headers, symbols, and runtime initialization smoke tests succeed.
     */
    fun verifyWaylandFoundation(): Boolean {
        return try {
            nativeVerifyWaylandFoundation()
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    /**
     * Returns a descriptive string detailing the status of each native Wayland foundation dependency.
     */
    fun getWaylandFoundationDetails(): String {
        return try {
            nativeGetWaylandFoundationDetails()
        } catch (e: UnsatisfiedLinkError) {
            "NativeBridge library not loaded or symbol not found: ${e.message}"
        }
    }

    // ─── Native GUI Host Lifecycle ────────────────────────────────────────────────

    /**
     * Starts the native GUI host worker thread and initializes the Wayland/Weston runtime.
     * Idempotent: repeated calls do not create duplicate workers.
     * Blocks the caller until RUNNING or FAILED, but runs the event loop on a dedicated worker thread.
     */
    fun guiStart(): Boolean {
        return if (isLoaded) {
            try { nativeGuiStart() } catch (_: UnsatisfiedLinkError) { false }
        } else {
            false
        }
    }

    /**
     * Stops the native GUI host worker thread and deterministically tears down Wayland/Weston runtime.
     * Idempotent: safe to call repeatedly when stopped.
     */
    fun guiStop(): Boolean {
        return if (isLoaded) {
            try { nativeGuiStop() } catch (_: UnsatisfiedLinkError) { false }
        } else {
            false
        }
    }

    /**
     * Returns true if the native GUI host is currently in RUNNING state.
     */
    fun guiIsRunning(): Boolean {
        return if (isLoaded) {
            try { nativeGuiIsRunning() } catch (_: UnsatisfiedLinkError) { false }
        } else {
            false
        }
    }

    /**
     * Returns the lifecycle state integer (0: STOPPED, 1: STARTING, 2: RUNNING, 3: STOPPING).
     */
    fun guiGetState(): Int {
        return if (isLoaded) {
            try { nativeGuiGetState() } catch (_: UnsatisfiedLinkError) { 0 }
        } else {
            0
        }
    }

    // ─── Desktop Environment & Applications ───────────────────────────────────────

    fun interface AppLaunchListener {
        fun onLaunchApp(name: String, execPath: String)
    }

    fun updateDesktopApplications(
        names: Array<String>,
        execs: Array<String>,
        categories: Array<String>,
        icons: Array<String>
    ) {
        if (isLoaded) {
            try {
                nativeUpdateDesktopApplications(names, execs, categories, icons)
            } catch (_: UnsatisfiedLinkError) {}
        }
    }

    fun setAppLaunchListener(listener: AppLaunchListener?) {
        if (isLoaded) {
            try {
                nativeSetAppLaunchListener(listener)
            } catch (_: UnsatisfiedLinkError) {}
        }
    }

    fun getActiveWindows(): Array<String> {
        return if (isLoaded) {
            try {
                nativeGetActiveWindows()
            } catch (_: UnsatisfiedLinkError) {
                emptyArray()
            }
        } else {
            emptyArray()
        }
    }

    /**
     * Sets the Wayland output scale factor (1, 2, 3, or 4).
     */
    fun setOutputScale(scale: Int) {
        if (isLoaded) {
            try {
                nativeSetOutputScale(scale)
            } catch (_: UnsatisfiedLinkError) {}
        }
    }

    /**
     * Gets the current Wayland output scale factor.
     */
    fun getOutputScale(): Int {
        return if (isLoaded) {
            try {
                nativeGetOutputScale()
            } catch (_: UnsatisfiedLinkError) {
                1
            }
        } else {
            1
        }
    }

    /**
     * Enqueues a window action ("activate", "minimize", "maximize", "restore", "close")
     * for a tracked window ID.
     */
    fun performWindowAction(windowId: Long, action: String) {
        if (isLoaded) {
            try {
                nativePerformWindowAction(windowId, action)
            } catch (_: UnsatisfiedLinkError) {}
        }
    }

    // ─── External JNI declarations ─────────────────────────────────────────────────

    @JvmStatic external fun nativeGetBridgeVersion(): Int
    @JvmStatic external fun nativeIsExecutable(path: String): Boolean
    @JvmStatic external fun nativeSetExecutable(path: String): Int
    @JvmStatic external fun nativeGetAbi(): String
    @JvmStatic external fun nativeSendSignal(pid: Int, signal: Int): Int
    @JvmStatic external fun nativeGetAvailableMemoryBytes(): Long
    @JvmStatic external fun nativeCreatePtyProcess(cmd: Array<String>, cwd: String, env: Array<String>?, rows: Int, cols: Int, outPidAndFd: IntArray): Int
    @JvmStatic external fun nativeSetPtyWindowSize(fd: Int, rows: Int, cols: Int): Int
    @JvmStatic external fun nativeWriteFd(fd: Int, data: ByteArray, offset: Int, length: Int): Int
    @JvmStatic external fun nativeReadFd(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int
    @JvmStatic external fun nativeCloseFd(fd: Int)
    @JvmStatic external fun nativeWaitpid(pid: Int, block: Boolean): Int

    @JvmStatic external fun nativeOnSurfaceCreated(surface: Surface, width: Int, height: Int)
    @JvmStatic external fun nativeOnSurfaceChanged(surface: Surface, width: Int, height: Int, format: Int)
    @JvmStatic external fun nativeOnSurfaceDestroyed()

    @JvmStatic external fun nativeGetGpuVendor(): String
    @JvmStatic external fun nativeGetGpuRenderer(): String
    @JvmStatic external fun nativeGetGpuVersion(): String
    @JvmStatic external fun nativeIsVulkanSupported(): Boolean
    @JvmStatic external fun nativeIsHardwareAccelerated(): Boolean

    @JvmStatic external fun nativeSendTouchEvent(action: Int, pointerId: Int, x: Float, y: Float, pressure: Float)
    @JvmStatic external fun nativeSendMouseEvent(action: Int, buttonState: Int, x: Float, y: Float, scrollX: Float, scrollY: Float)
    @JvmStatic external fun nativeSendKeyEvent(keyCode: Int, isDown: Boolean, metaState: Int, unicodeChar: Int)
    @JvmStatic external fun nativeResetInput()

    @JvmStatic external fun nativeAudioStart(sampleRate: Int, channels: Int, bufferSizeFrames: Int): Boolean
    @JvmStatic external fun nativeAudioStop()
    @JvmStatic external fun nativeAudioWritePcm(data: ByteArray, offset: Int, length: Int): Int
    @JvmStatic external fun nativeAudioGetLatencyMs(): Int

    @JvmStatic external fun nativeVerifyWaylandFoundation(): Boolean
    @JvmStatic external fun nativeGetWaylandFoundationDetails(): String

    @JvmStatic external fun nativeGuiStart(): Boolean
    @JvmStatic external fun nativeGuiStop(): Boolean
    @JvmStatic external fun nativeGuiIsRunning(): Boolean
    @JvmStatic external fun nativeGuiGetState(): Int

    @JvmStatic external fun nativeUpdateDesktopApplications(
        names: Array<String>,
        execs: Array<String>,
        categories: Array<String>,
        icons: Array<String>
    )
    @JvmStatic external fun nativeSetAppLaunchListener(listener: Any?)
    @JvmStatic external fun nativeGetActiveWindows(): Array<String>
    @JvmStatic external fun nativeSetOutputScale(scale: Int)
    @JvmStatic external fun nativeGetOutputScale(): Int
    @JvmStatic external fun nativePerformWindowAction(windowId: Long, action: String)
}
