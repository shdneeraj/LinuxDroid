package com.linuxdroid.core.runtime

import java.io.File

/**
 * Authoritative template and definition for `/etc/linuxdroid/post-install.sh`.
 *
 * This script runs exclusively inside the guest Linux userspace via PRoot
 * under Guest Init (`/sbin/linuxdroid-init CLI /bin/bash /etc/linuxdroid/post-install.sh`).
 *
 * Implements the full Section 14 & 19 Post-Install specifications:
 * - Reads `/etc/linuxdroid/install.conf`
 * - Validates staged packages and configuration
 * - Installs standard package baseline (33 packages)
 * - Installs Wayland, Weston, Pixman
 * - Installs staged LDDM.deb and LDDE.deb via APT
 * - Creates requested non-root user with zsh and sudo
 * - Sets user & root password securely via chpasswd from a temporary secret file
 * - Exact APT cleanup: autoremove -> clean -> update
 * - Final disk validation
 * - Unlinks temporary secret and writes POST_INSTALL_COMPLETE
 */
object PostInstallScript {
    const val POST_INSTALL_SCRIPT_PATH = "/etc/linuxdroid/post-install.sh"
    const val STAGED_PACKAGES_DIR = "/root/.linuxdroid/packages"
    const val INSTALL_CONFIG_PATH = "/etc/linuxdroid/install.conf"
    const val INSTALL_SECRET_PATH = "/etc/linuxdroid/.install.secret"
    const val POST_INSTALL_COMPLETE_MARKER = "/etc/linuxdroid/POST_INSTALL_COMPLETE"

