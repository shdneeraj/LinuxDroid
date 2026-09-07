#include "input_bridge.h"

#include <android/log.h>
#include <sys/eventfd.h>
#include <unistd.h>
#include <chrono>

#define TAG "LinuxDroid/Input"
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO, TAG, fmt, ##__VA_ARGS__)
#define LOGW(fmt, ...) __android_log_print(ANDROID_LOG_WARN, TAG, fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, ##__VA_ARGS__)

namespace linuxdroid {

InputBridge& InputBridge::getInstance() {
    static InputBridge instance;
    return instance;
}

InputBridge::InputBridge() = default;

void InputBridge::setWakeFd(int wakeFd) {
    wakeFd_.store(wakeFd, std::memory_order_release);
    LOGI("INPUT_DEVICE_INIT: compositor wake_fd set to %d", wakeFd);
}

void InputBridge::wakeCompositorLoop() {
    int fd = wakeFd_.load(std::memory_order_acquire);
    if (fd >= 0) {
        eventfd_t val = 1;
        eventfd_write(fd, val);
    }
}

static uint64_t getCurrentTimestampNs() {
    auto now = std::chrono::steady_clock::now().time_since_epoch();
    return std::chrono::duration_cast<std::chrono::nanoseconds>(now).count();
}

void InputBridge::pushEventLocked(const NativeInputEvent& evt) {
    totalEvents_.fetch_add(1, std::memory_order_relaxed);

    // Coalesce high-frequency motion events if preceding event is of the same type and target
    if (!eventQueue_.empty()) {
        if (evt.type == InputEventType::MOUSE_MOVE && eventQueue_.back().type == InputEventType::MOUSE_MOVE) {
            eventQueue_.back().x = evt.x;
            eventQueue_.back().y = evt.y;
            eventQueue_.back().timestampNs = evt.timestampNs;
            wakeCompositorLoop();
            return;
        } else if (evt.type == InputEventType::TOUCH_MOVE &&
                   eventQueue_.back().type == InputEventType::TOUCH_MOVE &&
                   eventQueue_.back().id == evt.id) {
            eventQueue_.back().x = evt.x;
            eventQueue_.back().y = evt.y;
            eventQueue_.back().pressure = evt.pressure;
            eventQueue_.back().timestampNs = evt.timestampNs;
            wakeCompositorLoop();
            return;
        }
    }

    if (eventQueue_.size() >= MAX_QUEUE_SIZE) {
        // Drop high-frequency motion events first to preserve button/key state transitions
        if (evt.type == InputEventType::TOUCH_MOVE || evt.type == InputEventType::MOUSE_MOVE) {
            droppedEvents_.fetch_add(1, std::memory_order_relaxed);
            return;
        }

        // Drop oldest event to make room for critical press/release transition
        eventQueue_.pop();
        droppedEvents_.fetch_add(1, std::memory_order_relaxed);
        LOGW("INPUT_QUEUE_OVERFLOW: input queue reached limit (%zu), dropped older event (total dropped: %llu)",
             MAX_QUEUE_SIZE, (unsigned long long)droppedEvents_.load(std::memory_order_relaxed));
    }

    eventQueue_.push(evt);
    wakeCompositorLoop();
}

void InputBridge::sendTouchEvent(int action, int pointerId, float x, float y, float pressure) {
    std::lock_guard<std::mutex> lock(mutex_);

    NativeInputEvent evt;
    evt.id = pointerId;
    evt.x = x;
    evt.y = y;
    evt.pressure = pressure;
    evt.timestampNs = getCurrentTimestampNs();

    switch (action) {
        case 0: // ACTION_DOWN
        case 5: // ACTION_POINTER_DOWN
            evt.type = InputEventType::TOUCH_DOWN;
            LOGI("INPUT_TOUCH_DOWN: id=%d at (%.1f, %.1f) pressure=%.2f", pointerId, x, y, pressure);
            break;
        case 1: // ACTION_UP
        case 6: // ACTION_POINTER_UP
            evt.type = InputEventType::TOUCH_UP;
            LOGI("INPUT_TOUCH_UP: id=%d at (%.1f, %.1f)", pointerId, x, y);
            break;
        case 3: // ACTION_CANCEL
            evt.type = InputEventType::TOUCH_CANCEL;
            LOGI("INPUT_TOUCH_CANCEL: id=%d", pointerId);
            break;
        default: // ACTION_MOVE (2)
            evt.type = InputEventType::TOUCH_MOVE;
            uint32_t count = motionLogThrottle_.fetch_add(1, std::memory_order_relaxed);
            if (count % 120 == 0) {
                LOGI("INPUT_TOUCH_MOTION: id=%d at (%.1f, %.1f) (sampled)", pointerId, x, y);
            }
            break;
    }

    pushEventLocked(evt);
}

void InputBridge::sendMouseEvent(int action, int buttonState, float x, float y, float scrollX, float scrollY) {
    std::lock_guard<std::mutex> lock(mutex_);

    NativeInputEvent evt;
    evt.id = buttonState;
    evt.x = x;
    evt.y = y;
    evt.scrollX = scrollX;
    evt.scrollY = scrollY;
    evt.timestampNs = getCurrentTimestampNs();

    if (scrollX != 0.0f || scrollY != 0.0f) {
        evt.type = InputEventType::MOUSE_SCROLL;
        LOGI("INPUT_POINTER_SCROLL: scroll=(%.2f, %.2f) at (%.1f, %.1f)", scrollX, scrollY, x, y);
    } else if (action == 0 || action == 11) { // ACTION_DOWN / ACTION_BUTTON_PRESS
        evt.type = InputEventType::MOUSE_DOWN;
        LOGI("INPUT_POINTER_BUTTON: pressed buttonState=0x%x at (%.1f, %.1f)", buttonState, x, y);
    } else if (action == 1 || action == 12) { // ACTION_UP / ACTION_BUTTON_RELEASE
        evt.type = InputEventType::MOUSE_UP;
        LOGI("INPUT_POINTER_BUTTON: released buttonState=0x%x at (%.1f, %.1f)", buttonState, x, y);
    } else {
        evt.type = InputEventType::MOUSE_MOVE;
        uint32_t count = motionLogThrottle_.fetch_add(1, std::memory_order_relaxed);
        if (count % 120 == 0) {
            LOGI("INPUT_POINTER_MOTION: pos=(%.1f, %.1f) (sampled)", x, y);
        }
    }

    pushEventLocked(evt);
}

void InputBridge::sendKeyEvent(int keyCode, bool isDown, int metaState, int unicodeChar) {
    std::lock_guard<std::mutex> lock(mutex_);

    NativeInputEvent evt;
    evt.type = isDown ? InputEventType::KEY_PRESS : InputEventType::KEY_RELEASE;
    evt.keyCode = keyCode;
    evt.metaState = metaState;
    evt.unicodeChar = unicodeChar;
    evt.timestampNs = getCurrentTimestampNs();

    if (isDown) {
        LOGI("INPUT_KEY_DOWN: android_kc=%d meta=0x%x unicode=%d", keyCode, metaState, unicodeChar);
    } else {
        LOGI("INPUT_KEY_UP: android_kc=%d meta=0x%x", keyCode, metaState);
    }

    pushEventLocked(evt);
}

void InputBridge::resetInput() {
    std::lock_guard<std::mutex> lock(mutex_);
    // Clear pending unconsumed events to prevent stale moves or clicks
    while (!eventQueue_.empty()) {
        eventQueue_.pop();
    }
    NativeInputEvent evt;
    evt.type = InputEventType::RESET_INPUT;
    evt.timestampNs = getCurrentTimestampNs();
    eventQueue_.push(evt);
    wakeCompositorLoop();
    LOGI("INPUT_STATE_RESET: queued full input state reset for compositor thread");
}

bool InputBridge::popEvent(NativeInputEvent* outEvent) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (eventQueue_.empty() || outEvent == nullptr) return false;
    *outEvent = eventQueue_.front();
    eventQueue_.pop();
    return true;
}

size_t InputBridge::getPendingEventCount() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return eventQueue_.size();
}

void InputBridge::clear() {
    std::lock_guard<std::mutex> lock(mutex_);
    while (!eventQueue_.empty()) {
        eventQueue_.pop();
    }
}

} // namespace linuxdroid
