#include "input_translator.h"
#include "input_bridge.h"
#include "linuxdroid_backend.h"
#include "gui_host.h"

#include <wayland-server.h>
#include <wayland-client.h>
#include <libweston/libweston.h>
#include <libweston/weston-log.h>
#include <xkbcommon/xkbcommon.h>
#include <android/keycodes.h>
#include <linux/input-event-codes.h>
#include <sys/socket.h>
#include <sys/mman.h>
#include <unistd.h>

#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstring>

using namespace linuxdroid;

static void test_keycode_translation() {
    printf("[RUN] test_keycode_translation\n");

    // Letters
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_A) == KEY_A);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_Z) == KEY_Z);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_M) == KEY_M);

    // Digits
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_0) == KEY_0);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_9) == KEY_9);

    // Navigation & editing
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_ENTER) == KEY_ENTER);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_SPACE) == KEY_SPACE);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_DEL) == KEY_BACKSPACE);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_TAB) == KEY_TAB);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_ESCAPE) == KEY_ESC);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_DPAD_UP) == KEY_UP);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_DPAD_DOWN) == KEY_DOWN);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_DPAD_LEFT) == KEY_LEFT);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_DPAD_RIGHT) == KEY_RIGHT);

    // Modifiers
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_SHIFT_LEFT) == KEY_LEFTSHIFT);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_CTRL_LEFT) == KEY_LEFTCTRL);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_ALT_LEFT) == KEY_LEFTALT);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_CAPS_LOCK) == KEY_CAPSLOCK);

    // Symbols
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_MINUS) == KEY_MINUS);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_EQUALS) == KEY_EQUAL);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_SLASH) == KEY_SLASH);

    // Android system & media keys
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_BACK) == KEY_BACK);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_WINDOW) == KEY_LEFTMETA);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_SYSRQ) == KEY_SYSRQ);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_MENU) == KEY_COMPOSE);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_VOLUME_UP) == KEY_VOLUMEUP);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_VOLUME_DOWN) == KEY_VOLUMEDOWN);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_VOLUME_MUTE) == KEY_MUTE);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_MEDIA_PLAY_PAUSE) == KEY_PLAYPAUSE);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_MEDIA_STOP) == KEY_STOPCD);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_MEDIA_NEXT) == KEY_NEXTSONG);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_MEDIA_PREVIOUS) == KEY_PREVIOUSSONG);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_BRIGHTNESS_UP) == KEY_BRIGHTNESSUP);
    assert(InputTranslator::androidKeycodeToLinux(AKEYCODE_BRIGHTNESS_DOWN) == KEY_BRIGHTNESSDOWN);

    // Unknown keycode
    assert(InputTranslator::androidKeycodeToLinux(99999) == KEY_RESERVED);
    assert(InputTranslator::androidKeycodeToLinux(-1) == KEY_RESERVED);

    printf("[PASS] test_keycode_translation\n");
}

static void test_button_and_coordinate_translation() {
    printf("[RUN] test_button_and_coordinate_translation\n");

    // Mouse buttons
    assert(InputTranslator::androidButtonToLinux(1) == BTN_LEFT);
    assert(InputTranslator::androidButtonToLinux(2) == BTN_RIGHT);
    assert(InputTranslator::androidButtonToLinux(4) == BTN_MIDDLE);
    assert(InputTranslator::androidButtonToLinux(8) == BTN_SIDE);
    assert(InputTranslator::androidButtonToLinux(16) == BTN_EXTRA);

    // Coordinate clamping
    assert(InputTranslator::clampCoordinate(100.0f, 1920) == 100.0);
    assert(InputTranslator::clampCoordinate(-50.0f, 1920) == 0.0);
    assert(InputTranslator::clampCoordinate(2500.0f, 1920) == 1920.0);
    assert(InputTranslator::clampCoordinate(NAN, 1920) == 0.0);
    assert(InputTranslator::clampCoordinate(INFINITY, 1920) == 0.0);
    assert(InputTranslator::clampCoordinate(-INFINITY, 1920) == 0.0);

    // Scroll axis inversion
    assert(InputTranslator::translateScrollAxis(1.0f) < 0.0); // Forward scroll becomes negative
    assert(InputTranslator::translateScrollAxis(-1.0f) > 0.0);

    printf("[PASS] test_button_and_coordinate_translation\n");
}

