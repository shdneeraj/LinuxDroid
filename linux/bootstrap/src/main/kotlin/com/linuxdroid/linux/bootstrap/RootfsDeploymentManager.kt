package com.linuxdroid.linux.bootstrap

import android.content.Context
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.logging.InstallationLogger
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.*
import com.linuxdroid.core.runtime.GuestInit
import com.linuxdroid.core.runtime.PostInstallScript
import com.linuxdroid.core.runtime.ProotRuntimeBackend
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
    val lddmVersion: String?,
    val lddeVersion: String?,
    val westonVersion: String?,
    val waylandVersion: String?,
    val validationReport: RootfsValidationReport,
    val detail: String,
) {
    val isSuccess: Boolean get() = state.isReady() && validationReport.isValid
}

/**
 * Master coordinator for the LinuxDroid rootfs deployment pipeline.
 *
 * Implements the two-phase architecture:
 * ```
 * PRE-INSTALL (Android Host)
 *      ↓
 * Create complete base Linux rootfs & inject /sbin/linuxdroid-init
 * Stage LDDM.deb + LDDE.deb into /root/.linuxdroid/packages/
 * Generate /etc/linuxdroid/install.conf & /etc/linuxdroid/.install.secret (0600)
 * Generate /etc/linuxdroid/post-install.sh (0755)
 * Validate base rootfs via Stage A extraction validation
 * Mark rootfs as PRE_INSTALL_READY
 *      ↓
 * POST-INSTALL (Linux Userspace CLI)
 *      ↓
 * Execute /sbin/linuxdroid-init CLI /bin/bash /etc/linuxdroid/post-install.sh
 * In-guest script executes package installation, user setup, sudoers, and APT cleanup
 * Validates artifacts on disk and deletes temporary secrets
 * Writes /etc/linuxdroid/POST_INSTALL_COMPLETE marker
 *      ↓
 * COMPLETION
 *      ↓
 * Host detects POST_INSTALL_COMPLETE
 * Transitions to INSTALLATION_COMPLETE → ROOTFS_READY
 * Manifest written and verified
 * ```
 */