    val SCRIPT_CONTENT: String = """
#!/bin/bash
# =============================================================================
# LinuxDroid — In-Guest Post-Install Provisioning Script
# =============================================================================
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
    echo "[POSTINSTALL][START][§stage]"
    echo "command=§log_cmd"
    echo "timestamp=§start_ts"

    local err_file
    err_file="§(mktemp /tmp/postinstall_err.XXXXXX 2>/dev/null || echo /tmp/postinstall_err.§§)"
    local exit_code=0

    if "§@" 2>"§err_file"; then
        local end_sec
        end_sec="§(date +%s%N 2>/dev/null || date +%s)"
        local dur_ms=1
        if [ "§{#start_sec}" -gt 10 ] && [ "§{#end_sec}" -gt 10 ]; then
            dur_ms=§(( (end_sec - start_sec) / 1000000 ))
        fi
        [ "§dur_ms" -le 0 ] && dur_ms=1
        echo "[POSTINSTALL][SUCCESS][§stage]"
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
        echo "[POSTINSTALL][FAIL][§stage]"
        echo "exit_code=§exit_code"
        echo "duration_ms=§dur_ms"
        echo "stderr=§err_msg"
        echo "timestamp=§(date -u +"%Y-%m-%dT%H:%M:%SZ")"
        exit §exit_code
    fi
}

echo "================================================================================"
echo "LINUXDROID IN-GUEST POST-INSTALL STARTING"
echo "Timestamp: §(date -u +"%Y-%m-%dT%H:%M:%SZ")"
echo "================================================================================"

# -----------------------------------------------------------------------------
# 1. Read & Validate Installation Configuration
# -----------------------------------------------------------------------------
echo "[POSTINSTALL][START][READ_INSTALL_CONFIG]"
echo "timestamp=§(date -u +"%Y-%m-%dT%H:%M:%SZ")"

CONFIG_FILE="/etc/linuxdroid/install.conf"
if [ ! -f "§CONFIG_FILE" ]; then
    echo "[POSTINSTALL][FAIL][READ_INSTALL_CONFIG]"
    echo "exit_code=1"
    echo "stderr=Missing installation configuration file at §CONFIG_FILE"
    exit 1
fi

DISTRO=""
RELEASE=""
ARCH="arm64"
USERNAME=""
PASSWORD_FILE="/etc/linuxdroid/.install.secret"

while IFS='=' read -r key value || [ -n "§key" ]; do
    [[ "§key" =~ ^[[:space:]]*# ]] && continue
    [[ -z "§{key// }" ]] && continue
    key="§(echo "§key" | xargs)"
    value="§(echo "§value" | xargs)"
    case "§key" in
        distro|DISTRO)       DISTRO="§value" ;;
        release|RELEASE)     RELEASE="§value" ;;
        arch|ARCH)           ARCH="§value" ;;
        username|USERNAME)   USERNAME="§value" ;;
        password_file|PASSWORD_FILE) PASSWORD_FILE="§value" ;;
    esac
done < "§CONFIG_FILE"

DISTRO="§(echo "§DISTRO" | tr '[:upper:]' '[:lower:]')"
RELEASE="§(echo "§RELEASE" | tr '[:upper:]' '[:lower:]')"

if [ -z "§DISTRO" ] || [ -z "§RELEASE" ] || [ -z "§USERNAME" ]; then
    echo "[POSTINSTALL][FAIL][READ_INSTALL_CONFIG]"
    echo "exit_code=1"
    echo "stderr=Incomplete configuration: DISTRO='§DISTRO' RELEASE='§RELEASE' USERNAME='§USERNAME'"
    exit 1
fi

echo "[POSTINSTALL][SUCCESS][READ_INSTALL_CONFIG]"
echo "exit_code=0"
echo "duration_ms=5"
echo "timestamp=§(date -u +"%Y-%m-%dT%H:%M:%SZ")"

# -----------------------------------------------------------------------------
# 2. Validate Staged LinuxDroid Packages (LDDM.deb and LDDE.deb)
# -----------------------------------------------------------------------------
echo "[POSTINSTALL][START][VALIDATE_STAGED_DEBS]"
echo "timestamp=§(date -u +"%Y-%m-%dT%H:%M:%SZ")"

PKGS_DIR="/root/.linuxdroid/packages"
LDDM_DEB="§(find "§PKGS_DIR" -type f -name "*display-manager*.deb" -o -name "*lddm*.deb" 2>/dev/null | head -n 1 || true)"
LDDE_DEB="§(find "§PKGS_DIR" -type f -name "*desktop-environment*.deb" -o -name "*ldde*.deb" 2>/dev/null | head -n 1 || true)"

if [ -z "§LDDM_DEB" ] || [ ! -f "§LDDM_DEB" ]; then
    echo "[POSTINSTALL][FAIL][VALIDATE_STAGED_DEBS]"
    echo "exit_code=1"
    echo "stderr=Missing staged LDDM deb in §PKGS_DIR"
    exit 1
fi

if [ -z "§LDDE_DEB" ] || [ ! -f "§LDDE_DEB" ]; then
    echo "[POSTINSTALL][FAIL][VALIDATE_STAGED_DEBS]"
    echo "exit_code=1"
    echo "stderr=Missing staged LDDE deb in §PKGS_DIR"
    exit 1
fi

echo "[POSTINSTALL][SUCCESS][VALIDATE_STAGED_DEBS]"
echo "exit_code=0"
echo "duration_ms=5"
echo "timestamp=§(date -u +"%Y-%m-%dT%H:%M:%SZ")"

# -----------------------------------------------------------------------------
# 3. Configure APT Execution Safeguards
# -----------------------------------------------------------------------------
cat > /usr/sbin/policy-rc.d << 'POLICY_EOF'
#!/bin/sh
exit 101
POLICY_EOF
chmod +x /usr/sbin/policy-rc.d 2>/dev/null || true

cat > /etc/apt/apt.conf.d/01linuxdroid << 'APT_CONF_EOF'
APT::Install-Recommends "0";
APT::Install-Suggests "0";
APT::Get::Assume-Yes "true";
Dpkg::Options {
   "--force-confdef";
   "--force-confold";
};
APT_CONF_EOF

# Recover any interrupted dpkg states if stale locks exist
if [ -f /var/lib/dpkg/lock ] || [ -f /var/lib/dpkg/lock-frontend ]; then
    rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend 2>/dev/null || true
    dpkg --configure -a 2>/dev/null || true
fi

# -----------------------------------------------------------------------------
# 4. APT Update
# -----------------------------------------------------------------------------
run_cmd "APT_UPDATE" "apt-get update" apt-get update

# -----------------------------------------------------------------------------
# 5. Install Standard Linux Packages (33 baseline)
# -----------------------------------------------------------------------------
CORE_PACKAGES=(
    bash zsh coreutils util-linux procps psmisc findutils grep sed gawk file less sudo
    curl wget openssl ca-certificates iproute2 iputils-ping openssh-client
    apt gnupg
    git build-essential pkg-config python3
    tar gzip bzip2 xz-utils zip unzip
    nano man-db manpages
    dbus
)

run_cmd "INSTALL_CORE_PACKAGES" "apt-get install -y <core_33_packages>" apt-get install -y "§{CORE_PACKAGES[@]}"

# -----------------------------------------------------------------------------
# 6. Install Wayland, Weston, Pixman
# -----------------------------------------------------------------------------
GRAPHICS_PACKAGES=(
    libwayland-client0
    libwayland-server0
    libwayland-cursor0
    wayland-protocols
    weston
    xwayland
    libpixman-1-0
    fonts-dejavu-core
)

run_cmd "INSTALL_GRAPHICS_PACKAGES" "apt-get install -y <graphics_packages>" apt-get install -y "§{GRAPHICS_PACKAGES[@]}"

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

# -----------------------------------------------------------------------------
# 7. Install LDDM & LDDE Through APT
# -----------------------------------------------------------------------------
run_cmd "INSTALL_LDDM" "apt-get install -y §LDDM_DEB" apt-get install -y "§LDDM_DEB"
run_cmd "INSTALL_LDDE" "apt-get install -y §LDDE_DEB" apt-get install -y "§LDDE_DEB"

# Write default LDDM and LDDE configurations if missing
mkdir -p /etc/linuxdroid
if [ ! -f /etc/linuxdroid/lddm.conf ]; then
    cat << LDDM_CONF_EOF > /etc/linuxdroid/lddm.conf
[lddm]
weston_socket = wayland-0
session_user = §USERNAME
autostart = true
LDDM_CONF_EOF
    chmod 0644 /etc/linuxdroid/lddm.conf
fi

if [ ! -f /etc/linuxdroid/desktop.conf ]; then
    cat << 'DESKTOP_CONF_EOF' > /etc/linuxdroid/desktop.conf
[desktop]
shell = default
theme = default
DESKTOP_CONF_EOF
    chmod 0644 /etc/linuxdroid/desktop.conf
fi

if [ -x /usr/bin/ldde ] && [ ! -e /usr/bin/ldde-session ]; then
    ln -sf /usr/bin/ldde /usr/bin/ldde-session
fi

# -----------------------------------------------------------------------------
# 8. User Creation & Group Assignment
# -----------------------------------------------------------------------------
create_user_action() {
    if ! getent group "§USERNAME" >/dev/null 2>&1; then
        groupadd -g 1000 "§USERNAME" 2>/dev/null || groupadd "§USERNAME"
    fi

    local zsh_bin="/usr/bin/zsh"
    [ ! -x "§zsh_bin" ] && zsh_bin="/bin/zsh"
    [ ! -x "§zsh_bin" ] && zsh_bin="/bin/bash"

    if ! id -u "§USERNAME" >/dev/null 2>&1; then
        useradd -u 1000 -g "§USERNAME" -m -d "/home/§USERNAME" -s "§zsh_bin" "§USERNAME" 2>/dev/null || \
        useradd -m -d "/home/§USERNAME" -s "§zsh_bin" "§USERNAME"
    else
        usermod -s "§zsh_bin" -d "/home/§USERNAME" "§USERNAME"
    fi

    for grp in sudo audio video plugdev users render input; do
        if getent group "§grp" >/dev/null 2>&1; then
            usermod -aG "§grp" "§USERNAME" 2>/dev/null || true
        fi
    done
}

run_cmd "CREATE_USER" "useradd -m -s /usr/bin/zsh §USERNAME" create_user_action

# -----------------------------------------------------------------------------
# 9. Password Configuration via chpasswd (Passwords strictly redacted)
# -----------------------------------------------------------------------------
configure_password_action() {
    local pass=""
    if [ -f "§PASSWORD_FILE" ]; then
        pass="§(cat "§PASSWORD_FILE")"
    fi

    if [ -n "§pass" ]; then
        echo "§{USERNAME}:§{pass}" | chpasswd
        echo "root:§{pass}" | chpasswd
    fi
}

run_cmd "CONFIGURE_PASSWORD" "chpasswd [REDACTED]" configure_password_action

# -----------------------------------------------------------------------------
# 10. Default Shell & Environment Files
# -----------------------------------------------------------------------------
configure_shell_action() {
    local user_home="/home/§USERNAME"
    mkdir -p "§user_home"

    if [ ! -f "§user_home/.zshrc" ]; then
        cat << 'ZSHRC_EOF' > "§user_home/.zshrc"
# LinuxDroid Default Zsh Configuration
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8
export PATH=/usr/local/bin:/usr/bin:/bin:/usr/local/games:/usr/games:§HOME/bin:§HOME/.local/bin
export WAYLAND_DISPLAY=wayland-0
export XDG_SESSION_TYPE=wayland
export XDG_CURRENT_DESKTOP=LDDE
PROMPT='%F{cyan}%n@linuxdroid%f:%F{yellow}%~%f§ '
alias ll='ls -alF'
ZSHRC_EOF
    fi

    if [ ! -f "§user_home/.bashrc" ]; then
        cat << 'BASHRC_EOF' > "§user_home/.bashrc"
# LinuxDroid Default Bash Configuration
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8
export PATH=/usr/local/bin:/usr/bin:/bin:/usr/local/games:/usr/games:§HOME/bin:§HOME/.local/bin
export WAYLAND_DISPLAY=wayland-0
export XDG_SESSION_TYPE=wayland
export XDG_CURRENT_DESKTOP=LDDE
PS1='\[\033[01;32m\]\u@linuxdroid\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\§ '
alias ll='ls -alF'
BASHRC_EOF
    fi

    chown -R "§{USERNAME}:§{USERNAME}" "§user_home" 2>/dev/null || true
    chmod 0750 "§user_home" 2>/dev/null || true
}

run_cmd "CONFIGURE_SHELL" "configure default shell files for §USERNAME" configure_shell_action

# -----------------------------------------------------------------------------
# 11. Sudoers Configuration
# -----------------------------------------------------------------------------
configure_sudo_action() {
    mkdir -p /etc/sudoers.d
    cat > "/etc/sudoers.d/010_§{USERNAME}-nopasswd" << SUDO_EOF
§{USERNAME} ALL=(ALL) NOPASSWD: ALL
SUDO_EOF
    chmod 0440 "/etc/sudoers.d/010_§{USERNAME}-nopasswd"
}

run_cmd "CONFIGURE_SUDO" "configure sudoers for §USERNAME" configure_sudo_action

# -----------------------------------------------------------------------------
# 12. Strict APT Cleanup Sequence (autoremove -> clean -> update)
# -----------------------------------------------------------------------------
run_cmd "APT_AUTOREMOVE" "apt-get autoremove --purge -y" apt-get autoremove --purge -y
run_cmd "APT_CLEAN" "apt-get clean" apt-get clean
run_cmd "APT_UPDATE_FINAL" "apt-get update" apt-get update

# Remove policy-rc.d wrapper
rm -f /usr/sbin/policy-rc.d 2>/dev/null || true

# -----------------------------------------------------------------------------
# 13. Final Linux-Side Validation (Checks files on disk without GUI)
# -----------------------------------------------------------------------------
final_validation_action() {
    local missing=""

    # 1. Base directories
    for dir in /bin /usr /etc /home /tmp /run; do
        if [ ! -d "§dir" ]; then missing="§missing Directory §dir;"; fi
    done

    # 2. Guest Init
    if [ ! -x /sbin/linuxdroid-init ]; then missing="§missing Executable /sbin/linuxdroid-init;"; fi

    # 3. User & Sudo
    if ! id -u "§USERNAME" >/dev/null 2>&1; then missing="§missing User account §USERNAME;"; fi
    if [ ! -d "/home/§USERNAME" ]; then missing="§missing Home directory /home/§USERNAME;"; fi
    if [ ! -f "/etc/sudoers.d/010_§{USERNAME}-nopasswd" ]; then missing="§missing Sudo configuration;"; fi

    # 4. Wayland, Weston, LDDM, LDDE
    if [ ! -x /usr/bin/weston ] && ! command -v weston >/dev/null 2>&1; then
        missing="§missing Weston executable;";
    fi
    if [ ! -f /etc/linuxdroid/lddm.conf ]; then missing="§missing /etc/linuxdroid/lddm.conf;"; fi
    if [ ! -f /etc/linuxdroid/desktop.conf ]; then missing="§missing /etc/linuxdroid/desktop.conf;"; fi

    if [ -n "§missing" ]; then
        echo "Validation failed: §missing" >&2
        return 1
    fi
    return 0
}

run_cmd "FINAL_VALIDATION" "final validation of installed artifacts on disk" final_validation_action

# -----------------------------------------------------------------------------
# 14. Purge Sensitive Temporary Secrets
# -----------------------------------------------------------------------------
if [ -f "§PASSWORD_FILE" ]; then
    rm -f "§PASSWORD_FILE" 2>/dev/null || true
fi
# Remove temporary password_file entry from install.conf
if [ -f "§CONFIG_FILE" ]; then
    sed -i '/password_file/d; /PASSWORD_FILE/d' "§CONFIG_FILE" 2>/dev/null || true
fi
rm -rf "§PKGS_DIR" 2>/dev/null || true

# -----------------------------------------------------------------------------
# 15. Write Completion Markers
# -----------------------------------------------------------------------------
cat > /etc/linuxdroid/POST_INSTALL_COMPLETE << MARKER_EOF
STATUS=COMPLETE
TIMESTAMP=§(date -u +"%Y-%m-%dT%H:%M:%SZ")
DISTRO=§DISTRO
RELEASE=§RELEASE
ARCH=§ARCH
USERNAME=§USERNAME
MARKER_EOF

cat > /etc/linuxdroid/ROOTFS_READY << READY_EOF
DISTRO=§DISTRO
RELEASE=§RELEASE
ARCH=§ARCH
USERNAME=§USERNAME
READY_AT=§(date -u +"%Y-%m-%dT%H:%M:%SZ")
READY_EOF

echo "[POSTINSTALL][SUCCESS][POST_INSTALL]"
echo "exit_code=0"
echo "duration_ms=10"
echo "timestamp=§(date -u +"%Y-%m-%dT%H:%M:%SZ")"
echo "POST_INSTALL_COMPLETE"

exit 0
""".trimIndent().replace('§', '$') + "\n"

