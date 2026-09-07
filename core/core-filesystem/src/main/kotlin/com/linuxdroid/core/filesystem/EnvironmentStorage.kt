package com.linuxdroid.core.filesystem

import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogCategory
import com.linuxdroid.core.logging.LogFileManager
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.EnvironmentId
import com.linuxdroid.core.model.FilesystemError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages the on-disk layout for a Linux environment.
 *
 * Layout:
 * ```
 * <base>/
 *   <env-id>/
 *     rootfs/          <- Linux filesystem root (persistent)
 *     metadata/        <- Environment metadata files
 *     runtime-state/   <- Transient runtime state (may be recreated)
 *     tmp/             <- Temporary files (may be cleaned at startup)
 * ```
 *
 * IMPORTANT: rootfs/ is NEVER deleted by LinuxDroid.
 * It is the persistent source of truth.
 */
class EnvironmentStorage(
    /** Base directory for all environments (e.g. app's filesDir/environments). */
    private val baseDir: File,
) {
    init {
        LogFileManager.setBaseLogsDir(baseDir.parentFile ?: baseDir)
    }

    private val log = LinuxDroidLogger(LogSubsystem.FILESYSTEM)

    /** Returns the root directory for a specific environment. */
    fun environmentDir(id: EnvironmentId): File = File(baseDir, id.value)

    /** Returns the rootfs directory (persistent). */
    fun rootfsDir(id: EnvironmentId): File = File(environmentDir(id), "rootfs")

    /** Returns the metadata directory. */
    fun metadataDir(id: EnvironmentId): File = File(environmentDir(id), "metadata")

    /** Returns the runtime-state directory (transient). */
    fun runtimeStateDir(id: EnvironmentId): File = File(environmentDir(id), "runtime-state")

    /** Returns the tmp directory. */
    fun tmpDir(id: EnvironmentId): File = File(environmentDir(id), "tmp")

    /** Returns the logs directory. */
    fun logsDir(id: EnvironmentId): File = File(environmentDir(id), "logs").apply {
        LogFileManager.registerEnvironmentLogsDir(id, this)
    }

    /** Returns the shm directory for POSIX shared memory emulation (/dev/shm). */
    fun shmDir(id: EnvironmentId): File = File(environmentDir(id), "shm")

    /** Returns the console log file for runtime diagnostics. */
    fun consoleLogFile(id: EnvironmentId): File = File(logsDir(id), LogCategory.CONSOLE.filename)

    /** Returns the PRoot internal log file for runtime diagnostics. */
    fun prootLogFile(id: EnvironmentId): File = File(logsDir(id), LogCategory.PROOT.filename)

    /** Returns the starting session log file. */
    fun sessionLogFile(id: EnvironmentId): File = File(logsDir(id), LogCategory.SESSION.filename)

    /** Returns the preboot validation log file. */
    fun prebootLogFile(id: EnvironmentId): File = File(logsDir(id), LogCategory.PREBOOT.filename)

    /** Returns the guest init (/sbin/linuxdroid-init) execution log file. */
    fun guestInitLogFile(id: EnvironmentId): File = File(logsDir(id), LogCategory.GUEST_INIT.filename)

    /** Returns the system process lifecycle log file. */
    fun processLogFile(id: EnvironmentId): File = File(logsDir(id), LogCategory.SYSTEM_PROCESS.filename)

    /** Returns the interactive terminal shell session log file. */
    fun terminalLogFile(id: EnvironmentId): File = File(logsDir(id), LogCategory.TERMINAL.filename)

    /** Returns the diagnostics summary log file. */
    fun diagnosticsLogFile(id: EnvironmentId): File = File(logsDir(id), LogCategory.DIAGNOSTICS.filename)

    /** Returns the dedicated rootfs installation directory. */
    fun installationDir(id: EnvironmentId): File = File(environmentDir(id), "installation")

    /** Returns the dedicated rootfs installation log file. */
    fun installationLogFile(id: EnvironmentId): File = File(installationDir(id), "install.log")

    /** Returns the persistent installation state file. */
    fun installationStateFile(id: EnvironmentId): File = File(installationDir(id), "install-state")

    /** Returns the persistent installation metadata file. */
    fun installationMetadataFile(id: EnvironmentId): File = File(installationDir(id), "install-metadata")

    /** Returns all available categorized log files for the environment. */
    fun allLogFiles(id: EnvironmentId): List<File> = listOf(
        sessionLogFile(id),
        prebootLogFile(id),
        guestInitLogFile(id),
        processLogFile(id),
        terminalLogFile(id),
        prootLogFile(id),
        consoleLogFile(id),
        diagnosticsLogFile(id),
        installationLogFile(id),
    )

    /**
     * Creates the directory structure for a new environment.
     * Does NOT create or touch rootfs/.
     */
    suspend fun initializeEnvironmentDirs(id: EnvironmentId) = withContext(Dispatchers.IO) {
        log.info("Initializing environment directories for $id")
        listOf(metadataDir(id), runtimeStateDir(id), tmpDir(id), logsDir(id), shmDir(id), installationDir(id)).forEach { dir ->
            if (!dir.exists() && !dir.mkdirs()) {
                throw FilesystemError(dir.path, "Failed to create directory")
            }
        }
        LogFileManager.registerEnvironmentLogsDir(id, logsDir(id))
    }

    /**
     * Atomically writes string content to a file via a temporary file and atomic rename.
     * Prevents metadata corruption in case of unexpected termination or crashes.
     */
    suspend fun writeAtomic(targetFile: File, content: String) = withContext(Dispatchers.IO) {
        val parent = targetFile.parentFile ?: throw FilesystemError(targetFile.path, "Target file has no parent directory")
        if (!parent.exists() && !parent.mkdirs()) {
            throw FilesystemError(parent.path, "Failed to create directory for atomic write")
        }
        val tempFile = File(parent, "${targetFile.name}.tmp.${System.nanoTime()}")
        try {
            tempFile.writeText(content, Charsets.UTF_8)
            try {
                java.nio.file.Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: Exception) {
                // Fallback if ATOMIC_MOVE is not supported across filesystem boundaries
                if (!tempFile.renameTo(targetFile)) {
                    targetFile.delete()
                    if (!tempFile.renameTo(targetFile)) {
                        throw FilesystemError(targetFile.path, "Failed to rename temp file atomically: ${e.message}")
                    }
                }
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    /**
     * Verifies that the rootfs directory exists and contains a minimal Linux structure.
     * Checks core directories and ensures any existing metadata manifest is non-empty.
     */
    suspend fun verifyRootfs(id: EnvironmentId): Boolean = withContext(Dispatchers.IO) {
        val rootfs = rootfsDir(id)
        if (!rootfs.isDirectory) {
            log.warn("Rootfs directory does not exist: ${rootfs.path}")
            return@withContext false
        }
        val markers = listOf("bin", "etc", "usr")
        val missing = markers.filter { !File(rootfs, it).exists() }
        if (missing.isNotEmpty()) {
            log.warn("Rootfs missing expected directories: $missing")
            return@withContext false
        }
        val manifestFile = File(metadataDir(id), "rootfs-manifest.json")
        if (manifestFile.exists() && manifestFile.length() == 0L) {
            log.warn("Rootfs manifest file is empty (corrupted): ${manifestFile.path}")
            return@withContext false
        }
        true
    }

    /**
     * Returns the total size of the rootfs in bytes.
     */
    suspend fun rootfsSize(id: EnvironmentId): Long = withContext(Dispatchers.IO) {
        rootfsDir(id).walkTopDown().sumOf { it.length() }
    }

    /**
     * Cleans transient runtime state directory.
     * NEVER touches rootfs/.
     */
    suspend fun cleanRuntimeState(id: EnvironmentId) = withContext(Dispatchers.IO) {
        log.debug("Cleaning runtime state for $id")
        val stateDir = runtimeStateDir(id)
        stateDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /** Returns the staging rootfs directory. */
    fun stagingRootfsDir(id: EnvironmentId): File = File(tmpDir(id), "rootfs-staging")

    /** Returns the backup rootfs directory used during rootfs replacement/promotion. */
    fun backupRootfsDir(id: EnvironmentId): File = File(tmpDir(id), "rootfs-backup")

    /**
     * Atomically promotes the staging rootfs to the active rootfs directory.
     * Backs up the old rootfs and restores it if promotion fails.
     */
    suspend fun promoteStagedRootfs(id: EnvironmentId): Boolean = withContext(Dispatchers.IO) {
        val staging = stagingRootfsDir(id)
        if (!staging.exists() || !staging.isDirectory) {
            log.warn("Staging rootfs does not exist for promotion: ${staging.path}")
            return@withContext false
        }

        val target = rootfsDir(id)
        val backup = backupRootfsDir(id)

        // 1. If an existing rootfs exists, move it to backup
        if (target.exists()) {
            if (backup.exists()) backup.deleteRecursively()
            if (!target.renameTo(backup)) {
                log.error("Failed to move active rootfs to backup directory before promotion")
                return@withContext false
            }
        }

        // 2. Promote staging to active rootfs
        if (staging.renameTo(target)) {
            log.info("Successfully promoted staging rootfs to active rootfs for $id")
            if (backup.exists()) backup.deleteRecursively()
            true
        } else {
            log.error("Failed to rename staging to active rootfs. Restoring backup...")
            if (backup.exists()) {
                backup.renameTo(target)
            }
            false
        }
    }

    /**
     * Attempts to recover an interrupted promotion.
     * If rootfs is missing or invalid but backup exists, restores backup to rootfs.
     * If rootfs is valid, cleans up backup.
     */
    suspend fun recoverInterruptedPromotion(id: EnvironmentId): Boolean = withContext(Dispatchers.IO) {
        val target = rootfsDir(id)
        val backup = backupRootfsDir(id)
        val staging = stagingRootfsDir(id)

        // If target rootfs is missing or corrupt, but backup exists, restore backup!
        if ((!target.exists() || !verifyRootfs(id)) && backup.exists()) {
            log.warn("Recovering rootfs for $id from backup directory")
            if (target.exists()) target.deleteRecursively()
            if (backup.renameTo(target)) {
                if (staging.exists()) staging.deleteRecursively()
                return@withContext true
            }
        }

        // If target rootfs is valid and backup exists, safely remove backup
        if (target.exists() && verifyRootfs(id) && backup.exists()) {
            backup.deleteRecursively()
        }

        // Discard any residual staging
        if (staging.exists()) {
            staging.deleteRecursively()
        }

        verifyRootfs(id)
    }

    /**
     * Discards any residual staging rootfs directory.
     * Preserves backup if target rootfs is invalid.
     */
    suspend fun discardStaging(id: EnvironmentId) = withContext(Dispatchers.IO) {
        val staging = stagingRootfsDir(id)
        if (staging.exists()) {
            log.debug("Discarding staging rootfs for $id")
            staging.deleteRecursively()
        }
        val backup = backupRootfsDir(id)
        val target = rootfsDir(id)
        if (backup.exists()) {
            if (target.exists() && verifyRootfs(id)) {
                backup.deleteRecursively()
            } else {
                log.warn("Preserving rootfs-backup for $id as active rootfs is not verified")
            }
        }
    }

    /**
     * Deletes the entire environment directory on disk.
     * Called strictly on explicit user deletion request.
     */
    suspend fun deleteEnvironment(id: EnvironmentId) = withContext(Dispatchers.IO) {
        log.info("Deleting environment storage for $id")
        environmentDir(id).deleteRecursively()
    }

    /**
     * Returns true if an environment directory exists.
     */
    fun environmentExists(id: EnvironmentId): Boolean = environmentDir(id).isDirectory
}
