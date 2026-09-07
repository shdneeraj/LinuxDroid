#!/usr/bin/env bash
# =============================================================================
# LinuxDroid — Unified Production-Grade Rootfs Installer
# =============================================================================
# Single entry point for installing and configuring a complete, validated,
# ready-to-run Debian or Ubuntu Linux ARM64 rootfs for LinuxDroid.
#
# Runs directly on target Android ARM64 devices through the existing PRoot
# runtime architecture.
#
# Supported Distributions:
#  - Debian: 13 (trixie), 12 (bookworm)
#  - Ubuntu: 24.04 (noble), 22.04 (jammy)
# Architecture: arm64 (fixed internally)
# =============================================================================

set -euo pipefail

SCRIPT_VERSION="1.0.0"
ARCH="arm64"

# -----------------------------------------------------------------------------
# Global Variables
# -----------------------------------------------------------------------------
DISTRO=""
RELEASE=""
USERNAME=""
PASSWORD=""
ROOTFS_DIR=""
CONFIG_FILE=""
PROOT_BIN=""
DEB_DIR=""
LDDM_DEB=""
LDDE_DEB=""
IN_GUEST=false
SKIP_DOWNLOAD=false
FORCE=false
VERBOSE=false

# Stage markers for Android UI / Process monitoring
STAGE_STARTING="ROOTFS_STARTING"
STAGE_CREATING="ROOTFS_CREATING"
STAGE_EXTRACTED="ROOTFS_EXTRACTED"
STAGE_CONFIGURING="ROOTFS_CONFIGURING"
STAGE_RUNTIME_READY="ROOTFS_RUNTIME_READY"
STAGE_PACKAGES="ROOTFS_PACKAGES_INSTALLING"
STAGE_GRAPHICS="ROOTFS_GRAPHICS_DEPLOYING"
STAGE_VALIDATING="ROOTFS_VALIDATING"
STAGE_CLEANUP="ROOTFS_CLEANING"
STAGE_READY="ROOTFS_READY"
STAGE_FAILED="ROOTFS_DEPLOYMENT_FAILED"

# Logging helpers
log_info()    { echo ">>> [INFO] $*"; }
log_step()    { echo ">>> [STAGE] $*"; }
log_pass()    { echo ">>> [PASS] $*"; }
log_warn()    { echo ">>> [WARN] $*" >&2; }
log_error()   { echo ">>> [ERROR] $*" >&2; }
log_fatal()   { echo ">>> [STAGE] ${STAGE_FAILED}"; echo ">>> [FATAL] $*" >&2; exit 1; }

# -----------------------------------------------------------------------------
# Configuration & Argument Parsing
# -----------------------------------------------------------------------------
usage() {
    cat << 'EOF'
LinuxDroid Rootfs Installer v1.0.0
Usage: install_rootfs.sh [OPTIONS]

Required Options:
  --distro <debian|ubuntu>     Linux distribution (Debian or Ubuntu)
  --release <release>          Distribution release (e.g. trixie, bookworm, noble, jammy)
  --username <name>            Target Linux username (non-root)
  --password <pass>            Password for target user
  --rootfs <path>              Target rootfs directory path

Optional Options:
  --config <file>              Path to config file with key=value settings
  --proot <path>               Path to PRoot binary (for host execution)
  --pkgs-dir <path>            Directory containing bundled LDDM and LDDE .deb packages
  --lddm-deb <path>            Explicit path to LDDM .deb package
  --ldde-deb <path>            Explicit path to LDDE .deb package
  --in-guest                   Execute inside guest/PRoot environment
  --skip-download              Skip rootfs download if base directory already populated
  --force                      Overwrite existing rootfs or ready marker
  --verbose                    Enable verbose output
  -h, --help                   Show this help message
EOF
}

load_config_file() {
    if [[ -n "${CONFIG_FILE}" && -f "${CONFIG_FILE}" ]]; then
        log_info "Loading configuration from ${CONFIG_FILE}"
        while IFS='=' read -r key value || [[ -n "$key" ]]; do
            [[ "$key" =~ ^[[:space:]]*# ]] && continue
            [[ -z "${key// }" ]] && continue
            key="$(echo "$key" | xargs)"
            value="$(echo "$value" | xargs)"
            case "$key" in
                distro|DISTRO)       DISTRO="${DISTRO:-$value}" ;;
                release|RELEASE)     RELEASE="${RELEASE:-$value}" ;;
                username|USERNAME)   USERNAME="${USERNAME:-$value}" ;;
                password|PASSWORD)   PASSWORD="${PASSWORD:-$value}" ;;
                rootfs|ROOTFS_DIR)   ROOTFS_DIR="${ROOTFS_DIR:-$value}" ;;
                pkgs_dir|DEB_DIR)    DEB_DIR="${DEB_DIR:-$value}" ;;
                lddm_deb|LDDM_DEB)   LDDM_DEB="${LDDM_DEB:-$value}" ;;
                ldde_deb|LDDE_DEB)   LDDE_DEB="${LDDE_DEB:-$value}" ;;
                proot|PROOT_BIN)     PROOT_BIN="${PROOT_BIN:-$value}" ;;
            esac
        done < "${CONFIG_FILE}"
    fi
}

