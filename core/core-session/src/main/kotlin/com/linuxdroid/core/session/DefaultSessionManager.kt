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
            state = SessionState.INITIALIZING,
            startedAt = System.currentTimeMillis(),
        )
        sessionMap[sessionId] = session
        activeEnvironments[sessionId] = environment
        _sessions.value = sessionMap.toMap()
        persistSessionState(session)

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

            log.withEnvironment(environment.id).info("[SESSION_STEP_8] Launching graphical session via Guest Init -> LDDM ($lddmPath)")
            val sessionProcess = runtimeBackend.execute(
                environment = environment,
                command = listOf(GuestInit.GUEST_INIT_PATH, lddmPath),
                workingDirectory = "/home/user",
                extraEnv = mapOf(
                    "WAYLAND_DISPLAY" to waylandSocket,
                    "XDG_RUNTIME_DIR" to "/tmp",
                    "DISPLAY" to ":0",
                    "XDG_SESSION_TYPE" to "wayland",
                    "XDG_CURRENT_DESKTOP" to "LDDE",
                    "XDG_SESSION_DESKTOP" to "LDDE",
                ),
                sessionId = sessionId,
            )

            // 9. Mark RUNNING
            val runningSession = session.copy(
                state = SessionState.RUNNING,
                waylandSocket = waylandSocket,
                display = if (environment.configuration.desktop.xwaylandEnabled) ":0" else null,
                compositorPid = sessionProcess.pid,
                runtimePid = sessionProcess.pid,
            )
            sessionMap[sessionId] = runningSession
            _sessions.value = sessionMap.toMap()
            persistSessionState(runningSession)
            log.withEnvironment(environment.id).info(
                "Session $sessionId is now fully active (RUNNING)",
                details = mapOf(
                    "sessionId" to sessionId.value,
                    "compositorPid" to sessionProcess.pid.toString(),
                    "waylandSocket" to waylandSocket,
                )
            )
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

    private fun ensureGuiSessionEnvironment(rootfsDir: File) {
        // Ensure /etc/environment exists with Wayland defaults
        val envFile = File(rootfsDir, "etc/environment")
        if (!envFile.exists()) {
            envFile.parentFile?.mkdirs()
            envFile.writeText(
                """
                WAYLAND_DISPLAY=wayland-0
                XDG_RUNTIME_DIR=/tmp
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
}
