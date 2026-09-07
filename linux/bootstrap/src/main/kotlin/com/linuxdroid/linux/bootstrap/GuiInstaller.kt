package com.linuxdroid.linux.bootstrap

import android.content.Context
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.logging.InstallationLogger
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.*
import com.linuxdroid.core.runtime.GuiInstallScript
import com.linuxdroid.core.runtime.RuntimeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified GUI installation, repair, and validation manager for LinuxDroid.
 *
 * Implements the GUI-optional architecture:
 * - GUI installation runs INSIDE the existing CLI runtime environment
 * - Failure during GUI installation or repair NEVER invalidates the CLI foundation
 * - Validates Wayland, Weston, LDDM, and LDDE artifacts on disk
 * - Supports partial repair without reinstalling already working components
 */
class GuiInstaller(
    private val context: Context? = null,
    private val storage: EnvironmentStorage,
    private val runtimeBackend: RuntimeBackend? = null,
    private val packageInstaller: LinuxDroidPackageInstaller = LinuxDroidPackageInstaller(context, runtimeBackend),
    private val graphicalInstaller: GraphicalDependencyInstaller = GraphicalDependencyInstaller(runtimeBackend),
    private val validator: RootfsValidator = RootfsValidator(),
) {
    private val log = LinuxDroidLogger(LogSubsystem.BOOTSTRAP)
    private val _guiStates = MutableStateFlow<Map<String, GuiState>>(emptyMap())
    val guiStates: StateFlow<Map<String, GuiState>> = _guiStates.asStateFlow()

    companion object {
        private val guiLocks = ConcurrentHashMap<EnvironmentId, Mutex>()
    }

    /**
     * Checks the current persistent GUI state for the given environment.
     * Combines persistent marker with disk validation.
     */
    fun checkStatus(environment: Environment): GuiState {
        val id = environment.id
        val stateFile = storage.guiStateFile(id)
        if (!stateFile.exists()) {
            return GuiState.NOT_INSTALLED
        }

        val text = runCatching { stateFile.readText(Charsets.UTF_8).trim() }.getOrNull()
        val parsed = GuiState.fromString(text)

        // If marked INSTALLED, verify the completion marker actually exists on disk
        if (parsed == GuiState.INSTALLED) {
            val rootfsDir = storage.rootfsDir(id)
            val marker = File(rootfsDir, "etc/linuxdroid/GUI_INSTALL_COMPLETE")
            if (!marker.exists()) {
                return GuiState.FAILED
            }
        }

        return parsed
    }

    /**
     * Performs lightweight disk validation of the graphical stack.
     * Does NOT require running GUI processes.
     */
    fun validate(environment: Environment): RootfsValidationReport {
        val rootfsDir = storage.rootfsDir(environment.id)
        return validator.validateGraphics(
            rootfsDir = rootfsDir,
            distribution = environment.distribution,
            architecture = environment.architecture,
        )
    }

    /**
     * Atomically writes the GUI state for an environment.
     */
    suspend fun writeGuiState(environment: Environment, state: GuiState) = withContext(Dispatchers.IO) {
        val stateFile = storage.guiStateFile(environment.id)
        storage.writeAtomic(stateFile, "${state.name}\n")
        _guiStates.value = _guiStates.value + (environment.id.value to state)
    }

    /**
     * Returns the dedicated GUI installation log file if it exists.
     */
    fun getLog(environment: Environment): File? {
        val logFile = storage.guiInstallLogFile(environment.id)
        return if (logFile.exists() && logFile.length() > 0) logFile else null
    }

    /**
     * Installs the complete graphical layer on top of an existing CLI environment.
     *
     * In case of failure:
     * - Marks GUI state as [GuiState.FAILED]
     * - Keeps the CLI rootfs, user configuration, and CLI packages fully intact
     */
    suspend fun install(
        environment: Environment,
        lddmDebOverride: File? = null,
        lddeDebOverride: File? = null,
        onProgress: suspend (Float, String) -> Unit = { _, _ -> },
        onLog: suspend (String) -> Unit = { _ -> },
    ): GuiState = withContext(Dispatchers.IO) {
        val environmentId = environment.id
        val mutex = guiLocks.computeIfAbsent(environmentId) { Mutex() }

        mutex.withLock {
            val envKey = environmentId.value
            val rootfsDir = storage.rootfsDir(environmentId)

            if (!storage.verifyRootfs(environmentId)) {
                throw IllegalStateException("Cannot install GUI: CLI rootfs for $environmentId is not ready or valid")
            }

            log.info("[GUI_INSTALL] Starting GUI installation for $environmentId")
            onLog(">>> [GUI_INSTALL][START][FULL_INSTALL]")

            writeGuiState(environment, GuiState.INSTALLING)
            onProgress(0.05f, "Preparing GUI installation…")

            val startTime = System.currentTimeMillis()
            val logFile = storage.guiInstallLogFile(environmentId)
            logFile.parentFile?.mkdirs()

            fun appendLogLine(line: String) {
                runCatching {
                    logFile.appendText("$line\n", Charsets.UTF_8)
                }
            }

            appendLogLine("=== LINUXDROID GUI INSTALLATION LOG ===")
            appendLogLine("Environment: ${environmentId.value}")
            appendLogLine("Started: ${java.time.Instant.now()}")

            try {
                // 1. Resolve and stage LDDM & LDDE .deb packages
                onProgress(0.15f, "Staging LinuxDroid GUI packages…")
                val lddmDeb = lddmDebOverride ?: packageInstaller.resolvePackageDeb("linuxdroid-display-manager", environment)
                val lddeDeb = lddeDebOverride ?: packageInstaller.resolvePackageDeb("linuxdroid-desktop-environment", environment)
                GuiInstallScript.stageGuiPackages(rootfsDir, lddmDeb, lddeDeb)
                GuiInstallScript.writeScript(rootfsDir)

                if (runtimeBackend != null) {
                    onProgress(0.25f, "Executing in-guest GUI installer…")
                    onLog(">>> [GUI_INSTALL] Starting in-guest GUI installer via CLI runtime")

                    val cmd = listOf("/sbin/linuxdroid-init", "CLI", "/bin/bash", "/etc/linuxdroid/gui-install.sh")
                    val extraEnv = mapOf(
                        "DEBIAN_FRONTEND" to "noninteractive",
                        "LINUXDROID_START_MODE" to "CLI",
                        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    )

                    val result = runtimeBackend.executeAndWait(
                        environment = environment.copy(rootfsPath = rootfsDir.absolutePath),
                        command = cmd,
                        workingDirectory = "/root",
                        extraEnv = extraEnv,
                        timeoutMs = 900_000, // 15 min
                    )

                    val lines = (result.stdout + "\n" + result.stderr).lines()
                    for (line in lines) {
                        if (line.isBlank()) continue
                        appendLogLine(line)
                        onLog(">>> $line")
                    }

                    val marker = File(rootfsDir, "etc/linuxdroid/GUI_INSTALL_COMPLETE")
                    if (result.exitCode != 0 || !marker.exists()) {
                        val errMsg = "GUI install script failed with exit code ${result.exitCode}: ${result.stderr.ifBlank { result.stdout }}"
                        log.error("[GUI_INSTALL][FAIL] $errMsg")
                        appendLogLine("[GUI_INSTALL][FAIL][FULL_INSTALL] $errMsg")
                        writeGuiState(environment, GuiState.FAILED)
                        return@withLock GuiState.FAILED
                    }
                } else {
                    // Offline / simulated fallback for JVM unit testing
                    executeSimulatedGuiInstall(
                        environment = environment,
                        rootfsDir = rootfsDir,
                        lddmDebOverride = lddmDebOverride,
                        lddeDebOverride = lddeDebOverride,
                        onProgress = onProgress,
                        onLog = onLog,
                    )
                }

                // 2. Validate installed graphical stack
                onProgress(0.95f, "Validating GUI installation…")
                val report = validate(environment)
                if (!report.isValid) {
                    val errMsg = "GUI validation failed with ${report.errors.size} errors: ${report.errors}"
                    log.error("[GUI_INSTALL][FAIL] $errMsg")
                    appendLogLine("[GUI_INSTALL][FAIL][VALIDATE] $errMsg")
                    writeGuiState(environment, GuiState.FAILED)
                    return@withLock GuiState.FAILED
                }

                val duration = System.currentTimeMillis() - startTime
                appendLogLine("[GUI_INSTALL][SUCCESS][FULL_INSTALL] duration_ms=$duration")
                onLog(">>> [GUI_INSTALL][SUCCESS][FULL_INSTALL] GUI installed successfully in ${duration}ms")
                onProgress(1.0f, "GUI installed successfully")

                writeGuiState(environment, GuiState.INSTALLED)
                GuiState.INSTALLED
            } catch (e: Exception) {
                log.error("[GUI_INSTALL][FAIL] Exception during GUI install: ${e.message}", e)
                appendLogLine("[GUI_INSTALL][FAIL][EXCEPTION] ${e.message}")
                writeGuiState(environment, GuiState.FAILED)
                GuiState.FAILED
            }
        }
    }

    /**
     * Repairs only broken or missing components of the GUI stack without reinstalling everything.
     */
    suspend fun repair(
        environment: Environment,
        lddmDebOverride: File? = null,
        lddeDebOverride: File? = null,
        onProgress: suspend (Float, String) -> Unit = { _, _ -> },
        onLog: suspend (String) -> Unit = { _ -> },
    ): GuiState = withContext(Dispatchers.IO) {
        val environmentId = environment.id
        val mutex = guiLocks.computeIfAbsent(environmentId) { Mutex() }

        mutex.withLock {
            val rootfsDir = storage.rootfsDir(environmentId)
            log.info("[GUI_REPAIR] Starting GUI repair for $environmentId")
            onLog(">>> [GUI_REPAIR][START]")

            writeGuiState(environment, GuiState.REPAIRING)
            onProgress(0.1f, "Diagnosing graphical stack…")

            val report = validate(environment)
            val missingChecks = report.checks.filter { !it.passed }.map { it.name }
            log.info("[GUI_REPAIR] Broken/missing checks: $missingChecks")
            onLog(">>> [GUI_REPAIR] Found issues: $missingChecks")

            val needsWaylandWeston = missingChecks.any { it.contains("wayland") || it.contains("weston") }
            val needsLddm = missingChecks.any { it.contains("lddm") }
            val needsLdde = missingChecks.any { it.contains("ldde") }

            try {
                if (needsWaylandWeston) {
                    onProgress(0.3f, "Repairing Wayland and Weston packages…")
                    onLog(">>> [GUI_REPAIR] Reinstalling Wayland/Weston dependencies…")
                    graphicalInstaller.ensureGraphicalDependencies(environment, rootfsDir, onProgress, onLog)
                    graphicalInstaller.ensureWestonConfig(rootfsDir)
                }

                if (needsLddm) {
                    onProgress(0.6f, "Repairing LDDM display manager…")
                    onLog(">>> [GUI_REPAIR] Reinstalling LDDM package…")
                    packageInstaller.installLDDM(environment, rootfsDir, lddmDebOverride, onProgress, onLog)
                }

                if (needsLdde) {
                    onProgress(0.8f, "Repairing LDDE desktop environment…")
                    onLog(">>> [GUI_REPAIR] Reinstalling LDDE package…")
                    packageInstaller.installLDDE(environment, rootfsDir, lddeDebOverride, onProgress, onLog)
                }

                // Ensure default configuration files are present
                ensureDefaultConfigs(rootfsDir, environment.configuration.linuxUser)

                // Write completion marker
                GuiInstallScript.writeGuiInstallCompleteMarker(rootfsDir)

                // Re-validate
                onProgress(0.95f, "Re-validating graphical stack…")
                val finalReport = validate(environment)
                if (finalReport.isValid) {
                    log.info("[GUI_REPAIR][SUCCESS] GUI repaired successfully")
                    onLog(">>> [GUI_REPAIR][SUCCESS] All components repaired and verified.")
                    writeGuiState(environment, GuiState.INSTALLED)
                    GuiState.INSTALLED
                } else {
                    log.warn("[GUI_REPAIR][FAIL] Repair could not resolve all issues: ${finalReport.errors}")
                    onLog(">>> [GUI_REPAIR][FAIL] Issues remain: ${finalReport.errors}")
                    writeGuiState(environment, GuiState.FAILED)
                    GuiState.FAILED
                }
            } catch (e: Exception) {
                log.error("[GUI_REPAIR][FAIL] Exception during GUI repair: ${e.message}", e)
                onLog(">>> [GUI_REPAIR][FAIL] Error: ${e.message}")
                writeGuiState(environment, GuiState.FAILED)
                GuiState.FAILED
            }
        }
    }

    private suspend fun executeSimulatedGuiInstall(
        environment: Environment,
        rootfsDir: File,
        lddmDebOverride: File?,
        lddeDebOverride: File?,
        onProgress: suspend (Float, String) -> Unit,
        onLog: suspend (String) -> Unit,
    ) {
        onProgress(0.40f, "Installing Wayland and Weston…")
        graphicalInstaller.ensureGraphicalDependencies(environment, rootfsDir, onProgress, onLog)
        graphicalInstaller.ensureWestonConfig(rootfsDir)

        onProgress(0.60f, "Installing LDDM display manager…")
        packageInstaller.installLDDM(environment, rootfsDir, lddmDebOverride, onProgress, onLog)

        onProgress(0.80f, "Installing LDDE desktop environment…")
        packageInstaller.installLDDE(environment, rootfsDir, lddeDebOverride, onProgress, onLog)

        ensureDefaultConfigs(rootfsDir, environment.configuration.linuxUser)
        GuiInstallScript.writeGuiInstallCompleteMarker(rootfsDir)
    }

    private fun ensureDefaultConfigs(rootfsDir: File, username: String) {
        val lddmConf = File(rootfsDir, "etc/linuxdroid/lddm.conf")
        if (!lddmConf.exists()) {
            lddmConf.parentFile?.mkdirs()
            lddmConf.writeText("[lddm]\nweston_socket=wayland-0\nsession_user=$username\nautostart=true\n")
        }
        val desktopConf = File(rootfsDir, "etc/linuxdroid/desktop.conf")
        if (!desktopConf.exists()) {
            desktopConf.parentFile?.mkdirs()
            desktopConf.writeText("[desktop]\nshell=default\ntheme=default\n")
        }
    }
}
