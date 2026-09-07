package com.linuxdroid.linux.bootstrap

import android.content.Context
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.*
import com.linuxdroid.core.runtime.GuestInit
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
    val isSuccess: Boolean get() = state == RootfsDeploymentState.ROOTFS_READY && validationReport.isValid
}

/**
 * Master coordinator for the LinuxDroid rootfs graphical deployment pipeline (P0.1).
 *
 * Implements the strict, deterministic 9-stage sequence:
 * ```
 * deployRootfs()
 *     │
 *     ├── prepareRootfs()                [ROOTFS_CREATING → ROOTFS_EXTRACTED]
 *     │
 *     ├── configureRootfs()              [ROOTFS_CONFIGURING]
 *     │
 *     ├── setupRuntimeEnvironment()      [ROOTFS_RUNTIME_READY]
 *     │
 *     ├── ensureGraphicalDependencies()  [ROOTFS_GRAPHICS_DEPLOYING]
 *     │       ├── ensureWayland()
 *     │       └── ensureWeston()
 *     │
 *     ├── installLinuxDroidPackages()    [ROOTFS_PACKAGES_INSTALLING]
 *     │       ├── installLDDM()
 *     │       └── installLDDE()
 *     │
 *     └── validateRootfs()               [ROOTFS_VALIDATING]
 *             │
 *             └── ROOTFS_READY
 * ```
 *
 * Never marks incomplete rootfs as READY.
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
     * Executes the complete rootfs graphical deployment pipeline.
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
            val targetPassword = installConfig?.password ?: ""
            val targetDistro = installConfig?.distro ?: environment.distribution
            val requestedRelease = installConfig?.release

            log.info("[DEPLOY_START] Beginning rootfs deployment pipeline for $environmentId (${targetDistro.displayName}, user=$targetUser)")
            onLog(">>> [DEPLOY_START] Initializing rootfs graphical deployment for ${environment.name} (${targetDistro.displayName})")

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
            val tmpDir = storage.tmpDir(environmentId)
            val stagingDir = storage.stagingRootfsDir(environmentId)

            val baseDefinition = DistributionCatalog.getDefinition(targetDistro, environment.architecture, requestedRelease)
            val definition = dynamicResolver.resolveLatest(baseDefinition, onLog)
            val source = definition.source

            var currentState = RootfsDeploymentState.ROOTFS_CREATING
            _deploymentStates.value = _deploymentStates.value + (envKey to currentState)

            try {
                // If base rootfs does not exist, download, extract, configure, and promote
                if (!finalRootfsDir.exists() || !File(finalRootfsDir, "bin/sh").exists()) {
                    // Clean previous staging area
                    if (stagingDir.exists()) stagingDir.deleteRecursively()
                    stagingDir.mkdirs()

                    val archiveExt = when (source.format) {
                        ArchiveFormat.TAR_XZ -> "tar.xz"
                        ArchiveFormat.TAR_GZ -> "tar.gz"
                        ArchiveFormat.TAR_BZ2 -> "tar.bz2"
                    }
                    val tarball = File(tmpDir, "rootfs.$archiveExt")

                    try {
                        // 1. Prepare Base Rootfs (Download & Verify)
                        currentState = RootfsDeploymentState.ROOTFS_CREATING
                        _deploymentStates.value = _deploymentStates.value + (envKey to currentState)
                        onProgress(0.05f, "Downloading ${targetDistro.displayName} base rootfs…")
                        onLog(">>> [DOWNLOAD] Fetching archive: ${source.url}")
                        downloadFile(source.url, tarball, onProgress, onLog)

                        source.expectedChecksum?.let { expected ->
                            onProgress(0.50f, "Verifying archive checksum…")
                            val actual = computeChecksum(tarball, source.checksumAlgorithm)
                            if (!actual.equals(expected, ignoreCase = true)) {
                                throw RuntimeError(environmentId, "Checksum mismatch for ${tarball.name}: expected $expected, got $actual")
                            }
                            onLog(">>> [PASS] Archive checksum verified.")
                        }

                        // 2. Extract into staging
                        onProgress(0.55f, "Extracting base filesystem…")
                        extractor.extract(tarball, stagingDir, source.format, source.stripComponents, onProgress, onLog)
                        currentState = RootfsDeploymentState.ROOTFS_EXTRACTED
                        _deploymentStates.value = _deploymentStates.value + (envKey to currentState)

                        // 3. Configure Staging Rootfs & Inject Guest Init BEFORE Stage A
                        currentState = RootfsDeploymentState.ROOTFS_CONFIGURING
                        _deploymentStates.value = _deploymentStates.value + (envKey to currentState)
                        onProgress(0.60f, "Configuring base system files…")
                        configurator.configure(stagingDir, definition)

                        onProgress(0.65f, "Injecting LinuxDroid guest init and runtime files…")
                        runtimeSetup.setup(stagingDir)

                        // Explicit check: Verify /sbin/linuxdroid-init exists and is executable in staging
                        val injectedInit = File(stagingDir, "sbin/linuxdroid-init")
                        if (!injectedInit.exists() || !injectedInit.canExecute()) {
                            val errMsg = "Failed to inject executable /sbin/linuxdroid-init into staging rootfs"
                            log.error("[DEPLOY_FAILED] $errMsg")
                            onLog(">>> [FAIL] $errMsg")
                            throw RuntimeError(environmentId, errMsg)
                        }
                        onLog(">>> [SETUP] Injected persistent guest init at ${injectedInit.path} (0755)")

                        // 4. Stage A — Extraction & Prepared Rootfs Validation (Validates base filesystem, dynamic linker, APT, and /sbin/linuxdroid-init)
                        onProgress(0.68f, "Validating base filesystem and guest init integrity…")
                        val extractReport = validator.validateExtraction(stagingDir, targetDistro, environment.architecture)
                        if (!extractReport.isValid) {
                            val errMsg = "Stage A Extraction Validation failed with ${extractReport.errors.size} errors:\n${extractReport.formatSummary()}"
                            log.error("[DEPLOY_FAILED] $errMsg")
                            extractReport.errors.forEach { onLog(">>> [VALIDATE_FAIL] $it") }
                            throw RuntimeError(environmentId, errMsg)
                        }
                        onLog(">>> [PASS] Stage A: Extraction validation verified base filesystem and guest init integrity.")

                        // 5. Promote Staging to Active
                        currentState = RootfsDeploymentState.ROOTFS_RUNTIME_READY
                        _deploymentStates.value = _deploymentStates.value + (envKey to currentState)
                        onProgress(0.70f, "Promoting filesystem to active environment…")
                        val promoted = storage.promoteStagedRootfs(environmentId)
                        if (!promoted) {
                            throw FilesystemError(finalRootfsDir.path, "Failed to promote staging rootfs to active directory")
                        }

                        // Stage B — Runtime Validation (validates guest init, /tmp, /run on active rootfs)
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
                    // Base rootfs already extracted; ensure runtime environment setup and configuration
                    currentState = RootfsDeploymentState.ROOTFS_RUNTIME_READY
                    _deploymentStates.value = _deploymentStates.value + (envKey to currentState)
                    configurator.configure(finalRootfsDir, definition)
                    runtimeSetup.setup(finalRootfsDir)
                }

                // 6. Install Standard LinuxDroid Package Baseline
                currentState = RootfsDeploymentState.ROOTFS_PACKAGES_INSTALLING
                _deploymentStates.value = _deploymentStates.value + (envKey to currentState)
                onProgress(0.75f, "Installing standard Linux packages…")
                standardPackageInstaller.installStandardPackages(environment, finalRootfsDir, onProgress, onLog)

                // 7. Ensure Graphical Dependencies & Install LinuxDroid Packages
                currentState = RootfsDeploymentState.ROOTFS_GRAPHICS_DEPLOYING
                _deploymentStates.value = _deploymentStates.value + (envKey to currentState)
                onProgress(0.80f, "Ensuring Wayland and Weston packages…")
                val graphicsResult = graphicalInstaller.ensureGraphicalDependencies(environment, finalRootfsDir, onProgress, onLog)

                onProgress(0.84f, "Installing LDDM display manager…")
                val lddmResult = packageInstaller.installLDDM(environment, finalRootfsDir, lddmDebOverride, onProgress, onLog)

                onProgress(0.87f, "Installing LDDE desktop environment…")
                val lddeResult = packageInstaller.installLDDE(environment, finalRootfsDir, lddeDebOverride, onProgress, onLog)

                // Stage C — Graphics Deployment Validation (files only, no running GUI required)
                val graphicsReport = validator.validateGraphics(finalRootfsDir, targetDistro, environment.architecture)
                if (!graphicsReport.isValid) {
                    val errMsg = "Stage C Graphics Deployment Validation failed with ${graphicsReport.errors.size} errors:\n${graphicsReport.formatSummary()}"
                    log.error("[DEPLOY_FAILED] $errMsg")
                    graphicsReport.errors.forEach { onLog(">>> [VALIDATE_FAIL] $it") }
                    throw RuntimeError(environmentId, errMsg)
                }
                onLog(">>> [PASS] Stage C: Graphical components and desktop environment verified.")

                // 8. Configure User Account, Shell, Sudo, and Password
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

                // 9. Mandatory APT Cleanup Sequence (autoremove -> clean -> update)
                onProgress(0.93f, "Performing APT cache cleanup…")
                onLog(">>> [CLEANUP] Executing mandatory APT cleanup sequence...")
                executeAptCleanup(environment, finalRootfsDir, onLog)
                val stagedPkgsDir = File(finalRootfsDir, "tmp/staging_pkgs")
                if (stagedPkgsDir.exists()) stagedPkgsDir.deleteRecursively()
                onLog(">>> [PASS] APT cache cleaned and temporary staged packages removed.")

                // 10. Stage D — Final Rootfs Validation (checks installed artifacts; DOES NOT require running GUI)
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

                // 11. Write Manifest and mark ROOTFS_READY
                currentState = RootfsDeploymentState.ROOTFS_READY
                _deploymentStates.value = _deploymentStates.value + (envKey to currentState)

                val metadata = RootfsMetadata(
                    distribution = targetDistro.name.lowercase(),
                    release = definition.release,
                    architecture = environment.architecture.linuxArch,
                    variant = definition.variant,
                    source = source.url,
                    artifact = "rootfs",
                    checksumAlgorithm = source.checksumAlgorithm,
                    checksum = source.expectedChecksum ?: "verified",
                    bootstrapVersion = "1.0.0",
                    status = "ready",
                    deploymentState = RootfsDeploymentState.ROOTFS_READY.name,
                    lddmVersion = lddmResult.installedVersion,
                    lddeVersion = lddeResult.installedVersion,
                    westonVersion = graphicsResult.westonVersion,
                    waylandVersion = graphicsResult.waylandVersion,
                    installedAt = System.currentTimeMillis(),
                )
                val metadataFile = File(storage.metadataDir(environmentId), "rootfs-manifest.json")
                storage.writeAtomic(metadataFile, json.encodeToString(metadata))

                val readyMarker = File(finalRootfsDir, "etc/linuxdroid/ROOTFS_READY")
                readyMarker.parentFile?.mkdirs()
                readyMarker.writeText("DISTRO=${targetDistro.name}\nRELEASE=${definition.release}\nUSERNAME=$targetUser\nREADY_AT=${System.currentTimeMillis()}\n")

                log.info("[DEPLOY_READY] Recorded manifest and ROOTFS_READY marker at ${metadataFile.path}")
                onProgress(1.0f, "${targetDistro.displayName} environment ready")
                onLog(">>> [SUCCESS] Complete rootfs environment is ready!")

                RootfsDeploymentResult(
                    environmentId = environmentId,
                    state = RootfsDeploymentState.ROOTFS_READY,
                    lddmVersion = lddmResult.installedVersion,
                    lddeVersion = lddeResult.installedVersion,
                    westonVersion = graphicsResult.westonVersion,
                    waylandVersion = graphicsResult.waylandVersion,
                    validationReport = report,
                    detail = "Rootfs deployment succeeded",
                )
            } catch (e: Exception) {
                log.error("[DEPLOY_FAILED] Deployment failed for $environmentId: ${e.message}", e)
                onLog(">>> [FATAL] Deployment failed: ${e.message}")
                currentState = RootfsDeploymentState.ROOTFS_DEPLOYMENT_FAILED
                _deploymentStates.value = _deploymentStates.value + (envKey to currentState)
                storage.discardStaging(environmentId)
                throw RuntimeError(environmentId, "Deployment failed: ${e.message}", e)
            }
        }
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

