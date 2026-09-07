#!/usr/bin/env bash
# =============================================================================
# LinuxDroid — Production LDDM & LDDE ARM64 Package Builder & Stager
# =============================================================================
# Builds LinuxDroid Display Manager (LDDM) and LinuxDroid Desktop Environment (LDDE)
# as production-grade ARM64 Debian (.deb) packages and stages them as Android
# runtime APK assets with integrity manifests.
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

LDDM_DIR="${ROOT_DIR}/vendor/LDDM"
LDDE_DIR="${ROOT_DIR}/vendor/LDDE"
ASSETS_PKG_DIR="${ROOT_DIR}/app/src/main/assets/packages"
ASSETS_SCRIPTS_DIR="${ROOT_DIR}/app/src/main/assets/scripts"
BOOTSTRAP_SCRIPT="${ROOT_DIR}/linux/bootstrap/install_rootfs.sh"

echo "========================================================================"
echo " LinuxDroid: Building Production ARM64 Graphical Packages"
echo "========================================================================"

HOST_ARCH="$(uname -m)"
CC_BIN="gcc"
CXX_BIN="g++"
STRIP_BIN="strip"
CMAKE_TOOLCHAIN_ARGS=()

if [[ "${HOST_ARCH}" != "aarch64" && "${HOST_ARCH}" != "arm64" ]]; then
    echo ">>> Host is ${HOST_ARCH}. Cross-compiling for Linux ARM64 (aarch64)..."
    if command -v aarch64-linux-gnu-gcc >/dev/null 2>&1 && command -v aarch64-linux-gnu-g++ >/dev/null 2>&1; then
        CC_BIN="aarch64-linux-gnu-gcc"
        CXX_BIN="aarch64-linux-gnu-g++"
        STRIP_BIN="aarch64-linux-gnu-strip"
        CMAKE_TOOLCHAIN_ARGS=(
            "-DCMAKE_SYSTEM_NAME=Linux"
            "-DCMAKE_SYSTEM_PROCESSOR=aarch64"
            "-DCMAKE_C_COMPILER=${CC_BIN}"
            "-DCMAKE_CXX_COMPILER=${CXX_BIN}"
        )
    else
        echo "ERROR: Cross-compiler aarch64-linux-gnu-gcc/g++ not found!" >&2
        echo "Install via: sudo apt-get install -y gcc-aarch64-linux-gnu g++-aarch64-linux-gnu" >&2
        exit 1
    fi
else
    echo ">>> Native ARM64 build environment detected."
fi

# -----------------------------------------------------------------------------
# 1. Build LDDM Package
# -----------------------------------------------------------------------------
echo ""
echo "------------------------------------------------------------------------"
echo " [1/4] Building LDDM (linuxdroid-display-manager) for ARM64..."
echo "------------------------------------------------------------------------"

LDDM_BUILD_DIR="${LDDM_DIR}/build-arm64"
LDDM_OUT_DIR="${LDDM_DIR}/build-release/packages"
mkdir -p "${LDDM_OUT_DIR}"

cmake -S "${LDDM_DIR}" -B "${LDDM_BUILD_DIR}"     "${CMAKE_TOOLCHAIN_ARGS[@]}"     -DCMAKE_BUILD_TYPE=Release     -DLDDM_BUILD_TESTS=OFF

cmake --build "${LDDM_BUILD_DIR}" --parallel "$(nproc)"

echo ">>> Packaging LDDM as Debian package..."
"${LDDM_DIR}/packaging/build_package.sh"     --build-dir "${LDDM_BUILD_DIR}"     --output-dir "${LDDM_OUT_DIR}"     --arch "arm64"

LDDM_DEB="$(find "${LDDM_OUT_DIR}" -name "linuxdroid-display-manager_*_arm64.deb" | head -n 1)"
if [[ -z "${LDDM_DEB}" || ! -f "${LDDM_DEB}" ]]; then
    echo "ERROR: LDDM ARM64 .deb package was not created!" >&2
    exit 1
