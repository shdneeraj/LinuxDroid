#include "input_translator.h"

#include <android/keycodes.h>
#include <algorithm>
#include <cmath>

namespace linuxdroid {

uint32_t InputTranslator::androidKeycodeToLinux(int32_t androidKeycode) {
    switch (androidKeycode) {
        // Letters A-Z
        case AKEYCODE_A: return KEY_A;
        case AKEYCODE_B: return KEY_B;
        case AKEYCODE_C: return KEY_C;
        case AKEYCODE_D: return KEY_D;
        case AKEYCODE_E: return KEY_E;
        case AKEYCODE_F: return KEY_F;
        case AKEYCODE_G: return KEY_G;
        case AKEYCODE_H: return KEY_H;
        case AKEYCODE_I: return KEY_I;
        case AKEYCODE_J: return KEY_J;
        case AKEYCODE_K: return KEY_K;
        case AKEYCODE_L: return KEY_L;
        case AKEYCODE_M: return KEY_M;
        case AKEYCODE_N: return KEY_N;
        case AKEYCODE_O: return KEY_O;
        case AKEYCODE_P: return KEY_P;
        case AKEYCODE_Q: return KEY_Q;
        case AKEYCODE_R: return KEY_R;
        case AKEYCODE_S: return KEY_S;
        case AKEYCODE_T: return KEY_T;
        case AKEYCODE_U: return KEY_U;
        case AKEYCODE_V: return KEY_V;
        case AKEYCODE_W: return KEY_W;
        case AKEYCODE_X: return KEY_X;
        case AKEYCODE_Y: return KEY_Y;
        case AKEYCODE_Z: return KEY_Z;

        // Numbers 0-9
        case AKEYCODE_0: return KEY_0;
        case AKEYCODE_1: return KEY_1;
        case AKEYCODE_2: return KEY_2;
        case AKEYCODE_3: return KEY_3;
        case AKEYCODE_4: return KEY_4;
        case AKEYCODE_5: return KEY_5;
        case AKEYCODE_6: return KEY_6;
        case AKEYCODE_7: return KEY_7;
        case AKEYCODE_8: return KEY_8;
        case AKEYCODE_9: return KEY_9;

        // Numeric Keypad
        case AKEYCODE_NUMPAD_0: return KEY_KP0;
        case AKEYCODE_NUMPAD_1: return KEY_KP1;
        case AKEYCODE_NUMPAD_2: return KEY_KP2;
        case AKEYCODE_NUMPAD_3: return KEY_KP3;
        case AKEYCODE_NUMPAD_4: return KEY_KP4;
        case AKEYCODE_NUMPAD_5: return KEY_KP5;
        case AKEYCODE_NUMPAD_6: return KEY_KP6;
        case AKEYCODE_NUMPAD_7: return KEY_KP7;
        case AKEYCODE_NUMPAD_8: return KEY_KP8;
        case AKEYCODE_NUMPAD_9: return KEY_KP9;
        case AKEYCODE_NUMPAD_ENTER: return KEY_KPENTER;
        case AKEYCODE_NUMPAD_DIVIDE: return KEY_KPSLASH;
        case AKEYCODE_NUMPAD_MULTIPLY: return KEY_KPASTERISK;
        case AKEYCODE_NUMPAD_SUBTRACT: return KEY_KPMINUS;
        case AKEYCODE_NUMPAD_ADD: return KEY_KPPLUS;
        case AKEYCODE_NUMPAD_DOT: return KEY_KPDOT;
        case AKEYCODE_NUMPAD_EQUALS: return KEY_KPEQUAL;

        // Control & Editing Keys
        case AKEYCODE_ENTER: return KEY_ENTER;
        case AKEYCODE_SPACE: return KEY_SPACE;
        case AKEYCODE_DEL: return KEY_BACKSPACE;
        case AKEYCODE_FORWARD_DEL: return KEY_DELETE;
        case AKEYCODE_TAB: return KEY_TAB;
        case AKEYCODE_ESCAPE: return KEY_ESC;
        case AKEYCODE_INSERT: return KEY_INSERT;
        case AKEYCODE_MOVE_HOME: return KEY_HOME;
        case AKEYCODE_MOVE_END: return KEY_END;
        case AKEYCODE_PAGE_UP: return KEY_PAGEUP;
        case AKEYCODE_PAGE_DOWN: return KEY_PAGEDOWN;

        // Navigation / DPAD
        case AKEYCODE_DPAD_UP: return KEY_UP;
        case AKEYCODE_DPAD_DOWN: return KEY_DOWN;
        case AKEYCODE_DPAD_LEFT: return KEY_LEFT;
        case AKEYCODE_DPAD_RIGHT: return KEY_RIGHT;

        // Function Keys F1-F12
        case AKEYCODE_F1: return KEY_F1;
        case AKEYCODE_F2: return KEY_F2;
        case AKEYCODE_F3: return KEY_F3;
        case AKEYCODE_F4: return KEY_F4;
        case AKEYCODE_F5: return KEY_F5;
        case AKEYCODE_F6: return KEY_F6;
        case AKEYCODE_F7: return KEY_F7;
        case AKEYCODE_F8: return KEY_F8;
        case AKEYCODE_F9: return KEY_F9;
        case AKEYCODE_F10: return KEY_F10;
        case AKEYCODE_F11: return KEY_F11;
        case AKEYCODE_F12: return KEY_F12;

        // Modifiers
        case AKEYCODE_SHIFT_LEFT: return KEY_LEFTSHIFT;
        case AKEYCODE_SHIFT_RIGHT: return KEY_RIGHTSHIFT;
        case AKEYCODE_CTRL_LEFT: return KEY_LEFTCTRL;
        case AKEYCODE_CTRL_RIGHT: return KEY_RIGHTCTRL;
        case AKEYCODE_ALT_LEFT: return KEY_LEFTALT;
        case AKEYCODE_ALT_RIGHT: return KEY_RIGHTALT;
        case AKEYCODE_META_LEFT: return KEY_LEFTMETA;
        case AKEYCODE_META_RIGHT: return KEY_RIGHTMETA;
        case AKEYCODE_CAPS_LOCK: return KEY_CAPSLOCK;
        case AKEYCODE_NUM_LOCK: return KEY_NUMLOCK;
        case AKEYCODE_SCROLL_LOCK: return KEY_SCROLLLOCK;

        // Punctuation & Symbols
        case AKEYCODE_GRAVE: return KEY_GRAVE;
        case AKEYCODE_MINUS: return KEY_MINUS;
        case AKEYCODE_EQUALS: return KEY_EQUAL;
        case AKEYCODE_LEFT_BRACKET: return KEY_LEFTBRACE;
        case AKEYCODE_RIGHT_BRACKET: return KEY_RIGHTBRACE;
        case AKEYCODE_BACKSLASH: return KEY_BACKSLASH;
        case AKEYCODE_SEMICOLON: return KEY_SEMICOLON;
        case AKEYCODE_APOSTROPHE: return KEY_APOSTROPHE;
        case AKEYCODE_COMMA: return KEY_COMMA;
        case AKEYCODE_PERIOD: return KEY_DOT;
        case AKEYCODE_SLASH: return KEY_SLASH;

        // Android Specific & System Keys
        case AKEYCODE_BACK: return KEY_BACK;
        case AKEYCODE_WINDOW: return KEY_LEFTMETA;
        case AKEYCODE_SYSRQ: return KEY_SYSRQ;
        case AKEYCODE_MENU: return KEY_COMPOSE;
        case AKEYCODE_VOLUME_UP: return KEY_VOLUMEUP;
        case AKEYCODE_VOLUME_DOWN: return KEY_VOLUMEDOWN;
        case AKEYCODE_VOLUME_MUTE: return KEY_MUTE;
        case AKEYCODE_MEDIA_PLAY_PAUSE: return KEY_PLAYPAUSE;
        case AKEYCODE_MEDIA_STOP: return KEY_STOPCD;
        case AKEYCODE_MEDIA_NEXT: return KEY_NEXTSONG;
        case AKEYCODE_MEDIA_PREVIOUS: return KEY_PREVIOUSSONG;
        case AKEYCODE_MEDIA_PLAY: return KEY_PLAY;
        case AKEYCODE_MEDIA_PAUSE: return KEY_PAUSE;
        case AKEYCODE_MEDIA_RECORD: return KEY_RECORD;
        case AKEYCODE_BRIGHTNESS_UP: return KEY_BRIGHTNESSUP;
        case AKEYCODE_BRIGHTNESS_DOWN: return KEY_BRIGHTNESSDOWN;

        default:
            return KEY_RESERVED; // 0 (unmapped key)
    }
}

uint32_t InputTranslator::androidButtonToLinux(int32_t androidButton) {
    // Check specific bit flags (AMOTION_EVENT_BUTTON_*)
    if (androidButton & 1) return BTN_LEFT;       // AMOTION_EVENT_BUTTON_PRIMARY (1 << 0)
    if (androidButton & 2) return BTN_RIGHT;      // AMOTION_EVENT_BUTTON_SECONDARY (1 << 1)
    if (androidButton & 4) return BTN_MIDDLE;     // AMOTION_EVENT_BUTTON_TERTIARY (1 << 2)
    if (androidButton & 8) return BTN_SIDE;       // AMOTION_EVENT_BUTTON_BACK (1 << 3)
    if (androidButton & 16) return BTN_EXTRA;     // AMOTION_EVENT_BUTTON_FORWARD (1 << 4)

    return BTN_LEFT; // Default to primary left button
}

double InputTranslator::clampCoordinate(float coord, int32_t maxBound) {
    if (std::isnan(coord) || std::isinf(coord)) {
        return 0.0;
    }
    double val = static_cast<double>(coord);
    double maxVal = (maxBound > 0) ? static_cast<double>(maxBound) : 0.0;
    return std::clamp(val, 0.0, maxVal);
}

double InputTranslator::translateScrollAxis(float androidScrollDelta) {
    // Invert sign: Android forward scroll is positive, Wayland is negative
    return -static_cast<double>(androidScrollDelta) * 10.0;
}

} // namespace linuxdroid
