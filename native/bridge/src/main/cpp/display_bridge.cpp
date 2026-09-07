#include "display_bridge.h"
#include "gui_host.h"

#include <android/log.h>

#define TAG "LinuxDroid/DisplayBridge"
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO, TAG, fmt, ##__VA_ARGS__)
#define LOGW(fmt, ...) __android_log_print(ANDROID_LOG_WARN, TAG, fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, ##__VA_ARGS__)

namespace linuxdroid {

DisplayBridge& DisplayBridge::getInstance() {
    static DisplayBridge instance;
    return instance;
}

DisplayBridge::DisplayBridge() = default;

DisplayBridge::~DisplayBridge() {
    onSurfaceDestroyed();
}

void DisplayBridge::onSurfaceCreated(JNIEnv* env, jobject surface, int width, int height) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ != nullptr) {
        linuxdroid::gui::GuiHost::getInstance().destroyNativeWindow();
        ANativeWindow_release(window_);
        window_ = nullptr;
    }

    if (surface != nullptr) {
        window_ = ANativeWindow_fromSurface(env, surface);
        if (window_ != nullptr) {
            width_ = width;
            height_ = height;
            ANativeWindow_setBuffersGeometry(window_, width_, height_, format_);
            LOGI("ANativeWindow attached successfully: %dx%d", width_, height_);
            linuxdroid::gui::GuiHost::getInstance().setNativeWindow(window_, width_, height_);
        } else {
            LOGE("ANativeWindow_fromSurface failed");
        }
    }
}

void DisplayBridge::onSurfaceChanged(JNIEnv* env, jobject surface, int width, int height, int format) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (surface == nullptr) {
        onSurfaceDestroyed();
        return;
    }

    if (window_ == nullptr) {
        window_ = ANativeWindow_fromSurface(env, surface);
    }

    if (window_ != nullptr) {
        width_ = width;
        height_ = height;
        format_ = (format != 0) ? format : WINDOW_FORMAT_RGBA_8888;
        ANativeWindow_setBuffersGeometry(window_, width_, height_, format_);
        LOGI("ANativeWindow changed: %dx%d, format=%d", width_, height_, format_);
        linuxdroid::gui::GuiHost::getInstance().changeNativeWindow(window_, width_, height_, format_);
    } else {
        LOGE("ANativeWindow_fromSurface failed in onSurfaceChanged");
    }
}

void DisplayBridge::onSurfaceDestroyed() {
    std::lock_guard<std::mutex> lock(mutex_);
    LOGI("onSurfaceDestroyed: synchronizing teardown with GUI host and draining buffers");
    linuxdroid::gui::GuiHost::getInstance().destroyNativeWindow();
    if (window_ != nullptr) {
        LOGI("Releasing ANativeWindow: %p", window_);
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
}

bool DisplayBridge::isReady() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return window_ != nullptr;
}

bool DisplayBridge::lockBuffer(ANativeWindow_Buffer* outBuffer) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ == nullptr || outBuffer == nullptr) return false;
    return ANativeWindow_lock(window_, outBuffer, nullptr) == 0;
}

bool DisplayBridge::unlockAndPost() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ == nullptr) return false;
    return ANativeWindow_unlockAndPost(window_) == 0;
}

} // namespace linuxdroid