fi
echo ">>> LDDM .deb: ${LDDM_DEB}"

# -----------------------------------------------------------------------------
# 2. Build LDDE Package
# -----------------------------------------------------------------------------
echo ""
echo "------------------------------------------------------------------------"
echo " [2/4] Building LDDE (linuxdroid-desktop-environment) for ARM64..."
echo "------------------------------------------------------------------------"

LDDE_BUILD_DIR="${LDDE_DIR}/build-arm64"
LDDE_OUT_DIR="${LDDE_DIR}/dist"
mkdir -p "${LDDE_OUT_DIR}"

if [[ -d "/usr/lib/aarch64-linux-gnu/pkgconfig" ]]; then
    export PKG_CONFIG_PATH="/usr/lib/aarch64-linux-gnu/pkgconfig:/usr/share/pkgconfig:${PKG_CONFIG_PATH:-}"
fi

cmake -S "${LDDE_DIR}" -B "${LDDE_BUILD_DIR}"     "${CMAKE_TOOLCHAIN_ARGS[@]}"     -DCMAKE_BUILD_TYPE=Release     -DCMAKE_INSTALL_PREFIX=/usr     -DCMAKE_INSTALL_SYSCONFDIR=/etc     -DINSTALL_DEV_LIBRARIES=OFF     -DBUILD_TESTING=OFF     -DCPACK_DEBIAN_PACKAGE_ARCHITECTURE=arm64

cmake --build "${LDDE_BUILD_DIR}" --parallel "$(nproc)"

echo ">>> Packaging LDDE via CPack DEB..."
(
    cd "${LDDE_BUILD_DIR}"
    cpack -G DEB
)

cp -f "${LDDE_BUILD_DIR}"/linuxdroid-desktop-environment_*_arm64.deb "${LDDE_OUT_DIR}/"
LDDE_DEB="$(find "${LDDE_OUT_DIR}" -name "linuxdroid-desktop-environment_*_arm64.deb" | head -n 1)"
if [[ -z "${LDDE_DEB}" || ! -f "${LDDE_DEB}" ]]; then
    echo "ERROR: LDDE ARM64 .deb package was not created!" >&2
    exit 1
fi
echo ">>> LDDE .deb: ${LDDE_DEB}"

# -----------------------------------------------------------------------------
# 3. Validate Packages (Architecture, Binary ELF, Control Fields)
# -----------------------------------------------------------------------------
echo ""
echo "------------------------------------------------------------------------"
echo " [3/4] Validating Debian Packages..."
echo "------------------------------------------------------------------------"

validate_deb() {
    local deb="$1"
    local expected_pkg="$2"
    echo "Validating: $(basename "${deb}")..."

    local arch
    arch="$(dpkg-deb -f "${deb}" Architecture)"
    if [[ "${arch}" != "arm64" ]]; then
        echo "ERROR: Package $(basename "${deb}") has architecture '${arch}', expected 'arm64'!" >&2
        exit 1
    fi

    local pkg
    pkg="$(dpkg-deb -f "${deb}" Package)"
    if [[ "${pkg}" != "${expected_pkg}" ]]; then
        echo "ERROR: Package name '${pkg}' does not match expected '${expected_pkg}'!" >&2
        exit 1
    fi

    local tmp_inspect
    tmp_inspect="$(mktemp -d)"
    dpkg-deb -x "${deb}" "${tmp_inspect}"

    local elf_files
    elf_files="$(find "${tmp_inspect}" -type f -executable)"
    for elf in ${elf_files}; do
        if file "${elf}" | grep -q "ELF"; then
            if ! file "${elf}" | grep -E -q "ARM aarch64|aarch64"; then
                echo "ERROR: Executable ${elf} in $(basename "${deb}") is NOT ARM64! ($(file "${elf}"))" >&2
                rm -rf "${tmp_inspect}"
                exit 1
            fi
            echo "  Verified ELF: $(basename "${elf}") is ARM aarch64"
        fi
    done
    rm -rf "${tmp_inspect}"
    echo "  Package $(basename "${deb}") is VALID."
}

