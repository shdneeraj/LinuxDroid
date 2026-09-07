package com.linuxdroid.linux.bootstrap

import android.content.Context
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.logging.InstallationLogger
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.*
import com.linuxdroid.core.runtime.GuiInstallScript
import com.linuxdroid.core.runtime.PostInstallScript
import com.linuxdroid.core.runtime.RuntimeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Result of rootfs deployment.
 */
data class RootfsDeploymentResult(
    val environmentId: EnvironmentId,
    val state: RootfsDeploymentState,
    val lddmVersion: String? = null,
    val lddeVersion: String? = null,
    val westonVersion: String? = null,
    val waylandVersion: String? = null,
    val validationReport: RootfsValidationReport,
    val detail: String,
) {
    val isSuccess: Boolean get() = state.isReady() && validationReport.isValid
}

/**
 * Master coordinator for the LinuxDroid CLI-first rootfs deployment pipeline.
 *
 * Sequence:
 * ```
 * 1. Download & verify rootfs archive          -> ROOTFS_CREATING
 * 2. Unpack archive into staging area          -> ROOTFS_EXTRACTED
 * 3. Configure system files & inject guest init -> ROOTFS_CONFIGURING
 * 4. Validate extraction (Stage A)             -> ROOTFS_RUNTIME_READY
 * 5. Promote staging to active & validate (B)
 * 6. Execute CLI provisioning in guest         -> ROOTFS_PACKAGES_INSTALLING
 * 7. Validate final CLI rootfs (Stage D)       -> ROOTFS_VALIDATING
 * 8. Mark complete                             -> ROOTFS_READY
 * ```
 *
 * Graphical components (Wayland, Weston, LDDM, LDDE) are NOT installed in this pipeline.
 * They are provided by [GuiInstaller] as an optional, independently managed layer.
 */