load_env_fallbacks() {
    DISTRO="${DISTRO:-${LINUXDROID_DISTRO:-}}"
    RELEASE="${RELEASE:-${LINUXDROID_RELEASE:-}}"
    USERNAME="${USERNAME:-${LINUXDROID_USERNAME:-}}"
    PASSWORD="${PASSWORD:-${LINUXDROID_PASSWORD:-}}"
    ROOTFS_DIR="${ROOTFS_DIR:-${LINUXDROID_ROOTFS:-}}"
    DEB_DIR="${DEB_DIR:-${LINUXDROID_DEB_DIR:-}}"
    LDDM_DEB="${LDDM_DEB:-${LINUXDROID_LDDM_DEB:-}}"
    LDDE_DEB="${LDDE_DEB:-${LINUXDROID_LDDE_DEB:-}}"
    PROOT_BIN="${PROOT_BIN:-${LINUXDROID_PROOT:-}}"
}

parse_arguments() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --distro)
                DISTRO="$2"; shift 2 ;;
            --release)
                RELEASE="$2"; shift 2 ;;
            --username)
                USERNAME="$2"; shift 2 ;;
            --password)
                PASSWORD="$2"; shift 2 ;;
            --rootfs)
                ROOTFS_DIR="$2"; shift 2 ;;
            --config)
                CONFIG_FILE="$2"; shift 2 ;;
            --proot)
                PROOT_BIN="$2"; shift 2 ;;
            --pkgs-dir)
                DEB_DIR="$2"; shift 2 ;;
            --lddm-deb)
                LDDM_DEB="$2"; shift 2 ;;
            --ldde-deb)
                LDDE_DEB="$2"; shift 2 ;;
            --in-guest)
                IN_GUEST=true; shift ;;
            --skip-download)
                SKIP_DOWNLOAD=true; shift ;;
            --force)
                FORCE=true; shift ;;
            --verbose)
                VERBOSE=true; shift ;;
            -h|--help)
                usage; exit 0 ;;
            *)
                log_error "Unknown argument: $1"
                usage
                exit 1 ;;
        esac
    done
}

# -----------------------------------------------------------------------------
# Input Validation & Dynamic Release Discovery
# -----------------------------------------------------------------------------
validate_inputs() {
    log_info "Validating installation inputs..."

    # Normalize distro name
    DISTRO="$(echo "${DISTRO}" | tr '[:upper:]' '[:lower:]' | xargs)"
    RELEASE="$(echo "${RELEASE}" | tr '[:upper:]' '[:lower:]' | xargs)"

    case "${DISTRO}" in
        debian)
            case "${RELEASE}" in
                13|trixie)   RELEASE="trixie" ;;
                12|bookworm) RELEASE="bookworm" ;;
                *)
                    log_fatal "Unsupported Debian release '${RELEASE}'. Supported: trixie (13), bookworm (12)."
                    ;;
            esac
            ;;
        ubuntu)
            case "${RELEASE}" in
                24.04|noble) RELEASE="noble" ;;
                22.04|jammy) RELEASE="jammy" ;;
                *)
                    log_fatal "Unsupported Ubuntu release '${RELEASE}'. Supported: noble (24.04), jammy (22.04)."
                    ;;
            esac
            ;;
        kali)
            log_fatal "Kali Linux is explicitly removed from LinuxDroid V1 installer."
            ;;
        *)
            log_fatal "Unsupported distribution '${DISTRO}'. Supported: debian, ubuntu."
            ;;
    esac

    # Validate Username
    if [[ -z "${USERNAME}" ]]; then
        log_fatal "Username cannot be empty. Please specify --username <name>."
    fi
    if ! [[ "${USERNAME}" =~ ^[a-z_][a-z0-9_-]{0,31}$ ]]; then
        log_fatal "Invalid username '${USERNAME}'. Must start with letter/underscore and contain only lowercase alphanumeric, underscores, or hyphens (max 32 chars)."
    fi
    if [[ "${USERNAME}" == "root" ]]; then
        log_fatal "Cannot use 'root' as the user account. Please specify a non-root username."
    fi

    # Validate Password
    if [[ -z "${PASSWORD}" ]]; then
        log_fatal "Password cannot be empty. Please specify --password <pass>."
    fi

    # Validate Rootfs Directory
    if [[ -z "${ROOTFS_DIR}" ]]; then
        log_fatal "Rootfs directory path is required. Please specify --rootfs <path>."
    fi

    log_pass "Input validation successful: distro=${DISTRO}, release=${RELEASE}, user=${USERNAME}, arch=${ARCH}"
}

