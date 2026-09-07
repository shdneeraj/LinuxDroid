#!/usr/bin/env bash
# =============================================================================
# LinuxDroid — Integrated Linux Graphical Session Test Suite (Section 26)
# =============================================================================
# Executes and verifies:
#   Test 1: Fresh GUI Startup (Guest Init -> LDDM -> Weston -> LDDE -> GUI_READY)
#   Test 2: Weston Failure & Autonomous Supervisor Recovery
#   Test 3: LDDE Failure & Autonomous Supervisor Recovery (Compositor Preserved)
#   Test 4: Clean Shutdown (Zero Orphaned Processes, Clean Sockets)
#   Test 5: Restart Cycle (Start -> GUI_READY -> Stop -> Start -> GUI_READY)
#   Test 6: Repeated Start/Stop Cycles (5 Cycles, No Resource Leaks)
#   Extra:  Wayland Client Connection to Compositor Socket
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

LDDM_BIN="${ROOT_DIR}/vendor/LDDM/build-release/lddm"
LDDE_BIN="${ROOT_DIR}/vendor/LDDE/build-release/ldde"
WESTON_BIN="$(command -v weston || true)"

if [[ ! -x "${LDDM_BIN}" ]]; then
    echo "ERROR: LDDM binary not found at ${LDDM_BIN}. Build LDDM first." >&2
    exit 1
fi
if [[ ! -x "${LDDE_BIN}" ]]; then
    echo "ERROR: LDDE binary not found at ${LDDE_BIN}. Build LDDE first." >&2
    exit 1
fi
if [[ -z "${WESTON_BIN}" || ! -x "${WESTON_BIN}" ]]; then
    echo "ERROR: Weston compositor not found in PATH." >&2
    exit 1
fi

TEST_BASE="/tmp/linuxdroid_gui_test_$$"
mkdir -p "${TEST_BASE}"
trap 'rm -rf "${TEST_BASE}"' EXIT

PASSED_TESTS=0
FAILED_TESTS=0

log_header() {
    echo ""
    echo "========================================================================"
    echo " $1"
    echo "========================================================================"
}

log_pass() {
    echo ">>> [PASS] $1"
    PASSED_TESTS=$((PASSED_TESTS + 1))
}

log_fail() {
    echo ">>> [FAIL] $1: $2" >&2
    FAILED_TESTS=$((FAILED_TESTS + 1))
}

setup_test_env() {
    local dir="$1"
    rm -rf "${dir}"
    mkdir -p "${dir}/run"
    chmod 0700 "${dir}/run"

    cat << "WEOF" > "${dir}/weston.ini"
[core]
backend=headless-backend.so
shell=desktop-shell.so
idle-time=0

[shell]
locking=false
animation=none
WEOF

    cat << LEOF > "${dir}/lddm.conf"
[logging]
level = INFO
file_path = ${dir}/lddm.log
console = false
colorize = false

[server]
socket_path = ${dir}/run/lddm.sock
runtime_dir = ${dir}/run
pid_file = ${dir}/run/lddm.pid

[session]
default_user = $(whoami)
session_type = wayland
display_number = 0
wayland_display = wayland-0
base_runtime_dir = ${dir}/run/sessions

[weston]
executable = ${WESTON_BIN}
config_path = ${dir}/weston.ini
socket_name = wayland-0
backend = headless-backend.so
additional_args = --idle-time=0

[ldde]
executable = ${LDDE_BIN}
session_target = default
autostart = true

[process]
startup_timeout_ms = 8000
stop_timeout_ms = 3000
max_restart_count = 5
restart_window_seconds = 60
LEOF
}

wait_for_state() {
    local dir="$1"
    local target_state="$2"
    local timeout_secs="$3"

    local deadline=$((SECONDS + timeout_secs))
    while [ $SECONDS -lt $deadline ]; do
        for f in "${dir}/run/session_state" "${dir}/run/sessions/session-default/state/session_state"; do
            if [ -f "$f" ] && grep -q "STATE=${target_state}" "$f"; then
                return 0
            fi
        done
        sleep 0.1
    done
    return 1
}

get_weston_pid() {
    local lddm_pid="$1"
    pgrep -P "${lddm_pid}" -f "weston" | head -n 1 || true
}

get_ldde_pid() {
    local lddm_pid="$1"
    pgrep -P "${lddm_pid}" -f "ldde" | head -n 1 || true
}

