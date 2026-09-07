package com.linuxdroid.core.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/**
 * Immutable identifier for a Linux environment.
 * Once created, the ID never changes.
 */
@Serializable
@JvmInline
value class EnvironmentId(val value: String) {
    init {
        require(value.isNotBlank()) { "EnvironmentId cannot be blank" }
        require(value.length <= 64) { "EnvironmentId too long" }
        require(value.matches(Regex("[a-zA-Z0-9_-]+"))) {
            "EnvironmentId must be alphanumeric with underscores/dashes: $value"
        }
    }

    companion object {
        /** Generate a new random EnvironmentId. */
        fun generate(): EnvironmentId = EnvironmentId(UUID.randomUUID().toString().replace("-", ""))
    }

    override fun toString(): String = value
}

/**
 * The lifecycle state of a Linux environment.
 *
 * State machine:
 *
 * ```
 * CREATED ──────────────────────────────────────────────────────► FAILED
 *    │                                                                ▲
 *    ▼                                                                │
 * INSTALLING ──────────────────────────────────────────────────► FAILED
 *    │                                                                ▲
 *    ▼                                                                │
 * READY ◄──── STOPPED ◄──── STOPPING ◄──── RUNNING ──────────► FAILED
 *    │                                          ▲                     ▲
 *    └──► STARTING ──────────────────────────────┘                    │
 *              │                                                       │
 *              └──────────────────────────────────────────────────► FAILED
 *
 * FAILED ──► RECOVERING ──► READY
 *       └──────────────────► FAILED  (unrecoverable)
 * ```
 */
enum class EnvironmentState {
    /** Environment record exists but rootfs not installed. */
    CREATED,
    /** Rootfs bootstrap in progress. */
    INSTALLING,
    /** Rootfs installed, environment is ready to start. */
    READY,
    /** Runtime starting up. */
    STARTING,
    /** Linux session is active and running. */
    RUNNING,
    /** Graceful shutdown in progress. */
    STOPPING,
    /** Linux session stopped cleanly. */
    STOPPED,
    /** Environment deletion in progress. */
    DELETING,
    /** Environment clone in progress. */
    CLONING,
    /** Environment reset in progress. */
    RESETTING,
    /** A subsystem failure occurred. */
    FAILED,
    /** Attempting recovery after a failure. */
    RECOVERING;

    /** Returns true if a runtime start can be issued from this state. */
    fun canStart(): Boolean = this in setOf(READY, STOPPED)

    /** Returns true if a stop command can be issued from this state. */
    fun canStop(): Boolean = this in setOf(RUNNING, STARTING)

    /** Returns true if this is a terminal/active state where the runtime is live. */
    fun isActive(): Boolean = this in setOf(STARTING, RUNNING, STOPPING)

    /** Returns true if transitions INTO this state are permitted from the given state. */
    fun isValidTransitionFrom(from: EnvironmentState): Boolean = when (this) {
        CREATED -> false // CREATED is only the initial state, never transitioned into
        INSTALLING -> from in setOf(CREATED, FAILED)
        READY -> from in setOf(INSTALLING, RECOVERING, RESETTING, CLONING, CREATED)
        STARTING -> from in setOf(READY, STOPPED)
        RUNNING -> from == STARTING
        STOPPING -> from in setOf(RUNNING, STARTING)
        STOPPED -> from in setOf(STOPPING, RUNNING, RESETTING)
        DELETING -> from in setOf(READY, STOPPED, FAILED, CREATED, INSTALLING, RESETTING, CLONING)
        CLONING -> from in setOf(CREATED, READY, STOPPED)
        RESETTING -> from in setOf(READY, STOPPED, FAILED)
        FAILED -> from in setOf(INSTALLING, STARTING, RUNNING, STOPPING, RECOVERING, RESETTING, CLONING, DELETING)
        RECOVERING -> from == FAILED
    }
}

/**
 * Supported Linux distributions.
 */
@Serializable
enum class Distribution(val displayName: String, val packageManager: String) {
    DEBIAN("Debian", "apt"),
    UBUNTU("Ubuntu", "apt"),
    KALI("Kali Linux", "apt"),
    ARCH_LINUX("Arch Linux", "pacman"),
    ALPINE("Alpine Linux", "apk");

    companion object {
        fun default(): Distribution = DEBIAN
    }
}

/**
 * Target CPU architecture.
 */
@Serializable
enum class Architecture(val abiName: String, val linuxArch: String) {
    ARM64("arm64-v8a", "aarch64");

    companion object {
        fun current(): Architecture = ARM64
    }
}

/**
 * Runtime configuration for the Linux environment.
 */
@Serializable
data class RuntimeConfig(
    /** Maximum RAM the Linux session may use (MB). 0 = unlimited within Android limits. */
    val maxRamMb: Int = 0,
    /** Extra environment variables to inject at startup. */
    val extraEnv: Map<String, String> = emptyMap(),
    /** Whether to bind-mount the Android shared directory into Linux. */
    val sharedStorageEnabled: Boolean = true,
    /** Custom proot binary path override (null = use bundled). */
    val customProotPath: String? = null,
)

/**
 * Display configuration for the Linux graphical session.
 */
@Serializable
data class DisplayConfig(
    /** Display width in pixels. 0 = auto-detect from Android display. */
    val widthPx: Int = 0,
    /** Display height in pixels. 0 = auto-detect from Android display. */
    val heightPx: Int = 0,
    /** Display DPI. 0 = auto-detect from Android display. */
    val dpi: Int = 0,
    /** Whether to follow Android orientation changes. */
    val followOrientation: Boolean = true,
)