static void test_input_bridge_queue_and_overflow() {
    printf("[RUN] test_input_bridge_queue_and_overflow\n");

    auto& bridge = InputBridge::getInstance();
    bridge.clear();
    assert(bridge.getPendingEventCount() == 0);

    // Push touch down
    bridge.sendTouchEvent(0, 0, 100.0f, 200.0f, 1.0f);
    assert(bridge.getPendingEventCount() == 1);

    // Push mouse move
    bridge.sendMouseEvent(2, 0, 105.0f, 205.0f, 0.0f, 0.0f);
    assert(bridge.getPendingEventCount() == 2);

    // Push key down
    bridge.sendKeyEvent(AKEYCODE_A, true, 0, 'a');
    assert(bridge.getPendingEventCount() == 3);

    // Pop and verify FIFO order
    NativeInputEvent e1, e2, e3;
    assert(bridge.popEvent(&e1));
    assert(e1.type == InputEventType::TOUCH_DOWN);
    assert(e1.x == 100.0f && e1.y == 200.0f);

    assert(bridge.popEvent(&e2));
    assert(e2.type == InputEventType::MOUSE_MOVE);

    assert(bridge.popEvent(&e3));
    assert(e3.type == InputEventType::KEY_PRESS);
    assert(e3.keyCode == AKEYCODE_A);

    assert(bridge.getPendingEventCount() == 0);

    // Push over MAX_QUEUE_SIZE (512) motion events to verify bounded behavior
    for (int i = 0; i < 600; ++i) {
        bridge.sendTouchEvent(2, 0, static_cast<float>(i), static_cast<float>(i), 1.0f);
    }
    assert(bridge.getPendingEventCount() <= 512);
    assert(bridge.getDroppedEventCount() > 0);

    bridge.clear();
    assert(bridge.getPendingEventCount() == 0);

    // Test resetInput
    bridge.sendTouchEvent(0, 0, 50.0f, 50.0f, 1.0f);
    bridge.sendKeyEvent(AKEYCODE_SHIFT_LEFT, true, 0, 0);
    assert(bridge.getPendingEventCount() == 2);
    bridge.resetInput();
    assert(bridge.getPendingEventCount() == 1);
    NativeInputEvent resetEvt;
    assert(bridge.popEvent(&resetEvt));
    assert(resetEvt.type == InputEventType::RESET_INPUT);

    printf("[PASS] test_input_bridge_queue_and_overflow\n");
}

static void test_seat_and_backend_lifecycle() {
    printf("[RUN] test_seat_and_backend_lifecycle\n");

    struct wl_display *display = wl_display_create();
    assert(display != nullptr);

    struct weston_log_context *log_ctx = weston_log_ctx_create();
    assert(log_ctx != nullptr);

    struct weston_compositor *compositor = weston_compositor_create(display, log_ctx, nullptr, nullptr);
    assert(compositor != nullptr);

    struct linuxdroid_backend_config config = {
        .refresh_mhz = 60000
    };
    struct linuxdroid_backend *backend = linuxdroid_backend_create(compositor, &config);
    assert(backend != nullptr);

    // Verify seat is initialized
    struct weston_seat *seat = linuxdroid_backend_get_seat(backend);
    assert(seat != nullptr);
    assert(seat->pointer_state != nullptr);
    assert(seat->keyboard_state != nullptr);
    assert(seat->touch_state != nullptr);

    // Verify touch device is created
    struct weston_touch_device *touch_dev = linuxdroid_backend_get_touch_device(backend);
    assert(touch_dev != nullptr);

    // Verify input reset is callable and idempotent
    linuxdroid_backend_reset_input(backend);
    linuxdroid_backend_reset_input(backend);

    // Destroy compositor (destroys backend and seat)
    weston_compositor_destroy(compositor);
    weston_log_ctx_destroy(log_ctx);
    wl_display_destroy(display);

    printf("[PASS] test_seat_and_backend_lifecycle\n");
}

