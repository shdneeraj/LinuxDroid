package com.linuxdroid.core.model

/**
 * Base class for all LinuxDroid structured errors.
 * All errors carry a user-actionable message and an optional cause.
 */
sealed class LinuxDroidError(message: String, cause: Throwable? = null) : Exception(message, cause)

// ─── Runtime ────────────────────────────────────────────────────────────────

class RuntimeError(
    val environmentId: EnvironmentId? = null,
    message: String,
    cause: Throwable? = null,
) : LinuxDroidError("[Runtime${environmentId?.let { "/$it" } ?: ""}] $message", cause)

class RuntimeNotReadyError(
    val environmentId: EnvironmentId,
    val currentState: EnvironmentState,
) : LinuxDroidError("Runtime not ready for $environmentId (state: $currentState)")

// ─── Filesystem ─────────────────────────────────────────────────────────────

class FilesystemError(
    val path: String,
    message: String,
    cause: Throwable? = null,
) : LinuxDroidError("[Filesystem:$path] $message", cause)

class PathTraversalError(
    val attemptedPath: String,
    val basePath: String,
) : LinuxDroidError("Path traversal attempt: '$attemptedPath' outside '$basePath'")

// ─── Process ─────────────────────────────────────────────────────────────────

class ProcessError(
    val handleId: String?,
    message: String,
    cause: Throwable? = null,
) : LinuxDroidError("[Process${handleId?.let { "/$it" } ?: ""}] $message", cause)

// ─── Session ──────────────────────────────────────────────────────────────────

class SessionError(
    val sessionId: SessionId?,
    message: String,
    cause: Throwable? = null,
) : LinuxDroidError("[Session${sessionId?.let { "/$it" } ?: ""}] $message", cause)

// ─── Display ─────────────────────────────────────────────────────────────────

class DisplayError(
    message: String,
    cause: Throwable? = null,
) : LinuxDroidError("[Display] $message", cause)

// ─── GPU ──────────────────────────────────────────────────────────────────────

class GpuError(
    message: String,
    cause: Throwable? = null,
) : LinuxDroidError("[GPU] $message", cause)

// ─── Input ───────────────────────────────────────────────────────────────────

class InputError(
    message: String,
    cause: Throwable? = null,
) : LinuxDroidError("[Input] $message", cause)

// ─── Audio ───────────────────────────────────────────────────────────────────

class AudioError(
    message: String,
    cause: Throwable? = null,
) : LinuxDroidError("[Audio] $message", cause)

// ─── Network ─────────────────────────────────────────────────────────────────

class NetworkError(
    message: String,
    cause: Throwable? = null,
) : LinuxDroidError("[Network] $message", cause)

// ─── Package ─────────────────────────────────────────────────────────────────

class PackageError(
    val packageName: String?,
    message: String,
    cause: Throwable? = null,
) : LinuxDroidError("[Package${packageName?.let { "/$it" } ?: ""}] $message", cause)

// ─── Storage ─────────────────────────────────────────────────────────────────

class StorageError(
    message: String,
    cause: Throwable? = null,
) : LinuxDroidError("[Storage] $message", cause)

class StorageAuthorizationError(
    message: String,
) : LinuxDroidError("[Storage/Authorization] $message")

// ─── Security ────────────────────────────────────────────────────────────────

class SecurityError(
    message: String,
    cause: Throwable? = null,
) : LinuxDroidError("[Security] $message", cause)

class CommandInjectionError(
    val command: String,
) : LinuxDroidError("[Security] Potential command injection detected in: $command")

// ─── Preboot & Guest Init ───────────────────────────────────────────────────

enum class PrebootErrorCode {
    HOST_PREBOOT_ROOTFS_INVALID,
    HOST_PREBOOT_PROOT_MISSING,
    HOST_PREBOOT_RUNTIME_INVALID,
    HOST_PREBOOT_INIT_MISSING,
    HOST_PREBOOT_INIT_NOT_EXECUTABLE,
    PROOT_START_FAILED,
    GUEST_INIT_FAILED,
    GUEST_RUNTIME_SETUP_FAILED,
    GUEST_ENVIRONMENT_SETUP_FAILED,
    GUEST_HOOK_FAILED,
    GUEST_WORKLOAD_EXEC_FAILED,
}

class PrebootError(
    val code: PrebootErrorCode,
    val environmentId: EnvironmentId? = null,
    val detail: String,
    cause: Throwable? = null,
) : LinuxDroidError("[HOST-PREBOOT] ${code.name}: $detail", cause)

class GuestInitError(
    val code: PrebootErrorCode,
    val detail: String,
    cause: Throwable? = null,
) : LinuxDroidError("[GUEST-INIT] ${code.name}: $detail", cause)

// ─── GUI Installation ─────────────────────────────────────────────────────────

/**
 * Thrown when a GUI session is requested but the GUI is not installed.
 * The CLI environment remains fully operational.
 */
class GuiNotInstalledError(
    val environmentId: EnvironmentId,
) : LinuxDroidError(
    "[GUI] GUI is not installed for environment $environmentId. " +
        "Use GuiInstaller.install() to set up the graphical layer."
)

/**
 * Thrown when a GUI session is requested but the last GUI installation failed.
 * The CLI environment remains fully operational.
 */
class GuiInstallFailedError(
    val environmentId: EnvironmentId,
    val reason: String,
) : LinuxDroidError(
    "[GUI] GUI installation previously failed for environment $environmentId: $reason. " +
        "Use GuiInstaller.install() to retry or GuiInstaller.repair() to repair."
)

/**
 * Thrown when a GUI session is requested but installation is still in progress.
 */
class GuiInstallInProgressError(
    val environmentId: EnvironmentId,
) : LinuxDroidError(
    "[GUI] GUI installation is in progress for environment $environmentId. " +
        "Wait for installation to complete before starting a GUI session."
)

/**
 * Thrown when GUI state marker shows INSTALLED but validation reveals broken components.
 * Triggers repair flow. The CLI environment remains fully operational.
 */
class GuiValidationFailedError(
    val environmentId: EnvironmentId,
    val details: String,
) : LinuxDroidError(
    "[GUI] GUI validation failed for environment $environmentId: $details. " +
        "Use GuiInstaller.repair() to repair missing components."
)
