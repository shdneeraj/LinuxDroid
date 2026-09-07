# LinuxDroid Core Stack — Git Submodules Specification

LinuxDroid consumes its core native components directly from dedicated, maintained repositories integrated as Git submodules under `vendor/`.

This architecture guarantees reproducible builds, source ownership, hermetic native toolchains, and strict isolation between host Android runtime and guest Linux environments.

---

## 1. Submodule Registry & Pinned Revisions

Every LinuxDroid-owned component is strictly pinned to an authoritative commit SHA in `.gitmodules` and the Git index. Builds never track floating branches.

| Component | Repository URL | Vendor Path | Pinned Commit SHA | Primary Role in Stack |
| :--- | :--- | :--- | :--- | :--- |
| **PRoot** | `https://github.com/LinuxDroidapp/proot` | `vendor/proot` | `caadcae0e7697ec29f02e231a3a88866561aacd0` | User-space chroot/bind virtualization, syscall emulation, ptrace & seccomp sandboxing |
| **LDDM** | `https://github.com/LinuxDroidapp/LDDM` | `vendor/LDDM` | `aa6c3d38f874244bcd60162889a914637e4ddf46` | LinuxDroid Display Manager (Wayland login greeter and session manager) |
| **LDDE** | `https://github.com/LinuxDroidapp/LDDE` | `vendor/LDDE` | `9ee575e963d6d1ff4086fc16fb119daf6ead6db2` | LinuxDroid Desktop Environment (lightweight graphical shell and workspace) |

---

## 2. Linux Rootfs Package Dependencies (Not Submodules)

Wayland, Weston, wayland-protocols, and Pixman are **Linux distribution package dependencies**. They are installed inside the Linux rootfs by the `linux/bootstrap` deployment pipeline and are not Android project Git submodules.

| Component | Package | Provider |
| :--- | :--- | :--- |
| Wayland | `libwayland-dev` | Linux distribution (Debian/Ubuntu APT) |
| Weston | `weston` | Linux distribution (Debian/Ubuntu APT) |
| Wayland Protocols | `wayland-protocols` | Linux distribution (Debian/Ubuntu APT) |
| Pixman | `libpixman-1-dev` | Linux distribution (Debian/Ubuntu APT) |

Pre-built `.so` artifacts (`libweston-17.so`, `libwayland-*.so`, `libpixman-1.so`) remain in `app/src/main/jniLibs/arm64-v8a/` for use by the Android bridge library (`native/bridge`). These are static artifacts — they are not rebuilt from source during the Android project build.

---

## 3. Cloning & Initializing

To clone the repository with all submodules initialized:

```bash
git clone --recurse-submodules https://github.com/LinuxDroidapp/LinuxDroid.git
cd LinuxDroid
```

For an existing checkout where submodules have not yet been checked out:

```bash
git submodule update --init --recursive
```

To verify that all submodules match their expected pinned commits without dirty modifications:

```bash
git submodule status
```

---

## 4. Integration & Build Architecture

### 4.1 Strict Source Tree Isolation
- Submodule repositories inside `vendor/*` are maintained as clean, unmodified source trees.
- Build systems (CMake, NDK) must not write build artifacts, generated headers, or in-place patches into `vendor/*`.

### 4.2 PRoot Android/ARM64 Patch Baseline
The pinned PRoot revision (`caadcae0e7697ec29f02e231a3a88866561aacd0`) incorporates essential Android compatibility modifications:
- `PTRACE_PEEKDATA` memory read workaround for Bionic ptrace behavior.
- ARM64 Top-Byte-Ignore (TBI) pointer handling in syscall translation.
- Seccomp exit `SIGSYS` trap handler and graceful fallback.
- Guest syscall translation for Android kernel sandboxing.
- Ashmem-backed emulation for SYSV IPC shared memory.

---

## 5. Submodule Maintenance Workflow

When updating a submodule to a new upstream release or bug fix commit:

1. **Navigate to Submodule Directory**:
   ```bash
   cd vendor/<component>
   git fetch origin
   git checkout <target-commit-sha>
   ```

2. **Verify Compatibility**:
   - Run automated unit and integration tests.

3. **Record Updated Provenance**:
   - Update `app/src/main/assets/components_provenance.json`.

4. **Commit Submodule Reference in LinuxDroid**:
   ```bash
   cd /workspaces/LinuxDroid
   git add vendor/<component> app/src/main/assets/components_provenance.json
   git commit -m "chore(vendor): bump <component> to <target-commit-sha>"
   ```