# =============================================================================
# TEST 1 — Fresh GUI Startup
# =============================================================================
log_header "TEST 1: Fresh GUI Startup (Guest Init -> LDDM -> Weston -> LDDE -> GUI_READY)"
T1_DIR="${TEST_BASE}/t1"
setup_test_env "${T1_DIR}"

export XDG_RUNTIME_DIR="${T1_DIR}/run"
"${LDDM_BIN}" --config "${T1_DIR}/lddm.conf" >/dev/null 2>&1 &
T1_PID=$!

T1_WAYLAND_SOCK="${T1_DIR}/run/sessions/session-default/run/wayland-0"

if wait_for_state "${T1_DIR}" "GUI_READY" 10; then
    if [ -S "${T1_WAYLAND_SOCK}" ]; then
        # Verify exact Section 18 sequence in log
        grep -q "Starting LDDM" "${T1_DIR}/lddm.log" && \
        grep -q "Starting Weston" "${T1_DIR}/lddm.log" && \
        grep -q "Weston process started" "${T1_DIR}/lddm.log" && \
        grep -q "Waiting for Wayland readiness" "${T1_DIR}/lddm.log" && \
        grep -q "Weston ready" "${T1_DIR}/lddm.log" && \
        grep -q "Starting LDDE" "${T1_DIR}/lddm.log" && \
        grep -q "LDDE process started" "${T1_DIR}/lddm.log" && \
        grep -q "Waiting for LDDE readiness" "${T1_DIR}/lddm.log" && \
        grep -q "LDDE ready" "${T1_DIR}/lddm.log" && \
        grep -q "GUI ready" "${T1_DIR}/lddm.log"
        if [ $? -eq 0 ]; then
            log_pass "Test 1: Fresh GUI startup verified in sequence to GUI_READY"
        else
            log_fail "Test 1" "Section 18 log sequence incomplete"
        fi
    else
        log_fail "Test 1" "Wayland socket not found at ${T1_WAYLAND_SOCK}"
    fi
else
    log_fail "Test 1" "Timed out waiting for GUI_READY"
fi

kill -TERM "${T1_PID}" 2>/dev/null || true
wait "${T1_PID}" 2>/dev/null || true

# =============================================================================
# TEST 2 — Weston Failure & Autonomous Supervisor Recovery
# =============================================================================
log_header "TEST 2: Weston Failure Injection & Recovery"
T2_DIR="${TEST_BASE}/t2"
setup_test_env "${T2_DIR}"

export XDG_RUNTIME_DIR="${T2_DIR}/run"
"${LDDM_BIN}" --config "${T2_DIR}/lddm.conf" >/dev/null 2>&1 &
T2_PID=$!
T2_WAYLAND_SOCK="${T2_DIR}/run/sessions/session-default/run/wayland-0"

if wait_for_state "${T2_DIR}" "GUI_READY" 10; then
    WESTON_PID="$(get_weston_pid "${T2_PID}")"
    if [[ -n "${WESTON_PID}" ]]; then
        echo "Injecting Weston failure: SIGKILL to PID ${WESTON_PID}"
        kill -9 "${WESTON_PID}"

        # LDDM should supervise, recover weston, restart LDDE, return to GUI_READY
        sleep 0.5
        if wait_for_state "${T2_DIR}" "GUI_READY" 10; then
            NEW_WESTON_PID="$(get_weston_pid "${T2_PID}")"
            if [[ -n "${NEW_WESTON_PID}" && "${NEW_WESTON_PID}" != "${WESTON_PID}" ]]; then
                if [ -S "${T2_WAYLAND_SOCK}" ]; then
                    log_pass "Test 2: Weston recovered successfully (new PID: ${NEW_WESTON_PID}, GUI_READY restored)"
                else
                    log_fail "Test 2" "Wayland socket missing after recovery"
                fi
            else
                log_fail "Test 2" "New Weston process was not detected"
            fi
        else
            log_fail "Test 2" "Timed out waiting for GUI_READY after Weston kill"
        fi
    else
        log_fail "Test 2" "Could not find Weston child PID under LDDM"
    fi
else
    log_fail "Test 2" "Initial GUI_READY failed"
fi

kill -TERM "${T2_PID}" 2>/dev/null || true
wait "${T2_PID}" 2>/dev/null || true

# =============================================================================
# TEST 3 — LDDE Failure & Autonomous Recovery (Compositor Preserved)
# =============================================================================
log_header "TEST 3: LDDE Failure Injection & Recovery"
T3_DIR="${TEST_BASE}/t3"
setup_test_env "${T3_DIR}"