static void test_synthetic_input_dispatch() {
    printf("[RUN] test_synthetic_input_dispatch\n");

    struct wl_display *display = wl_display_create();
    assert(display != nullptr);

    struct weston_log_context *log_ctx = weston_log_ctx_create();
    assert(log_ctx != nullptr);

    struct weston_compositor *compositor = weston_compositor_create(display, log_ctx, nullptr, nullptr);
    assert(compositor != nullptr);

    struct linuxdroid_backend_config config = { .refresh_mhz = 60000 };
    struct linuxdroid_backend *backend = linuxdroid_backend_create(compositor, &config);
    assert(backend != nullptr);

    struct weston_seat *seat = linuxdroid_backend_get_seat(backend);
    assert(seat != nullptr);
    struct weston_touch_device *touch_dev = linuxdroid_backend_get_touch_device(backend);
    assert(touch_dev != nullptr);

    struct timespec ts = { 1, 0 };

    // 1. Synthetic Keyboard Press & Release
    struct weston_key_event key_down = {};
    key_down.base.ts = ts;
    key_down.base.seat = seat;
    key_down.key = KEY_A;
    key_down.key_state = WL_KEYBOARD_KEY_STATE_PRESSED;
    key_down.key_update_state = STATE_UPDATE_AUTOMATIC;
    notify_key(&key_down);

    struct weston_key_event key_up = {};
    key_up.base.ts = ts;
    key_up.base.seat = seat;
    key_up.key = KEY_A;
    key_up.key_state = WL_KEYBOARD_KEY_STATE_RELEASED;
    key_up.key_update_state = STATE_UPDATE_AUTOMATIC;
    notify_key(&key_up);

    // 2. Synthetic Pointer Motion & Button & Axis
    struct weston_coord_global pos = { .c = { .x = 100.0, .y = 150.0 } };
    struct weston_pointer_motion_event motion_event = {};
    motion_event.base.ts = ts;
    motion_event.base.seat = seat;
    motion_event.mask = WESTON_POINTER_MOTION_ABS;
    motion_event.abs = pos;
    notify_motion(&motion_event);
    notify_pointer_frame(seat);

    struct weston_pointer_button_event btn_event = {};
    btn_event.base.ts = ts;
    btn_event.base.seat = seat;
    btn_event.button = BTN_LEFT;
    btn_event.button_state = WL_POINTER_BUTTON_STATE_PRESSED;
    notify_button(&btn_event);
    notify_pointer_frame(seat);

    struct weston_pointer_axis_event axis_ev = {};
    axis_ev.base.ts = ts;
    axis_ev.base.seat = seat;
    axis_ev.axis = WL_POINTER_AXIS_VERTICAL_SCROLL;
    axis_ev.value = 10.0;
    notify_axis(&axis_ev);
    notify_pointer_frame(seat);

    // 3. Synthetic Touch Down, Motion, Up, Cancel
    struct weston_touch_event touch_down = {};
    touch_down.base.ts = ts;
    touch_down.base.seat = seat;
    touch_down.device = touch_dev;
    touch_down.touch_type = WL_TOUCH_DOWN;
    touch_down.touch_id = 0;
    touch_down.pos = pos;
    notify_touch(&touch_down);
    notify_touch_frame(touch_dev);

    struct weston_touch_event touch_up = {};
    touch_up.base.ts = ts;
    touch_up.base.seat = seat;
    touch_up.device = touch_dev;
    touch_up.touch_type = WL_TOUCH_UP;
    touch_up.touch_id = 0;
    touch_up.pos = pos;
    notify_touch(&touch_up);
    notify_touch_frame(touch_dev);

    notify_touch_cancel(touch_dev);

    weston_compositor_destroy(compositor);
    weston_log_ctx_destroy(log_ctx);
    wl_display_destroy(display);

    printf("[PASS] test_synthetic_input_dispatch\n");
}

struct ClientTestState {
    bool keymap_received = false;
    bool keyboard_enter = false;
    uint32_t last_key = 0;
    xkb_keysym_t last_keysym = 0;
    uint32_t key_press_count = 0;
    uint32_t key_release_count = 0;

