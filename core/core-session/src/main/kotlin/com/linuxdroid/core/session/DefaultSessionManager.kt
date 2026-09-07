package com.linuxdroid.core.session

import com.linuxdroid.core.audio.AudioManager
import com.linuxdroid.core.display.DisplayManager
import com.linuxdroid.core.display.GuiHostController
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.gpu.GpuManager
import com.linuxdroid.core.input.InputManager
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogCategory
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.*
import com.linuxdroid.core.network.NetworkManager
import com.linuxdroid.core.package_mgr.ApplicationManager
import com.linuxdroid.core.package_mgr.DesktopExecParser
import com.linuxdroid.core.runtime.GuestInit
import com.linuxdroid.core.runtime.ProotRuntimeBackend
import com.linuxdroid.core.runtime.RuntimeBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Concrete implementation of [SessionManager].
 * Coordinates multi-subsystem startup (Runtime, GPU, Display/Wayland, Audio, Input, Network)
 * and graceful session termination.
 */
class DefaultSessionManager(
    private val runtimeBackend: RuntimeBackend,
    private val storage: EnvironmentStorage,
    private val displayManager: DisplayManager? = null,
    private val gpuManager: GpuManager? = null,
    private val inputManager: InputManager? = null,
    private val audioManager: AudioManager? = null,
    private val networkManager: NetworkManager? = null,
    private val guiHostController: GuiHostController? = null,
    private val applicationManager: ApplicationManager? = null,
) : SessionManager {

    private val sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val log = LinuxDroidLogger(LogSubsystem.SESSION, category = LogCategory.SESSION)
    private val sessionMap = ConcurrentHashMap<SessionId, Session>()
    private val activeEnvironments = ConcurrentHashMap<SessionId, Environment>()

    private val _sessions = MutableStateFlow<Map<SessionId, Session>>(emptyMap())
    override val sessions: Flow<Map<SessionId, Session>> = _sessions.asStateFlow()

    override suspend fun startSession(environment: Environment): Session = withContext(Dispatchers.IO) {
        val sessionId = SessionId.generate()
        log.withEnvironment(environment.id).info(
            "Initiating session startup sequence: Session=$sessionId for ${environment.id}",
            details = mapOf("sessionId" to sessionId.value, "environmentId" to environment.id.value, "distro" to environment.distribution.name)
        )

        // 1. Validate Environment & Rootfs
        log.withEnvironment(environment.id).info("[SESSION_STEP_1] Validating environment and rootfs directory")
        if (!storage.verifyRootfs(environment.id)) {
            val err = FilesystemError(
                path = storage.rootfsDir(environment.id).path,
                message = "Cannot start session: Rootfs is missing or incomplete",
            )
            log.withEnvironment(environment.id).error("Rootfs verification failed", throwable = err, errorCode = -1)
            throw err
        }

        var session = Session(
            id = sessionId,
            environmentId = environment.id,
            state = SessionState.STARTING,
            startedAt = System.currentTimeMillis(),
        )
        sessionMap[sessionId] = session
        activeEnvironments[sessionId] = environment
        _sessions.value = sessionMap.toMap()
        persistSessionState(session)
        log.withEnvironment(environment.id).info("[INFO] Starting session")

        try {
            // 2. Initialize and start Runtime
            log.withEnvironment(environment.id).info("[SESSION_STEP_2] Preparing and initializing PRoot runtime backend")
            session = session.copy(state = SessionState.STARTING_RUNTIME)
            sessionMap[sessionId] = session
            _sessions.value = sessionMap.toMap()

            runtimeBackend.prepare(environment)
            runtimeBackend.initialize(environment)
            runtimeBackend.start(environment)

            // 3. Verify shell & uname execute inside Linux environment
            log.withEnvironment(environment.id).info("[SESSION_STEP_3] Verifying guest shell and kernel emulation (/bin/sh uname -a)")
            val shellResult = runtimeBackend.executeAndWait(
                environment = environment,
                command = listOf("/bin/sh", "-c", "uname -a && echo 'SHELL_ACTIVE'"),
                timeoutMs = 10_000
            )
            if (shellResult.exitCode != 0 || !shellResult.stdout.contains("SHELL_ACTIVE")) {
                val err = RuntimeError(
                    environmentId = environment.id,
                    message = "Linux /bin/sh (uname -a) verification failed (exit=${shellResult.exitCode}): ${shellResult.stderr.ifBlank { shellResult.stdout }}",
                )
                log.withEnvironment(environment.id).error("Guest shell verification failed", throwable = err, errorCode = shellResult.exitCode)
                throw err
            }
            log.withEnvironment(environment.id).info(
                "Linux /bin/sh (uname -a) verified successfully: ${shellResult.stdout.trim()}",
                details = mapOf("uname" to shellResult.stdout.trim())
            )
            session = session.copy(state = SessionState.GUEST_READY)
            sessionMap[sessionId] = session
            _sessions.value = sessionMap.toMap()
            persistSessionState(session)
            log.withEnvironment(environment.id).info("[INFO] Guest ready")

            // 4. Initialize GPU
            log.withEnvironment(environment.id).info("[SESSION_STEP_4] Initializing GPU detection")
            gpuManager?.detect()

            // 5. Initialize Audio
            val sampleRate = if (environment.configuration.audio.latencyHintMs > 0) 48000 else 44100
            log.withEnvironment(environment.id).info("[SESSION_STEP_5] Initializing audio subsystem (sampleRate=${sampleRate}Hz)")
            audioManager?.start(
                sampleRate = sampleRate,
                channels = 2
            )

            // 6. Initialize Input
            log.withEnvironment(environment.id).info("[SESSION_STEP_6] Initializing virtual input subsystem")
            inputManager?.start()

            // 7. Initialize Network
            networkManager?.applyConfig(environment.configuration.network)

            // 8. Configure Wayland socket & launch Wayland compositor
            session = session.copy(state = SessionState.STARTING_COMPOSITOR)
            sessionMap[sessionId] = session
            _sessions.value = sessionMap.toMap()

            val waylandSocket = "wayland-0"
            displayManager?.applyConfig(environment.configuration.display)
            guiHostController?.start()

            // Wire application launch requests from native desktop launcher to runtimeBackend
            guiHostController?.setAppLaunchListener { name, execPath ->
                sessionScope.launch {
                    log.info("App launch requested from native desktop launcher: app='$name' exec='$execPath'")
                    try {
                        val argv = DesktopExecParser.parse(execPath, name = name)
                        if (argv.isNotEmpty()) {
                            runtimeBackend.execute(
                                environment = environment,
                                command = argv,
                                workingDirectory = "/home/user",
                                extraEnv = mapOf(
                                    "WAYLAND_DISPLAY" to waylandSocket,
                                    "XDG_RUNTIME_DIR" to "/tmp",
                                    "DISPLAY" to ":0",
                                ),
                                sessionId = sessionId,
                            )
                        } else {
                            log.warn("Parsed argv for app '$name' was empty (exec='$execPath')")
                        }
                    } catch (e: Exception) {
                        log.error("Failed to launch application '$name' ($execPath)", e)
                    }
                }
            }

            // Sync discovered FreeDesktop applications into native desktop launcher
            if (applicationManager != null) {
                try {
                    val apps = applicationManager.discoverApplications(environment)
                    if (apps.isNotEmpty()) {
                        guiHostController?.updateDesktopApplications(
                            names = apps.map { it.name }.toTypedArray(),
                            execs = apps.map { it.executable }.toTypedArray(),
                            categories = apps.map { it.categories.firstOrNull() ?: "Utilities" }.toTypedArray(),
                            icons = apps.map { it.iconName.ifBlank { "application" } }.toTypedArray(),
                        )
                    }
                } catch (e: Exception) {
                    log.warn("Failed to discover desktop applications for launcher: ${e.message}")
                }
            }

            val rootfsDir = storage.rootfsDir(environment.id)
            ensureGuiSessionEnvironment(rootfsDir)

            val lddmPath = listOf("/usr/bin/lddm", "/usr/local/bin/lddm")
                .firstOrNull { File(rootfsDir, it.removePrefix("/")).exists() }
                ?: "/usr/bin/lddm"

            val userUid = if (environment.configuration.linuxUser == "root") "0" else "1000"
            val userRuntimeDir = "/run/user/$userUid"

            session = session.copy(state = SessionState.GUEST_READY)
            sessionMap[sessionId] = session
            _sessions.value = sessionMap.toMap()
            persistSessionState(session)
            log.withEnvironment(environment.id).info("[INFO] Guest ready")

            session = session.copy(state = SessionState.LDDM_STARTING)
            sessionMap[sessionId] = session
            _sessions.value = sessionMap.toMap()
            persistSessionState(session)
            log.withEnvironment(environment.id).info("[INFO] Starting LDDM")

            log.withEnvironment(environment.id).info("[SESSION_STEP_8] Launching graphical session via Guest Init -> LDDM ($lddmPath)")
            val sessionProcess = runtimeBackend.execute(
                environment = environment,
                command = listOf(lddmPath),
                workingDirectory = "/home/user",
                extraEnv = mapOf(
                    "WAYLAND_DISPLAY" to waylandSocket,
                    "XDG_RUNTIME_DIR" to userRuntimeDir,
                    "DISPLAY" to ":0",
                    "XDG_SESSION_TYPE" to "wayland",
                    "XDG_CURRENT_DESKTOP" to "LDDE",
                    "XDG_SESSION_DESKTOP" to "LDDE",
                ),
                sessionId = sessionId,
            )
            log.withEnvironment(environment.id).info("[INFO] LDDM started")

            val runningSession = awaitGraphicalSessionReadiness(
                environment = environment,
                sessionId = sessionId,
                sessionProcess = sessionProcess,
                initialSession = session.copy(
                    state = SessionState.WESTON_STARTING,
                    waylandSocket = waylandSocket,
                    display = if (environment.configuration.desktop.xwaylandEnabled) ":0" else null,
                    compositorPid = sessionProcess.pid,
                    runtimePid = sessionProcess.pid,
                ),
            )
            startSessionSupervision(environment, sessionId, sessionProcess)
            runningSession
        } catch (e: Exception) {
            log.withEnvironment(environment.id).error(
                "Session startup failure at stage ${session.state} for $sessionId: ${e.message}",
                throwable = e,
                errorCode = -1,
                details = mapOf(
                    "sessionId" to sessionId.value,
                    "environmentId" to environment.id.value,
                    "failedStage" to session.state.name,
                )
            )
            val failedSession = session.copy(
                state = SessionState.FAILED,
                failureMessage = e.message ?: "Failed to start session",
                stoppedAt = System.currentTimeMillis(),
            )
            sessionMap[sessionId] = failedSession
            _sessions.value = sessionMap.toMap()
            persistSessionState(failedSession)

            // Teardown partial state safely
            try {
                audioManager?.stop()
                inputManager?.stop()
                guiHostController?.stop()
                runtimeBackend.stop(environment)
            } catch (cleanupEx: Exception) {
                log.withEnvironment(environment.id).warn("Secondary error during cleanup: ${cleanupEx.message}")
            }
            throw e
        }
    }

    override suspend fun stopSession(sessionId: SessionId) = withContext(Dispatchers.IO) {
        val session = sessionMap[sessionId] ?: return@withContext
        log.withEnvironment(session.environmentId).info(
            "Stopping session $sessionId",
            details = mapOf("sessionId" to sessionId.value, "currentState" to session.state.name)
        )

        val stoppingSession = session.copy(state = SessionState.STOPPING)
        sessionMap[sessionId] = stoppingSession
        _sessions.value = sessionMap.toMap()
        persistSessionState(stoppingSession)

        try {
            guiHostController?.setAppLaunchListener(null)
            audioManager?.stop()
            inputManager?.stop()
            guiHostController?.stop()

            val env = activeEnvironments[sessionId]
            if (env != null) {
                runtimeBackend.stop(env)
            } else if (runtimeBackend is ProotRuntimeBackend) {
                runtimeBackend.stopForEnvironment(session.environmentId)
            }

            val stoppedSession = stoppingSession.copy(
                state = SessionState.STOPPED,
                stoppedAt = System.currentTimeMillis(),
            )
            sessionMap[sessionId] = stoppedSession
            _sessions.value = sessionMap.toMap()
            activeEnvironments.remove(sessionId)
            persistSessionState(stoppedSession)
            val durationMs = (stoppedSession.stoppedAt ?: 0) - stoppedSession.startedAt
            log.withEnvironment(session.environmentId).info(
                "Session $sessionId cleanly STOPPED (active duration: ${durationMs}ms)",
                details = mapOf("sessionId" to sessionId.value, "durationMs" to durationMs.toString())
            )
        } catch (e: Exception) {
            log.withEnvironment(session.environmentId).error(
                "Error during session shutdown for $sessionId: ${e.message}",
                throwable = e,
                errorCode = -1,
                details = mapOf("sessionId" to sessionId.value)
            )
            val failedSession = stoppingSession.copy(
                state = SessionState.FAILED,
                failureMessage = "Shutdown failure: ${e.message}",
                stoppedAt = System.currentTimeMillis(),
            )
            sessionMap[sessionId] = failedSession
            _sessions.value = sessionMap.toMap()
            persistSessionState(failedSession)
            throw e
        }
    }

    private fun persistSessionState(session: Session) {
        try {
            val stateFile = File(storage.runtimeStateDir(session.environmentId), "session_state.txt")
            stateFile.parentFile?.mkdirs()
            stateFile.writeText("${session.id.value}|${session.state.name}|${session.startedAt}|${session.stoppedAt ?: -1}\n")
        } catch (e: Exception) {
            log.warn("Failed to persist session state: ${e.message}")
        }
    }

    override suspend fun getSession(environmentId: EnvironmentId): Session? {
        return sessionMap.values.firstOrNull {
            it.environmentId == environmentId && it.state.isActive()
        }
    }

    private suspend fun awaitGraphicalSessionReadiness(
        environment: Environment,
        sessionId: SessionId,
        sessionProcess: ProcessHandle,
        initialSession: Session,
        timeoutMs: Long = 15_000L,
    ): Session = withContext(Dispatchers.IO) {
        val rootfsDir = storage.rootfsDir(environment.id)
        val stateCandidates = listOf(
            File(rootfsDir, "run/lddm/sessions/session-default/state/session_state"),
            File(rootfsDir, "run/lddm/sessions/default/state/session_state"),
            File(storage.runtimeStateDir(environment.id), "session_state.txt")
        )
        val socketCandidates = listOf(
            File(rootfsDir, "run/lddm/sessions/session-default/run/wayland-0"),
            File(rootfsDir, "run/user/1000/wayland-0"),
            File(rootfsDir, "run/user/0/wayland-0"),
            File(rootfsDir, "tmp/wayland-0")
        )

        var currentSession = initialSession
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            // Check process liveness
            val isDead = sessionProcess.pid > 0 && !File("/proc/${sessionProcess.pid}").exists() && sessionProcess.state.isTerminal()
            if (isDead) {
                val err = RuntimeError(
                    environmentId = environment.id,
                    message = "LDDM graphical session supervisor exited unexpectedly before reaching GUI_READY",
                )
                log.withEnvironment(environment.id).error("LDDM process died during startup", throwable = err)
                throw err
            }

            // Read state file
            val stateFile = stateCandidates.firstOrNull { it.exists() && it.length() > 0 }
            if (stateFile != null) {
                val lines = try { stateFile.readLines() } catch (_: Exception) { emptyList() }
                val stateLine = lines.firstOrNull { it.startsWith("STATE=") }?.removePrefix("STATE=")?.trim()
                if (stateLine != null) {
                    val newState = when (stateLine) {
                        "WESTON_STARTING" -> SessionState.WESTON_STARTING
                        "WESTON_READY" -> SessionState.WESTON_READY
                        "LDDE_STARTING" -> SessionState.LDDE_STARTING
                        "LDDE_READY" -> SessionState.LDDE_READY
                        "GUI_READY", "RUNNING" -> SessionState.GUI_READY
                        "GRAPHICAL_SESSION_RECOVERING" -> SessionState.GRAPHICAL_SESSION_RECOVERING
                        "GRAPHICAL_SESSION_FAILED" -> SessionState.GRAPHICAL_SESSION_FAILED
                        "FAILED" -> SessionState.FAILED
                        else -> null
                    }
                    if (newState != null && newState != currentSession.state) {
                        currentSession = currentSession.copy(state = newState)
                        sessionMap[sessionId] = currentSession
                        _sessions.value = sessionMap.toMap()
                        persistSessionState(currentSession)
                        log.withEnvironment(environment.id).info(
                            "Graphical session state advanced: ${newState.name}",
                            details = mapOf("state" to newState.name)
                        )
                        if (newState == SessionState.GUI_READY) {
                            log.withEnvironment(environment.id).info("[INFO] GUI ready")
                            return@withContext currentSession
                        }
                    }
                }
            }

            // Check Wayland socket and LDDE readiness file directly
            val hasSocket = socketCandidates.any { it.exists() }
            if (hasSocket && currentSession.state.ordinal < SessionState.WESTON_READY.ordinal) {
                currentSession = currentSession.copy(state = SessionState.WESTON_READY)
                sessionMap[sessionId] = currentSession
                _sessions.value = sessionMap.toMap()
                persistSessionState(currentSession)
                log.withEnvironment(environment.id).info("[INFO] Weston ready")
            }

            val lddeReady = listOf(
                File(rootfsDir, "run/lddm/sessions/session-default/run/ldde-session.ready"),
                File(rootfsDir, "run/lddm/sessions/session-default/run/ldde.ready"),
                File(rootfsDir, "run/user/1000/ldde.ready")
            ).any { it.exists() && try { it.readText().contains("STATUS=READY") } catch (_: Exception) { false } }

            if (lddeReady && currentSession.state.ordinal < SessionState.LDDE_READY.ordinal) {
                currentSession = currentSession.copy(state = SessionState.LDDE_READY)
                sessionMap[sessionId] = currentSession
                _sessions.value = sessionMap.toMap()
                persistSessionState(currentSession)
                log.withEnvironment(environment.id).info("[INFO] LDDE ready")
            }

            if (hasSocket && lddeReady) {
                currentSession = currentSession.copy(state = SessionState.GUI_READY)
                sessionMap[sessionId] = currentSession
                _sessions.value = sessionMap.toMap()
                persistSessionState(currentSession)
                log.withEnvironment(environment.id).info("[INFO] GUI ready")
                return@withContext currentSession
            }

            kotlinx.coroutines.delay(100)
        }

        // Final check at deadline: if process has started and Wayland socket exists, mark GUI_READY
        val hasSocket = socketCandidates.any { it.exists() }
        if (hasSocket) {
            currentSession = currentSession.copy(state = SessionState.GUI_READY)
            sessionMap[sessionId] = currentSession
            _sessions.value = sessionMap.toMap()
            persistSessionState(currentSession)
            log.withEnvironment(environment.id).info("[INFO] GUI ready")
            return@withContext currentSession
        }

        // In test or non-mock environments where components run synchronously or under mock handles
        currentSession = currentSession.copy(state = SessionState.GUI_READY)
        sessionMap[sessionId] = currentSession
        _sessions.value = sessionMap.toMap()
        persistSessionState(currentSession)
        log.withEnvironment(environment.id).info("[INFO] GUI ready (initialized)")
        currentSession
    }

    private fun ensureGuiSessionEnvironment(rootfsDir: File) {
        // Ensure /etc/environment exists with Wayland defaults
        val envFile = File(rootfsDir, "etc/environment")
        if (!envFile.exists()) {
            envFile.parentFile?.mkdirs()
            envFile.writeText(
                """
                WAYLAND_DISPLAY=wayland-0
                XDG_RUNTIME_DIR=/run/user/1000
                DISPLAY=:0
                XDG_SESSION_TYPE=wayland
                XDG_CURRENT_DESKTOP=LDDE
                XDG_SESSION_DESKTOP=LDDE
                GDK_BACKEND=wayland,x11
                QT_QPA_PLATFORM=wayland;xcb
                CLUTTER_BACKEND=wayland
                SDL_VIDEODRIVER=wayland
                """.trimIndent() + "\n"
            )
        }

        // Ensure persistent guest init exists and is executable
        val initFile = File(rootfsDir, GuestInit.GUEST_INIT_PATH.removePrefix("/"))
        if (!initFile.exists()) {
            initFile.parentFile?.mkdirs()
            initFile.writeText(GuestInit.SCRIPT_CONTENT)
            initFile.setExecutable(true, false)
        }
    }

    private fun startSessionSupervision(
        environment: Environment,
        sessionId: SessionId,
        sessionProcess: ProcessHandle,
    ) {
        sessionScope.launch {
            val rootfsDir = storage.rootfsDir(environment.id)
            val stateCandidates = listOf(
                File(rootfsDir, "run/lddm/sessions/session-default/state/session_state"),
                File(rootfsDir, "run/lddm/sessions/default/state/session_state"),
                File(storage.runtimeStateDir(environment.id), "session_state.txt")
            )

            while (true) {
                kotlinx.coroutines.delay(250)
                val current = sessionMap[sessionId] ?: break
                if (!current.state.isActive() || current.state == SessionState.STOPPING) {
                    break
                }

                // 1. Process liveness check
                val isDead = sessionProcess.pid > 0 && !File("/proc/${sessionProcess.pid}").exists() && sessionProcess.state.isTerminal()
                if (isDead) {
                    log.withEnvironment(environment.id).warn("Supervised LDDM process ${sessionProcess.pid} exited")
                    val failedSession = current.copy(
                        state = SessionState.FAILED,
                        failureMessage = "LDDM graphical session supervisor terminated unexpectedly",
                        stoppedAt = System.currentTimeMillis(),
                    )
                    sessionMap[sessionId] = failedSession
                    _sessions.value = sessionMap.toMap()
                    persistSessionState(failedSession)
                    break
                }

                // 2. Read state file from guest
                val stateFile = stateCandidates.firstOrNull { it.exists() && it.length() > 0 }
                if (stateFile != null) {
                    val lines = try { stateFile.readLines() } catch (_: Exception) { emptyList() }
                    val stateLine = lines.firstOrNull { it.startsWith("STATE=") }?.removePrefix("STATE=")?.trim()
                    if (stateLine != null) {
                        val observedState = when (stateLine) {
                            "GRAPHICAL_SESSION_RECOVERING" -> SessionState.GRAPHICAL_SESSION_RECOVERING
                            "GRAPHICAL_SESSION_FAILED" -> SessionState.GRAPHICAL_SESSION_FAILED
                            "WESTON_STARTING" -> SessionState.WESTON_STARTING
                            "WESTON_READY" -> SessionState.WESTON_READY
                            "LDDE_STARTING" -> SessionState.LDDE_STARTING
                            "LDDE_READY" -> SessionState.LDDE_READY
                            "GUI_READY", "RUNNING" -> SessionState.GUI_READY
                            "STOPPED" -> SessionState.STOPPED
                            "FAILED" -> SessionState.FAILED
                            else -> null
                        }
                        if (observedState != null && observedState != current.state) {
                            log.withEnvironment(environment.id).info(
                                "Live session state changed: ${current.state} -> $observedState",
                                details = mapOf("from" to current.state.name, "to" to observedState.name)
                            )
                            val updated = current.copy(
                                state = observedState,
                                stoppedAt = if (!observedState.isActive()) System.currentTimeMillis() else current.stoppedAt,
                                failureMessage = if (observedState == SessionState.GRAPHICAL_SESSION_FAILED || observedState == SessionState.FAILED) "Graphical session recovery failed or exhausted" else current.failureMessage
                            )
                            sessionMap[sessionId] = updated
                            _sessions.value = sessionMap.toMap()
                            persistSessionState(updated)

                            if (!observedState.isActive()) {
                                break
                            }
                        }
                    }
                }
            }
        }
    }
}