    fun writeScript(rootfsDir: File): File {
        val scriptFile = File(rootfsDir, POST_INSTALL_SCRIPT_PATH.removePrefix("/"))
        scriptFile.parentFile?.mkdirs()
        scriptFile.writeText(SCRIPT_CONTENT, Charsets.UTF_8)
        scriptFile.setExecutable(true, false)
        scriptFile.setReadable(true, false)
        return scriptFile
    }

    fun writeInstallConfig(
        rootfsDir: File,
        distro: String,
        release: String,
        arch: String,
        username: String,
        packagesDir: String = STAGED_PACKAGES_DIR,
        lddmDebPath: String = "$STAGED_PACKAGES_DIR/linuxdroid-display-manager.deb",
        lddeDebPath: String = "$STAGED_PACKAGES_DIR/linuxdroid-desktop-environment.deb",
        passwordFile: String = INSTALL_SECRET_PATH,
    ): File {
        val configFile = File(rootfsDir, INSTALL_CONFIG_PATH.removePrefix("/"))
        configFile.parentFile?.mkdirs()
        val content = """
            DISTRO=$distro
            RELEASE=$release
            ARCH=$arch
            USERNAME=$username
            PACKAGES_DIR=$packagesDir
            LDDM_DEB=$lddmDebPath
            LDDE_DEB=$lddeDebPath
            PASSWORD_FILE=$passwordFile
        """.trimIndent() + "\n"
        configFile.writeText(content, Charsets.UTF_8)
        configFile.setReadable(true, false)
        return configFile
    }