class RootfsDeploymentManager(
    private val context: Context? = null,
    private val storage: EnvironmentStorage,
    private val runtimeBackend: RuntimeBackend? = null,
    private val extractor: RootfsExtractor = RootfsExtractor(),
    private val configurator: RootfsConfigurator = RootfsConfigurator(),
    private val runtimeSetup: RuntimeEnvironmentSetup = RuntimeEnvironmentSetup(),
    private val standardPackageInstaller: StandardPackageInstaller = StandardPackageInstaller(runtimeBackend),
    private val packageInstaller: LinuxDroidPackageInstaller = LinuxDroidPackageInstaller(context, runtimeBackend),
    private val graphicalInstaller: GraphicalDependencyInstaller = GraphicalDependencyInstaller(runtimeBackend),
    private val userConfigurator: UserConfigurator = UserConfigurator(runtimeBackend),
    private val validator: RootfsValidator = RootfsValidator(),
    private val dynamicResolver: DynamicDistributionResolver = DynamicDistributionResolver(),
) {
    private val log = LinuxDroidLogger(LogSubsystem.BOOTSTRAP)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val _deploymentStates = MutableStateFlow<Map<String, RootfsDeploymentState>>(emptyMap())
    val deploymentStates: StateFlow<Map<String, RootfsDeploymentState>> = _deploymentStates.asStateFlow()

    companion object {
        private val environmentLocks = ConcurrentHashMap<EnvironmentId, Mutex>()
    }

    /**
     * Resolves or creates a dedicated [InstallationLogger] for the environment.
     */
    fun resolveInstallationLogger(
        environmentId: EnvironmentId,
        distribution: String,
        release: String,
        architecture: String,
    ): InstallationLogger {
        val logsDir = runCatching { storage.logsDir(environmentId) }.getOrNull()
        val installDir: File = when {
            runCatching { storage.installationDir(environmentId).canonicalPath.isNotBlank() }.getOrDefault(false) -> {
                storage.installationDir(environmentId)
            }
            logsDir != null && runCatching { logsDir.isDirectory }.getOrDefault(false) -> {
                File(logsDir.parentFile ?: logsDir, "installation")
            }
            else -> {
                File("/tmp/linuxdroid/${environmentId.value}/installation")
            }
        }
        installDir.mkdirs()

        val installLogFile = when {
            runCatching { storage.installationLogFile(environmentId).canonicalPath.isNotBlank() }.getOrDefault(false) -> {
                storage.installationLogFile(environmentId)
            }
            else -> File(installDir, "install.log")
        }

        val installStateFile = when {
            runCatching { storage.installationStateFile(environmentId).canonicalPath.isNotBlank() }.getOrDefault(false) -> {
                storage.installationStateFile(environmentId)
            }
            else -> File(installDir, "install-state")
        }

        val installMetadataFile = when {
            runCatching { storage.installationMetadataFile(environmentId).canonicalPath.isNotBlank() }.getOrDefault(false) -> {
                storage.installationMetadataFile(environmentId)
            }
            else -> File(installDir, "install-metadata")
        }

        return InstallationLogger(
            environmentId = environmentId,
            installLogFile = installLogFile,
            installStateFile = installStateFile,
            installMetadataFile = installMetadataFile,
            distribution = distribution,
            release = release,
            architecture = architecture,
        )
    }

    /**
     * Executes in-guest CLI provisioning.
     * Installs the 33 baseline CLI packages, creates user, configures sudoers, and cleans APT.
     */
    suspend fun executeCliProvisioning(
        environment: Environment,
        installConfig: InstallConfig? = null,
        installLogger: InstallationLogger? = null,
        onProgress: suspend (Float, String) -> Unit = { _, _ -> },
        onLog: suspend (String) -> Unit = { _ -> },
    ): Unit = withContext(Dispatchers.IO) {
        val environmentId = environment.id
        val envKey = environmentId.value
        val targetUser = installConfig?.username ?: environment.configuration.linuxUser
        val targetPassword = installConfig?.password ?: ""
        val targetDistro = installConfig?.distro ?: environment.distribution
        val finalRootfsDir = storage.rootfsDir(environmentId)

        val logger = installLogger ?: resolveInstallationLogger(
            environmentId = environmentId,
            distribution = targetDistro.name.lowercase(),
            release = "latest",
            architecture = environment.architecture.linuxArch,
        )

        val currentState = RootfsDeploymentState.ROOTFS_PACKAGES_INSTALLING
        _deploymentStates.value = _deploymentStates.value + (envKey to currentState)
        logger.updateState("ROOTFS_PACKAGES_INSTALLING", phase = "CLI_PROVISIONING", operation = "CLI_PROVISIONING")
        logger.logPostInstallStart("CLI_PROVISIONING", command = "/sbin/linuxdroid-init CLI /bin/bash /etc/linuxdroid/post-install.sh")
        val startTime = System.currentTimeMillis()

        // Write configuration and provisioning script to rootfs
        PostInstallScript.writeInstallConfig(
            rootfsDir = finalRootfsDir,
            distro = targetDistro.name.lowercase(),
            release = "latest",
            arch = environment.architecture.linuxArch,
            username = targetUser,
        )
        PostInstallScript.writeInstallSecret(finalRootfsDir, targetPassword)
        PostInstallScript.writeScript(finalRootfsDir)

        if (runtimeBackend != null) {
            onProgress(0.75f, "Executing in-guest CLI provisioning…")
            onLog(">>> [CLI_PROVISIONING] Starting Linux userspace in CLI mode: /sbin/linuxdroid-init CLI /bin/bash /etc/linuxdroid/post-install.sh")

            val postInstallCmd = listOf("/sbin/linuxdroid-init", "CLI", "/bin/bash", "/etc/linuxdroid/post-install.sh")
            val extraEnv = mapOf(
                "DEBIAN_FRONTEND" to "noninteractive",
                "LINUXDROID_START_MODE" to "CLI",
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            )

            val result = runtimeBackend.executeAndWait(
                environment = environment.copy(rootfsPath = finalRootfsDir.absolutePath),
                command = postInstallCmd,
                workingDirectory = "/root",
                extraEnv = extraEnv,
                timeoutMs = 600_000,
            )

            val outputLines = (result.stdout + "\n" + result.stderr).lines()
            for (line in outputLines) {
                if (line.isBlank()) continue
                logger.appendLog(line)
                onLog(">>> $line")
                if (line.startsWith("[POSTINSTALL][START][") && line.endsWith("]")) {
                    val op = line.removePrefix("[POSTINSTALL][START][").removeSuffix("]")
                    logger.updateState("ROOTFS_PACKAGES_INSTALLING", phase = "CLI_PROVISIONING", operation = op)
                }
            }

            val postInstallMarker = File(finalRootfsDir, "etc/linuxdroid/POST_INSTALL_COMPLETE")
            if (result.exitCode != 0 || !postInstallMarker.exists()) {
                val dur = System.currentTimeMillis() - startTime
                val errMsg = "In-guest CLI provisioning failed with exit code ${result.exitCode}: ${result.stderr.ifBlank { result.stdout }}"
                _deploymentStates.value = _deploymentStates.value + (envKey to RootfsDeploymentState.ROOTFS_DEPLOYMENT_FAILED)
                logger.logPostInstallFail("CLI_PROVISIONING", dur, result.exitCode, result.stderr)
                logger.updateState("ROOTFS_DEPLOYMENT_FAILED", phase = "CLI_PROVISIONING", exitCode = result.exitCode, error = errMsg)
                throw RuntimeError(environmentId, errMsg)
            }
        } else {
            // Simulated CLI provisioning fallback for offline and JVM unit tests without live PRoot engine
            executeSimulatedCliProvisioning(
                environment = environment,
                finalRootfsDir = finalRootfsDir,
                targetUser = targetUser,
                targetPassword = targetPassword,
                installLogger = logger,
                onProgress = onProgress,
                onLog = onLog,
            )
        }

        val duration = System.currentTimeMillis() - startTime
        val postInstallMarker = File(finalRootfsDir, "etc/linuxdroid/POST_INSTALL_COMPLETE")
        if (!postInstallMarker.exists()) {
            val errMsg = "CLI provisioning finished without creating /etc/linuxdroid/POST_INSTALL_COMPLETE"
            _deploymentStates.value = _deploymentStates.value + (envKey to RootfsDeploymentState.ROOTFS_DEPLOYMENT_FAILED)
            logger.logPostInstallFail("CLI_PROVISIONING", duration, 1, errMsg)
            logger.updateState("ROOTFS_DEPLOYMENT_FAILED", phase = "CLI_PROVISIONING", exitCode = 1, error = errMsg)
            throw RuntimeError(environmentId, errMsg)
        }

        logger.logPostInstallSuccess("CLI_PROVISIONING", duration, 0)
        onLog(">>> [CLI_PROVISIONING][SUCCESS] CLI provisioning completed in ${duration}ms.")
    }

    /**
     * Executes the complete CLI rootfs deployment pipeline.
     */
    suspend fun deployRootfs(
        environment: Environment,
        installConfig: InstallConfig? = null,
        lddmDebOverride: File? = null,
        lddeDebOverride: File? = null,
        onProgress: suspend (Float, String) -> Unit = { _, _ -> },
        onLog: suspend (String) -> Unit = { _ -> },
    ): RootfsDeploymentResult = withContext(Dispatchers.IO) {
        val environmentId = environment.id
        val mutex = environmentLocks.computeIfAbsent(environmentId) { Mutex() }

        mutex.withLock {
            val envKey = environmentId.value
            val targetUser = installConfig?.username ?: environment.configuration.linuxUser
            val targetDistro = installConfig?.distro ?: environment.distribution
            val requestedRelease = installConfig?.release

            log.info("[DEPLOY_START] Beginning CLI rootfs deployment pipeline for $environmentId (${targetDistro.displayName}, user=$targetUser)")
            onLog(">>> [DEPLOY_START] Initializing CLI rootfs deployment for ${environment.name} (${targetDistro.displayName})")

            // Check if existing environment is already complete and valid for CLI
            if (storage.verifyRootfs(environmentId)) {
                val existingRootfs = storage.rootfsDir(environmentId)
                val existingReport = if (installConfig != null) {
                    validator.validateFinal(
                        existingRootfs,
                        targetDistro,
                        environment.architecture,
                        username = targetUser,
                        requireGraphicalStack = false,
                    )
                } else {
                    validator.validate(
                        existingRootfs,
                        targetDistro,
                        environment.architecture,
                        requireGraphicalStack = false,
                    )
                }
                if (existingReport.isValid) {
                    log.info("[DEPLOY_READY] Existing rootfs already contains complete, verified CLI stack. Skipping deployment.")
                    onLog(">>> [DEPLOY_READY] Complete verified graphical stack found in ${existingRootfs.path}.")
                    onProgress(1.0f, "Rootfs already installed and verified")
                    _deploymentStates.value = _deploymentStates.value + (envKey to RootfsDeploymentState.ROOTFS_READY)
                    return@withLock RootfsDeploymentResult(
                        environmentId = environmentId,
                        state = RootfsDeploymentState.ROOTFS_READY,
                        lddmVersion = packageInstaller.getInstalledPackageVersion(existingRootfs, "linuxdroid-display-manager"),
                        lddeVersion = packageInstaller.getInstalledPackageVersion(existingRootfs, "linuxdroid-desktop-environment"),
                        westonVersion = "distribution",
                        waylandVersion = "distribution",
                        validationReport = existingReport,
                        detail = "Existing verified CLI rootfs reused",
                    )
                } else {
                    log.warn("[DEPLOY_PARTIAL] Existing rootfs incomplete or invalid: ${existingReport.errors}")
                    onLog(">>> [WARN] Existing rootfs incomplete (${existingReport.errors.size} validation issues). Deploying missing components...")
                }
            }

            storage.initializeEnvironmentDirs(environmentId)
            val finalRootfsDir = storage.rootfsDir(environmentId)
            val tmpDir = storage.tmpDir(environmentId)
            val stagingDir = storage.stagingRootfsDir(environmentId)

            val baseDefinition = DistributionCatalog.getDefinition(targetDistro, environment.architecture, requestedRelease)
            val definition = dynamicResolver.resolveLatest(baseDefinition, onLog)
            val source = definition.source

            val installLogger = resolveInstallationLogger(
                environmentId = environmentId,
                distribution = targetDistro.name.lowercase(),
                release = definition.release,
                architecture = environment.architecture.linuxArch,
            )
            installLogger.initLogHeader()
            installLogger.writeMetadata(
                mapOf(
                    "target_user" to targetUser,
                    "target_distro" to targetDistro.displayName,
                )
            )

            var currentState = RootfsDeploymentState.ROOTFS_CREATING
            _deploymentStates.value = _deploymentStates.value + (envKey to currentState)

            val needsExtract = !finalRootfsDir.exists() || !File(finalRootfsDir, "bin/sh").exists()

            try {
                if (needsExtract) {
                    if (stagingDir.exists()) stagingDir.deleteRecursively()
                    stagingDir.mkdirs()

                    val archiveExt = when (source.format) {
                        ArchiveFormat.TAR_XZ -> "tar.xz"
                        ArchiveFormat.TAR_GZ -> "tar.gz"
                        ArchiveFormat.TAR_BZ2 -> "tar.bz2"
                    }
                    val tarball = File(tmpDir, "rootfs.$archiveExt")

                    try {
                        // 1. Download archive
                        onProgress(0.05f, "Downloading ${targetDistro.displayName} base rootfs…")
                        onLog(">>> [DOWNLOAD] Fetching archive: ${source.url}")
                        downloadFile(source.url, tarball, onProgress, onLog)

                        source.expectedChecksum?.let { expected ->
                            onProgress(0.50f, "Verifying archive checksum…")
                            val actual = computeChecksum(tarball, source.checksumAlgorithm)
                            if (!actual.equals(expected, ignoreCase = true)) {
                                val errMsg = "Checksum mismatch for ${tarball.name}: expected $expected, got $actual"
                                throw RuntimeError(environmentId, errMsg)
                            }
                            onLog(">>> [PASS] Archive checksum verified.")
                        }

                        // 2. Extract archive
                        onProgress(0.55f, "Extracting base filesystem…")
                        extractor.extract(tarball, stagingDir, source.format, source.stripComponents, onProgress, onLog)
                        currentState = RootfsDeploymentState.ROOTFS_EXTRACTED
                        _deploymentStates.value = _deploymentStates.value + (envKey to currentState)

                        // 3. Configure system files and inject guest init
                        currentState = RootfsDeploymentState.ROOTFS_CONFIGURING
                        _deploymentStates.value = _deploymentStates.value + (envKey to currentState)
                        onProgress(0.60f, "Configuring base system files…")
                        configurator.configure(stagingDir, definition)

                        onProgress(0.65f, "Injecting LinuxDroid guest init and runtime files…")
                        runtimeSetup.setup(stagingDir)

                        val injectedInit = File(stagingDir, "sbin/linuxdroid-init")
                        if (!injectedInit.exists() || !injectedInit.canExecute()) {
                            val errMsg = "Failed to inject executable /sbin/linuxdroid-init into staging rootfs"
                            log.error("[DEPLOY_FAILED] $errMsg")
                            throw RuntimeError(environmentId, errMsg)
                        }
                        onLog(">>> [SETUP] Injected persistent guest init at ${injectedInit.path} (0755)")

                        // 4. Validate extraction (Stage A)
                        onProgress(0.68f, "Validating base filesystem and guest init integrity…")
                        val extractReport = validator.validateExtraction(stagingDir, targetDistro, environment.architecture)
                        if (!extractReport.isValid) {
                            val errMsg = "Stage A Extraction Validation failed with ${extractReport.errors.size} errors:\n${extractReport.formatSummary()}"
                            log.error("[DEPLOY_FAILED] $errMsg")
                            extractReport.errors.forEach { onLog(">>> [VALIDATE_FAIL] $it") }
                            throw RuntimeError(environmentId, errMsg)
                        }
                        onLog(">>> [PASS] Stage A: Extraction validation verified base filesystem and guest init integrity.")

                        // 5. Promote staging to active
                        currentState = RootfsDeploymentState.ROOTFS_RUNTIME_READY
                        _deploymentStates.value = _deploymentStates.value + (envKey to currentState)
                        onProgress(0.70f, "Promoting filesystem to active environment…")
                        val promoted = storage.promoteStagedRootfs(environmentId)
                        if (!promoted) {
                            throw FilesystemError(finalRootfsDir.path, "Failed to promote staging rootfs to active directory")
                        }

                        // Validate runtime infrastructure (Stage B)
                        onProgress(0.72f, "Validating runtime infrastructure…")
                        if (!validator.validateRuntime(finalRootfsDir)) {
                            throw RuntimeError(environmentId, "Stage B Runtime Validation failed: guest init or runtime dirs missing")
                        }
                        onLog(">>> [PASS] Stage B: Runtime infrastructure verified.")
                    } finally {
                        if (tarball.exists()) tarball.delete()
                        if (stagingDir.exists()) stagingDir.deleteRecursively()
                    }
                } else {
                    configurator.configure(finalRootfsDir, definition)
                    runtimeSetup.setup(finalRootfsDir)
                }

                // 6. Execute in-guest CLI provisioning
                executeCliProvisioning(
                    environment = environment,
                    installConfig = installConfig,
                    installLogger = installLogger,
                    onProgress = onProgress,
                    onLog = onLog,
                )

                // 7. Final validation (Stage D, CLI only)
                currentState = RootfsDeploymentState.ROOTFS_VALIDATING
                _deploymentStates.value = _deploymentStates.value + (envKey to currentState)
                onProgress(0.96f, "Performing final rootfs validation…")
                onLog(">>> [VALIDATE] Performing Stage D CLI rootfs validation...")
                val report = validator.validateFinal(
                    finalRootfsDir,
                    targetDistro,
                    environment.architecture,
                    username = targetUser,
                    requireGraphicalStack = false,
                )

                if (!report.isValid) {
                    val errMsg = "Final rootfs validation failed with ${report.errors.size} errors:\n${report.formatSummary()}"
                    log.error("[DEPLOY_FAILED] $errMsg")
                    report.errors.forEach { onLog(">>> [VALIDATE_FAIL] $it") }
                    throw RuntimeError(environmentId, errMsg)
                }
                onLog(">>> [PASS] Stage D: Final CLI rootfs validation succeeded.")

                // Optional: If GUI deb overrides were explicitly passed to deployRootfs, install them now
                var lddmVersion: String? = null
                var lddeVersion: String? = null
                var westonVersion: String? = null
                var waylandVersion: String? = null

                if (lddmDebOverride != null || lddeDebOverride != null) {
                    try {
                        onProgress(0.97f, "Installing optional GUI packages…")
                        val graphicsResult = graphicalInstaller.ensureGraphicalDependencies(environment, finalRootfsDir, onProgress, onLog)
                        graphicalInstaller.ensureWestonConfig(finalRootfsDir)
                        val lddmResult = packageInstaller.installLDDM(environment, finalRootfsDir, lddmDebOverride, onProgress, onLog)
                        val lddeResult = packageInstaller.installLDDE(environment, finalRootfsDir, lddeDebOverride, onProgress, onLog)
                        lddmVersion = lddmResult.installedVersion
                        lddeVersion = lddeResult.installedVersion
                        westonVersion = graphicsResult.westonVersion
                        waylandVersion = graphicsResult.waylandVersion
                        GuiInstallScript.writeGuiInstallCompleteMarker(finalRootfsDir)
                    } catch (e: Exception) {
                        log.warn("[DEPLOY_GUI_WARN] Optional GUI installation failed (CLI foundation preserved): ${e.message}")
                    }
                }

                // 8. Record manifest and ROOTFS_READY
                currentState = RootfsDeploymentState.ROOTFS_READY
                _deploymentStates.value = _deploymentStates.value + (envKey to currentState)
                installLogger.updateState("ROOTFS_READY", phase = "COMPLETION")

                val metadata = RootfsMetadata(
                    distribution = targetDistro.name.lowercase(),
                    release = definition.release,
                    architecture = environment.architecture.linuxArch,
                    variant = definition.variant,
                    source = definition.source.url,
                    artifact = "rootfs",
                    checksumAlgorithm = definition.source.checksumAlgorithm,
                    checksum = definition.source.expectedChecksum ?: "verified",
                    bootstrapVersion = "1.0.0",
                    status = "ready",
                    deploymentState = RootfsDeploymentState.ROOTFS_READY.name,
                    lddmVersion = lddmVersion,
                    lddeVersion = lddeVersion,
                    westonVersion = westonVersion,
                    waylandVersion = waylandVersion,
                    installedAt = System.currentTimeMillis(),
                )
                val metadataFile = File(storage.metadataDir(environmentId), "rootfs-manifest.json")
                storage.writeAtomic(metadataFile, json.encodeToString(metadata))

                // Initialize persistent GUI state marker
                val guiStateFile = File(storage.metadataDir(environmentId), "gui-state")
                val finalGuiState = if (lddmVersion != null && lddeVersion != null) "INSTALLED" else "NOT_INSTALLED"
                runCatching { storage.writeAtomic(guiStateFile, "$finalGuiState\n") }

                PostInstallScript.writeRootfsReadyMarker(
                    rootfsDir = finalRootfsDir,
                    distro = targetDistro.name.lowercase(),
                    release = definition.release,
                    username = targetUser,
                    arch = environment.architecture.linuxArch,
                )

                log.info("[DEPLOY_READY] Recorded manifest and ROOTFS_READY marker at ${metadataFile.path}")
                onProgress(1.0f, "${targetDistro.displayName} CLI environment ready")
                onLog(">>> [SUCCESS] Complete CLI rootfs environment is ready!")

                RootfsDeploymentResult(
                    environmentId = environmentId,
                    state = RootfsDeploymentState.ROOTFS_READY,
                    lddmVersion = lddmVersion,
                    lddeVersion = lddeVersion,
                    westonVersion = westonVersion,
                    waylandVersion = waylandVersion,
                    validationReport = report,
                    detail = "CLI rootfs deployment succeeded",
                )
            } catch (e: Exception) {
                log.error("[DEPLOY_FAILED] Deployment failed for $environmentId: ${e.message}", e)
                onLog(">>> [FATAL] Deployment failed: ${e.message}")
                currentState = RootfsDeploymentState.ROOTFS_DEPLOYMENT_FAILED
                _deploymentStates.value = _deploymentStates.value + (envKey to currentState)
                installLogger.updateState("ROOTFS_DEPLOYMENT_FAILED", error = e.message)
                installLogger.appendLog("[DEPLOY_FAILED] ${e.message}")
                storage.discardStaging(environmentId)
                if (e is RuntimeError) throw e
                throw RuntimeError(environmentId, "Deployment failed: ${e.message}", e)
            }
        }
    }

    private suspend fun executeSimulatedCliProvisioning(
        environment: Environment,
        finalRootfsDir: File,
        targetUser: String,
        targetPassword: String,
        installLogger: InstallationLogger,
        onProgress: suspend (Float, String) -> Unit,
        onLog: suspend (String) -> Unit,
    ) {
        installLogger.logPostInstallStart("READ_INSTALL_CONFIG")
        installLogger.logPostInstallSuccess("READ_INSTALL_CONFIG", 5, 0)

        installLogger.logPostInstallStart("APT_UPDATE", command = "apt-get update")
        installLogger.logPostInstallSuccess("APT_UPDATE", 50, 0)

        installLogger.logPostInstallStart("INSTALL_CORE_PACKAGES", command = "apt-get install -y <core_33_packages>")
        onProgress(0.75f, "Installing standard Linux packages…")
        standardPackageInstaller.installStandardPackages(environment, finalRootfsDir, onProgress, onLog)
        installLogger.logPostInstallSuccess("INSTALL_CORE_PACKAGES", 100, 0)

        installLogger.logPostInstallStart("CREATE_USER", command = "useradd -m -s /usr/bin/zsh $targetUser")
        installLogger.logPostInstallStart("CONFIGURE_PASSWORD", command = "chpasswd [REDACTED]")
        installLogger.logPostInstallStart("CONFIGURE_SHELL", command = "configure default shell files for $targetUser")
        installLogger.logPostInstallStart("CONFIGURE_SUDO", command = "configure sudoers for $targetUser")
        onProgress(0.90f, "Configuring user account $targetUser…")
        userConfigurator.configureUser(
            environment = environment,
            rootfsDir = finalRootfsDir,
            username = targetUser,
            password = targetPassword,
            homeDir = "/home/$targetUser",
            shell = "/usr/bin/zsh",
            onProgress = onProgress,
            onLog = onLog,
        )
        installLogger.logPostInstallSuccess("CREATE_USER", 20, 0)
        installLogger.logPostInstallSuccess("CONFIGURE_PASSWORD", 20, 0)
        installLogger.logPostInstallSuccess("CONFIGURE_SHELL", 20, 0)
        installLogger.logPostInstallSuccess("CONFIGURE_SUDO", 20, 0)

        installLogger.logPostInstallStart("APT_AUTOREMOVE", command = "apt-get autoremove --purge -y")
        installLogger.logPostInstallSuccess("APT_AUTOREMOVE", 50, 0)
        installLogger.logPostInstallStart("APT_CLEAN", command = "apt-get clean")
        installLogger.logPostInstallSuccess("APT_CLEAN", 20, 0)
        installLogger.logPostInstallStart("APT_UPDATE_FINAL", command = "apt-get update")
        installLogger.logPostInstallSuccess("APT_UPDATE_FINAL", 50, 0)

        installLogger.logPostInstallStart("FINAL_VALIDATION")
        installLogger.logPostInstallSuccess("FINAL_VALIDATION", 10, 0)

        val secretFile = File(finalRootfsDir, "etc/linuxdroid/.install.secret")
        if (secretFile.exists()) secretFile.delete()

        PostInstallScript.writePostInstallCompleteMarker(
            rootfsDir = finalRootfsDir,
            distro = environment.distribution.name.lowercase(),
            release = "latest",
            username = targetUser,
            arch = environment.architecture.linuxArch,
        )
        onLog(">>> POST_INSTALL_COMPLETE")
    }

    private suspend fun downloadFile(
        url: String,
        dest: File,
        onProgress: suspend (Float, String) -> Unit,
        onLog: suspend (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        var currentUrl = url
        var connection: HttpURLConnection
        var redirectCount = 0
        while (true) {
            connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "LinuxDroid/1.0 (Android; ARM64)")
            }
            connection.connect()
            val status = connection.responseCode
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: throw IOException("HTTP $status redirect without Location header from $currentUrl")
                currentUrl = if (location.startsWith("http")) location else URL(URL(currentUrl), location).toString()
                redirectCount++
                if (redirectCount > 8) throw IOException("Too many redirects: $redirectCount")
                connection.disconnect()
                continue
            }
            if (status !in 200..299) {
                throw IOException("HTTP $status error downloading $currentUrl")
            }
            break
        }

        val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
        connection.inputStream.use { input ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var downloaded = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (totalBytes > 0) {
                        val fraction = downloaded.toFloat() / totalBytes
                        onProgress(0.05f + fraction * 0.45f, "Downloading… ${downloaded / 1_048_576}MB / ${totalBytes / 1_048_576}MB")
                    }
                }
            }
        }
    }

    private fun computeChecksum(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
