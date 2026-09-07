package com.linuxdroid.core.display

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.native_bridge.NativeBridge

/**
 * GuiSurfaceView provides the hardware-backed presentation surface for the Wayland/Weston
 * desktop and routes Android touch, mouse, and keyboard input events to NativeBridge.
 */
class GuiSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private val log = LinuxDroidLogger(LogSubsystem.INPUT)
    private var lastMouseButtonState: Int = MotionEvent.BUTTON_PRIMARY

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
        NativeBridge.onSurfaceChanged(holder.surface, width, height, format)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        log.info("Surface destroyed")
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

        // Touchscreen & Stylus handling
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerId = event.getPointerId(actionIndex)
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                val pressure = event.getPressure(actionIndex)
                NativeBridge.sendTouchEvent(action, pointerId, x, y, pressure)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    val pressure = event.getPressure(i)
                    NativeBridge.sendTouchEvent(action, pointerId, x, y, pressure)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(actionIndex)
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                val pressure = event.getPressure(actionIndex)
                NativeBridge.sendTouchEvent(action, pointerId, x, y, pressure)
            }
            MotionEvent.ACTION_CANCEL -> {
                NativeBridge.resetInput()
            }
        }
        return true
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
            // Prevent duplicate repeat dispatch: libweston handles repeats via wl_keyboard repeat_info
            return true
        }
        NativeBridge.sendKeyEvent(keyCode, true, event.metaState, event.unicodeChar)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        NativeBridge.sendKeyEvent(keyCode, false, event.metaState, event.unicodeChar)
        return true
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