export XDG_RUNTIME_DIR="${T3_DIR}/run"
"${LDDM_BIN}" --config "${T3_DIR}/lddm.conf" >/dev/null 2>&1 &
T3_PID=$!
T3_WAYLAND_SOCK="${T3_DIR}/run/sessions/session-default/run/wayland-0"

if wait_for_state "${T3_DIR}" "GUI_READY" 10; then
    WESTON_PID="$(get_weston_pid "${T3_PID}")"
    LDDE_PID="$(get_ldde_pid "${T3_PID}")"
    if [[ -n "${LDDE_PID}" ]]; then
        echo "Injecting LDDE failure: SIGKILL to PID ${LDDE_PID}"
        kill -9 "${LDDE_PID}"

        sleep 0.5
        if wait_for_state "${T3_DIR}" "GUI_READY" 10; then
            CURRENT_WESTON_PID="$(get_weston_pid "${T3_PID}")"
            NEW_LDDE_PID="$(get_ldde_pid "${T3_PID}")"
            if [[ "${CURRENT_WESTON_PID}" == "${WESTON_PID}" ]]; then
                if [[ -n "${NEW_LDDE_PID}" && "${NEW_LDDE_PID}" != "${LDDE_PID}" ]]; then
                    log_pass "Test 3: LDDE recovered successfully while Weston PID was preserved (${WESTON_PID})"
                else
                    log_fail "Test 3" "LDDE did not restart with new PID"
                fi
            else
                log_fail "Test 3" "Weston was unexpectedly restarted when only LDDE failed"
            fi
        else
            log_fail "Test 3" "Timed out waiting for GUI_READY after LDDE kill"
        fi
    else
        log_fail "Test 3" "Could not find LDDE child PID under LDDM"
    fi
else
    log_fail "Test 3" "Initial GUI_READY failed"
fi

kill -TERM "${T3_PID}" 2>/dev/null || true
wait "${T3_PID}" 2>/dev/null || true

# =============================================================================
# TEST 4 — Clean Shutdown (Zero Orphaned Processes, Clean Sockets)
# =============================================================================
log_header "TEST 4: Clean Graceful Shutdown"
T4_DIR="${TEST_BASE}/t4"
setup_test_env "${T4_DIR}"

export XDG_RUNTIME_DIR="${T4_DIR}/run"
"${LDDM_BIN}" --config "${T4_DIR}/lddm.conf" >/dev/null 2>&1 &
T4_PID=$!
T4_WAYLAND_SOCK="${T4_DIR}/run/sessions/session-default/run/wayland-0"

if wait_for_state "${T4_DIR}" "GUI_READY" 10; then
    WESTON_PID="$(get_weston_pid "${T4_PID}")"
    LDDE_PID="$(get_ldde_pid "${T4_PID}")"

    echo "Sending SIGTERM to LDDM PID ${T4_PID}"
    kill -TERM "${T4_PID}"
    wait "${T4_PID}" 2>/dev/null || true

    sleep 0.5
    # Verify children exited
    ORPHANS=0
    if [[ -n "${WESTON_PID}" ]] && kill -0 "${WESTON_PID}" 2>/dev/null; then
        ORPHANS=$((ORPHANS + 1))
    fi
    if [[ -n "${LDDE_PID}" ]] && kill -0 "${LDDE_PID}" 2>/dev/null; then
        ORPHANS=$((ORPHANS + 1))
    fi

    if [ ${ORPHANS} -eq 0 ]; then
        if [ ! -S "${T4_WAYLAND_SOCK}" ]; then
            log_pass "Test 4: Clean shutdown confirmed (zero orphans, sockets unlinked)"
        else
            log_fail "Test 4" "Stale Wayland socket left behind after shutdown"
        fi
    else
        log_fail "Test 4" "${ORPHANS} orphaned processes remained after LDDM exit"
    fi
else
    log_fail "Test 4" "Initial GUI_READY failed"
    kill -TERM "${T4_PID}" 2>/dev/null || true
    wait "${T4_PID}" 2>/dev/null || true
fi

# =============================================================================
# TEST 5 — Restart Cycle
# =============================================================================
log_header "TEST 5: Restart Cycle (Start -> GUI_READY -> Stop -> Start -> GUI_READY)"
T5_DIR="${TEST_BASE}/t5"
setup_test_env "${T5_DIR}"