    bool pointer_enter = false;
    double last_pointer_x = 0;
    double last_pointer_y = 0;
    uint32_t last_button = 0;
    uint32_t button_press_count = 0;
    double last_axis_val = 0;

    bool touch_down_received = false;
    int32_t last_touch_id = -1;
    double last_touch_x = 0;
    double last_touch_y = 0;
    bool touch_up_received = false;
    bool touch_cancel_received = false;

    struct xkb_context *xkb_ctx = nullptr;
    struct xkb_keymap *xkb_keymap = nullptr;
    struct xkb_state *xkb_state = nullptr;
};

static void test_kb_keymap(void *data, struct wl_keyboard *, uint32_t format, int32_t fd, uint32_t size) {
    auto *state = static_cast<ClientTestState*>(data);
    if (format == WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1) {
        char *map_str = static_cast<char*>(mmap(nullptr, size, PROT_READ, MAP_SHARED, fd, 0));
        if (map_str != MAP_FAILED) {
            state->xkb_keymap = xkb_keymap_new_from_string(state->xkb_ctx, map_str,
                                                           XKB_KEYMAP_FORMAT_TEXT_V1,
                                                           XKB_KEYMAP_COMPILE_NO_FLAGS);
            munmap(map_str, size);
            if (state->xkb_keymap) {
                state->xkb_state = xkb_state_new(state->xkb_keymap);
                state->keymap_received = true;
            }
        }
    }
    close(fd);
}

static void test_kb_enter(void *data, struct wl_keyboard *, uint32_t, struct wl_surface *, struct wl_array *) {
    auto *state = static_cast<ClientTestState*>(data);
    state->keyboard_enter = true;
}

static void test_kb_leave(void *, struct wl_keyboard *, uint32_t, struct wl_surface *) {}

static void test_kb_key(void *data, struct wl_keyboard *, uint32_t, uint32_t, uint32_t key, uint32_t key_state) {
    auto *state = static_cast<ClientTestState*>(data);
    state->last_key = key;
    if (state->xkb_state) {
        state->last_keysym = xkb_state_key_get_one_sym(state->xkb_state, key + 8);
    }
    if (key_state == WL_KEYBOARD_KEY_STATE_PRESSED) {
        state->key_press_count++;
    } else {
        state->key_release_count++;
    }
}

static void test_kb_mods(void *data, struct wl_keyboard *, uint32_t, uint32_t depressed, uint32_t latched, uint32_t locked, uint32_t group) {
    auto *state = static_cast<ClientTestState*>(data);
    if (state->xkb_state) {
        xkb_state_update_mask(state->xkb_state, depressed, latched, locked, 0, 0, group);
    }
}

static void test_kb_repeat(void *, struct wl_keyboard *, int32_t, int32_t) {}

static const struct wl_keyboard_listener test_keyboard_listener = {
    .keymap = test_kb_keymap,
    .enter = test_kb_enter,
    .leave = test_kb_leave,
    .key = test_kb_key,
    .modifiers = test_kb_mods,
    .repeat_info = test_kb_repeat,
};

static void test_ptr_enter(void *data, struct wl_pointer *, uint32_t, struct wl_surface *, wl_fixed_t sx, wl_fixed_t sy) {
    auto *state = static_cast<ClientTestState*>(data);
    state->pointer_enter = true;
    state->last_pointer_x = wl_fixed_to_double(sx);
    state->last_pointer_y = wl_fixed_to_double(sy);
}

static void test_ptr_leave(void *, struct wl_pointer *, uint32_t, struct wl_surface *) {}
static void test_ptr_motion(void *data, struct wl_pointer *, uint32_t, wl_fixed_t sx, wl_fixed_t sy) {
    auto *state = static_cast<ClientTestState*>(data);
    state->last_pointer_x = wl_fixed_to_double(sx);
    state->last_pointer_y = wl_fixed_to_double(sy);
}
static void test_ptr_button(void *data, struct wl_pointer *, uint32_t, uint32_t, uint32_t button, uint32_t btn_state) {
    auto *state = static_cast<ClientTestState*>(data);
    state->last_button = button;
    if (btn_state == WL_POINTER_BUTTON_STATE_PRESSED) {
        state->button_press_count++;
    }
}
static void test_ptr_axis(void *data, struct wl_pointer *, uint32_t, uint32_t, wl_fixed_t value) {
    auto *state = static_cast<ClientTestState*>(data);
    state->last_axis_val = wl_fixed_to_double(value);
}
static void test_ptr_frame(void *, struct wl_pointer *) {}
static void test_ptr_axis_source(void *, struct wl_pointer *, uint32_t) {}
static void test_ptr_axis_stop(void *, struct wl_pointer *, uint32_t, uint32_t) {}
static void test_ptr_axis_discrete(void *, struct wl_pointer *, uint32_t, int32_t) {}