# -----------------------------------------------------------------------------
# Base Rootfs Acquisition & Extraction
# -----------------------------------------------------------------------------
acquire_rootfs() {
    log_step "${STAGE_CREATING}"
    mkdir -p "${ROOTFS_DIR}"

    if [[ -f "${ROOTFS_DIR}/etc/os-release" && "${SKIP_DOWNLOAD}" == true ]]; then
        log_info "Found existing rootfs in ${ROOTFS_DIR} and --skip-download is set. Skipping download."
        return 0
    fi

    if [[ -f "${ROOTFS_DIR}/etc/os-release" && -f "${ROOTFS_DIR}/etc/linuxdroid/ROOTFS_READY" && "${FORCE}" != true ]]; then
        log_info "Rootfs already marked READY in ${ROOTFS_DIR}. Use --force to reinstall."
        log_step "${STAGE_READY}"
        exit 0
    fi

    local tarball_url=""
    local cache_tarball="/tmp/linuxdroid_${DISTRO}_${RELEASE}_${ARCH}.tar.xz"
    local tarball_alt="/tmp/linuxdroid_${DISTRO}_${RELEASE}_${ARCH}.tar.gz"

    if [[ "${DISTRO}" == "debian" ]]; then
        tarball_url="https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/dist-arm64v8/${RELEASE}/rootfs.tar.xz"
    elif [[ "${DISTRO}" == "ubuntu" ]]; then
        tarball_url="https://cloud-images.ubuntu.com/minimal/releases/${RELEASE}/release/ubuntu-${RELEASE}-minimal-cloudimg-arm64-root.tar.xz"
    fi

    local source_archive=""
    if [[ -f "${cache_tarball}" ]]; then
        source_archive="${cache_tarball}"
        log_info "Using cached rootfs archive: ${source_archive}"
    elif [[ -f "${tarball_alt}" ]]; then
        source_archive="${tarball_alt}"
        log_info "Using cached rootfs archive: ${source_archive}"
    fi

    if [[ -z "${source_archive}" ]]; then
        log_info "Acquiring base ${DISTRO} (${RELEASE}) ${ARCH} rootfs from: ${tarball_url}"
        if command -v curl >/dev/null 2>&1; then
            curl -fSL --retry 3 -o "${cache_tarball}" "${tarball_url}" || {
                log_warn "Direct curl download failed from primary URL. Checking fallback mirrors..."
                if [[ "${DISTRO}" == "ubuntu" ]]; then
                    tarball_url="https://cloud-images.ubuntu.com/releases/${RELEASE}/release/ubuntu-${RELEASE}-server-cloudimg-arm64-root.tar.xz"
                    curl -fSL --retry 3 -o "${cache_tarball}" "${tarball_url}"
                else
                    tarball_url="https://github.com/debuerreotype/docker-debian-artifacts/raw/dist-arm64v8/${RELEASE}/slim/rootfs.tar.xz"
                    curl -fSL --retry 3 -o "${cache_tarball}" "${tarball_url}"
                fi
            }
            source_archive="${cache_tarball}"
        elif command -v wget >/dev/null 2>&1; then
            wget -q --tries=3 -O "${cache_tarball}" "${tarball_url}"
            source_archive="${cache_tarball}"
        else
            log_fatal "Neither curl nor wget available to download rootfs."
        fi
    fi

    log_info "Extracting base rootfs into ${ROOTFS_DIR}..."
    tar -xpf "${source_archive}" -C "${ROOTFS_DIR}" --exclude='./dev/*' || {
        log_warn "Standard extraction had non-fatal warnings. Continuing..."
    }

    log_step "${STAGE_EXTRACTED}"
    log_pass "Base rootfs extracted successfully."
}