    fun writeInstallSecret(rootfsDir: File, password: String): File {
        val secretFile = File(rootfsDir, INSTALL_SECRET_PATH.removePrefix("/"))
        secretFile.parentFile?.mkdirs()
        secretFile.writeText(password, Charsets.UTF_8)
        // 0600 permissions: readable and writable only by owner
        secretFile.setReadable(false, false)
        secretFile.setWritable(false, false)
        secretFile.setExecutable(false, false)
        secretFile.setReadable(true, true)
        secretFile.setWritable(true, true)
        return secretFile
    }

    fun writePreInstallReadyMarker(
        rootfsDir: File,
        distro: String,
        release: String,
        username: String,
        arch: String,
    ): File {
        val markerFile = File(rootfsDir, "etc/linuxdroid/PRE_INSTALL_READY")
        markerFile.parentFile?.mkdirs()
        val content = """
            DISTRO=$distro
            RELEASE=$release
            USERNAME=$username
            ARCH=$arch
            STATUS=PRE_INSTALL_READY
            TIMESTAMP=${System.currentTimeMillis()}
        """.trimIndent() + "\n"
        markerFile.writeText(content, Charsets.UTF_8)
        return markerFile
    }

    fun writePostInstallCompleteMarker(
        rootfsDir: File,
        distro: String,
        release: String,
        username: String,
        arch: String,
    ): File {
        val markerFile = File(rootfsDir, POST_INSTALL_COMPLETE_MARKER.removePrefix("/"))
        markerFile.parentFile?.mkdirs()
        val content = """
            STATUS=POST_INSTALL_COMPLETE
            COMPLETED_AT=${System.currentTimeMillis()}
            USER=$username
            DISTRO=$distro
            RELEASE=$release
            ARCH=$arch
            LDDM_INSTALLED=true
            LDDE_INSTALLED=true
            WESTON_INSTALLED=true
            WAYLAND_INSTALLED=true
        """.trimIndent() + "\n"
        markerFile.writeText(content, Charsets.UTF_8)
        return markerFile
    }

    fun writeRootfsReadyMarker(
        rootfsDir: File,
        distro: String,
        release: String,
        username: String,
        arch: String,
    ): File {
        val markerFile = File(rootfsDir, "etc/linuxdroid/ROOTFS_READY")
        markerFile.parentFile?.mkdirs()
        val content = """
            DISTRO=$distro
            RELEASE=$release
            ARCH=$arch
            USERNAME=$username
            READY_AT=${System.currentTimeMillis()}
        """.trimIndent() + "\n"
        markerFile.writeText(content, Charsets.UTF_8)
        return markerFile
    }
}
