#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <queue>

namespace linuxdroid {

enum class InputEventType {
    TOUCH_DOWN,
    TOUCH_UP,
    TOUCH_MOVE,
    TOUCH_CANCEL,
    MOUSE_DOWN,
    MOUSE_UP,
    MOUSE_MOVE,
    MOUSE_SCROLL,
    KEY_PRESS,
    KEY_RELEASE,
    RESET_INPUT,
};

struct NativeInputEvent {
    InputEventType type;
    int32_t id = 0;           // pointerId for touch, buttonState for mouse
    float x = 0.0f;
    float y = 0.0f;
    float pressure = 1.0f;
    float scrollX = 0.0f;
    float scrollY = 0.0f;
    int32_t keyCode = 0;      // Android AKEYCODE_*
    int32_t metaState = 0;
    int32_t unicodeChar = 0;
    uint64_t timestampNs = 0;
};

class InputBridge {
public:
    static InputBridge& getInstance();

    void setWakeFd(int wakeFd);

    void sendTouchEvent(int action, int pointerId, float x, float y, float pressure);
    void sendMouseEvent(int action, int buttonState, float x, float y, float scrollX, float scrollY);
    void sendKeyEvent(int keyCode, bool isDown, int metaState, int unicodeChar);
    void resetInput();

    bool popEvent(NativeInputEvent* outEvent);
    size_t getPendingEventCount() const;
    void clear();

    uint64_t getDroppedEventCount() const { return droppedEvents_.load(std::memory_order_relaxed); }
    uint64_t getTotalEventCount() const { return totalEvents_.load(std::memory_order_relaxed); }

private:
    InputBridge();
    void pushEventLocked(const NativeInputEvent& evt);
    void wakeCompositorLoop();

    mutable std::mutex mutex_;
    std::queue<NativeInputEvent> eventQueue_;
    std::atomic<int> wakeFd_{-1};

    std::atomic<uint64_t> totalEvents_{0};
    std::atomic<uint64_t> droppedEvents_{0};
    std::atomic<uint32_t> motionLogThrottle_{0};

    static constexpr size_t MAX_QUEUE_SIZE = 512;
};

} // namespace linuxdroid
