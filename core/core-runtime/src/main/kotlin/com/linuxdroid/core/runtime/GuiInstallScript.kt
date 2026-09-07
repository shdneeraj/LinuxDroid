package com.linuxdroid.core.runtime

import java.io.File

/**
 * Authoritative template and definition for `/etc/linuxdroid/gui-install.sh`.
 *
 * This script runs exclusively inside the guest Linux userspace via PRoot
 * under Guest Init in CLI mode, AFTER the base CLI provisioning is complete.
 *
 * Installs the optional GUI layer:
 * - Wayland libraries
 * - Weston compositor
 * - Pixman
 * - LDDM (LinuxDroid Display Manager) from staged .deb
 * - LDDE (LinuxDroid Desktop Environment) from staged .deb
 * - GUI configuration files (weston.ini, lddm.conf, desktop.conf)
 *
 * A failure in this script NEVER invalidates the CLI environment.
 * The [POST_INSTALL_COMPLETE] marker is NOT affected by this script.
 * Only [GUI_INSTALL_COMPLETE] is written on success.
 */
object GuiInstallScript {
    const val GUI_INSTALL_SCRIPT_PATH = "/etc/linuxdroid/gui-install.sh"
    const val GUI_STAGED_PACKAGES_DIR = "/root/.linuxdroid/gui-packages"
    const val GUI_INSTALL_COMPLETE_MARKER = "/etc/linuxdroid/GUI_INSTALL_COMPLETE"
    const val GUI_INSTALL_STATE_PATH = "/etc/linuxdroid/gui-install-state"

