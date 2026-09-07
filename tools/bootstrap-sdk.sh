#!/usr/bin/env bash
# ==============================================================================
# LinuxDroid — Android SDK & Toolchain Bootstrap Tool
# ==============================================================================
# Mirrors the official CI standard defined in:
# .github/workflows/build-release-apk.yml
#
# Components aligned with CI & gradle/libs.versions.toml:
#   - platform-tools:        latest
#   - platforms:             android-36
#   - build-tools:           36.0.0
#   - ndk:                   30.0.16138531
#   - cmake:                 3.22.1
#   - commandlinetools:      15859902 (Android CLI 22.0)
#
# Capabilities:
#   - Idempotent: Skips downloading components already installed
#   - Supports canary/preview channels (--channel=3) for modern NDK versions
#   - Auto-accepts all SDK licenses (both pre-seeded hashes and sdkmanager)
#   - Configures repo local.properties (sdk.dir)
#   - Makes ./gradlew executable
#   - Optional native toolchain installation & Weston stack compilation (--with-native-tools, --build-native-stack)
#   - Generates sourceable environment setup file (env.sh)
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TOML_FILE="$REPO_ROOT/gradle/libs.versions.toml"
LOCAL_PROPERTIES="$REPO_ROOT/local.properties"

# Text styling
if [[ -t 1 ]]; then
    C_RESET="\033[0m"
    C_BOLD="\033[1m"
    C_GREEN="\033[32m"
    C_YELLOW="\033[33m"
    C_BLUE="\033[34m"
    C_RED="\033[31m"
    C_CYAN="\033[36m"
else
    C_RESET=""
    C_BOLD=""
    C_GREEN=""
    C_YELLOW=""
    C_BLUE=""
    C_RED=""
    C_CYAN=""
fi

log_info()    { echo -e "${C_BLUE}[INFO]${C_RESET} $*"; }
log_success() { echo -e "${C_GREEN}[SUCCESS]${C_RESET} $*"; }
log_warn()    { echo -e "${C_YELLOW}[WARN]${C_RESET} $*"; }
log_error()   { echo -e "${C_RED}[ERROR]${C_RESET} $*" >&2; }
log_step()    { echo -e "\n${C_BOLD}${C_CYAN}==> $*${C_RESET}"; }

TMP_DIR=""
cleanup() {
    if [[ -n "$TMP_DIR" && -d "$TMP_DIR" ]]; then
        rm -rf "$TMP_DIR"
    fi
}
trap cleanup EXIT INT TERM

# Dynamic version resolution from version catalog with CI-aligned fallbacks
extract_version() {
    local key="$1"
    local fallback="$2"
    if [[ -f "$TOML_FILE" ]]; then
        local val
        val=$(grep -E "^[[:space:]]*${key}[[:space:]]*=" "$TOML_FILE" | head -n1 | sed -E 's/.*"([^"]+)".*/\1/' || true)
        if [[ -n "$val" ]]; then
            echo "$val"
            return
        fi
    fi
    echo "$fallback"
}

# CI-aligned standard versions
CI_COMPILE_SDK="$(extract_version "compileSdk" "36")"
CI_BUILD_TOOLS="$(extract_version "buildTools" "36.0.0")"
CI_NDK_VERSION="$(extract_version "ndk" "30.0.16138531")"
CI_CMAKE_VERSION="$(extract_version "cmake" "4.4.3")"
CI_CMDLINE_VERSION="15859902"

# Option flags
SDK_ROOT_OVERRIDE=""
COMPILE_SDK="$CI_COMPILE_SDK"
BUILD_TOOLS="$CI_BUILD_TOOLS"
NDK_VERSION="$CI_NDK_VERSION"
CMAKE_VERSION="$CI_CMAKE_VERSION"
CMDLINE_VERSION="$CI_CMDLINE_VERSION"
INSTALL_NDK=true
INSTALL_CMAKE=true
INSTALL_NATIVE_TOOLS=false
FORCE_REINSTALL=false
ACCEPT_LICENSES_ONLY=false
CHECK_ONLY=false