static const struct wl_pointer_listener test_pointer_listener = {
    .enter = test_ptr_enter,
    .leave = test_ptr_leave,
    .motion = test_ptr_motion,
    .button = test_ptr_button,
    .axis = test_ptr_axis,
    .frame = test_ptr_frame,
    .axis_source = test_ptr_axis_source,
    .axis_stop = test_ptr_axis_stop,
    .axis_discrete = test_ptr_axis_discrete,
};

static void test_touch_down(void *data, struct wl_touch *, uint32_t, uint32_t, struct wl_surface *, int32_t id, wl_fixed_t x, wl_fixed_t y) {
    auto *state = static_cast<ClientTestState*>(data);
    state->touch_down_received = true;
    state->last_touch_id = id;
    state->last_touch_x = wl_fixed_to_double(x);
    state->last_touch_y = wl_fixed_to_double(y);
}
static void test_touch_up(void *data, struct wl_touch *, uint32_t, uint32_t, int32_t id) {
    auto *state = static_cast<ClientTestState*>(data);
    state->touch_up_received = true;
    state->last_touch_id = id;
}
static void test_touch_motion(void *data, struct wl_touch *, uint32_t, int32_t id, wl_fixed_t x, wl_fixed_t y) {
    auto *state = static_cast<ClientTestState*>(data);
    state->last_touch_id = id;
    state->last_touch_x = wl_fixed_to_double(x);
    state->last_touch_y = wl_fixed_to_double(y);
}
static void test_touch_frame(void *, struct wl_touch *) {}
static void test_touch_cancel(void *data, struct wl_touch *) {
    auto *state = static_cast<ClientTestState*>(data);
    state->touch_cancel_received = true;
}

static const struct wl_touch_listener test_touch_listener = {
    .down = test_touch_down,
    .up = test_touch_up,
    .motion = test_touch_motion,
    .frame = test_touch_frame,
    .cancel = test_touch_cancel,
};

struct TestRegistryContext {
    struct wl_compositor *compositor = nullptr;
    struct wl_seat *seat = nullptr;
    struct wl_keyboard *keyboard = nullptr;
    struct wl_pointer *pointer = nullptr;
    struct wl_touch *touch = nullptr;
    ClientTestState *state = nullptr;
};

static void test_seat_capabilities(void *data, struct wl_seat *seat, uint32_t caps) {
    auto *ctx = static_cast<TestRegistryContext*>(data);
    if ((caps & WL_SEAT_CAPABILITY_KEYBOARD) && !ctx->keyboard) {
        ctx->keyboard = wl_seat_get_keyboard(seat);
        wl_keyboard_add_listener(ctx->keyboard, &test_keyboard_listener, ctx->state);
    }
    if ((caps & WL_SEAT_CAPABILITY_POINTER) && !ctx->pointer) {
        ctx->pointer = wl_seat_get_pointer(seat);
        wl_pointer_add_listener(ctx->pointer, &test_pointer_listener, ctx->state);
    }
    if ((caps & WL_SEAT_CAPABILITY_TOUCH) && !ctx->touch) {
        ctx->touch = wl_seat_get_touch(seat);
        wl_touch_add_listener(ctx->touch, &test_touch_listener, ctx->state);
    }
}

static void test_seat_name(void *, struct wl_seat *, const char *) {}

static const struct wl_seat_listener test_seat_listener = {
    .capabilities = test_seat_capabilities,
    .name = test_seat_name,
};