# -----------------------------------------------------------------------------
# Host-Level PRoot Bridge Configuration
# -----------------------------------------------------------------------------
prepare_guest_environment() {
    log_step "${STAGE_CONFIGURING}"
    log_info "Preparing guest environment mounts and DNS..."

    mkdir -p "${ROOTFS_DIR}/dev" \
             "${ROOTFS_DIR}/dev/pts" \
             "${ROOTFS_DIR}/dev/shm" \
             "${ROOTFS_DIR}/proc" \
             "${ROOTFS_DIR}/sys" \
             "${ROOTFS_DIR}/tmp" \
             "${ROOTFS_DIR}/run" \
             "${ROOTFS_DIR}/etc/linuxdroid"

    chmod 1777 "${ROOTFS_DIR}/tmp"

    cat > "${ROOTFS_DIR}/etc/resolv.conf" << 'EOF'
nameserver 1.1.1.1
nameserver 8.8.8.8
nameserver 8.8.4.4
EOF

    echo "linuxdroid" > "${ROOTFS_DIR}/etc/hostname"
    cat > "${ROOTFS_DIR}/etc/hosts" << 'EOF'
127.0.0.1   localhost linuxdroid
::1         localhost ip6-localhost ip6-loopback
EOF

    local staging_pkg_dir="${ROOTFS_DIR}/tmp/linuxdroid-packages"
    mkdir -p "${staging_pkg_dir}"

    if [[ -n "${LDDM_DEB}" && -f "${LDDM_DEB}" ]]; then
        cp "${LDDM_DEB}" "${staging_pkg_dir}/"
    elif [[ -n "${DEB_DIR}" && -d "${DEB_DIR}" ]]; then
        local found_lddm
        found_lddm="$(find "${DEB_DIR}" -type f -name "linuxdroid-display-manager*.deb" | head -n 1 || true)"
        if [[ -n "${found_lddm}" ]]; then
            cp "${found_lddm}" "${staging_pkg_dir}/"
        fi
    fi

    if [[ -n "${LDDE_DEB}" && -f "${LDDE_DEB}" ]]; then
        cp "${LDDE_DEB}" "${staging_pkg_dir}/"
    elif [[ -n "${DEB_DIR}" && -d "${DEB_DIR}" ]]; then
        local found_ldde
        found_ldde="$(find "${DEB_DIR}" -type f -name "linuxdroid-desktop-environment*.deb" | head -n 1 || true)"
        if [[ -n "${found_ldde}" ]]; then
            cp "${found_ldde}" "${staging_pkg_dir}/"
        fi
    fi

    cp "$0" "${ROOTFS_DIR}/tmp/install_rootfs.sh"
    chmod +x "${ROOTFS_DIR}/tmp/install_rootfs.sh"

    log_pass "Guest staging environment prepared."
}

# -----------------------------------------------------------------------------
# PRoot Execution Wrapper
# -----------------------------------------------------------------------------
execute_in_guest() {
    local cmd="$*"
    if [[ -z "${PROOT_BIN}" ]]; then
        if command -v proot >/dev/null 2>&1; then
            PROOT_BIN="$(command -v proot)"
        else
            log_fatal "PRoot binary not specified and not found in PATH."
        fi
    fi

    log_info "Executing inside PRoot: ${cmd}"
    "${PROOT_BIN}" \
        -r "${ROOTFS_DIR}" \
        -0 \
        -b /dev \
        -b /dev/urandom:/dev/random \
        -b /proc \
        -b /sys \
        -b /dev/pts \
        -b /dev/shm \
        -w /root \
        /bin/bash -c "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; ${cmd}"
}

# -----------------------------------------------------------------------------
# Guest In-Chroot Provisioning Steps (Executed when IN_GUEST=true)
# -----------------------------------------------------------------------------
guest_configure_apt() {
    log_info "[Guest] Configuring APT sources for ${DISTRO} ${RELEASE}..."
    export DEBIAN_FRONTEND=noninteractive

    cat > /usr/sbin/policy-rc.d << 'EOF'
#!/bin/sh
exit 101
EOF
    chmod +x /usr/sbin/policy-rc.d

    rm -f /etc/dpkg/dpkg.cfg.d/excludes || true

    if [[ "${DISTRO}" == "debian" ]]; then
        cat > /etc/apt/sources.list << EOF
deb http://deb.debian.org/debian ${RELEASE} main contrib non-free non-free-firmware
deb http://deb.debian.org/debian ${RELEASE}-updates main contrib non-free non-free-firmware
deb http://security.debian.org/debian-security ${RELEASE}-security main contrib non-free non-free-firmware
EOF
    elif [[ "${DISTRO}" == "ubuntu" ]]; then
        cat > /etc/apt/sources.list << EOF
deb http://ports.ubuntu.com/ubuntu-ports/ ${RELEASE} main restricted universe multiverse
deb http://ports.ubuntu.com/ubuntu-ports/ ${RELEASE}-updates main restricted universe multiverse
deb http://ports.ubuntu.com/ubuntu-ports/ ${RELEASE}-security main restricted universe multiverse
EOF
    fi

    cat > /etc/apt/apt.conf.d/01linuxdroid << 'EOF'
APT::Install-Recommends "0";
APT::Install-Suggests "0";
APT::Get::Assume-Yes "true";
Dpkg::Options {
   "--force-confdef";
   "--force-confold";
}
EOF

    log_info "[Guest] Updating APT package index..."
    apt-get update
    log_pass "[Guest] APT configured and updated."
}

