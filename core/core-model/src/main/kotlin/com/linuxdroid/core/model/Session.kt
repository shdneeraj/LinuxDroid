package com.linuxdroid.core.model

import java.time.Instant

/**
 * Unique identifier for an active session.
 */
@JvmInline
value class SessionId(val value: String) {
    override fun toString(): String = value

    companion object {
        fun generate(): SessionId = SessionId(java.util.UUID.randomUUID().toString())
    }
}

/**
 * Lifecycle state of a session.
 */
enum class SessionState {
    /** Session is being created. */
    INITIALIZING,
    /** Session startup in progress. */
    STARTING,
    /** Guest runtime and PRoot are validated and running. */
    GUEST_READY,
    /** LDDM display manager is starting. */
    LDDM_STARTING,
    /** Weston Wayland compositor is starting. */
    WESTON_STARTING,
    /** Weston Wayland compositor is running and socket is usable. */
    WESTON_READY,
    /** LDDE desktop environment is starting. */
    LDDE_STARTING,
    /** LDDE desktop environment has established operational readiness. */
    LDDE_READY,
    /** Complete graphical session is operational (Guest + LDDM + Weston + LDDE). */
    GUI_READY,
    /** Graphical session failed to start or unrecoverably crashed. */
    GUI_FAILED,
    /** Terminal CLI session is initializing. */
    CLI_STARTING,
    /** Terminal CLI session is ready and running. */
    CLI_READY,
    /** Terminal CLI session failed. */
    CLI_FAILED,
    /** Backward compatibility alias for GUI_READY / active session. */
    RUNNING,
    /** Backward compatibility alias for STARTING / GUEST_READY. */
    STARTING_RUNTIME,
    /** Backward compatibility alias for WESTON_STARTING. */
    STARTING_COMPOSITOR,
    /** Backward compatibility alias for LDDE_STARTING. */
    STARTING_DESKTOP,
    /** Weston compositor failed. */
    WESTON_FAILED,
    /** LDDE desktop environment failed. */
    LDDE_FAILED,
    /** Graphical session encountered a component failure and LDDM recovery is actively restoring it. */
    GRAPHICAL_SESSION_RECOVERING,
    /** Graphical session recovery exhausted all retry attempts or failed unrecoverably. */
    GRAPHICAL_SESSION_FAILED,
    /** Session is shutting down. */
    STOPPING,
    /** Session stopped cleanly. */
    STOPPED,
    /** Session failed. */
    FAILED;

    fun isActive(): Boolean = this in setOf(
        INITIALIZING,
        STARTING,
        GUEST_READY,
        LDDM_STARTING,
        WESTON_STARTING,
        WESTON_READY,
        LDDE_STARTING,
        LDDE_READY,
        GUI_READY,
        CLI_STARTING,
        CLI_READY,
        RUNNING,
        STARTING_RUNTIME,
        STARTING_COMPOSITOR,
        STARTING_DESKTOP,
        GRAPHICAL_SESSION_RECOVERING,
        STOPPING,
    )
}

/**
 * A Session represents a complete active Linux graphical or CLI environment.
 * It owns the runtime, compositor/shell, and associated subsystems.
 */
data class Session(
    val id: SessionId,
    val environmentId: EnvironmentId,
    val state: SessionState,
    val startMode: StartMode = StartMode.GUI,
    val startedAt: Long = System.currentTimeMillis(),
    val stoppedAt: Long? = null,
    val failureMessage: String? = null,
    /** PID of the proot/runtime process. -1 if not running. */
    val runtimePid: Int = -1,
    /** PID of the Wayland compositor. -1 if not running. */
    val compositorPid: Int = -1,
    /** PID of the desktop session. -1 if not running. */
    val desktopPid: Int = -1,
    /** Wayland socket name (e.g. "wayland-0"). */
    val waylandSocket: String? = null,
    /** DISPLAY variable if XWayland is running. */
    val display: String? = null,
)
