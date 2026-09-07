package com.linuxdroid.core.display

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.native_bridge.NativeBridge
import kotlin.math.hypot

/**
 * GuiSurfaceView provides the hardware-backed presentation surface for the Wayland/Weston
 * desktop and routes Android touch, mouse, and keyboard input events to NativeBridge.
 *
 * Phase 10 Enhancements:
 * - TouchMode: Direct vs Trackpad mode
 * - Direct mode: single tap = left click, long-press (450ms) = right click with haptic feedback,
 *   two-finger drag = vertical/horizontal scroll
 * - Trackpad mode: relative cursor movement, 1-finger tap = left click, 2-finger tap = right click,
 *   two-finger drag = scroll
 * - Soft modifier latching (Ctrl, Alt, Shift, Super) for touch-driven modifier shortcuts
 * - Quick text paste dispatching into active Wayland focus
 */
class GuiSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    enum class TouchMode {
        DIRECT,
        TRACKPAD
    }

    var touchMode: TouchMode = TouchMode.DIRECT
    var trackpadSensitivity: Float = 1.25f

    // Trackpad virtual cursor coordinates
    var cursorX: Float = 0f
        private set
    var cursorY: Float = 0f
        private set

    private val log = LinuxDroidLogger(LogSubsystem.INPUT)
    private var lastMouseButtonState: Int = MotionEvent.BUTTON_PRIMARY

    // Long press right-click detection in DIRECT mode
    private val mainHandler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var longPressRunnable: Runnable? = null
    private var isLongPressActive = false
    private var touchDownX = 0f
    private var touchDownY = 0f

    // Two-finger dragging / scroll tracking
    private var isTwoFingerDragging = false
    private var lastTwoFingerX = 0f
    private var lastTwoFingerY = 0f

    // Trackpad gestures
    private var trackpadLastX = 0f
    private var trackpadLastY = 0f
    private var trackpadDownTime = 0L
    private var trackpadMoved = false

    // Soft modifier latching
    private val activeModifiers = mutableSetOf<Int>()

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        log.info("onWindowFocusChanged: hasWindowFocus=$hasWindowFocus")
        if (hasWindowFocus) {
            requestFocus()
        } else {
            // Cancel active touches and reset modifier/key state on window focus loss to prevent stuck input
            clearLongPress()
            clearModifiers()
            isTwoFingerDragging = false
            NativeBridge.resetInput()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        log.info("Surface created: ${width}x${height}")
        requestFocus()
        NativeBridge.onSurfaceCreated(holder.surface, width, height)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        log.info("Surface changed: ${width}x${height} format=$format")
        if (cursorX == 0f && cursorY == 0f && width > 0 && height > 0) {
            cursorX = width / 2f
            cursorY = height / 2f
        }
        NativeBridge.onSurfaceChanged(holder.surface, width, height, format)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        log.info("Surface destroyed")
        clearLongPress()
        clearModifiers()
        isTwoFingerDragging = false
        NativeBridge.resetInput()
        NativeBridge.onSurfaceDestroyed()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val actionIndex = event.actionIndex

        if (action == MotionEvent.ACTION_DOWN) {
            requestFocus()
        }

        val toolType = event.getToolType(actionIndex)
        val isMouse = toolType == MotionEvent.TOOL_TYPE_MOUSE || event.isFromSource(InputDevice.SOURCE_MOUSE)

        if (isMouse) {
            val x = event.getX(actionIndex)
            val y = event.getY(actionIndex)
            val btnState = event.buttonState

            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    lastMouseButtonState = if (btnState != 0) btnState else MotionEvent.BUTTON_PRIMARY
                    NativeBridge.sendMouseEvent(action, lastMouseButtonState, x, y, 0f, 0f)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (btnState != 0) lastMouseButtonState = btnState
                    NativeBridge.sendMouseEvent(action, lastMouseButtonState, x, y, 0f, 0f)
                }
                MotionEvent.ACTION_UP -> {
                    val releasedBtn = if (btnState != 0) btnState else lastMouseButtonState
                    NativeBridge.sendMouseEvent(action, releasedBtn, x, y, 0f, 0f)
                    lastMouseButtonState = MotionEvent.BUTTON_PRIMARY
                }
                MotionEvent.ACTION_CANCEL -> {
                    NativeBridge.resetInput()
                }
            }
            return true
        }

        return when (touchMode) {
            TouchMode.DIRECT -> handleDirectTouch(event, action, actionIndex)
            TouchMode.TRACKPAD -> handleTrackpadTouch(event, action, actionIndex)
        }
    }

    private fun handleDirectTouch(event: MotionEvent, action: Int, actionIndex: Int): Boolean {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                clearLongPress()
                isLongPressActive = false
                isTwoFingerDragging = false
                touchDownX = event.x
                touchDownY = event.y

                val pointerId = event.getPointerId(actionIndex)
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                val pressure = event.getPressure(actionIndex)

                longPressRunnable = Runnable {
                    isLongPressActive = true
                    try {
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    } catch (_: Exception) {}
                    // Synthesize right-click button down
                    NativeBridge.sendMouseEvent(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_SECONDARY, touchDownX, touchDownY, 0f, 0f)
                }
                mainHandler.postDelayed(longPressRunnable!!, 450)

                NativeBridge.sendTouchEvent(action, pointerId, x, y, pressure)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                clearLongPress()
                if (event.pointerCount == 2) {
                    isTwoFingerDragging = true
                    lastTwoFingerX = (event.getX(0) + event.getX(1)) / 2f
                    lastTwoFingerY = (event.getY(0) + event.getY(1)) / 2f
                }
                val pointerId = event.getPointerId(actionIndex)
                NativeBridge.sendTouchEvent(action, pointerId, event.getX(actionIndex), event.getY(actionIndex), event.getPressure(actionIndex))
            }
            MotionEvent.ACTION_MOVE -> {
                if (isLongPressActive) {
                    NativeBridge.sendMouseEvent(MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_SECONDARY, event.x, event.y, 0f, 0f)
                    return true
                }

                if (longPressRunnable != null && hypot(event.x - touchDownX, event.y - touchDownY) > touchSlop) {
                    clearLongPress()
                }

                if (event.pointerCount >= 2 && isTwoFingerDragging) {
                    val currX = (event.getX(0) + event.getX(1)) / 2f
                    val currY = (event.getY(0) + event.getY(1)) / 2f
                    val deltaX = currX - lastTwoFingerX
                    val deltaY = currY - lastTwoFingerY
                    if (hypot(deltaX, deltaY) > 1.5f) {
                        val scrollX = deltaX / 20f
                        val scrollY = deltaY / 20f
                        NativeBridge.sendMouseEvent(MotionEvent.ACTION_SCROLL, 0, currX, currY, scrollX, scrollY)
                        lastTwoFingerX = currX
                        lastTwoFingerY = currY
                    }
                    return true
                }

                for (i in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(i)
                    NativeBridge.sendTouchEvent(action, pointerId, event.getX(i), event.getY(i), event.getPressure(i))
                }
            }
            MotionEvent.ACTION_UP -> {
                clearLongPress()
                if (isLongPressActive) {
                    isLongPressActive = false
                    NativeBridge.sendMouseEvent(MotionEvent.ACTION_UP, MotionEvent.BUTTON_SECONDARY, event.x, event.y, 0f, 0f)
                    return true
                }
                isTwoFingerDragging = false
                val pointerId = event.getPointerId(actionIndex)
                NativeBridge.sendTouchEvent(action, pointerId, event.x, event.y, event.pressure)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) {
                    isTwoFingerDragging = false
                }
                val pointerId = event.getPointerId(actionIndex)
                NativeBridge.sendTouchEvent(action, pointerId, event.getX(actionIndex), event.getY(actionIndex), event.getPressure(actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> {
                clearLongPress()
                if (isLongPressActive) {
                    isLongPressActive = false
                    NativeBridge.sendMouseEvent(MotionEvent.ACTION_UP, MotionEvent.BUTTON_SECONDARY, event.x, event.y, 0f, 0f)
                }
                isTwoFingerDragging = false
                NativeBridge.resetInput()
            }
        }
        return true
    }

    private fun handleTrackpadTouch(event: MotionEvent, action: Int, actionIndex: Int): Boolean {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                trackpadDownTime = System.currentTimeMillis()
                trackpadLastX = event.x
                trackpadLastY = event.y
                touchDownX = event.x
                touchDownY = event.y
                trackpadMoved = false
                isTwoFingerDragging = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    isTwoFingerDragging = true
                    lastTwoFingerX = (event.getX(0) + event.getX(1)) / 2f
                    lastTwoFingerY = (event.getY(0) + event.getY(1)) / 2f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2 && isTwoFingerDragging) {
                    val currX = (event.getX(0) + event.getX(1)) / 2f
                    val currY = (event.getY(0) + event.getY(1)) / 2f
                    val deltaX = currX - lastTwoFingerX
                    val deltaY = currY - lastTwoFingerY
                    if (hypot(deltaX, deltaY) > 1.5f) {
                        trackpadMoved = true
                        val scrollX = deltaX / 20f
                        val scrollY = deltaY / 20f
                        NativeBridge.sendMouseEvent(MotionEvent.ACTION_SCROLL, 0, cursorX, cursorY, scrollX, scrollY)
                        lastTwoFingerX = currX
                        lastTwoFingerY = currY
                    }
                } else if (event.pointerCount == 1) {
                    val dx = event.x - trackpadLastX
                    val dy = event.y - trackpadLastY
                    if (hypot(dx, dy) > 1f) {
                        if (hypot(event.x - touchDownX, event.y - touchDownY) > touchSlop) {
                            trackpadMoved = true
                        }
                        val maxX = if (width > 0) width.toFloat() else 1920f
                        val maxY = if (height > 0) height.toFloat() else 1080f
                        cursorX = (cursorX + dx * trackpadSensitivity).coerceIn(0f, maxX)
                        cursorY = (cursorY + dy * trackpadSensitivity).coerceIn(0f, maxY)
                        trackpadLastX = event.x
                        trackpadLastY = event.y
                        NativeBridge.sendMouseEvent(MotionEvent.ACTION_MOVE, 0, cursorX, cursorY, 0f, 0f)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val duration = System.currentTimeMillis() - trackpadDownTime
                if (!trackpadMoved && duration < 350) {
                    // 1-finger tap -> Left click
                    try {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    } catch (_: Exception) {}
                    NativeBridge.sendMouseEvent(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY, cursorX, cursorY, 0f, 0f)
                    NativeBridge.sendMouseEvent(MotionEvent.ACTION_UP, MotionEvent.BUTTON_PRIMARY, cursorX, cursorY, 0f, 0f)
                }
                isTwoFingerDragging = false
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val duration = System.currentTimeMillis() - trackpadDownTime
                if (event.pointerCount == 2 && !trackpadMoved && duration < 350) {
                    // 2-finger tap -> Right click
                    try {
                        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    } catch (_: Exception) {}
                    NativeBridge.sendMouseEvent(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_SECONDARY, cursorX, cursorY, 0f, 0f)
                    NativeBridge.sendMouseEvent(MotionEvent.ACTION_UP, MotionEvent.BUTTON_SECONDARY, cursorX, cursorY, 0f, 0f)
                }
                if (event.pointerCount <= 2) {
                    isTwoFingerDragging = false
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                isTwoFingerDragging = false
                NativeBridge.resetInput()
            }
        }
        return true
    }

    private fun clearLongPress() {
        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
        longPressRunnable = null
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE,
                MotionEvent.ACTION_SCROLL,
                MotionEvent.ACTION_BUTTON_PRESS,
                MotionEvent.ACTION_BUTTON_RELEASE -> {
                    val x = event.x
                    val y = event.y
                    val scrollX = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                    val scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    val buttonState = event.buttonState
                    NativeBridge.sendMouseEvent(event.actionMasked, buttonState, x, y, scrollX, scrollY)
                    return true
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount > 0) {
            return true
        }
        val meta = event.metaState or getCurrentMetaState()
        NativeBridge.sendKeyEvent(keyCode, true, meta, event.unicodeChar)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val meta = event.metaState or getCurrentMetaState()
        NativeBridge.sendKeyEvent(keyCode, false, meta, event.unicodeChar)
        return true
    }

    // ─── Modifier Key Latching ──────────────────────────────────────────────────

    fun toggleModifier(keyCode: Int): Boolean {
        val isNowActive = if (activeModifiers.contains(keyCode)) {
            activeModifiers.remove(keyCode)
            NativeBridge.sendKeyEvent(keyCode, false, getCurrentMetaState(), 0)
            false
        } else {
            activeModifiers.add(keyCode)
            NativeBridge.sendKeyEvent(keyCode, true, getCurrentMetaState(), 0)
            true
        }
        return isNowActive
    }

    fun isModifierActive(keyCode: Int): Boolean = activeModifiers.contains(keyCode)

    fun clearModifiers() {
        for (keyCode in activeModifiers) {
            NativeBridge.sendKeyEvent(keyCode, false, 0, 0)
        }
        activeModifiers.clear()
    }

    fun getCurrentMetaState(): Int {
        var meta = 0
        if (activeModifiers.contains(KeyEvent.KEYCODE_SHIFT_LEFT) || activeModifiers.contains(KeyEvent.KEYCODE_SHIFT_RIGHT)) {
            meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        }
        if (activeModifiers.contains(KeyEvent.KEYCODE_CTRL_LEFT) || activeModifiers.contains(KeyEvent.KEYCODE_CTRL_RIGHT)) {
            meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        }
        if (activeModifiers.contains(KeyEvent.KEYCODE_ALT_LEFT) || activeModifiers.contains(KeyEvent.KEYCODE_ALT_RIGHT)) {
            meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        }
        if (activeModifiers.contains(KeyEvent.KEYCODE_META_LEFT) || activeModifiers.contains(KeyEvent.KEYCODE_META_RIGHT)) {
            meta = meta or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        }
        return meta
    }

    fun sendSingleKey(keyCode: Int) {
        val meta = getCurrentMetaState()
        NativeBridge.sendKeyEvent(keyCode, true, meta, 0)
        NativeBridge.sendKeyEvent(keyCode, false, meta, 0)
    }

    fun pasteText(text: String) {
        for (ch in text) {
            dispatchCharacter(ch)
        }
    }

    // ─── Soft Keyboard (IME) Support ───────────────────────────────────────────────

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (text == null || text.isEmpty()) return true
                for (ch in text) {
                    dispatchCharacter(ch)
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                for (i in 0 until beforeLength) {
                    NativeBridge.sendKeyEvent(KeyEvent.KEYCODE_DEL, true)
                    NativeBridge.sendKeyEvent(KeyEvent.KEYCODE_DEL, false)
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    onKeyDown(event.keyCode, event)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    onKeyUp(event.keyCode, event)
                }
                return true
            }
        }
    }

    private fun dispatchCharacter(ch: Char) {
        val kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = kcm.getEvents(charArrayOf(ch))
        if (events != null && events.isNotEmpty()) {
            for (evt in events) {
                if (evt.action == KeyEvent.ACTION_DOWN) {
                    onKeyDown(evt.keyCode, evt)
                } else if (evt.action == KeyEvent.ACTION_UP) {
                    onKeyUp(evt.keyCode, evt)
                }
            }
            return
        }

        val (keyCode, shift) = when (ch) {
            '\n' -> Pair(KeyEvent.KEYCODE_ENTER, false)
            '\t' -> Pair(KeyEvent.KEYCODE_TAB, false)
            ' ' -> Pair(KeyEvent.KEYCODE_SPACE, false)
            in 'a'..'z' -> Pair(KeyEvent.KEYCODE_A + (ch - 'a'), false)
            in 'A'..'Z' -> Pair(KeyEvent.KEYCODE_A + (ch - 'A'), true)
            in '0'..'9' -> Pair(KeyEvent.KEYCODE_0 + (ch - '0'), false)
            '!' -> Pair(KeyEvent.KEYCODE_1, true)
            '@' -> Pair(KeyEvent.KEYCODE_2, true)
            '#' -> Pair(KeyEvent.KEYCODE_3, true)
            '$' -> Pair(KeyEvent.KEYCODE_4, true)
            '%' -> Pair(KeyEvent.KEYCODE_5, true)
            '^' -> Pair(KeyEvent.KEYCODE_6, true)
            '&' -> Pair(KeyEvent.KEYCODE_7, true)
            '*' -> Pair(KeyEvent.KEYCODE_8, true)
            '(' -> Pair(KeyEvent.KEYCODE_9, true)
            ')' -> Pair(KeyEvent.KEYCODE_0, true)
            '-' -> Pair(KeyEvent.KEYCODE_MINUS, false)
            '_' -> Pair(KeyEvent.KEYCODE_MINUS, true)
            '=' -> Pair(KeyEvent.KEYCODE_EQUALS, false)
            '+' -> Pair(KeyEvent.KEYCODE_EQUALS, true)
            '[' -> Pair(KeyEvent.KEYCODE_LEFT_BRACKET, false)
            '{' -> Pair(KeyEvent.KEYCODE_LEFT_BRACKET, true)
            ']' -> Pair(KeyEvent.KEYCODE_RIGHT_BRACKET, false)
            '}' -> Pair(KeyEvent.KEYCODE_RIGHT_BRACKET, true)
            '\\' -> Pair(KeyEvent.KEYCODE_BACKSLASH, false)
            '|' -> Pair(KeyEvent.KEYCODE_BACKSLASH, true)
            ';' -> Pair(KeyEvent.KEYCODE_SEMICOLON, false)
            ':' -> Pair(KeyEvent.KEYCODE_SEMICOLON, true)
            '\'' -> Pair(KeyEvent.KEYCODE_APOSTROPHE, false)
            '"' -> Pair(KeyEvent.KEYCODE_APOSTROPHE, true)
            ',' -> Pair(KeyEvent.KEYCODE_COMMA, false)
            '<' -> Pair(KeyEvent.KEYCODE_COMMA, true)
            '.' -> Pair(KeyEvent.KEYCODE_PERIOD, false)
            '>' -> Pair(KeyEvent.KEYCODE_PERIOD, true)
            '/' -> Pair(KeyEvent.KEYCODE_SLASH, false)
            '?' -> Pair(KeyEvent.KEYCODE_SLASH, true)
            '`' -> Pair(KeyEvent.KEYCODE_GRAVE, false)
            '~' -> Pair(KeyEvent.KEYCODE_GRAVE, true)
            else -> Pair(0, false)
        }

        if (keyCode != 0) {
            val meta = if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
            if (shift) {
                NativeBridge.sendKeyEvent(KeyEvent.KEYCODE_SHIFT_LEFT, true, meta, 0)
            }
            NativeBridge.sendKeyEvent(keyCode, true, meta, ch.code)
            NativeBridge.sendKeyEvent(keyCode, false, meta, ch.code)
            if (shift) {
                NativeBridge.sendKeyEvent(KeyEvent.KEYCODE_SHIFT_LEFT, false, 0, 0)
            }
        }
    }

    fun showSoftKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideSoftKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }

    @Suppress("DEPRECATION")
    fun toggleSoftKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
    }
}