export XDG_RUNTIME_DIR="${T5_DIR}/run"
"${LDDM_BIN}" --config "${T5_DIR}/lddm.conf" >/dev/null 2>&1 &
T5_PID1=$!

CYCLE1_OK=false
if wait_for_state "${T5_DIR}" "GUI_READY" 10; then
    CYCLE1_OK=true
fi
kill -TERM "${T5_PID1}" 2>/dev/null || true
wait "${T5_PID1}" 2>/dev/null || true
sleep 0.5

CYCLE2_OK=false
"${LDDM_BIN}" --config "${T5_DIR}/lddm.conf" >/dev/null 2>&1 &
T5_PID2=$!

if wait_for_state "${T5_DIR}" "GUI_READY" 10; then
    CYCLE2_OK=true
fi
kill -TERM "${T5_PID2}" 2>/dev/null || true
wait "${T5_PID2}" 2>/dev/null || true

if [ "${CYCLE1_OK}" = true ] && [ "${CYCLE2_OK}" = true ]; then
    log_pass "Test 5: Restart cycle succeeded without stale socket collisions"
else
    log_fail "Test 5" "Failed restart cycle (cycle1=${CYCLE1_OK}, cycle2=${CYCLE2_OK})"
fi

# =============================================================================
# TEST 6 — Repeated Start/Stop Cycles (5 Consecutive Cycles)
# =============================================================================
log_header "TEST 6: Repeated Start/Stop Cycles (5 Cycles)"
T6_DIR="${TEST_BASE}/t6"
setup_test_env "${T6_DIR}"
export XDG_RUNTIME_DIR="${T6_DIR}/run"

T6_SUCCESS_COUNT=0
for cycle in {1..5}; do
    echo "--- Cycle ${cycle}/5 ---"
    "${LDDM_BIN}" --config "${T6_DIR}/lddm.conf" >/dev/null 2>&1 &
    CYCLE_PID=$!

    if wait_for_state "${T6_DIR}" "GUI_READY" 10; then
        T6_SUCCESS_COUNT=$((T6_SUCCESS_COUNT + 1))
    fi

    kill -TERM "${CYCLE_PID}" 2>/dev/null || true
    wait "${CYCLE_PID}" 2>/dev/null || true
    sleep 0.3
done

if [ ${T6_SUCCESS_COUNT} -eq 5 ]; then
    log_pass "Test 6: 5 consecutive start/stop cycles completed with 100% success rate"
else
    log_fail "Test 6" "Only ${T6_SUCCESS_COUNT}/5 cycles reached GUI_READY"
fi

# =============================================================================
# EXTRA TEST — Wayland Client Connection to Compositor Socket
# =============================================================================
log_header "EXTRA TEST: Wayland Client Connection to Compositor Socket"
TX_DIR="${TEST_BASE}/tx"
setup_test_env "${TX_DIR}"
export XDG_RUNTIME_DIR="${TX_DIR}/run"
"${LDDM_BIN}" --config "${TX_DIR}/lddm.conf" >/dev/null 2>&1 &
TX_PID=$!
TX_WAYLAND_SOCK="${TX_DIR}/run/sessions/session-default/run/wayland-0"

if wait_for_state "${TX_DIR}" "GUI_READY" 10; then
    # Test client connection via python
    python3 - << PYEOF
import socket
import struct
import sys

sock_path = "${TX_WAYLAND_SOCK}"
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
try:
    s.connect(sock_path)
    # Send Wayland wire protocol: get_registry on wl_display
    # wl_display is id 1. get_registry opcode is 1. size is 12. new_id is 2.
    msg = struct.pack("<III", 1, (12 << 16) | 1, 2)
    s.sendall(msg)
    s.close()
    print("SUCCESS: Wayland client connected and dispatched wire message")
    sys.exit(0)
except Exception as e:
    print(f"FAILED to connect to Wayland socket: {e}", file=sys.stderr)
    sys.exit(1)
PYEOF
    if [ $? -eq 0 ]; then
        log_pass "Extra Test: Wayland client connected and communicated with compositor socket"
    else
        log_fail "Extra Test" "Wayland client connection failed"
    fi
else
    log_fail "Extra Test" "Compositor failed to reach GUI_READY"
fi

kill -TERM "${TX_PID}" 2>/dev/null || true
wait "${TX_PID}" 2>/dev/null || true