    val SCRIPT_CONTENT: String = """
#!/bin/bash
# =============================================================================
# LinuxDroid — In-Guest GUI Installation Script
# =============================================================================
# Installs the optional graphical layer on top of an existing CLI environment.
# A failure here does NOT affect the CLI environment or CLI_READY status.
set -e

export DEBIAN_FRONTEND=noninteractive
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:§{PATH:-}"

run_cmd() {
    local stage="§1"
    local log_cmd="§2"
    shift 2
    local start_ts
    start_ts="§(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    local start_sec
    start_sec="§(date +%s%N 2>/dev/null || date +%s)"
    echo "[GUI_INSTALL][START][§stage]"
    echo "command=§log_cmd"
    echo "timestamp=§start_ts"

    local err_file
    err_file="§(mktemp /tmp/gui_install_err.XXXXXX 2>/dev/null || echo /tmp/gui_install_err.§§)"
    local exit_code=0

    if "§@" 2>"§err_file"; then
        local end_sec
        end_sec="§(date +%s%N 2>/dev/null || date +%s)"
        local dur_ms=1
        if [ "§{#start_sec}" -gt 10 ] && [ "§{#end_sec}" -gt 10 ]; then
            dur_ms=§(( (end_sec - start_sec) / 1000000 ))
        fi
        [ "§dur_ms" -le 0 ] && dur_ms=1
        echo "[GUI_INSTALL][SUCCESS][§stage]"
        echo "exit_code=0"
        echo "duration_ms=§dur_ms"
        echo "timestamp=§(date -u +"%Y-%m-%dT%H:%M:%SZ")"
        rm -f "§err_file" 2>/dev/null || true
        return 0
    else
        exit_code=§?
        local end_sec
        end_sec="§(date +%s%N 2>/dev/null || date +%s)"
        local dur_ms=1
        if [ "§{#start_sec}" -gt 10 ] && [ "§{#end_sec}" -gt 10 ]; then
            dur_ms=§(( (end_sec - start_sec) / 1000000 ))
        fi
        [ "§dur_ms" -le 0 ] && dur_ms=1
        local err_msg
        err_msg="§(cat "§err_file" 2>/dev/null || echo "Unknown error")"
        rm -f "§err_file" 2>/dev/null || true
        echo "[GUI_INSTALL][FAIL][§stage]"
        echo "exit_code=§exit_code"
        echo "duration_ms=§dur_ms"
        echo "stderr=§err_msg"
        echo "timestamp=§(date -u +"%Y-%m-%dT%H:%M:%SZ")"
        # Update the GUI install state marker to FAILED
        echo "STATE=GUI_INSTALL_FAILED" > /etc/linuxdroid/gui-install-state
        echo "STAGE=§stage" >> /etc/linuxdroid/gui-install-state
        echo "EXIT_CODE=§exit_code" >> /etc/linuxdroid/gui-install-state
        echo "TIMESTAMP=§(date -u +"%Y-%m-%dT%H:%M:%SZ")" >> /etc/linuxdroid/gui-install-state
        exit §exit_code
    fi
}

echo "================================================================================"
echo "LINUXDROID GUI INSTALLATION STARTING"
echo "Timestamp: §(date -u +"%Y-%m-%dT%H:%M:%SZ")"
echo "CLI environment foundation is preserved regardless of GUI install outcome."
echo "================================================================================"

# Write in-progress state marker
mkdir -p /etc/linuxdroid
echo "STATE=GUI_INSTALLING" > /etc/linuxdroid/gui-install-state
echo "TIMESTAMP=§(date -u +"%Y-%m-%dT%H:%M:%SZ")" >> /etc/linuxdroid/gui-install-state

# -----------------------------------------------------------------------------
# 1. Validate CLI Prerequisites
# -----------------------------------------------------------------------------
validate_prerequisites_action() {
    local missing=""

    # Verify CLI foundation is present
    if [ ! -x /sbin/linuxdroid-init ]; then missing="§missing /sbin/linuxdroid-init;"; fi
    if [ ! -f /etc/linuxdroid/POST_INSTALL_COMPLETE ]; then
        missing="§missing CLI foundation not complete (POST_INSTALL_COMPLETE missing);"
    fi

    if [ -n "§missing" ]; then
        echo "Prerequisites check failed: §missing" >&2
        return 1
    fi
    return 0
}

run_cmd "VALIDATE_PREREQUISITES" "validate CLI prerequisites" validate_prerequisites_action

# Locate staged LinuxDroid .deb files
GUI_PKGS_DIR="/root/.linuxdroid/gui-packages"
LDDM_DEB="§(find "§GUI_PKGS_DIR" -type f \( -name "*display-manager*.deb" -o -name "*lddm*.deb" \) 2>/dev/null | head -n 1 || true)"
LDDE_DEB="§(find "§GUI_PKGS_DIR" -type f \( -name "*desktop-environment*.deb" -o -name "*ldde*.deb" \) 2>/dev/null | head -n 1 || true)"

if [ -z "§LDDM_DEB" ] || [ ! -f "§LDDM_DEB" ]; then
    echo "[GUI_INSTALL][FAIL][VALIDATE_PREREQUISITES]"
    echo "exit_code=1"
    echo "stderr=Missing staged LDDM deb in §GUI_PKGS_DIR"
    echo "STATE=GUI_INSTALL_FAILED" > /etc/linuxdroid/gui-install-state
    exit 1
fi

if [ -z "§LDDE_DEB" ] || [ ! -f "§LDDE_DEB" ]; then
    echo "[GUI_INSTALL][FAIL][VALIDATE_PREREQUISITES]"
    echo "exit_code=1"
    echo "stderr=Missing staged LDDE deb in §GUI_PKGS_DIR"
    echo "STATE=GUI_INSTALL_FAILED" > /etc/linuxdroid/gui-install-state
    exit 1
fi

# -----------------------------------------------------------------------------
# 2. Configure APT Safeguards
# -----------------------------------------------------------------------------
cat > /usr/sbin/policy-rc.d << 'POLICY_EOF'
#!/bin/sh
exit 101
POLICY_EOF
chmod +x /usr/sbin/policy-rc.d 2>/dev/null || true

# Recover any interrupted dpkg states
if [ -f /var/lib/dpkg/lock ] || [ -f /var/lib/dpkg/lock-frontend ]; then
    rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend 2>/dev/null || true
    dpkg --configure -a 2>/dev/null || true
fi

# -----------------------------------------------------------------------------
# 3. APT Update
# -----------------------------------------------------------------------------
run_cmd "APT_UPDATE" "apt-get update" apt-get update

# -----------------------------------------------------------------------------
# 4. Install Wayland Libraries
# -----------------------------------------------------------------------------
WAYLAND_PACKAGES=(
    libwayland-client0
    libwayland-server0
    libwayland-cursor0
    wayland-protocols
)

run_cmd "INSTALL_WAYLAND" "apt-get install -y <wayland_packages>" apt-get install -y "§{WAYLAND_PACKAGES[@]}"

# -----------------------------------------------------------------------------
# 5. Install Weston Compositor
# -----------------------------------------------------------------------------
run_cmd "INSTALL_WESTON" "apt-get install -y weston" apt-get install -y weston

# -----------------------------------------------------------------------------
# 6. Install Pixman
# -----------------------------------------------------------------------------
run_cmd "INSTALL_PIXMAN" "apt-get install -y libpixman-1-0 fonts-dejavu-core xwayland" \
    apt-get install -y libpixman-1-0 fonts-dejavu-core xwayland

# -----------------------------------------------------------------------------
# 7. Configure Weston
# -----------------------------------------------------------------------------
configure_weston_action() {
    mkdir -p /etc/xdg/weston
    if [ ! -f /etc/xdg/weston/weston.ini ]; then
        cat << 'WESTON_INI_EOF' > /etc/xdg/weston/weston.ini
[core]
idle-time=0
require-input=false
backend=headless-backend.so

[shell]
locking=false
WESTON_INI_EOF
        chmod 0644 /etc/xdg/weston/weston.ini
    fi
}

run_cmd "CONFIGURE_WESTON" "write default weston.ini" configure_weston_action

# -----------------------------------------------------------------------------
# 8. Install LDDM from staged .deb
# -----------------------------------------------------------------------------
run_cmd "INSTALL_LDDM" "apt-get install -y <LDDM.deb>" apt-get install -y "§LDDM_DEB"

# -----------------------------------------------------------------------------
# 9. Install LDDE from staged .deb
# -----------------------------------------------------------------------------
run_cmd "INSTALL_LDDE" "apt-get install -y <LDDE.deb>" apt-get install -y "§LDDE_DEB"

# -----------------------------------------------------------------------------
# 10. Configure GUI (LDDM and LDDE config files)
# -----------------------------------------------------------------------------
configure_gui_action() {
    mkdir -p /etc/linuxdroid

    # Read username from install.conf if available
    local username="user"
    if [ -f /etc/linuxdroid/install.conf ]; then
        local conf_user
        conf_user="§(grep -E '^(USERNAME|username)=' /etc/linuxdroid/install.conf | cut -d= -f2 | xargs 2>/dev/null || true)"
        [ -n "§conf_user" ] && username="§conf_user"
    fi

    if [ ! -f /etc/linuxdroid/lddm.conf ]; then
        cat > /etc/linuxdroid/lddm.conf << LDDM_CONF_EOF
[lddm]
weston_socket = wayland-0
session_user = §username
autostart = true
LDDM_CONF_EOF
        chmod 0644 /etc/linuxdroid/lddm.conf
    fi

    if [ ! -f /etc/linuxdroid/desktop.conf ]; then
        cat > /etc/linuxdroid/desktop.conf << 'DESKTOP_CONF_EOF'
[desktop]
shell = default
theme = default
DESKTOP_CONF_EOF
        chmod 0644 /etc/linuxdroid/desktop.conf
    fi

    if [ -x /usr/bin/ldde ] && [ ! -e /usr/bin/ldde-session ]; then
        ln -sf /usr/bin/ldde /usr/bin/ldde-session
    fi
}

run_cmd "CONFIGURE_GUI" "write lddm.conf and desktop.conf" configure_gui_action

# -----------------------------------------------------------------------------
# 11. APT Cleanup
# -----------------------------------------------------------------------------
apt-get autoremove --purge -y 2>/dev/null || true
apt-get clean 2>/dev/null || true
apt-get update 2>/dev/null || true

# Remove policy-rc.d wrapper
rm -f /usr/sbin/policy-rc.d 2>/dev/null || true

# -----------------------------------------------------------------------------
# 12. Validate GUI Installation
# -----------------------------------------------------------------------------
validate_gui_action() {
    local missing=""

    # Wayland libraries
    local wayland_found=false
    for libdir in /usr/lib /usr/lib/aarch64-linux-gnu /usr/lib64 /lib /lib/aarch64-linux-gnu; do
        if [ -f "§libdir/libwayland-client.so.0" ] || ls "§libdir"/libwayland-client* >/dev/null 2>&1; then
            wayland_found=true
            break
        fi
    done
    §wayland_found || missing="§missing Wayland client library;"

    # Weston binary
    if [ ! -x /usr/bin/weston ] && ! command -v weston >/dev/null 2>&1; then
        missing="§missing Weston compositor executable;"
    fi

    # LDDM
    if [ ! -x /usr/bin/lddm ] && [ ! -x /usr/local/bin/lddm ]; then
        missing="§missing LDDM binary;"
    fi
    if [ ! -f /etc/linuxdroid/lddm.conf ]; then missing="§missing /etc/linuxdroid/lddm.conf;"; fi

    # LDDE
    if [ ! -x /usr/bin/ldde ] && [ ! -x /usr/local/bin/ldde ]; then
        missing="§missing LDDE binary;"
    fi
    if [ ! -f /etc/linuxdroid/desktop.conf ]; then missing="§missing /etc/linuxdroid/desktop.conf;"; fi

    if [ -n "§missing" ]; then
        echo "GUI validation failed: §missing" >&2
        return 1
    fi
    return 0
}

run_cmd "VALIDATE_GUI" "validate GUI installation artifacts" validate_gui_action

# -----------------------------------------------------------------------------
# 13. Remove Staged .deb Files
# -----------------------------------------------------------------------------
rm -rf "§GUI_PKGS_DIR" 2>/dev/null || true

# -----------------------------------------------------------------------------
# 14. Write GUI_INSTALL_COMPLETE Marker
# -----------------------------------------------------------------------------
cat > /etc/linuxdroid/GUI_INSTALL_COMPLETE << GUI_MARKER_EOF
STATUS=COMPLETE
TIMESTAMP=§(date -u +\"%Y-%m-%dT%H:%M:%SZ\")
GUI_MARKER_EOF

echo "STATE=GUI_INSTALLED" > /etc/linuxdroid/gui-install-state
echo "TIMESTAMP=§(date -u +\"%Y-%m-%dT%H:%M:%SZ\")" >> /etc/linuxdroid/gui-install-state

echo "[GUI_INSTALL][SUCCESS][GUI_INSTALL]"
echo "exit_code=0"
echo "timestamp=§(date -u +\"%Y-%m-%dT%H:%M:%SZ\")"
echo "GUI_INSTALL_COMPLETE"

exit 0
""".trimIndent().replace('§', '$') + "\n"