guest_install_core_packages() {
    log_step "${STAGE_PACKAGES}"
    log_info "[Guest] Installing LinuxDroid standard package baseline..."
    export DEBIAN_FRONTEND=noninteractive

    local core_pkgs=(
        bash
        zsh
        coreutils
        util-linux
        procps
        psmisc
        findutils
        grep
        sed
        gawk
        file
        less
        sudo
        curl
        wget
        openssl
        ca-certificates
        iproute2
        iputils-ping
        openssh-client
        apt
        gnupg
        locales
        tzdata
    )

    if [[ "${DISTRO}" == "ubuntu" ]]; then
        core_pkgs+=(ubuntu-keyring)
    else
        core_pkgs+=(debian-archive-keyring)
    fi

    local shell_pkgs=(
        net-tools
        dnsutils
    )

    local dev_pkgs=(
        git
        build-essential
        pkg-config
        python3
    )

    local archive_pkgs=(
        tar
        gzip
        bzip2
        xz-utils
        zip
        unzip
    )

    local doc_pkgs=(
        nano
        vim-tiny
        man-db
        manpages
    )

    local dbus_pkgs=(
        dbus
        dbus-x11
        libdbus-1-3
    )

    log_info "[Guest] Installing base system, network, dev, archive, and dbus packages..."
    apt-get install -y \
        "${core_pkgs[@]}" \
        "${shell_pkgs[@]}" \
        "${dev_pkgs[@]}" \
        "${archive_pkgs[@]}" \
        "${doc_pkgs[@]}" \
        "${dbus_pkgs[@]}"

    log_pass "[Guest] Standard package baseline installed."
}

guest_install_graphics_stack() {
    log_step "${STAGE_GRAPHICS}"
    log_info "[Guest] Installing Wayland and Weston distribution packages..."
    export DEBIAN_FRONTEND=noninteractive

    local wayland_pkgs=(
        libwayland-client0
        libwayland-server0
        libwayland-cursor0
        wayland-protocols
    )

    local weston_pkgs=(
        weston
        xwayland
    )

    apt-get install -y \
        "${wayland_pkgs[@]}" \
        "${weston_pkgs[@]}" \
        fonts-dejavu-core

    mkdir -p /etc/xdg/weston
    if [[ ! -f /etc/xdg/weston/weston.ini ]]; then
        cat <<'EOF' > /etc/xdg/weston/weston.ini
# LinuxDroid Default Weston Configuration
[core]
idle-time=0
require-input=false
backend=headless-backend.so

[shell]
locking=false
EOF
        chmod 0644 /etc/xdg/weston/weston.ini
    fi

    log_pass "[Guest] Wayland and Weston packages installed."
}

guest_install_deb_idempotent() {
    local pkg_name="$1"
    local deb_path="$2"

    if [[ -z "${deb_path}" || ! -f "${deb_path}" ]]; then
        log_warn "[Guest] Bundled package file for '${pkg_name}' not found."
        return 0
    fi

    # Handle interrupted dpkg/apt states
    if [[ -f /var/lib/dpkg/lock || -f /var/lib/dpkg/lock-frontend ]]; then
        log_warn "[Guest] Stale dpkg lock detected. Recovering package manager state..."
        rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend
        dpkg --configure -a || true
        apt-get install -f -y || true
    fi

    local bundled_version
    bundled_version="$(dpkg-deb -f "${deb_path}" Version 2>/dev/null || true)"

    local installed_status
    local installed_version
    if dpkg -s "${pkg_name}" >/dev/null 2>&1; then
        installed_status="$(dpkg-query -W -f='${Status}' "${pkg_name}" 2>/dev/null || true)"
        installed_version="$(dpkg-query -W -f='${Version}' "${pkg_name}" 2>/dev/null || true)"
    fi

    if [[ "${installed_status}" == "install ok installed" && -n "${installed_version}" && -n "${bundled_version}" ]]; then
        if dpkg --compare-versions "${installed_version}" eq "${bundled_version}"; then
            log_pass "[Guest] Package '${pkg_name}' is already at target version (${installed_version}). Preserving."
            return 0
        elif dpkg --compare-versions "${installed_version}" gt "${bundled_version}"; then
            log_warn "[Guest] Existing '${pkg_name}' (${installed_version}) is newer than bundled (${bundled_version}). Skipping downgrade."
            return 0
        else
            log_info "[Guest] Upgrading '${pkg_name}': ${installed_version} -> ${bundled_version}"
        fi
    else
        log_info "[Guest] Installing '${pkg_name}' (${bundled_version})..."
    fi

    apt-get install -y "${deb_path}" || {
        log_warn "Direct apt install had dependency issues. Running apt-get install -f..."
        apt-get install -f -y
    }
}

