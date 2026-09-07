package com.linuxdroid.core.session

import com.linuxdroid.core.model.*
import kotlinx.coroutines.flow.Flow

/**
 * SessionManager manages the lifecycle of active Linux graphical sessions.
 *
 * A Session represents a complete active Linux graphical environment including:
 * - Runtime (proot)
 * - Wayland compositor
 * - Desktop environment
 * - Input, Audio, Network
 *
 * Implementation: Phase 10 of the development roadmap.
 */
interface SessionManager {
    /** Active sessions by SessionId. */
    val sessions: Flow<Map<SessionId, Session>>

    /** Creates and starts a new session for the given environment in the requested [startMode]. */
    suspend fun startSession(environment: Environment, startMode: StartMode = StartMode.GUI): Session

    /** Stops a running session. */
    suspend fun stopSession(sessionId: SessionId)

    /** Returns the active session for a given environment, if any. */
    suspend fun getSession(environmentId: EnvironmentId): Session?
}