show_help() {
    echo -e "${C_BOLD}LinuxDroid Android SDK & Toolchain Bootstrap (CI Standard)${C_RESET}"
    echo
    echo "Usage: $(basename "$0") [options]"
    echo
    echo "Options:"
    echo "  -h, --help                 Show this help message and exit"
    echo "  --sdk-root <path>          Target Android SDK installation directory"
    echo "  --compile-sdk <api>        Android platform API level (default: $CI_COMPILE_SDK)"
    echo "  --build-tools <version>    Android build-tools version (default: $CI_BUILD_TOOLS)"
    echo "  --ndk-version <version>    Android NDK version (default: $CI_NDK_VERSION)"
    echo "  --cmake-version <version>  CMake version (default: $CI_CMAKE_VERSION)"
    echo "  --cmdline-version <id>     Command-line tools build ID (default: $CI_CMDLINE_VERSION)"
    echo "  --no-ndk                   Skip NDK installation"
    echo "  --no-cmake                 Skip CMake installation"
    echo "  --with-native-tools        Install native host tools (flex, bison, ninja, meson)"
    echo "  --build-native-stack       Build native Wayland & Weston stack after SDK setup"
    echo "  --force                    Force reinstallation of tools and components"
    echo "  --accept-licenses-only     Accept SDK licenses and exit immediately"
    echo "  --check-only               Verify prerequisite tools and installation status without changes"
    echo
    echo "CI Standard Components (.github/workflows/build-release-apk.yml):"
    echo "  - platform-tools:          latest"
    echo "  - platforms;android-$CI_COMPILE_SDK"
    echo "  - build-tools;$CI_BUILD_TOOLS"
    echo "  - ndk;$CI_NDK_VERSION"
    echo "  - cmake;$CI_CMAKE_VERSION"
    echo "  - commandlinetools:        build $CI_CMDLINE_VERSION"
}

# Parse CLI arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--help)
            show_help
            exit 0
            ;;
        --sdk-root)
            SDK_ROOT_OVERRIDE="$2"
            shift 2
            ;;
        --compile-sdk)
            COMPILE_SDK="$2"
            shift 2
            ;;
        --build-tools)
            BUILD_TOOLS="$2"
            shift 2
            ;;
        --ndk-version)
            NDK_VERSION="$2"
            shift 2
            ;;
        --cmake-version)
            CMAKE_VERSION="$2"
            shift 2
            ;;
        --cmdline-version)
            CMDLINE_VERSION="$2"
            shift 2
            ;;
        --no-ndk)
            INSTALL_NDK=false
            shift
            ;;
        --no-cmake)
            INSTALL_CMAKE=false
            shift
            ;;
        --with-native-tools)
            INSTALL_NATIVE_TOOLS=true
            shift
            ;;
        --force)
            FORCE_REINSTALL=true
            shift
            ;;
        --accept-licenses-only)
            ACCEPT_LICENSES_ONLY=true
            shift
            ;;
        --check-only)
            CHECK_ONLY=true
            shift
            ;;
        *)
            log_error "Unknown option: $1"
            show_help
            exit 1
            ;;
    esac
done

# ==============================================================================
# [1/7] Resolving Android SDK Directory
# ==============================================================================
log_step "[1/7] Resolving Android SDK directory"

ANDROID_SDK_ROOT="${SDK_ROOT_OVERRIDE:-}"

if [[ -z "$ANDROID_SDK_ROOT" ]]; then
    # 1. Environment variables
    if [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME:-}" ]]; then
        ANDROID_SDK_ROOT="$ANDROID_HOME"
    elif [[ -n "${ANDROID_SDK_ROOT:-}" && -d "${ANDROID_SDK_ROOT:-}" ]]; then
        ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT"
    fi
fi

if [[ -z "$ANDROID_SDK_ROOT" && -f "$LOCAL_PROPERTIES" ]]; then
    # 2. local.properties sdk.dir
    local_dir="$(grep -E '^[[:space:]]*sdk.dir[[:space:]]*=' "$LOCAL_PROPERTIES" | cut -d= -f2- | tr -d '\r' | sed 's/\\:/:/g' | xargs || true)"
    if [[ -n "$local_dir" && -d "$local_dir" ]]; then
        ANDROID_SDK_ROOT="$local_dir"
    fi
fi

if [[ -z "$ANDROID_SDK_ROOT" ]]; then
    # 3. Standard Linux / container locations
    if [[ -d "$HOME/Android/Sdk" ]]; then
        ANDROID_SDK_ROOT="$HOME/Android/Sdk"
    elif [[ -d "/workspaces/android-sdk" ]]; then
        ANDROID_SDK_ROOT="/workspaces/android-sdk"
    else
        ANDROID_SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
    fi
fi

log_info "Android SDK Root:    ${C_BOLD}$ANDROID_SDK_ROOT${C_RESET}"
log_info "Platform API:        android-$COMPILE_SDK"
log_info "Build-Tools:         $BUILD_TOOLS"
if [[ "$INSTALL_NDK" == true ]]; then
    log_info "NDK:                 $NDK_VERSION"