/**
 * GPU configuration.
 */
@Serializable
data class GpuConfig(
    /** Whether hardware acceleration is requested. */
    val hardwareAcceleration: Boolean = true,
    /** Vulkan requested (if supported). */
    val useVulkan: Boolean = true,
    /** OpenGL ES version override. 0 = auto-detect. */
    val openGlEsVersion: Int = 0,
)

/**
 * Audio configuration.
 */
@Serializable
data class AudioConfig(
    /** Whether audio is enabled for this environment. */
    val enabled: Boolean = true,
    /** Audio latency hint (ms). 0 = default. */
    val latencyHintMs: Int = 0,
)

/**
 * Network configuration.
 */
@Serializable
data class NetworkConfig(
    /** Whether the Linux environment has network access. */
    val enabled: Boolean = true,
    /** Custom DNS servers. Empty = inherit from Android. */
    val dnsServers: List<String> = emptyList(),
)

/**
 * Desktop environment configuration.
 */
@Serializable
data class DesktopConfig(
    /** Desktop environment to start (e.g. "xfce4", "gnome", "none"). */
    val desktopEnvironment: String = "xfce4",
    /** Wayland compositor to use (e.g. "cage", "weston"). */
    val waylandCompositor: String = "cage",
    /** Whether to start XWayland for X11 app compatibility. */
    val xwaylandEnabled: Boolean = true,
    /** Whether to automatically log in without prompting at display manager. */
    val autoLogin: Boolean = false,
)

/**
 * Immutable environment metadata that never changes after creation.
 */
@Serializable
data class EnvironmentMetadata(
    /** Immutable unique identifier. */
    val id: EnvironmentId,
    /** Human-readable name. */
    val name: String,
    /** Linux distribution. */
    val distribution: Distribution,
    /** CPU architecture. */
    val architecture: Architecture,
    /** When the environment was created. */
    val createdAt: Long = System.currentTimeMillis(),
) {
    init {
        require(name.isNotBlank()) { "Environment name cannot be blank" }
        require(name.length <= 100) { "Environment name too long" }
    }
}

/**
 * Mutable environment configuration.
 */
@Serializable
data class EnvironmentConfiguration(
    val runtime: RuntimeConfig = RuntimeConfig(),
    val display: DisplayConfig = DisplayConfig(),
    val gpu: GpuConfig = GpuConfig(),
    val audio: AudioConfig = AudioConfig(),
    val network: NetworkConfig = NetworkConfig(),
    val desktop: DesktopConfig = DesktopConfig(),
    /** Linux username inside the environment. */
    val linuxUser: String = "user",
    /** Linux user home directory inside the chroot. */
    val homeDir: String = "/home/user",
    /** Default login shell binary path inside the chroot. */
    val shell: String = "/bin/bash",
)

/**
 * Complete environment: metadata + configuration + current state.
 *
 * This is the primary domain object. The Android database stores metadata
 * and configuration. The current state is authoritative from the runtime.
 */
data class Environment(
    val metadata: EnvironmentMetadata,
    val configuration: EnvironmentConfiguration = EnvironmentConfiguration(),
    val state: EnvironmentState = EnvironmentState.CREATED,
    /** Absolute path to the rootfs directory on the Android filesystem. */
    val rootfsPath: String,
    /** Absolute path to metadata directory on Android filesystem. */
    val metadataPath: String,
    /** Last state change timestamp (epoch ms). */
    val lastStateChangeAt: Long = System.currentTimeMillis(),
    /** Error message if state == FAILED. */
    val failureMessage: String? = null,
    /** Installation state of the optional GUI layer. Never invalidates the CLI environment. */
    val guiState: GuiState = GuiState.NOT_INSTALLED,
    /** Last GUI installation or repair failure message. Null if no failure has occurred. */
    val guiFailureMessage: String? = null,
) {
    val id: EnvironmentId get() = metadata.id
    val name: String get() = metadata.name
    val distribution: Distribution get() = metadata.distribution
    val architecture: Architecture get() = metadata.architecture

    /**
     * Returns a copy of this environment with the new state applied,
     * after validating that the transition is legal.
     *
     * @throws IllegalStateTransitionException if the transition is invalid.
     */
    fun withState(
        newState: EnvironmentState,
        failureMessage: String? = null,
    ): Environment {
        if (state != newState) {
            if (!newState.isValidTransitionFrom(state)) {
                throw IllegalStateTransitionException(
                    environmentId = id,
                    from = state,
                    to = newState,
                )
            }
        }
        return copy(
            state = newState,
            lastStateChangeAt = System.currentTimeMillis(),
            failureMessage = if (newState == EnvironmentState.FAILED) failureMessage else null,
        )
    }

    /**
     * Returns a copy of this environment with the new GUI state applied.
     * Does not affect the CLI [state] — a GUI state change never invalidates the Linux environment.
     *
     * @param newGuiState The new GUI installation state.
     * @param guiFailureMessage Optional failure message when [newGuiState] is [GuiState.FAILED].
     */
    fun withGuiState(
        newGuiState: GuiState,
        guiFailureMessage: String? = null,
    ): Environment = copy(
        guiState = newGuiState,
        guiFailureMessage = if (newGuiState == GuiState.FAILED) guiFailureMessage else null,
    )
}

/**
 * Thrown when an invalid state transition is attempted.
 */
class IllegalStateTransitionException(
    val environmentId: EnvironmentId,
    val from: EnvironmentState,
    val to: EnvironmentState,
) : Exception("Invalid transition for environment $environmentId: $from → $to")