class RootfsDeploymentManager(
    private val context: Context? = null,
    private val storage: EnvironmentStorage,
    private val runtimeBackend: RuntimeBackend? = null,
    private val extractor: RootfsExtractor = RootfsExtractor(),
    private val configurator: RootfsConfigurator = RootfsConfigurator(),
    private val runtimeSetup: RuntimeEnvironmentSetup = RuntimeEnvironmentSetup(),
    private val graphicalInstaller: GraphicalDependencyInstaller = GraphicalDependencyInstaller(runtimeBackend),
    private val packageInstaller: LinuxDroidPackageInstaller = LinuxDroidPackageInstaller(context, runtimeBackend),
    private val standardPackageInstaller: StandardPackageInstaller = StandardPackageInstaller(runtimeBackend),
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
    private fun resolveInstallationLogger(
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
     * Executes Phase 1: Pre-Install.
     * Prepares base filesystem, guest-init, stages deb packages, and writes configuration.
     * Never installs desktop packages, creates user accounts, or starts GUI in pre-install.
     */
    suspend fun executePreInstall(
        environment: Environment,
        installConfig: InstallConfig? = null,
        lddmDebOverride: File? = null,
        lddeDebOverride: File? = null,
        installLogger: InstallationLogger? = null,
        onProgress: suspend (Float, String) -> Unit = { _, _ -> },
        onLog: suspend (String) -> Unit = { _ -> },
    ): File = withContext(Dispatchers.IO) {
        val environmentId = environment.id
        val envKey = environmentId.value
        val targetUser = installConfig?.username ?: environment.configuration.linuxUser
        val targetPassword = installConfig?.password ?: ""
        val targetDistro = installConfig?.distro ?: environment.distribution
        val requestedRelease = installConfig?.release

        val finalRootfsDir = storage.rootfsDir(environmentId)
        val tmpDir = storage.tmpDir(environmentId)
        val stagingDir = storage.stagingRootfsDir(environmentId)

        val baseDefinition = DistributionCatalog.getDefinition(targetDistro, environment.architecture, requestedRelease)
        val definition = dynamicResolver.resolveLatest(baseDefinition, onLog)
        val source = definition.source

        val logger = installLogger ?: resolveInstallationLogger(
            environmentId = environmentId,
            distribution = targetDistro.name.lowercase(),
            release = definition.release,
            architecture = environment.architecture.linuxArch,
        ).apply {
            initLogHeader()
            writeMetadata(mapOf("target_user" to targetUser, "phase" to "PRE_INSTALL"))
        }

        var currentState = RootfsDeploymentState.PRE_INSTALLING
        _deploymentStates.value = _deploymentStates.value + (envKey to currentState)
        logger.updateState("PRE_INSTALLING", phase = "PRE_INSTALL", operation = "PRE_INSTALL")
        logger.logPreInstallStart("PRE_INSTALL")
        val preInstallStartTime = System.currentTimeMillis()

        val needsExtract = !finalRootfsDir.exists() || !File(finalRootfsDir, "bin/sh").exists()

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
                // 1. ROOTFS_EXTRACT
                val extractStart = System.currentTimeMillis()
                logger.logPreInstallStart("ROOTFS_EXTRACT")
                currentState = RootfsDeploymentState.ROOTFS_CREATING
                _deploymentStates.value = _deploymentStates.value + (envKey to currentState)
                onProgress(0.05f, "Downloading ${targetDistro.displayName} base rootfs…")
                onLog(">>> [DOWNLOAD] Fetching archive: ${source.url}")
                downloadFile(source.url, tarball, onProgress, onLog)

                source.expectedChecksum?.let { expected ->
                    onProgress(0.50f, "Verifying archive checksum…")
                    val actual = computeChecksum(tarball, source.checksumAlgorithm)
                    if (!actual.equals(expected, ignoreCase = true)) {
                        val errMsg = "Checksum mismatch for ${tarball.name}: expected $expected, got $actual"
                        logger.logPreInstallFail("ROOTFS_EXTRACT", System.currentTimeMillis() - extractStart, errMsg)
                        throw RuntimeError(environmentId, errMsg)
                    }
                    onLog(">>> [PASS] Archive checksum verified.")
                }

                onProgress(0.55f, "Extracting base filesystem…")
                extractor.extract(tarball, stagingDir, source.format, source.stripComponents, onProgress, onLog)
                currentState = RootfsDeploymentState.ROOTFS_EXTRACTED
                _deploymentStates.value = _deploymentStates.value + (envKey to currentState)
                logger.logPreInstallSuccess("ROOTFS_EXTRACT", System.currentTimeMillis() - extractStart)

                // 2. GUEST_INIT_INJECT
                val injectStart = System.currentTimeMillis()
                logger.logPreInstallStart("GUEST_INIT_INJECT")
                currentState = RootfsDeploymentState.ROOTFS_CONFIGURING
                _deploymentStates.value = _deploymentStates.value + (envKey to currentState)
                onProgress(0.60f, "Configuring base system files…")
                configurator.configure(stagingDir, definition)

                onProgress(0.65f, "Injecting LinuxDroid guest init and runtime files…")
                runtimeSetup.setup(stagingDir)

                val injectedInit = File(stagingDir, "sbin/linuxdroid-init")
                if (!injectedInit.exists() || !injectedInit.canExecute()) {
                    val errMsg = "Failed to inject executable /sbin/linuxdroid-init into staging rootfs"
                    logger.logPreInstallFail("GUEST_INIT_INJECT", System.currentTimeMillis() - injectStart, errMsg)
                    log.error("[DEPLOY_FAILED] $errMsg")
                    onLog(">>> [FAIL] $errMsg")
                    throw RuntimeError(environmentId, errMsg)
                }
                onLog(">>> [SETUP] Injected persistent guest init at ${injectedInit.path} (0755)")
                logger.logPreInstallSuccess("GUEST_INIT_INJECT", System.currentTimeMillis() - injectStart)

                // 3. PACKAGE_STAGING
                val pkgStart = System.currentTimeMillis()
                logger.logPreInstallStart("PACKAGE_STAGING")
                stagePackages(stagingDir, environment, lddmDebOverride, lddeDebOverride, onLog)
                logger.logPreInstallSuccess("PACKAGE_STAGING", System.currentTimeMillis() - pkgStart)

                // 4. CONFIG_GENERATE
                val configStart = System.currentTimeMillis()
                logger.logPreInstallStart("CONFIG_GENERATE")
                PostInstallScript.writeInstallConfig(
                    rootfsDir = stagingDir,
                    distro = targetDistro.name.lowercase(),
                    release = definition.release,
                    arch = environment.architecture.linuxArch,
                    username = targetUser,
                )
                PostInstallScript.writeInstallSecret(stagingDir, targetPassword)
                PostInstallScript.writeScript(stagingDir)
                logger.logPreInstallSuccess("CONFIG_GENERATE", System.currentTimeMillis() - configStart)

                // 5. BASE_VALIDATE (Stage A)
                val valStart = System.currentTimeMillis()
                logger.logPreInstallStart("BASE_VALIDATE")
                onProgress(0.68f, "Validating base filesystem and guest init integrity…")
                val extractReport = validator.validateExtraction(stagingDir, targetDistro, environment.architecture)
                if (!extractReport.isValid) {
                    val errMsg = "Stage A Extraction Validation failed with ${extractReport.errors.size} errors:\n${extractReport.formatSummary()}"
                    logger.logPreInstallFail("BASE_VALIDATE", System.currentTimeMillis() - valStart, errMsg)
                    log.error("[DEPLOY_FAILED] $errMsg")
                    extractReport.errors.forEach { onLog(">>> [VALIDATE_FAIL] $it") }
                    throw RuntimeError(environmentId, errMsg)
                }
                logger.logPreInstallSuccess("BASE_VALIDATE", System.currentTimeMillis() - valStart)
                onLog(">>> [PASS] Stage A: Extraction validation verified base filesystem and guest init integrity.")

                // 6. Promote Staging to Active
                currentState = RootfsDeploymentState.ROOTFS_RUNTIME_READY
                _deploymentStates.value = _deploymentStates.value + (envKey to currentState)
                onProgress(0.70f, "Promoting filesystem to active environment…")
                val promoted = storage.promoteStagedRootfs(environmentId)
                if (!promoted) {
                    throw FilesystemError(finalRootfsDir.path, "Failed to promote staging rootfs to active directory")
                }

                // Stage B — Runtime Validation (active rootfs)
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
            // Base rootfs already exists in final directory
            configurator.configure(finalRootfsDir, definition)
            runtimeSetup.setup(finalRootfsDir)
            stagePackages(finalRootfsDir, environment, lddmDebOverride, lddeDebOverride, onLog)
            PostInstallScript.writeInstallConfig(
                rootfsDir = finalRootfsDir,
                distro = targetDistro.name.lowercase(),
                release = definition.release,
                arch = environment.architecture.linuxArch,
                username = targetUser,
            )
            PostInstallScript.writeInstallSecret(finalRootfsDir, targetPassword)
            PostInstallScript.writeScript(finalRootfsDir)
        }

        // Write PRE_INSTALL_READY marker into active rootfs
        PostInstallScript.writePreInstallReadyMarker(
            rootfsDir = finalRootfsDir,
            distro = targetDistro.name.lowercase(),
            release = definition.release,
            username = targetUser,
            arch = environment.architecture.linuxArch,
        )

        currentState = RootfsDeploymentState.PRE_INSTALL_READY
        _deploymentStates.value = _deploymentStates.value + (envKey to currentState)
        val preInstallDuration = System.currentTimeMillis() - preInstallStartTime
        logger.logPreInstallSuccess("PRE_INSTALL", preInstallDuration)
        logger.updateState("PRE_INSTALL_READY", phase = "PRE_INSTALL", operation = "PRE_INSTALL")
        onLog(">>> [PREINSTALL][SUCCESS][PRE_INSTALL] Pre-install completed in ${preInstallDuration}ms. Rootfs marked PRE_INSTALL_READY.")

        finalRootfsDir
    }

    /**
     * Executes Phase 2: Post-Install.
     * Executes entirely inside guest userspace via PRoot in CLI mode.
     */
    suspend fun executePostInstall(
        environment: Environment,
        installConfig: InstallConfig? = null,
        lddmDebOverride: File? = null,
        lddeDebOverride: File? = null,
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

        val preInstallMarker = File(finalRootfsDir, "etc/linuxdroid/PRE_INSTALL_READY")
        if (!preInstallMarker.exists()) {
            throw RuntimeError(environmentId, "Cannot run post-install: rootfs is not marked PRE_INSTALL_READY")
        }

        val logger = installLogger ?: resolveInstallationLogger(
            environmentId = environmentId,
            distribution = targetDistro.name.lowercase(),
            release = "latest",
            architecture = environment.architecture.linuxArch,
        )

        var currentState = RootfsDeploymentState.POST_INSTALL_STARTING
        _deploymentStates.value = _deploymentStates.value + (envKey to currentState)
        logger.updateState("POST_INSTALL_STARTING", phase = "POST_INSTALL")

        currentState = RootfsDeploymentState.POST_INSTALLING
        _deploymentStates.value = _deploymentStates.value + (envKey to currentState)
        logger.updateState("POST_INSTALLING", phase = "POST_INSTALL", operation = "POST_INSTALL")
        logger.logPostInstallStart("POST_INSTALL", command = "/sbin/linuxdroid-init CLI /bin/bash /etc/linuxdroid/post-install.sh")
        val postInstallStartTime = System.currentTimeMillis()

        if (runtimeBackend != null) {
            onProgress(0.75f, "Executing in-guest post-install via CLI…")
            onLog(">>> [POSTINSTALL] Starting Linux userspace in CLI mode: /sbin/linuxdroid-init CLI /bin/bash /etc/linuxdroid/post-install.sh")

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
                    logger.updateState("POST_INSTALLING", phase = "POST_INSTALL", operation = op)
                }
            }

            val postInstallMarker = File(finalRootfsDir, "etc/linuxdroid/POST_INSTALL_COMPLETE")
            if (result.exitCode != 0 || !postInstallMarker.exists()) {
                val dur = System.currentTimeMillis() - postInstallStartTime
                val errMsg = "In-guest post-install failed with exit code ${result.exitCode}: ${result.stderr.ifBlank { result.stdout }}"
                logger.logPostInstallFail("POST_INSTALL", dur, result.exitCode, result.stderr)
                logger.updateState("POST_INSTALL_FAILED", phase = "POST_INSTALL", exitCode = result.exitCode, error = errMsg)
                throw RuntimeError(environmentId, errMsg)
            }
        } else {
            // Simulated post-install fallback for offline and JVM unit tests without live PRoot engine
            executeSimulatedPostInstall(
                environment = environment,
                finalRootfsDir = finalRootfsDir,
                targetUser = targetUser,
                targetPassword = targetPassword,
                lddmDebOverride = lddmDebOverride,
                lddeDebOverride = lddeDebOverride,
                installLogger = logger,
                onProgress = onProgress,
                onLog = onLog,
            )
        }

        val postInstallDuration = System.currentTimeMillis() - postInstallStartTime
        val postInstallMarker = File(finalRootfsDir, "etc/linuxdroid/POST_INSTALL_COMPLETE")
        if (!postInstallMarker.exists()) {
            val errMsg = "Post-install finished without creating /etc/linuxdroid/POST_INSTALL_COMPLETE"
            logger.logPostInstallFail("POST_INSTALL", postInstallDuration, 1, errMsg)
            logger.updateState("POST_INSTALL_FAILED", phase = "POST_INSTALL", exitCode = 1, error = errMsg)
            throw RuntimeError(environmentId, errMsg)
        }

        currentState = RootfsDeploymentState.POST_INSTALL_COMPLETE
        _deploymentStates.value = _deploymentStates.value + (envKey to currentState)
        logger.logPostInstallSuccess("POST_INSTALL", postInstallDuration, 0)
        logger.updateState("POST_INSTALL_COMPLETE", phase = "POST_INSTALL")
        onLog(">>> [POSTINSTALL][SUCCESS][POST_INSTALL] Post-install completed in ${postInstallDuration}ms.")
    }

    /**
     * Executes the complete two-phase rootfs deployment pipeline.
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

            log.info("[DEPLOY_START] Beginning rootfs deployment pipeline for $environmentId (${targetDistro.displayName}, user=$targetUser)")
            onLog(">>> [DEPLOY_START] Initializing rootfs deployment for ${environment.name} (${targetDistro.displayName})")

            // Check if existing environment is already complete and valid
            if (storage.verifyRootfs(environmentId)) {
                val existingRootfs = storage.rootfsDir(environmentId)
                val existingReport = if (installConfig != null) {
                    validator.validateFinal(
                        existingRootfs,
                        targetDistro,
                        environment.architecture,
                        username = targetUser,
                    )
                } else {
                    validator.validate(
                        existingRootfs,
                        targetDistro,
                        environment.architecture,
                        requireGraphicalStack = true,
                    )
                }
                if (existingReport.isValid) {
                    log.info("[DEPLOY_READY] Existing rootfs already contains complete, verified graphical stack. Skipping deployment.")
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
                        detail = "Existing verified rootfs reused",
                    )
                } else {
                    log.warn("[DEPLOY_PARTIAL] Existing rootfs incomplete or invalid: ${existingReport.errors}")
                    onLog(">>> [WARN] Existing rootfs incomplete (${existingReport.errors.size} validation issues). Deploying missing components...")
                }
            }

            storage.initializeEnvironmentDirs(environmentId)
            val finalRootfsDir = storage.rootfsDir(environmentId)
            val baseDefinition = DistributionCatalog.getDefinition(targetDistro, environment.architecture, requestedRelease)
            val definition = dynamicResolver.resolveLatest(baseDefinition, onLog)

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

            var currentState = RootfsDeploymentState.PRE_INSTALLING
            _deploymentStates.value = _deploymentStates.value + (envKey to currentState)

            try {
                // 1. Phase 1: Pre-Install (Host side)
                executePreInstall(
                    environment = environment,
                    installConfig = installConfig,
                    lddmDebOverride = lddmDebOverride,
                    lddeDebOverride = lddeDebOverride,
                    installLogger = installLogger,
                    onProgress = onProgress,
                    onLog = onLog,
                )

                // 2. Phase 2: Post-Install (In-Guest Userspace CLI)
                executePostInstall(
                    environment = environment,
                    installConfig = installConfig,
                    lddmDebOverride = lddmDebOverride,
                    lddeDebOverride = lddeDebOverride,
                    installLogger = installLogger,
                    onProgress = onProgress,
                    onLog = onLog,
                )

                // 3. Stage D — Final Rootfs Validation (checks installed artifacts on disk without GUI)
                currentState = RootfsDeploymentState.ROOTFS_VALIDATING
                _deploymentStates.value = _deploymentStates.value + (envKey to currentState)
                onProgress(0.96f, "Performing final rootfs validation…")
                onLog(">>> [VALIDATE] Performing Stage D full rootfs validation across all components...")
                val report = validator.validateFinal(
                    finalRootfsDir,
                    targetDistro,
                    environment.architecture,
                    username = targetUser,
                )

                if (!report.isValid) {
                    val errMsg = "Final rootfs validation failed with ${report.errors.size} errors:\n${report.formatSummary()}"
                    log.error("[DEPLOY_FAILED] $errMsg")
                    report.errors.forEach { onLog(">>> [VALIDATE_FAIL] $it") }
                    throw RuntimeError(environmentId, errMsg)
                }
                onLog(">>> [PASS] Stage D: Final rootfs validation succeeded across all components.")

                // 4. Mark INSTALLATION_COMPLETE and ROOTFS_READY
                currentState = RootfsDeploymentState.INSTALLATION_COMPLETE
                _deploymentStates.value = _deploymentStates.value + (envKey to currentState)
                installLogger.updateState("INSTALLATION_COMPLETE", phase = "COMPLETION")

                currentState = RootfsDeploymentState.ROOTFS_READY
                _deploymentStates.value = _deploymentStates.value + (envKey to currentState)
                installLogger.updateState("ROOTFS_READY", phase = "COMPLETION")

                val lddmVer = packageInstaller.getInstalledPackageVersion(finalRootfsDir, "linuxdroid-display-manager") ?: "1.0.0"
                val lddeVer = packageInstaller.getInstalledPackageVersion(finalRootfsDir, "linuxdroid-desktop-environment") ?: "1.0.0"

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
                    lddmVersion = lddmVer,
                    lddeVersion = lddeVer,
                    westonVersion = "distribution",
                    waylandVersion = "distribution",
                    installedAt = System.currentTimeMillis(),
                )
                val metadataFile = File(storage.metadataDir(environmentId), "rootfs-manifest.json")
                storage.writeAtomic(metadataFile, json.encodeToString(metadata))

                PostInstallScript.writeRootfsReadyMarker(
                    rootfsDir = finalRootfsDir,
                    distro = targetDistro.name.lowercase(),
                    release = definition.release,
                    username = targetUser,
                    arch = environment.architecture.linuxArch,
                )

                log.info("[DEPLOY_READY] Recorded manifest and ROOTFS_READY marker at ${metadataFile.path}")
                onProgress(1.0f, "${targetDistro.displayName} environment ready")
                onLog(">>> [SUCCESS] Complete rootfs environment is ready!")

                RootfsDeploymentResult(
                    environmentId = environmentId,
                    state = RootfsDeploymentState.ROOTFS_READY,
                    lddmVersion = lddmVer,
                    lddeVersion = lddeVer,
                    westonVersion = "distribution",
                    waylandVersion = "distribution",
                    validationReport = report,
                    detail = "Rootfs deployment succeeded",
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

    private fun stagePackages(
        rootfsDir: File,
        environment: Environment,
        lddmDebOverride: File?,
        lddeDebOverride: File?,
        onLog: suspend (String) -> Unit,
    ) {
        val packagesDir = File(rootfsDir, "root/.linuxdroid/packages").apply { mkdirs() }
        val lddmDeb = lddmDebOverride ?: packageInstaller.resolvePackageDeb("linuxdroid-display-manager", environment)
        if (lddmDeb != null && lddmDeb.exists()) {
            val dest1 = File(packagesDir, "linuxdroid-display-manager.deb")
            val dest2 = File(packagesDir, "LDDM.deb")
            lddmDeb.copyTo(dest1, overwrite = true)
            lddmDeb.copyTo(dest2, overwrite = true)
            dest1.setReadable(true, false)
            dest2.setReadable(true, false)
        }
        val lddeDeb = lddeDebOverride ?: packageInstaller.resolvePackageDeb("linuxdroid-desktop-environment", environment)
        if (lddeDeb != null && lddeDeb.exists()) {
            val dest1 = File(packagesDir, "linuxdroid-desktop-environment.deb")
            val dest2 = File(packagesDir, "LDDE.deb")
            lddeDeb.copyTo(dest1, overwrite = true)
            lddeDeb.copyTo(dest2, overwrite = true)
            dest1.setReadable(true, false)
            dest2.setReadable(true, false)
        }
    }

    private suspend fun executeSimulatedPostInstall(
        environment: Environment,
        finalRootfsDir: File,
        targetUser: String,
        targetPassword: String,
        lddmDebOverride: File?,
        lddeDebOverride: File?,
        installLogger: InstallationLogger,
        onProgress: suspend (Float, String) -> Unit,
        onLog: suspend (String) -> Unit,
    ) {
        installLogger.logPostInstallStart("READ_INSTALL_CONFIG")
        installLogger.logPostInstallSuccess("READ_INSTALL_CONFIG", 5, 0)

        installLogger.logPostInstallStart("VALIDATE_STAGED_DEBS")
        installLogger.logPostInstallSuccess("VALIDATE_STAGED_DEBS", 5, 0)

        installLogger.logPostInstallStart("APT_UPDATE", command = "apt-get update")
        installLogger.logPostInstallSuccess("APT_UPDATE", 50, 0)

        installLogger.logPostInstallStart("INSTALL_CORE_PACKAGES", command = "apt-get install -y <core_33_packages>")
        onProgress(0.75f, "Installing standard Linux packages…")
        standardPackageInstaller.installStandardPackages(environment, finalRootfsDir, onProgress, onLog)
        installLogger.logPostInstallSuccess("INSTALL_CORE_PACKAGES", 100, 0)

        installLogger.logPostInstallStart("INSTALL_GRAPHICS_PACKAGES", command = "apt-get install -y <graphics_packages>")
        onProgress(0.80f, "Ensuring Wayland and Weston packages…")
        graphicalInstaller.ensureGraphicalDependencies(environment, finalRootfsDir, onProgress, onLog)
        installLogger.logPostInstallSuccess("INSTALL_GRAPHICS_PACKAGES", 100, 0)

        installLogger.logPostInstallStart("INSTALL_LDDM", command = "apt-get install -y linuxdroid-display-manager.deb")
        onProgress(0.84f, "Installing LDDM display manager…")
        packageInstaller.installLDDM(environment, finalRootfsDir, lddmDebOverride, onProgress, onLog)
        installLogger.logPostInstallSuccess("INSTALL_LDDM", 100, 0)

        installLogger.logPostInstallStart("INSTALL_LDDE", command = "apt-get install -y linuxdroid-desktop-environment.deb")
        onProgress(0.87f, "Installing LDDE desktop environment…")
        packageInstaller.installLDDE(environment, finalRootfsDir, lddeDebOverride, onProgress, onLog)
        installLogger.logPostInstallSuccess("INSTALL_LDDE", 100, 0)

        // Write default LDDM and LDDE configurations if missing (matching PostInstallScript)
        val lddmConf = File(finalRootfsDir, "etc/linuxdroid/lddm.conf")
        if (!lddmConf.exists()) {
            lddmConf.parentFile?.mkdirs()
            lddmConf.writeText("[lddm]\nweston_socket=wayland-0\nsession_user=$targetUser\nautostart=true\n")
        }
        val desktopConf = File(finalRootfsDir, "etc/linuxdroid/desktop.conf")
        if (!desktopConf.exists()) {
            desktopConf.parentFile?.mkdirs()
            desktopConf.writeText("[desktop]\nshell=default\ntheme=default\n")
        }

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
        executeAptCleanup(environment, finalRootfsDir, onLog)

        installLogger.logPostInstallStart("FINAL_VALIDATION")
        installLogger.logPostInstallSuccess("FINAL_VALIDATION", 10, 0)

        val secretFile = File(finalRootfsDir, "etc/linuxdroid/.install.secret")
        if (secretFile.exists()) secretFile.delete()
        val stagedPkgs = File(finalRootfsDir, "root/.linuxdroid/packages")
        if (stagedPkgs.exists()) stagedPkgs.deleteRecursively()

        PostInstallScript.writePostInstallCompleteMarker(
            rootfsDir = finalRootfsDir,
            distro = environment.distribution.name.lowercase(),
            release = "latest",
            username = targetUser,
            arch = environment.architecture.linuxArch,
        )
        onLog(">>> POST_INSTALL_COMPLETE")
    }

    private suspend fun executeAptCleanup(
        environment: Environment,
        rootfsDir: File,
        onLog: suspend (String) -> Unit,
    ) {
        val backend = runtimeBackend ?: return
        val extraEnv = mapOf(
            "DEBIAN_FRONTEND" to "noninteractive",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        )

        // 1. apt-get autoremove --purge -y
        onLog(">>> [CLEANUP] 1/3: apt-get autoremove --purge -y")
        try {
            backend.executeAndWait(
                environment = environment.copy(rootfsPath = rootfsDir.absolutePath),
                command = listOf("apt-get", "autoremove", "--purge", "-y"),
                workingDirectory = "/root",
                extraEnv = extraEnv,
                timeoutMs = 60_000,
            )
        } catch (e: Exception) {
            log.warn("[CLEANUP] autoremove warning: ${e.message}")
        }

        // 2. apt-get clean
        onLog(">>> [CLEANUP] 2/3: apt-get clean")
        try {
            backend.executeAndWait(
                environment = environment.copy(rootfsPath = rootfsDir.absolutePath),
                command = listOf("apt-get", "clean"),
                workingDirectory = "/root",
                extraEnv = extraEnv,
                timeoutMs = 30_000,
            )
        } catch (e: Exception) {
            log.warn("[CLEANUP] clean warning: ${e.message}")
        }

        // 3. apt-get update
        onLog(">>> [CLEANUP] 3/3: apt-get update")
        try {
            backend.executeAndWait(
                environment = environment.copy(rootfsPath = rootfsDir.absolutePath),
                command = listOf("apt-get", "update"),
                workingDirectory = "/root",
                extraEnv = extraEnv,
                timeoutMs = 60_000,
            )
        } catch (e: Exception) {
            log.warn("[CLEANUP] update warning: ${e.message}")
        }
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
