#pragma once

#include <atomic>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include <thread>
#include <android/native_window.h>
#include <libweston/libweston.h>
#include <libweston/desktop.h>

struct wl_display;
struct wl_event_source;
struct weston_compositor;
struct weston_log_context;
struct weston_output;
struct linuxdroid_backend;
struct linuxdroid_head;
struct linuxdroid_vsync_bridge;

namespace linuxdroid {
class DesktopShellClient;

namespace gui {

enum class LifecycleState {
    STOPPED = 0,
    STARTING = 1,
    RUNNING = 2,
    STOPPING = 3
};

const char* lifecycleStateToString(LifecycleState state);

class GuiHost {
public:
    static GuiHost& getInstance();

    GuiHost();
    ~GuiHost();

    GuiHost(const GuiHost&) = delete;
    GuiHost& operator=(const GuiHost&) = delete;

    bool start();
    bool stop();
    bool isRunning() const;
    LifecycleState getState() const;

    void setNativeWindow(struct ANativeWindow* window, int width, int height);
    void changeNativeWindow(struct ANativeWindow* window, int width, int height, int format);
    void destroyNativeWindow();

    void processQueuedInput();
    void resetInput();
    void setOutputScale(int32_t scale);
    int32_t getOutputScale() const;
    void processPendingWindowActions();
    void enqueueWindowAction(uint64_t window_id, const std::string& action);
    void enqueueWindowAction(void* handle, const std::string& action, int32_t p1 = 0, int32_t p2 = 0);
    bool restartDesktopShell();

    // Compositor Desktop API callback handlers
    static void handleSurfaceAdded(struct weston_desktop_surface* surface, void* user_data);
    static void handleSurfaceRemoved(struct weston_desktop_surface* surface, void* user_data);
    static void handleSurfaceCommitted(struct weston_desktop_surface* surface, struct weston_coord_surface buf_offset, void* user_data);
    static void handleSurfaceMove(struct weston_desktop_surface* surface, struct weston_seat* seat, uint32_t serial, void* user_data);
    static void handleSurfaceResize(struct weston_desktop_surface* surface, struct weston_seat* seat, uint32_t serial, enum weston_desktop_surface_edge edges, void* user_data);
    static void handleFullscreenRequested(struct weston_desktop_surface* surface, bool fullscreen, struct weston_output* output, void* user_data);
    static void handleMaximizedRequested(struct weston_desktop_surface* surface, bool maximized, void* user_data);
    static void handleMinimizedRequested(struct weston_desktop_surface* surface, void* user_data);
    static void handlePingTimeout(struct weston_desktop_client* client, void* user_data);
    static void handlePong(struct weston_desktop_client* client, void* user_data);

private:
    void workerMain();

    mutable std::mutex lifecycle_mutex_;
    std::condition_variable init_cv_;
    std::atomic<LifecycleState> state_{LifecycleState::STOPPED};

    std::thread worker_thread_;
    int wake_fd_ = -1;

    // Native Wayland / libweston runtime resources
    struct wl_display* display_ = nullptr;
    struct wl_event_source* wake_source_ = nullptr;
    struct wl_event_source* sigchld_source_ = nullptr;
    struct weston_log_context* log_ctx_ = nullptr;
    struct weston_compositor* compositor_ = nullptr;
    struct linuxdroid_backend* backend_ = nullptr;
    struct linuxdroid_head* head_ = nullptr;
    struct weston_output* output_ = nullptr;
    struct linuxdroid_vsync_bridge* vsync_bridge_ = nullptr;
    struct wl_event_source* vsync_source_ = nullptr;

    // Thread-safe window actions dispatched on compositor event loop
    struct PendingWindowAction {
        uint64_t window_id{0};
        void* handle{nullptr};
        std::string action;
        int32_t p1{0};
        int32_t p2{0};
    };
    mutable std::mutex action_mutex_;
    std::vector<PendingWindowAction> pending_actions_;

    // Weston Desktop & Layers (Phase 7 Desktop Shell & Toplevel Management)
    struct weston_desktop* desktop_ = nullptr;
    struct weston_layer background_layer_{};
    struct weston_layer desktop_layer_{};
    struct weston_layer panel_layer_{};
    uint32_t window_cascade_count_{0};

    // Client Desktop Shell instance
    std::unique_ptr<DesktopShellClient> shell_client_;

    mutable std::mutex window_mutex_;
    struct ANativeWindow* native_window_ = nullptr;
    int window_width_ = 0;
    int window_height_ = 0;
    int window_format_ = 0;
    std::atomic<int32_t> output_scale_{1};

    bool init_success_ = false;
};

} // namespace gui
} // namespace linuxdroid