validate_deb "${LDDM_DEB}" "linuxdroid-display-manager"
validate_deb "${LDDE_DEB}" "linuxdroid-desktop-environment"

# -----------------------------------------------------------------------------
# 4. Stage Assets & Generate Integrity Manifests
# -----------------------------------------------------------------------------
echo ""
echo "------------------------------------------------------------------------"
echo " [4/4] Staging Assets into Android APK Resource Tree..."
echo "------------------------------------------------------------------------"

mkdir -p "${ASSETS_PKG_DIR}"
mkdir -p "${ASSETS_SCRIPTS_DIR}"

cp -f "${LDDM_DEB}" "${ASSETS_PKG_DIR}/"
cp -f "${LDDE_DEB}" "${ASSETS_PKG_DIR}/"

if [[ -f "${BOOTSTRAP_SCRIPT}" ]]; then
    cp -f "${BOOTSTRAP_SCRIPT}" "${ASSETS_SCRIPTS_DIR}/install_rootfs.sh"
    chmod +x "${ASSETS_SCRIPTS_DIR}/install_rootfs.sh"
    echo ">>> Staged install_rootfs.sh into assets/scripts/"
fi

sha256_file() {
    sha256sum "$1" | awk "{print \$1}"
}

LDDM_NAME="$(basename "${LDDM_DEB}")"
LDDE_NAME="$(basename "${LDDE_DEB}")"
LDDM_VER="$(dpkg-deb -f "${LDDM_DEB}" Version)"
LDDE_VER="$(dpkg-deb -f "${LDDE_DEB}" Version)"
LDDM_SHA="$(sha256_file "${LDDM_DEB}")"
LDDE_SHA="$(sha256_file "${LDDE_DEB}")"

MANIFEST_FILE="${ASSETS_PKG_DIR}/PACKAGES_MANIFEST.txt"
cat > "${MANIFEST_FILE}" << EOF
Package: linuxdroid-display-manager
Version: ${LDDM_VER}
Architecture: arm64
File: ${LDDM_NAME}
SHA256: ${LDDM_SHA}

Package: linuxdroid-desktop-environment
Version: ${LDDE_VER}
Architecture: arm64
File: ${LDDE_NAME}
SHA256: ${LDDE_SHA}
EOF

echo ">>> Staged packages and generated manifest:"
cat "${MANIFEST_FILE}"

PROVENANCE_FILE="${ROOT_DIR}/app/src/main/assets/components_provenance.json"
if [[ -f "${PROVENANCE_FILE}" ]]; then
    python3 -c '
import sys, json
prov_file, lddm_ver, lddm_name, lddm_sha, ldde_ver, ldde_name, ldde_sha = sys.argv[1:8]
with open(prov_file, "r") as f:
    data = json.load(f)

data["components"]["LDDM"] = {
    "repository": "LinuxDroidapp/LDDM",
    "vendor_path": "vendor/LDDM",
    "version": lddm_ver,
    "architecture": "arm64",
    "package_file": lddm_name,
    "sha256": lddm_sha
}
data["components"]["LDDE"] = {
    "repository": "LinuxDroidapp/LDDE",
    "vendor_path": "vendor/LDDE",
    "version": ldde_ver,
    "architecture": "arm64",
    "package_file": ldde_name,
    "sha256": ldde_sha
}

with open(prov_file, "w") as f:
    json.dump(data, f, indent=2)
print(">>> Updated components_provenance.json")
' "${PROVENANCE_FILE}" "${LDDM_VER}" "${LDDM_NAME}" "${LDDM_SHA}" "${LDDE_VER}" "${LDDE_NAME}" "${LDDE_SHA}"
fi

echo ""
echo "========================================================================"
echo " Production Package Build & Asset Staging Complete!"
echo "========================================================================"