fi
if [[ "$INSTALL_CMAKE" == true ]]; then
    log_info "CMake:               $CMAKE_VERSION"
fi

# ==============================================================================
# [2/7] Verifying System Prerequisites (Java 21+ / Tools)
# ==============================================================================
log_step "[2/7] Verifying system prerequisites"

if ! command -v java >/dev/null 2>&1; then
    log_error "Java (JDK 21+) is required but not found in PATH."
    log_error "Please install OpenJDK 21 (matching CI standard: distribution temurin, version 21)."
    exit 1
fi
JAVA_VERSION_STR="$(java -version 2>&1 | head -n1)"
log_info "Java runtime: $JAVA_VERSION_STR"

if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
    log_error "Neither curl nor wget found. Please install curl."
    exit 1
fi
if ! command -v unzip >/dev/null 2>&1; then
    log_error "unzip not found. Please install unzip."
    exit 1
fi

# ==============================================================================
# Check-Only Mode Early Exit
# ==============================================================================
if [[ "$CHECK_ONLY" == true ]]; then
    log_step "Checking Android SDK component installation status"
    ALL_PRESENT=true
    check_pkg() {
        local name="$1"
        local path="$2"
        if [[ -d "$path" || -f "$path" ]]; then
            log_success "$name: Found ($path)"
        else
            log_error "$name: Missing ($path)"
            ALL_PRESENT=false
        fi
    }

    check_pkg "Command-Line Tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
    check_pkg "Platform-Tools" "$ANDROID_SDK_ROOT/platform-tools"
    check_pkg "Platform android-$COMPILE_SDK" "$ANDROID_SDK_ROOT/platforms/android-$COMPILE_SDK"
    check_pkg "Build-Tools $BUILD_TOOLS" "$ANDROID_SDK_ROOT/build-tools/$BUILD_TOOLS"
    if [[ "$INSTALL_NDK" == true ]]; then
        check_pkg "NDK $NDK_VERSION" "$ANDROID_SDK_ROOT/ndk/$NDK_VERSION"
    fi
    if [[ "$INSTALL_CMAKE" == true ]]; then
        check_pkg "CMake $CMAKE_VERSION" "$ANDROID_SDK_ROOT/cmake/$CMAKE_VERSION"
    fi

    if [[ "$ALL_PRESENT" == true ]]; then
        log_success "All required CI-standard SDK components are present."
        exit 0
    else
        log_error "Missing components detected. Run without --check-only to install."
        exit 1
    fi
fi

# ==============================================================================
# [3/7] Setting up Android Command-Line Tools
# ==============================================================================
log_step "[3/7] Setting up Android Command-Line Tools"

mkdir -p "$ANDROID_SDK_ROOT"
SDKMANAGER_BIN="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