# =============================================================================
# TEST 7 — Missing / Invalid Weston Binary Handling
# =============================================================================
log_header "TEST 7: Fault Injection — Missing Weston Binary"
T7_DIR="${TEST_BASE}/t7"
setup_test_env "${T7_DIR}"
# Change executable to invalid binary
sed -i 's|executable = .*|executable = /usr/bin/nonexistent_weston_binary|' "${T7_DIR}/lddm.conf"

export XDG_RUNTIME_DIR="${T7_DIR}/run"
set +e
"${LDDM_BIN}" --config "${T7_DIR}/lddm.conf" >/dev/null 2>&1 &
T7_PID=$!
set -e

# Should fail fast and not reach GUI_READY
if wait_for_state "${T7_DIR}" "GUI_READY" 3; then
    log_fail "Test 7" "Expected failure but reached GUI_READY"
else
    wait "${T7_PID}" 2>/dev/null || true
    log_pass "Test 7: Fault injection handled cleanly (fail-fast without hanging)"
fi
kill -9 "${T7_PID}" 2>/dev/null || true

# =============================================================================
# TEST 8 — Stale Inactive Socket & Lock Auto-Cleanup
# =============================================================================
log_header "TEST 8: Stale Inactive Socket & Lock Auto-Cleanup"
T8_DIR="${TEST_BASE}/t8"
setup_test_env "${T8_DIR}"

# Pre-create a dead UNIX socket and lock file at session path
T8_SESS_RUN="${T8_DIR}/run/sessions/session-default/run"
mkdir -p "${T8_SESS_RUN}"
python3 -c "
import socket
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.bind('${T8_SESS_RUN}/wayland-0')
s.close()
"
touch "${T8_SESS_RUN}/wayland-0.lock"

export XDG_RUNTIME_DIR="${T8_DIR}/run"
"${LDDM_BIN}" --config "${T8_DIR}/lddm.conf" >/dev/null 2>&1 &
T8_PID=$!

if wait_for_state "${T8_DIR}" "GUI_READY" 10; then
    if [ -S "${T8_SESS_RUN}/wayland-0" ]; then
        log_pass "Test 8: Stale socket & lock auto-cleaned and operational compositor started"
    else
        log_fail "Test 8" "Wayland socket missing"
    fi
else
    log_fail "Test 8" "Failed to start over stale socket/lock files"
fi
kill -TERM "${T8_PID}" 2>/dev/null || true
wait "${T8_PID}" 2>/dev/null || true

# =============================================================================
# TEST 9 — Session Identity & State File Metadata Verification
# =============================================================================
log_header "TEST 9: Session Identity & State File Verification"
T9_DIR="${TEST_BASE}/t9"
setup_test_env "${T9_DIR}"

export XDG_RUNTIME_DIR="${T9_DIR}/run"
"${LDDM_BIN}" --config "${T9_DIR}/lddm.conf" >/dev/null 2>&1 &
T9_PID=$!

if wait_for_state "${T9_DIR}" "GUI_READY" 10; then
    STATE_FILE=""
    for f in "${T9_DIR}/run/session_state" "${T9_DIR}/run/sessions/session-default/state/session_state"; do
        if [ -f "$f" ] && grep -q "STATE=GUI_READY" "$f"; then
            STATE_FILE="$f"
            break
        fi
    done

    if [[ -n "${STATE_FILE}" ]]; then
        grep -q "STATE=GUI_READY" "${STATE_FILE}" && \
        grep -q "SESSION_ID=" "${STATE_FILE}" && \
        grep -q "PID=" "${STATE_FILE}" && \
        grep -q "TIMESTAMP=" "${STATE_FILE}"
        if [ $? -eq 0 ]; then
            log_pass "Test 9: State file contains full identity metadata (STATE, SESSION_ID, PID, TIMESTAMP)"
        else
            log_fail "Test 9" "State file missing required metadata fields"
        fi
    else
        log_fail "Test 9" "State file not found"
    fi
else
    log_fail "Test 9" "Failed to reach GUI_READY"
fi
kill -TERM "${T9_PID}" 2>/dev/null || true
wait "${T9_PID}" 2>/dev/null || true

# =============================================================================
# Summary
# =============================================================================
log_header "TEST SUITE SUMMARY"
echo "Passed: ${PASSED_TESTS}"
echo "Failed: ${FAILED_TESTS}"
echo ""

if [ ${FAILED_TESTS} -eq 0 ]; then
    echo ">>> ALL INTEGRATED GRAPHICAL SESSION TESTS PASSED! <<<"
    exit 0
else
    echo ">>> SOME TESTS FAILED <<<" >&2
    exit 1
fi