    /**
     * Writes the GUI install script to the rootfs.
     */
    fun writeScript(rootfsDir: File): File {
        val scriptFile = File(rootfsDir, GUI_INSTALL_SCRIPT_PATH.removePrefix("/"))
        scriptFile.parentFile?.mkdirs()
        scriptFile.writeText(SCRIPT_CONTENT, Charsets.UTF_8)
        scriptFile.setExecutable(true, false)
        scriptFile.setReadable(true, false)
        return scriptFile
    }

    /**
     * Stages LDDM and LDDE .deb files into the GUI packages directory inside the rootfs.
     * This must be called before running the GUI install script via PRoot.
     */
    fun stageGuiPackages(
        rootfsDir: File,
        lddmDeb: File?,
        lddeDeb: File?,
    ) {
        val packagesDir = File(rootfsDir, GUI_STAGED_PACKAGES_DIR.removePrefix("/")).apply { mkdirs() }

        if (lddmDeb != null && lddmDeb.exists()) {
            val dest1 = File(packagesDir, "linuxdroid-display-manager.deb")
            val dest2 = File(packagesDir, "LDDM.deb")
            lddmDeb.copyTo(dest1, overwrite = true)
            lddmDeb.copyTo(dest2, overwrite = true)
            dest1.setReadable(true, false)
            dest2.setReadable(true, false)
        }

        if (lddeDeb != null && lddeDeb.exists()) {
            val dest1 = File(packagesDir, "linuxdroid-desktop-environment.deb")
            val dest2 = File(packagesDir, "LDDE.deb")
            lddeDeb.copyTo(dest1, overwrite = true)
            lddeDeb.copyTo(dest2, overwrite = true)
            dest1.setReadable(true, false)
            dest2.setReadable(true, false)
        }
    }

    /**
     * Writes the GUI_INSTALL_COMPLETE marker file from the Kotlin (host) side.
     * Used by the simulated GUI install path for offline/JVM testing.
     */
    fun writeGuiInstallCompleteMarker(rootfsDir: File): File {
        val markerFile = File(rootfsDir, GUI_INSTALL_COMPLETE_MARKER.removePrefix("/"))
        markerFile.parentFile?.mkdirs()
        markerFile.writeText(
            "STATUS=COMPLETE\nTIMESTAMP=${System.currentTimeMillis()}\n",
            Charsets.UTF_8,
        )
        return markerFile
    }
}