if [[ ! -x "$SDKMANAGER_BIN" || "$FORCE_REINSTALL" == true ]]; then
    TMP_DIR="$(mktemp -d)"
    CMDLINE_ZIP="$TMP_DIR/cmdtools.zip"
    CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_VERSION}_latest.zip"

    log_info "Downloading Command-Line Tools (build $CMDLINE_VERSION)..."
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$CMDLINE_URL" -o "$CMDLINE_ZIP"
    else
        wget -qO "$CMDLINE_ZIP" "$CMDLINE_URL"
    fi

    log_info "Extracting Command-Line Tools..."
    mkdir -p "$TMP_DIR/extracted"
    unzip -q "$CMDLINE_ZIP" -d "$TMP_DIR/extracted"

    mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
    rm -rf "$ANDROID_SDK_ROOT/cmdline-tools/latest"
    mv "$TMP_DIR/extracted/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
    chmod +x "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin"/*

    cleanup
    TMP_DIR=""
    log_success "Command-Line Tools installed: $ANDROID_SDK_ROOT/cmdline-tools/latest"
else
    log_info "Command-Line Tools already installed at $ANDROID_SDK_ROOT/cmdline-tools/latest"
fi

export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

# ==============================================================================
# [4/7] Accepting Android SDK Licenses (CI Step 30-31)
# ==============================================================================
log_step "[4/7] Accepting Android SDK licenses"

# Seed standard pre-computed license hashes
LIC_DIR="$ANDROID_SDK_ROOT/licenses"
mkdir -p "$LIC_DIR"
cat <<'EOF' > "$LIC_DIR/android-sdk-license"
24333f8a63b6825ea9c5514f83c2829b004d1fee
d56f5187479451eabf01fb78af6dfcb131a6481e
84831b9409646a918e30573bab4c9c91346d8abd
EOF
cat <<'EOF' > "$LIC_DIR/android-sdk-arm-dbt-license"
859f317696f67ef3d7f30a50a5560e7834b43903
EOF
cat <<'EOF' > "$LIC_DIR/android-sdk-preview-license"
84831b9409646a918e30573bab4c9c91346d8abd
EOF

# Run CI license acceptance command: yes | sdkmanager --licenses > /dev/null || true
yes | "$SDKMANAGER_BIN" --sdk_root="$ANDROID_SDK_ROOT" --licenses > /dev/null 2>&1 || true
log_success "SDK licenses accepted."

if [[ "$ACCEPT_LICENSES_ONLY" == true ]]; then
    log_success "Exiting after license acceptance as requested."
    exit 0
fi

# ==============================================================================
# [5/7] Installing SDK Components Matching CI Standard (CI Step 33-39)
# ==============================================================================
log_step "[5/7] Installing Android SDK components matching CI"

REQUIRED_PACKAGES=()
REQUIRED_PACKAGES+=("platform-tools")
REQUIRED_PACKAGES+=("platforms;android-${COMPILE_SDK}")
REQUIRED_PACKAGES+=("build-tools;${BUILD_TOOLS}")

if [[ "$INSTALL_NDK" == true ]]; then
    REQUIRED_PACKAGES+=("ndk;${NDK_VERSION}")
fi
if [[ "$INSTALL_CMAKE" == true ]]; then
    if [[ "$CMAKE_VERSION" =~ ^3\. ]]; then
        REQUIRED_PACKAGES+=("cmake;${CMAKE_VERSION}")
    fi
fi

PACKAGES_TO_INSTALL=()
for pkg in "${REQUIRED_PACKAGES[@]}"; do
    ALREADY_INSTALLED=false
    if [[ "$FORCE_REINSTALL" == false ]]; then
        case "$pkg" in
            "platform-tools")
                [[ -d "$ANDROID_SDK_ROOT/platform-tools" ]] && ALREADY_INSTALLED=true
                ;;
            "platforms;android-"*)
                [[ -d "$ANDROID_SDK_ROOT/platforms/android-${COMPILE_SDK}" ]] && ALREADY_INSTALLED=true
                ;;
            "build-tools;"*)
                [[ -d "$ANDROID_SDK_ROOT/build-tools/${BUILD_TOOLS}" ]] && ALREADY_INSTALLED=true
                ;;
            "ndk;"*)
                [[ -d "$ANDROID_SDK_ROOT/ndk/${NDK_VERSION}" ]] && ALREADY_INSTALLED=true
                ;;
            "cmake;"*)
                [[ -d "$ANDROID_SDK_ROOT/cmake/${CMAKE_VERSION}" ]] && ALREADY_INSTALLED=true
                ;;
        esac
    fi

    if [[ "$ALREADY_INSTALLED" == true ]]; then
        log_info "Component verified: $pkg"
    else
        PACKAGES_TO_INSTALL+=("$pkg")
    fi
done

if [[ ${#PACKAGES_TO_INSTALL[@]} -eq 0 ]]; then
    log_success "All required CI-standard SDK components are up to date."
else
    log_info "Installing missing components: ${PACKAGES_TO_INSTALL[*]}"
    "$SDKMANAGER_BIN" --sdk_root="$ANDROID_SDK_ROOT" --channel=3 "${PACKAGES_TO_INSTALL[@]}"
    log_success "SDK components installation complete."
fi

# Ensure modern CMake (e.g. 4.4.3) is available in Android SDK cmake directory
if [[ "$INSTALL_CMAKE" == true && (! -d "$ANDROID_SDK_ROOT/cmake/${CMAKE_VERSION}" || "$FORCE_REINSTALL" == true) ]]; then
    log_info "Installing CMake ${CMAKE_VERSION} via Python/pip..."
    pip install --break-system-packages "cmake==${CMAKE_VERSION}" || pip install "cmake==${CMAKE_VERSION}" || true
    CMAKE_DATA_DIR="$(python3 -c 'import cmake; print(cmake.CMAKE_DATA)' 2>/dev/null || true)"
    if [[ -n "$CMAKE_DATA_DIR" && -d "$CMAKE_DATA_DIR" ]]; then
        mkdir -p "$ANDROID_SDK_ROOT/cmake"
        ln -sfn "$CMAKE_DATA_DIR" "$ANDROID_SDK_ROOT/cmake/${CMAKE_VERSION}"
        log_success "CMake ${CMAKE_VERSION} configured at $ANDROID_SDK_ROOT/cmake/${CMAKE_VERSION}"
    fi
fi

# ==============================================================================
# [6/7] Configuring Project Files & Executables (CI Step 41-42)
# ==============================================================================
log_step "[6/7] Configuring project environment & wrapper"

# Make Gradle wrapper executable (CI line 42)
if [[ -f "$REPO_ROOT/gradlew" ]]; then
    chmod +x "$REPO_ROOT/gradlew"
    log_info "Ensured gradlew is executable: $REPO_ROOT/gradlew"
fi

# Synchronize local.properties
ESCAPED_SDK_PATH="${ANDROID_SDK_ROOT//\\/\\\\}"
if [[ -f "$LOCAL_PROPERTIES" ]]; then
    CURRENT_SDK_DIR="$(grep -E '^[[:space:]]*sdk.dir[[:space:]]*=' "$LOCAL_PROPERTIES" | cut -d= -f2- | tr -d '\r' || true)"
    if [[ "$CURRENT_SDK_DIR" != "$ANDROID_SDK_ROOT" ]]; then
        sed -i -E "s|^[[:space:]]*sdk.dir[[:space:]]*=.*|sdk.dir=${ESCAPED_SDK_PATH}|" "$LOCAL_PROPERTIES"
        log_info "Updated sdk.dir in $LOCAL_PROPERTIES"
    else
        log_info "$LOCAL_PROPERTIES is correctly configured."
    fi
else
    echo "sdk.dir=${ESCAPED_SDK_PATH}" > "$LOCAL_PROPERTIES"
    log_info "Created $LOCAL_PROPERTIES with sdk.dir=${ANDROID_SDK_ROOT}"
fi

# Generate env.sh helper script
ENV_HELPER="$ANDROID_SDK_ROOT/env.sh"
cat <<EOF > "$ENV_HELPER"
#!/usr/bin/env bash
# LinuxDroid Environment Setup (CI Aligned)
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT"
export ANDROID_NDK_ROOT="$ANDROID_SDK_ROOT/ndk/$NDK_VERSION"
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:\$PATH"
EOF
chmod +x "$ENV_HELPER"

# ==============================================================================
# [7/7] Native Build Tools & Weston Stack (CI Steps 44-54, Optional)
# ==============================================================================
if [[ "$INSTALL_NATIVE_TOOLS" == true ]]; then
    log_step "[7/7] Installing native host build tools (CI Steps 44-48)"
    sudo apt-get update
    sudo apt-get install -y flex bison ninja-build python3-pip python3-setuptools
    pip install --break-system-packages meson || pip install meson
    log_success "Native build tools installed."
fi

# NOTE: The native Wayland/Weston stack build (build_wayland_stack.sh) has been retired.
# Weston, Wayland, and Pixman are Linux rootfs dependencies supplied by the Linux
# distribution package manager. They are not Android project build targets.
# Pre-built .so artifacts (libweston-17.so, libwayland-*.so, libpixman-1.so) remain
# in app/src/main/jniLibs/arm64-v8a/ for the Android bridge library.


# Final Summary
log_step "Bootstrap Complete"
echo -e "${C_GREEN}${C_BOLD}==============================================================================${C_RESET}"
echo -e "${C_BOLD}LinuxDroid Android Toolchain Ready (CI Standard Aligned)${C_RESET}"
echo -e "=============================================================================="
echo -e "SDK Root:       ${C_CYAN}$ANDROID_SDK_ROOT${C_RESET}"
echo -e "Platform API:   API $COMPILE_SDK (android-$COMPILE_SDK)"
echo -e "Build-Tools:    $BUILD_TOOLS"
if [[ "$INSTALL_NDK" == true ]]; then
    echo -e "NDK:            $ANDROID_SDK_ROOT/ndk/$NDK_VERSION"
fi
if [[ "$INSTALL_CMAKE" == true ]]; then
    echo -e "CMake:          $ANDROID_SDK_ROOT/cmake/$CMAKE_VERSION"
fi
echo -e "Cmdline-Tools:  $ANDROID_SDK_ROOT/cmdline-tools/latest"
echo -e "Gradle Wrapper: $REPO_ROOT/gradlew"
echo -e "Environment:    $ENV_HELPER"
echo -e "=============================================================================="
echo -e "To configure your active shell, run:"
echo -e "  ${C_BOLD}source \"$ENV_HELPER\"${C_RESET}"
echo -e "=============================================================================="
