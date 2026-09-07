cmake_minimum_required(VERSION 3.22.1)

project(
    linuxdroid_bridge
    VERSION 1.0.0
    LANGUAGES C CXX
)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Compile flags
add_compile_options(
    -Wall
    -Wextra
    -fexceptions
    -frtti
    -DANDROID
    -Wno-unguarded-availability
    -Wno-unguarded-availability-new
    -Wno-missing-field-initializers
    -Wno-unused-parameter
    -Wno-unused-variable
)

get_filename_component(PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(WESTON_PREFIX "${PROJECT_ROOT}/native/weston/prefix")
set(ARM64_JNILIBS "${PROJECT_ROOT}/app/src/main/jniLibs/arm64-v8a")

# Main bridge library
add_library(
    linuxdroid_bridge
    SHARED
    linuxdroid_bridge.cpp
    process_manager.cpp
    filesystem_utils.cpp
    gpu_detector.cpp
    display_bridge.cpp
    input_bridge.cpp
    input_translator.cpp
    audio_bridge.cpp
    wayland_foundation_test.cpp
    android_presentation.cpp
    linuxdroid_backend.c
    desktop_window_tracker.cpp
    desktop_state.cpp
    desktop_session.cpp
    window_model.cpp
    window_manager.cpp
    ui_painter.cpp
    desktop_shell_client.cpp
    protocol/xdg-shell-protocol.c
    vsync_bridge.cpp
    gui_host.cpp
)

# Imported Wayland and Weston native dependency stack
add_library(wayland-server SHARED IMPORTED)
set_target_properties(wayland-server PROPERTIES IMPORTED_LOCATION ${ARM64_JNILIBS}/libwayland-server.so)

# Dynamically locate active libweston shared library in jniLibs
file(GLOB LIBWESTON_SO_FILES "${ARM64_JNILIBS}/libweston-*.so")
if(NOT LIBWESTON_SO_FILES)
    message(FATAL_ERROR "No libweston-*.so found in ${ARM64_JNILIBS}")
endif()
list(GET LIBWESTON_SO_FILES 0 LIBWESTON_ACTIVE_SO)
message(STATUS "Using libweston artifact: ${LIBWESTON_ACTIVE_SO}")

add_library(weston SHARED IMPORTED)
set_target_properties(weston PROPERTIES IMPORTED_LOCATION ${LIBWESTON_ACTIVE_SO})

add_library(wayland-client SHARED IMPORTED)
set_target_properties(wayland-client PROPERTIES IMPORTED_LOCATION ${ARM64_JNILIBS}/libwayland-client.so)

add_library(pixman-1 SHARED IMPORTED)
set_target_properties(pixman-1 PROPERTIES IMPORTED_LOCATION ${ARM64_JNILIBS}/libpixman-1.so)

add_library(xkbcommon SHARED IMPORTED)
set_target_properties(xkbcommon PROPERTIES IMPORTED_LOCATION ${ARM64_JNILIBS}/libxkbcommon.so)

# Dynamically locate libweston include directories in prefix
file(GLOB LIBWESTON_INC_DIRS "${WESTON_PREFIX}/include/libweston-*")

# Link Android system and graphics/audio libraries + Wayland stack
target_link_libraries(
    linuxdroid_bridge
    android
    log
    jnigraphics
    EGL
    GLESv2
    GLESv3
    wayland-server
    wayland-client
    weston
    pixman-1
    xkbcommon
)

target_include_directories(
    linuxdroid_bridge
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/protocol
    ${WESTON_PREFIX}/include
    ${WESTON_PREFIX}/include/pixman-1
    ${WESTON_PREFIX}/include/libdrm
    ${LIBWESTON_INC_DIRS}
)

# gl-renderer: pre-built Weston GLES renderer plugin artifact.
# Source build removed: vendor/weston submodule has been retired.
# Weston (and its gl-renderer plugin) is a Linux rootfs dependency supplied
# by the Linux distribution package manager, not an Android project build target.
# The pre-built libgl-renderer.so artifact resides in jniLibs and is packaged
# into the APK via the standard jniLibs mechanism.
add_library(gl-renderer SHARED IMPORTED)
set_target_properties(gl-renderer PROPERTIES IMPORTED_LOCATION ${ARM64_JNILIBS}/libgl-renderer.so)

# Standalone native GUI host test executable (minimal Phase 3 validation)
add_executable(
    gui_host_test
    gui_host_test.cpp
    input_translator.cpp
    input_bridge.cpp
    android_presentation.cpp
    linuxdroid_backend.c
    desktop_window_tracker.cpp
    desktop_state.cpp
    desktop_session.cpp
    window_model.cpp
    window_manager.cpp
    ui_painter.cpp
    desktop_shell_client.cpp
    protocol/xdg-shell-protocol.c
    vsync_bridge.cpp
    gui_host.cpp
)

target_link_libraries(
    gui_host_test
    android
    log
    EGL
    GLESv2
    GLESv3
    wayland-server
    wayland-client
    weston
    pixman-1
    xkbcommon
)

target_include_directories(
    gui_host_test
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/protocol
    ${WESTON_PREFIX}/include
    ${WESTON_PREFIX}/include/pixman-1
    ${WESTON_PREFIX}/include/libdrm
    ${LIBWESTON_INC_DIRS}
)

# Standalone native Android presentation test executable (Phase 4 validation)
add_executable(
    android_presentation_test
    android_presentation_test.cpp
    android_presentation.cpp
)

target_link_libraries(
    android_presentation_test
    android
    log
    EGL
    GLESv2
    GLESv3
)

target_include_directories(
    android_presentation_test
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Standalone native Pixman renderer test executable (Phase 5 validation)
add_executable(
    pixman_renderer_test
    pixman_renderer_test.cpp
    android_presentation.cpp
)

target_link_libraries(
    pixman_renderer_test
    android
    log
    EGL
    GLESv2
    GLESv3
    pixman-1
)

target_include_directories(
    pixman_renderer_test
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${WESTON_PREFIX}/include/pixman-1
    ${WESTON_PREFIX}/include/libdrm
)

# Standalone native input integration test executable (Phase 6 validation)
add_executable(
    input_integration_test
    input_integration_test.cpp
    input_translator.cpp
    input_bridge.cpp
    android_presentation.cpp
    linuxdroid_backend.c
    desktop_window_tracker.cpp
    desktop_state.cpp
    desktop_session.cpp
    window_model.cpp
    window_manager.cpp
    ui_painter.cpp
    desktop_shell_client.cpp
    protocol/xdg-shell-protocol.c
    vsync_bridge.cpp
    gui_host.cpp
)

target_link_libraries(
    input_integration_test
    android
    log
    EGL
    GLESv2
    GLESv3
    wayland-server
    wayland-client
    weston
    pixman-1
    xkbcommon
)

target_include_directories(
    input_integration_test
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/protocol
    ${WESTON_PREFIX}/include
    ${WESTON_PREFIX}/include/pixman-1
    ${WESTON_PREFIX}/include/libdrm
    ${LIBWESTON_INC_DIRS}
)

# Standalone minimal Wayland input test client (Phase 6 Wayland client verification)
add_executable(
    wayland_input_test_client
    wayland_input_test_client.cpp
)

target_link_libraries(
    wayland_input_test_client
    android
    log
    wayland-client
    xkbcommon
)

target_include_directories(
    wayland_input_test_client
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${WESTON_PREFIX}/include
)

# Standalone minimal Wayland desktop shell executable (Phase 7 standalone execution)
add_executable(
    linuxdroid_desktop_shell
    desktop_shell_client.cpp
    desktop_window_tracker.cpp
    desktop_state.cpp
    desktop_session.cpp
    window_model.cpp
    window_manager.cpp
    ui_painter.cpp
    protocol/xdg-shell-protocol.c
)

target_compile_definitions(
    linuxdroid_desktop_shell
    PRIVATE
    BUILD_STANDALONE_SHELL=1
)

target_link_libraries(
    linuxdroid_desktop_shell
    android
    log
    wayland-client
    xkbcommon
)

target_include_directories(
    linuxdroid_desktop_shell
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/protocol
    ${WESTON_PREFIX}/include
)

# Standalone minimal Wayland desktop shell test executable (Phase 7 validation)
add_executable(
    desktop_shell_test
    desktop_shell_test.cpp
    desktop_shell_client.cpp
    desktop_window_tracker.cpp
    desktop_state.cpp
    desktop_session.cpp
    window_model.cpp
    window_manager.cpp
    ui_painter.cpp
    protocol/xdg-shell-protocol.c
    input_translator.cpp
    input_bridge.cpp
    android_presentation.cpp
    linuxdroid_backend.c
    vsync_bridge.cpp
    gui_host.cpp
)

target_link_libraries(
    desktop_shell_test
    android
    log
    EGL
    GLESv2
    GLESv3
    wayland-server
    wayland-client
    weston
    pixman-1
    xkbcommon
)

target_include_directories(
    desktop_shell_test
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/protocol
    ${WESTON_PREFIX}/include
    ${WESTON_PREFIX}/include/pixman-1
    ${WESTON_PREFIX}/include/libdrm
    ${LIBWESTON_INC_DIRS}
)

# Standalone native GLES renderer test executable (Phase 8 validation)
add_executable(
    gles_renderer_test
    gles_renderer_test.cpp
    android_presentation.cpp
    linuxdroid_backend.c
    vsync_bridge.cpp
)

target_link_libraries(
    gles_renderer_test
    android
    log
    EGL
    GLESv2
    GLESv3
    wayland-server
    weston
    pixman-1
)

target_include_directories(
    gles_renderer_test
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${WESTON_PREFIX}/include
    ${WESTON_PREFIX}/include/pixman-1
    ${WESTON_PREFIX}/include/libdrm
    ${LIBWESTON_INC_DIRS}
)

# Standalone native Frame Timing and Android VSync test executable (Phase 9 validation)
add_executable(
    frame_timing_test
    frame_timing_test.cpp
    vsync_bridge.cpp
    linuxdroid_backend.c
    android_presentation.cpp
)

target_link_libraries(
    frame_timing_test
    android
    log
    EGL
    GLESv2
    GLESv3
    wayland-server
    weston
    pixman-1
)

target_include_directories(
    frame_timing_test
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${WESTON_PREFIX}/include
    ${WESTON_PREFIX}/include/pixman-1
    ${WESTON_PREFIX}/include/libdrm
    ${LIBWESTON_INC_DIRS}
)

add_executable(
    gui_hardening_test
    gui_hardening_test.cpp
    vsync_bridge.cpp
    input_bridge.cpp
    input_translator.cpp
    desktop_window_tracker.cpp
    desktop_state.cpp
    desktop_session.cpp
    window_model.cpp
    window_manager.cpp
    ui_painter.cpp
    desktop_shell_client.cpp
    gui_host.cpp
    linuxdroid_backend.c
    android_presentation.cpp
    protocol/xdg-shell-protocol.c
)

target_link_libraries(
    gui_hardening_test
    PRIVATE
    android
    log
    EGL
    GLESv2
    GLESv3
    wayland-server
    wayland-client
    weston
    pixman-1
    xkbcommon
)

target_include_directories(
    gui_hardening_test
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${WESTON_PREFIX}/include
    ${WESTON_PREFIX}/include/pixman-1
    ${WESTON_PREFIX}/include/libdrm
    ${LIBWESTON_INC_DIRS}
)