guest_install_bundled_packages() {
    log_info "[Guest] Installing bundled LinuxDroid packages (LDDM & LDDE)..."
    export DEBIAN_FRONTEND=noninteractive

    local pkg_dir="/tmp/linuxdroid-packages"
    local lddm_deb=""
    local ldde_deb=""

    if [[ -d "${pkg_dir}" ]]; then
        lddm_deb="$(find "${pkg_dir}" -name "linuxdroid-display-manager*.deb" | head -n 1 || true)"
        ldde_deb="$(find "${pkg_dir}" -name "linuxdroid-desktop-environment*.deb" | head -n 1 || true)"
    fi

    guest_install_deb_idempotent "linuxdroid-display-manager" "${lddm_deb}"
    guest_install_deb_idempotent "linuxdroid-desktop-environment" "${ldde_deb}"

    mkdir -p /etc/linuxdroid
    cat <<EOF > /etc/linuxdroid/lddm.conf
# LinuxDroid Display Manager Configuration
[server]
socket_path = /run/lddm/lddm.sock
runtime_dir = /run/lddm
pid_file = /run/lddm/lddm.pid

[session]
default_user = ${USERNAME:-root}
session_type = wayland
display_number = 0
wayland_display = wayland-0

[weston]
executable = /usr/bin/weston
config_path = /etc/xdg/weston/weston.ini
socket_name = wayland-0
backend = headless-backend.so

[ldde]
executable = /usr/bin/ldde
session_target = default
autostart = true

[process]
startup_timeout_ms = 10000
stop_timeout_ms = 5000
max_restart_count = 3
restart_window_seconds = 60
EOF
    chmod 0644 /etc/linuxdroid/lddm.conf

    # Ensure compatibility symlink ldde-session -> ldde
    if [[ -x /usr/bin/ldde && ! -e /usr/bin/ldde-session ]]; then
        ln -sf /usr/bin/ldde /usr/bin/ldde-session
    fi

    log_pass "[Guest] Bundled LinuxDroid packages installed and configured."
}

guest_configure_user() {
    log_info "[Guest] Configuring user account '${USERNAME}' with Zsh shell and sudo..."

    if ! getent group "${USERNAME}" >/dev/null 2>&1; then
        groupadd -g 1000 "${USERNAME}" || groupadd "${USERNAME}"
    fi

    local zsh_path="/usr/bin/zsh"
    if [[ ! -x "${zsh_path}" ]]; then
        zsh_path="/bin/zsh"
    fi

    if ! id -u "${USERNAME}" >/dev/null 2>&1; then
        useradd -u 1000 -g "${USERNAME}" -m -d "/home/${USERNAME}" -s "${zsh_path}" "${USERNAME}" || \
        useradd -m -d "/home/${USERNAME}" -s "${zsh_path}" "${USERNAME}"
    else
        usermod -s "${zsh_path}" -d "/home/${USERNAME}" "${USERNAME}"
    fi

    echo "${USERNAME}:${PASSWORD}" | chpasswd
    echo "root:${PASSWORD}" | chpasswd

    for grp in sudo audio video plugdev users render input; do
        if getent group "${grp}" >/dev/null 2>&1; then
            usermod -aG "${grp}" "${USERNAME}" || true
        fi
    done

    mkdir -p /etc/sudoers.d
    cat > "/etc/sudoers.d/010_${USERNAME}-nopasswd" << EOF
${USERNAME} ALL=(ALL) NOPASSWD: ALL
EOF
    chmod 0440 "/etc/sudoers.d/010_${USERNAME}-nopasswd"

    local user_home="/home/${USERNAME}"
    mkdir -p "${user_home}"

    if [[ ! -f "${user_home}/.zshrc" ]]; then
        cat > "${user_home}/.zshrc" << 'EOF'
# LinuxDroid default zsh configuration
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8
export PATH=/usr/local/bin:/usr/bin:/bin:/usr/local/games:/usr/games:$HOME/bin:$HOME/.local/bin
export XDG_RUNTIME_DIR=/tmp/runtime-${USER}

mkdir -p "$XDG_RUNTIME_DIR" 2>/dev/null || true
chmod 0700 "$XDG_RUNTIME_DIR" 2>/dev/null || true

PROMPT='%F{cyan}%n@linuxdroid%f:%F{yellow}%~%f$ '
alias ll='ls -alF'
alias la='ls -A'
alias l='ls -CF'
EOF
    fi

    if [[ ! -f "${user_home}/.bashrc" ]]; then
        cat > "${user_home}/.bashrc" << 'EOF'
# LinuxDroid default bash configuration
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8
export PATH=/usr/local/bin:/usr/bin:/bin:/usr/local/games:/usr/games:$HOME/bin:$HOME/.local/bin
export XDG_RUNTIME_DIR=/tmp/runtime-${USER}

mkdir -p "$XDG_RUNTIME_DIR" 2>/dev/null || true
chmod 0700 "$XDG_RUNTIME_DIR" 2>/dev/null || true

PS1='\[\033[01;32m\]\u@linuxdroid\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
alias ll='ls -alF'
alias la='ls -A'
alias l='ls -CF'
EOF
    fi

    chown -R "${USERNAME}:${USERNAME}" "${user_home}"
    chmod 0750 "${user_home}"

    log_pass "[Guest] User account '${USERNAME}' successfully configured."
}

