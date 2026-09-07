package com.linuxdroid.core.runtime

import java.io.File

/**
 * Authoritative template and definition for `/etc/linuxdroid/post-install.sh`.
 *
 * This script runs exclusively inside the guest Linux userspace via PRoot
 * under Guest Init (`/sbin/linuxdroid-init CLI /bin/bash /etc/linuxdroid/post-install.sh`).
 *
 * Implements the CLI foundation provisioning:
 * - Reads `/etc/linuxdroid/install.conf`
 * - Configures APT execution safeguards
 * - Installs standard CLI package baseline (33 packages)
 * - Creates the requested non-root user with zsh and sudo
 * - Sets user & root password securely via chpasswd from a temporary secret file
 * - Exact APT cleanup: autoremove -> clean -> update
 * - Final CLI-only disk validation (no GUI packages required)
 * - Unlinks temporary secret and writes POST_INSTALL_COMPLETE
 *
 * GUI installation is NOT performed here. GUI is an optional layer installed
 * separately by GuiInstaller after the CLI environment is ready.
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
# LinuxDroid — In-Guest CLI Provisioning Script
# =============================================================================
# Installs the CLI foundation only. GUI is installed separately by GuiInstaller.
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
echo "LINUXDROID IN-GUEST CLI PROVISIONING STARTING"
echo "Timestamp: §(date -u +"%Y-%m-%dT%H:%M:%SZ")"
echo "Note: GUI packages are NOT installed here. Use GUI installer for graphical layer."
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
# 2. Configure APT Execution Safeguards
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
# 3. APT Update
# -----------------------------------------------------------------------------
run_cmd "APT_UPDATE" "apt-get update" apt-get update

# -----------------------------------------------------------------------------
# 4. Install Standard CLI Linux Packages (33 baseline)
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
# 5. User Creation & Group Assignment
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
# 6. Password Configuration via chpasswd (Passwords strictly redacted)
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
# 7. Default Shell & Environment Files
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
PS1='\[\033[01;32m\]\u@linuxdroid\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\§ '
alias ll='ls -alF'
BASHRC_EOF
    fi

    chown -R "§{USERNAME}:§{USERNAME}" "§user_home" 2>/dev/null || true
    chmod 0750 "§user_home" 2>/dev/null || true
}

run_cmd "CONFIGURE_SHELL" "configure default shell files for §USERNAME" configure_shell_action

# -----------------------------------------------------------------------------
# 8. Sudoers Configuration
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
# 9. Strict APT Cleanup Sequence (autoremove -> clean -> update)
# -----------------------------------------------------------------------------
run_cmd "APT_AUTOREMOVE" "apt-get autoremove --purge -y" apt-get autoremove --purge -y
run_cmd "APT_CLEAN" "apt-get clean" apt-get clean
run_cmd "APT_UPDATE_FINAL" "apt-get update" apt-get update

# Remove policy-rc.d wrapper
rm -f /usr/sbin/policy-rc.d 2>/dev/null || true

# -----------------------------------------------------------------------------
# 10. Final CLI Validation (no GUI packages required)
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

    if [ -n "§missing" ]; then
        echo "CLI validation failed: §missing" >&2
        return 1
    fi
    return 0
}

run_cmd "FINAL_VALIDATION" "CLI validation of installed artifacts on disk" final_validation_action

# -----------------------------------------------------------------------------
# 11. Purge Sensitive Temporary Secrets
# -----------------------------------------------------------------------------
if [ -f "§PASSWORD_FILE" ]; then
    rm -f "§PASSWORD_FILE" 2>/dev/null || true
fi
# Remove temporary password_file entry from install.conf
if [ -f "§CONFIG_FILE" ]; then
    sed -i '/password_file/d; /PASSWORD_FILE/d' "§CONFIG_FILE" 2>/dev/null || true
fi

# -----------------------------------------------------------------------------
# 12. Write CLI Completion Markers
# -----------------------------------------------------------------------------
mkdir -p /etc/linuxdroid

cat > /etc/linuxdroid/POST_INSTALL_COMPLETE << MARKER_EOF
STATUS=COMPLETE
TIMESTAMP=§(date -u +"%Y-%m-%dT%H:%M:%SZ")
DISTRO=§DISTRO
RELEASE=§RELEASE
ARCH=§ARCH
USERNAME=§USERNAME
GUI_INSTALLED=false
MARKER_EOF

cat > /etc/linuxdroid/ROOTFS_READY << READY_EOF
DISTRO=§DISTRO
RELEASE=§RELEASE
ARCH=§ARCH
USERNAME=§USERNAME
READY_AT=§(date -u +"%Y-%m-%dT%H:%M:%SZ")
GUI_INSTALLED=false
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
        passwordFile: String = INSTALL_SECRET_PATH,
    ): File {
        val configFile = File(rootfsDir, INSTALL_CONFIG_PATH.removePrefix("/"))
        configFile.parentFile?.mkdirs()
        val content = """
            DISTRO=$distro
            RELEASE=$release
            ARCH=$arch
            USERNAME=$username
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
            GUI_INSTALLED=false
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
            GUI_INSTALLED=false
        """.trimIndent() + "\n"
        markerFile.writeText(content, Charsets.UTF_8)
        return markerFile
    }
}