static void test_registry_global(void *data, struct wl_registry *reg, uint32_t name, const char *interface, uint32_t version) {
    auto *ctx = static_cast<TestRegistryContext*>(data);
    if (strcmp(interface, wl_compositor_interface.name) == 0) {
        ctx->compositor = static_cast<struct wl_compositor*>(
            wl_registry_bind(reg, name, &wl_compositor_interface, version < 4 ? version : 4));
    } else if (strcmp(interface, wl_seat_interface.name) == 0) {
        ctx->seat = static_cast<struct wl_seat*>(
            wl_registry_bind(reg, name, &wl_seat_interface, version < 7 ? version : 7));
        wl_seat_add_listener(ctx->seat, &test_seat_listener, ctx);
    }
}
static void test_registry_global_remove(void *, struct wl_registry *, uint32_t) {}

static const struct wl_registry_listener test_registry_listener = {
    .global = test_registry_global,
    .global_remove = test_registry_global_remove,
};

static void test_wayland_client_input_delivery() {
    printf("[RUN] test_wayland_client_input_delivery\n");

    if (!getenv("XDG_RUNTIME_DIR")) {
        setenv("XDG_RUNTIME_DIR", "/tmp", 0);
    }
    if (!getenv("XKB_CONFIG_ROOT")) {
        if (access("/usr/share/X11/xkb", R_OK) == 0) {
            setenv("XKB_CONFIG_ROOT", "/usr/share/X11/xkb", 0);
        } else if (access("/data/data/com.linuxdroid/files/xkb", R_OK) == 0) {
            setenv("XKB_CONFIG_ROOT", "/data/data/com.linuxdroid/files/xkb", 0);
        }
    }

    struct wl_display *server_display = wl_display_create();
    assert(server_display != nullptr);

    wl_display_init_shm(server_display);

    struct weston_log_context *log_ctx = weston_log_ctx_create();
    assert(log_ctx != nullptr);

    struct weston_compositor *compositor = weston_compositor_create(server_display, log_ctx, nullptr, nullptr);
    assert(compositor != nullptr);

    struct linuxdroid_backend_config config = { .refresh_mhz = 60000 };
    struct linuxdroid_backend *backend = linuxdroid_backend_create(compositor, &config);
    assert(backend != nullptr);

    struct weston_seat *seat = linuxdroid_backend_get_seat(backend);
    assert(seat != nullptr);
    struct weston_touch_device *touch_dev = linuxdroid_backend_get_touch_device(backend);
    assert(touch_dev != nullptr);

    int sv[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, sv) == 0);

    struct wl_client *server_client = wl_client_create(server_display, sv[0]);
    assert(server_client != nullptr);

    struct wl_display *client_display = wl_display_connect_to_fd(sv[1]);
    assert(client_display != nullptr);

    ClientTestState cstate;
    cstate.xkb_ctx = xkb_context_new(XKB_CONTEXT_NO_FLAGS);
    assert(cstate.xkb_ctx != nullptr);

    TestRegistryContext reg_ctx;
    reg_ctx.state = &cstate;

    struct wl_registry *registry = wl_display_get_registry(client_display);
    wl_registry_add_listener(registry, &test_registry_listener, &reg_ctx);

    wl_display_flush_clients(server_display);
    wl_display_roundtrip(client_display);
    wl_display_flush_clients(server_display);
    wl_display_roundtrip(client_display);

    assert(reg_ctx.compositor != nullptr);
    assert(reg_ctx.seat != nullptr);
    assert(reg_ctx.keyboard != nullptr);
    assert(reg_ctx.pointer != nullptr);
    assert(reg_ctx.touch != nullptr);

    // Verify keymap received and parsed
    assert(cstate.keymap_received);
    assert(cstate.xkb_keymap != nullptr);
    assert(cstate.xkb_state != nullptr);

    // Create client surface
    struct wl_surface *c_surface = wl_compositor_create_surface(reg_ctx.compositor);
    assert(c_surface != nullptr);
    wl_surface_commit(c_surface);

    wl_display_flush_clients(server_display);
    wl_display_roundtrip(client_display);

    // Locate client's weston_surface via client-side proxy object ID
    uint32_t surf_id = wl_proxy_get_id(reinterpret_cast<struct wl_proxy*>(c_surface));
    struct wl_resource *res = wl_client_get_object(server_client, surf_id);
    assert(res != nullptr);
    struct weston_surface *w_surface = static_cast<struct weston_surface*>(wl_resource_get_user_data(res));
    assert(w_surface != nullptr);

    if (seat->keyboard_state) {
        weston_keyboard_set_focus(seat->keyboard_state, w_surface);
        wl_display_flush_clients(server_display);
        wl_display_roundtrip(client_display);
        assert(cstate.keyboard_enter);
    }

    struct timespec ts = { 1, 0 };

    // 1. Deliver Key Press & Release through libweston
    struct weston_key_event key_down = {};
    key_down.base.ts = ts;
    key_down.base.seat = seat;
    key_down.key = KEY_A;
    key_down.key_state = WL_KEYBOARD_KEY_STATE_PRESSED;
    key_down.key_update_state = STATE_UPDATE_AUTOMATIC;
    notify_key(&key_down);

    struct weston_key_event key_up = {};
    key_up.base.ts = ts;
    key_up.base.seat = seat;
    key_up.key = KEY_A;
    key_up.key_state = WL_KEYBOARD_KEY_STATE_RELEASED;
    key_up.key_update_state = STATE_UPDATE_AUTOMATIC;
    notify_key(&key_up);

    wl_display_flush_clients(server_display);
    wl_display_roundtrip(client_display);

    assert(cstate.last_key == KEY_A);
    assert(cstate.last_keysym == XKB_KEY_a);
    assert(cstate.key_press_count == 1);
    assert(cstate.key_release_count == 1);

    // 2. Deliver Touch down, up, and cancel through libweston
    struct weston_coord_global pos = { .c = { .x = 100.0, .y = 150.0 } };
    struct weston_touch_event touch_down = {};
    touch_down.base.ts = ts;
    touch_down.base.seat = seat;
    touch_down.device = touch_dev;
    touch_down.touch_type = WL_TOUCH_DOWN;
    touch_down.touch_id = 1;
    touch_down.pos = pos;
    notify_touch(&touch_down);
    notify_touch_frame(touch_dev);

    struct weston_touch_event touch_up = {};
    touch_up.base.ts = ts;
    touch_up.base.seat = seat;
    touch_up.device = touch_dev;
    touch_up.touch_type = WL_TOUCH_UP;
    touch_up.touch_id = 1;
    touch_up.pos = pos;
    notify_touch(&touch_up);
    notify_touch_frame(touch_dev);

    notify_touch_cancel(touch_dev);

    wl_display_flush_clients(server_display);
    wl_display_roundtrip(client_display);

    // Cleanup client resources
    wl_surface_destroy(c_surface);
    wl_keyboard_destroy(reg_ctx.keyboard);
    wl_pointer_destroy(reg_ctx.pointer);
    wl_touch_destroy(reg_ctx.touch);
    wl_seat_destroy(reg_ctx.seat);
    wl_compositor_destroy(reg_ctx.compositor);
    wl_registry_destroy(registry);
    wl_display_disconnect(client_display);

    if (cstate.xkb_state) xkb_state_unref(cstate.xkb_state);
    if (cstate.xkb_keymap) xkb_keymap_unref(cstate.xkb_keymap);
    if (cstate.xkb_ctx) xkb_context_unref(cstate.xkb_ctx);

    // Cleanup server compositor
    weston_compositor_destroy(compositor);
    weston_log_ctx_destroy(log_ctx);
    wl_display_destroy(server_display);

    printf("[PASS] test_wayland_client_input_delivery\n");
}

int main() {
    printf("=== LinuxDroid Phase 6 Native Input Integration Test Suite ===\n");
    test_keycode_translation();
    test_button_and_coordinate_translation();
    test_input_bridge_queue_and_overflow();
    test_seat_and_backend_lifecycle();
    test_synthetic_input_dispatch();
    test_wayland_client_input_delivery();
    printf("=== All Phase 6 Input Integration Tests PASSED! ===\n");
    return 0;
}