guest_configure_system() {
    log_info "[Guest] Configuring system locales, timezone, and machine-id..."

    if [[ -f /etc/locale.gen ]]; then
        sed -i 's/^# *en_US.UTF-8 UTF-8/en_US.UTF-8 UTF-8/' /etc/locale.gen || true
        echo "en_US.UTF-8 UTF-8" >> /etc/locale.gen
        locale-gen en_US.UTF-8 || true
    fi
    cat > /etc/default/locale << 'EOF'
LANG=en_US.UTF-8
LC_ALL=en_US.UTF-8
EOF

    if [[ -f /usr/share/zoneinfo/UTC ]]; then
        ln -sf /usr/share/zoneinfo/UTC /etc/localtime
        echo "UTC" > /etc/timezone
    fi

    mkdir -p /var/lib/dbus
    dbus-uuidgen > /etc/machine-id || true
    cp /etc/machine-id /var/lib/dbus/machine-id || true

    log_pass "[Guest] System localization and identifiers configured."
}

guest_mandatory_cleanup() {
    log_step "${STAGE_CLEANUP}"
    log_info "[Guest] Performing mandatory final package cleanup in strict order..."
    export DEBIAN_FRONTEND=noninteractive

    # 1. apt-get autoremove --purge -y
    log_info "[Guest] Step 1/3: Purging unneeded packages (autoremove --purge)..."
    apt-get autoremove --purge -y

    # 2. apt-get clean
    log_info "[Guest] Step 2/3: Cleaning package caches (clean)..."
    apt-get clean

    # 3. apt-get update
    log_info "[Guest] Step 3/3: Re-synchronizing package index (update)..."
    apt-get update

    rm -f /usr/sbin/policy-rc.d
    rm -rf /tmp/linuxdroid-packages
    rm -rf /var/lib/apt/lists/*
    mkdir -p /var/lib/apt/lists/partial
    rm -rf /tmp/* /var/tmp/*

    log_pass "[Guest] Mandatory package cleanup sequence completed successfully."
}

guest_validate_system() {
    log_step "${STAGE_VALIDATING}"
    log_info "[Guest] Performing comprehensive post-cleanup validation..."

    local validation_failed=false

    for d in /bin /etc /usr /var /home; do
        if [[ ! -d "$d" ]]; then
            log_error "Missing essential root directory: $d"
            validation_failed=true
        fi
    done

    if ! id -u "${USERNAME}" >/dev/null 2>&1; then
        log_error "Validation failed: User '${USERNAME}' does not exist!"
        validation_failed=true
    fi

    if [[ ! -d "/home/${USERNAME}" ]]; then
        log_error "Validation failed: Home directory /home/${USERNAME} does not exist!"
        validation_failed=true
    fi

    local user_shell
    user_shell="$(getent passwd "${USERNAME}" | cut -d: -f7)"
    if [[ "${user_shell}" != *"zsh"* ]]; then
        log_error "Validation failed: User shell is '${user_shell}', expected Zsh!"
        validation_failed=true
    else
        log_pass "Validation check: Default shell is Zsh (${user_shell})."
    fi

    if ! command -v sudo >/dev/null 2>&1 || ! sudo -V >/dev/null 2>&1; then
        log_error "Validation failed: 'sudo' binary missing or non-functional!"
        validation_failed=true
    else
        log_pass "Validation check: sudo is available and functional."
    fi

    log_info "[Guest] Checking dpkg audit status..."
    if ! dpkg --audit; then
        log_error "Validation failed: dpkg audit reported broken package status!"
        validation_failed=true
    fi

    if ! command -v weston >/dev/null 2>&1; then
        log_error "Validation failed: 'weston' binary not found!"
        validation_failed=true
    else
        log_pass "Validation check: Weston is present ($(weston --version 2>/dev/null || echo 'installed'))."
    fi

    if dpkg -s libwayland-client0 >/dev/null 2>&1 || [[ -f /usr/lib/aarch64-linux-gnu/libwayland-client.so.0 || -f /usr/lib/libwayland-client.so.0 ]]; then
        log_pass "Validation check: Wayland client library is installed."
    else
        log_error "Validation failed: Wayland client library not found!"
        validation_failed=true
    fi

    if dpkg -s linuxdroid-display-manager >/dev/null 2>&1 || [[ -x /usr/bin/lddm || -x /usr/local/bin/lddm ]]; then
        log_pass "Validation check: LDDM is installed."
    else
        log_error "Validation failed: LDDM package or binary not found!"
        validation_failed=true
    fi

    if dpkg -s linuxdroid-desktop-environment >/dev/null 2>&1 || [[ -x /usr/bin/ldde || -x /usr/local/bin/ldde ]]; then
        log_pass "Validation check: LDDE is installed."
    else
        log_error "Validation failed: LDDE package or binary not found!"
        validation_failed=true
    fi

    if [[ "${validation_failed}" == true ]]; then
        log_fatal "Post-cleanup validation failed. See error messages above."
    fi

    log_pass "[Guest] All post-cleanup validation checks passed!"
}

guest_write_manifest() {
    log_info "[Guest] Writing rootfs release manifest and ROOTFS_READY marker..."
    mkdir -p /etc/linuxdroid

    cat > /etc/linuxdroid/manifest.json << EOF
{
  "distro": "${DISTRO}",
  "release": "${RELEASE}",
  "arch": "${ARCH}",
  "username": "${USERNAME}",
  "installer_version": "${SCRIPT_VERSION}",
  "installed_at": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")",
  "default_shell": "/usr/bin/zsh",
  "graphics": "weston-wayland",
  "lddm": "bundled",
  "ldde": "bundled"
}
EOF

    cat > /etc/linuxdroid/ROOTFS_READY << EOF
DISTRO=${DISTRO}
RELEASE=${RELEASE}
ARCH=${ARCH}
USERNAME=${USERNAME}
READY_AT=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
INSTALLER_VERSION=${SCRIPT_VERSION}
EOF

    log_pass "Rootfs manifest and ROOTFS_READY marker written."
}

# -----------------------------------------------------------------------------
# Main Execution Orchestrator
# -----------------------------------------------------------------------------
main() {
    log_step "${STAGE_STARTING}"

    load_config_file
    load_env_fallbacks
    parse_arguments "$@"
    validate_inputs

    if [[ "${IN_GUEST}" == true ]]; then
        log_info "Running in-guest provisioning pipeline..."
        guest_configure_apt
        guest_install_core_packages
        guest_install_graphics_stack
        guest_install_bundled_packages
        guest_configure_user
        guest_configure_system
        guest_mandatory_cleanup
        guest_validate_system
        guest_write_manifest
        log_step "${STAGE_READY}"
        log_pass "LinuxDroid guest provisioning completed successfully."
    else
        log_info "Starting LinuxDroid host orchestration pipeline..."

        if [[ -f "${ROOTFS_DIR}/etc/linuxdroid/ROOTFS_READY" && "${FORCE}" != true ]]; then
            log_info "Rootfs in '${ROOTFS_DIR}' is already installed and marked ROOTFS_READY."
            log_step "${STAGE_READY}"
            exit 0
        fi

        acquire_rootfs
        prepare_guest_environment

        log_step "${STAGE_CONFIGURING}"
        log_info "Entering PRoot guest environment for provisioning..."

        execute_in_guest "/tmp/install_rootfs.sh --in-guest --distro '${DISTRO}' --release '${RELEASE}' --username '${USERNAME}' --password '${PASSWORD}' --rootfs /"

        rm -f "${ROOTFS_DIR}/tmp/install_rootfs.sh"

        log_step "${STAGE_READY}"
        log_pass "LinuxDroid ARM64 Rootfs successfully created and ready for graphical launch!"
    fi
}

main "$@"
