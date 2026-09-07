package com.linuxdroid.core.runtime

/**
 * Authoritative definition and installation template for `/sbin/linuxdroid-init`.
 *
 * This executable is the guest userspace entrypoint. It executes strictly inside
 * the virtualized rootfs, sets up runtime directories, establishes the guest environment,
 * triggers initialization hooks, and hands over to the requested workload via `exec`.
 */
object GuestInit {
    const val GUEST_INIT_PATH = "/sbin/linuxdroid-init"
    const val HOOKS_DIRECTORY = "/etc/linuxdroid/init.d"

    val SCRIPT_CONTENT: String = """
#!/bin/sh
# /sbin/linuxdroid-init - LinuxDroid production guest initialization entrypoint
set -e

init_log() {
    if [ "§{LINUXDROID_INIT_VERBOSE:-0}" = "1" ]; then
        echo "[GUEST-INIT] §*" >&2
    fi
}

init_err() {
    echo "[GUEST-INIT] ERROR: §*" >&2
}

# 1. Initialize guest runtime directories (idempotent, non-destructive)
for dir in /tmp /run /run/lock /var/run /dev/shm /dev/pts; do
    if [ ! -d "§dir" ]; then
        mkdir -p "§dir" 2>/dev/null || true
    fi
done
chmod 1777 /tmp 2>/dev/null || true
chmod 1777 /dev/shm 2>/dev/null || true

# 2. Construct guest environment independently from Android host
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:§{PATH:-}"

# Load guest environment from /etc/environment if present
if [ -f /etc/environment ]; then
    while IFS= read -r line || [ -n "§line" ]; do
        case "§line" in
            \#*|"") continue ;;
            *=*) export "§line" ;;
        esac
    done < /etc/environment
fi

# Essential guest defaults
export USER="§{USER:-root}"
export LOGNAME="§{LOGNAME:-§USER}"
if [ -z "§{HOME:-}" ]; then
    if [ "§USER" = "root" ]; then
        export HOME="/root"
    elif [ -d "/home/§USER" ]; then
        export HOME="/home/§USER"
    else
        export HOME="/"
    fi
fi

if [ -z "§{SHELL:-}" ] || [ ! -x "§SHELL" ]; then
    if [ -x /bin/bash ]; then
        export SHELL="/bin/bash"
    elif [ -x /usr/bin/bash ]; then
        export SHELL="/usr/bin/bash"
    elif [ -x /bin/sh ]; then
        export SHELL="/bin/sh"
    else
        export SHELL="/usr/bin/sh"
    fi
fi

export TERM="§{TERM:-xterm-256color}"
export LANG="§{LANG:-C.UTF-8}"
export LC_ALL="§{LC_ALL:-C.UTF-8}"
export TMPDIR="/tmp"

# Establish compliant XDG_RUNTIME_DIR (/run/user/<uid>) with strict 0700 permissions
USER_UID=§(id -u 2>/dev/null || echo 0)
if [ -z "§{XDG_RUNTIME_DIR:-}" ] || [ "§{XDG_RUNTIME_DIR}" = "/tmp" ]; then
    export XDG_RUNTIME_DIR="/run/user/§{USER_UID}"
fi
if [ ! -d "§{XDG_RUNTIME_DIR}" ]; then
    mkdir -p "§{XDG_RUNTIME_DIR}" 2>/dev/null || true
fi
chmod 0700 "§{XDG_RUNTIME_DIR}" 2>/dev/null || true

export WAYLAND_DISPLAY="§{WAYLAND_DISPLAY:-wayland-0}"
export DISPLAY="§{DISPLAY:-:0}"
export XDG_SESSION_TYPE="§{XDG_SESSION_TYPE:-wayland}"
export XDG_CURRENT_DESKTOP="§{XDG_CURRENT_DESKTOP:-LDDE}"
export XDG_SESSION_DESKTOP="§{XDG_SESSION_DESKTOP:-LDDE}"

# Scrub Android host environment leakage if present
unset ANDROID_ROOT ANDROID_DATA ANDROID_STORAGE ASEC_MOUNTPOINT BOOTCLASSPATH DEX2OATBOOTCLASSPATH EXTERNAL_STORAGE

# 3. Execute guest initialization hooks in deterministic order
HOOKS_DIR="/etc/linuxdroid/init.d"
if [ -d "§HOOKS_DIR" ]; then
    for hook in "§HOOKS_DIR"/*; do
        if [ -f "§hook" ] && [ -x "§hook" ]; then
            init_log "Executing hook: §(basename "§hook")"
            if ! "§hook"; then
                init_err "Hook failed: §(basename "§hook")"
                exit 1
            fi
        fi
    done
fi

# 4. Hand over to requested workload
echo "[INFO] Guest ready"

START_MODE="§{LINUXDROID_START_MODE:-}"
if [ -z "§START_MODE" ]; then
    if [ "§1" = "GUI" ] || [ "§1" = "CLI" ]; then
        START_MODE="§1"
        shift
    fi
fi

if [ -z "§START_MODE" ]; then
    init_err "Missing startMode: LINUXDROID_START_MODE is not set. Deterministic startup requires 'GUI' or 'CLI'."
    exit 1
fi

echo "[GUEST-INIT] startMode=§START_MODE" >&2

case "§START_MODE" in
    GUI)
        echo "[GUEST-INIT] Handing over to LDDM" >&2
        echo "[LDDM] Starting graphical session"
        if [ §# -gt 0 ] && [ "§1" != "GUI" ]; then
            init_log "Handing over to GUI workload: §1"
            exec "§@"
        elif [ -x /usr/bin/lddm ]; then
            exec /usr/bin/lddm
        elif [ -x /usr/local/bin/lddm ]; then
            exec /usr/local/bin/lddm
        else
            exec lddm
        fi
        ;;
    CLI)
        echo "[GUEST-INIT] Starting CLI session" >&2
        if [ §# -gt 0 ] && [ "§1" != "CLI" ]; then
            init_log "Handing over to CLI workload: §1"
            exec "§@"
        else
            exec "§SHELL" -l
        fi
        ;;
    *)
        init_err "Invalid startMode: '§START_MODE'. Only 'GUI' and 'CLI' are allowed."
        exit 1
        ;;
esac
""".trimIndent().replace('§', '$') + "\n"
}

