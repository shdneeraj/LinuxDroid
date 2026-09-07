package com.linuxdroid.core.runtime

import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogCategory
import com.linuxdroid.core.logging.LogConfig
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.ExecutionTarget
import com.linuxdroid.core.model.PrebootError
import com.linuxdroid.core.model.PrebootErrorCode
import com.linuxdroid.core.model.RuntimeSpec
import java.io.File

/**
 * Result of the Host Preboot stage containing all validated and prepared launch state.
 */
data class PrebootLaunchPlan(
    val commandLine: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: File,
    val guestWorkingDirectory: String,
    val prootExecutable: File,
    val logFile: File?,
)

/**
 * Production-grade Host Preboot stage.
 *
 * Enforces the architectural boundary between the Android host environment
 * and the virtualized Linux guest:
 * 1. Validates all host-side runtime prerequisites before entering the guest.
 * 2. Strictly constructs the host environment without leaking Android variables.
 * 3. Enforces the handover to `/sbin/linuxdroid-init` for all guest workloads.
 * 4. Preserves structured command and argument arrays without shell flattening.
 */
class HostPreboot(
    private val commandBuilder: RuntimeCommandBuilder = ProotCommandBuilder(),
) {
    private val log = LinuxDroidLogger(LogSubsystem.RUNTIME, category = LogCategory.PREBOOT)

    /**
     * Executes the complete Host Preboot stage for the specified [RuntimeSpec].
     *
     * @throws PrebootError if any preboot validation or preparation fails.
     */
    fun prepare(
        spec: RuntimeSpec,
        proot: File,
        loader: File?,
        rootfs: File,
        tmpDir: File,
        logFile: File? = spec.logFilePath?.let { File(it) },
    ): PrebootLaunchPlan {
        log.withEnvironment(spec.environmentId).info(
            "[HOST-PREBOOT] Starting preboot validation for environment ${spec.environmentId}",
            details = mapOf(
                "environmentId" to spec.environmentId.value,
                "executionTarget" to spec.executionTarget.name,
                "workingDirectory" to spec.workingDirectory,
            )
        )

        // 1. Rootfs directory validation
        validateRootfs(spec, rootfs)

        // 2. PRoot executable validation
        validateProot(spec, proot)

        // 3. Runtime temporary and logging directory validation
        validateRuntimeDirs(spec, tmpDir, logFile)

        // 4. Guest Init executable validation (when target is GUEST)
        if (spec.executionTarget == ExecutionTarget.GUEST) {
            validateGuestInit(spec, rootfs)
        }

        // 5. Working directory validation & preparation
        val guestCwd = spec.workingDirectory.ifBlank { "/" }
        val targetDir = File(rootfs, guestCwd.removePrefix("/"))
        val finalGuestCwd = if (!targetDir.exists()) {
            if (guestCwd.startsWith("/home") || guestCwd.startsWith("/root") || guestCwd.startsWith("/tmp")) {
                targetDir.mkdirs()
                guestCwd
            } else {
                log.warn("[HOST-PREBOOT] Working directory '$guestCwd' does not exist in rootfs; falling back to '/'")
                "/"
            }
        } else {
            guestCwd
        }

        // 6. Host environment construction (strict isolation)
        val hostEnv = assembleHostEnvironment(spec, loader, tmpDir, logFile)
        log.debug("[HOST-PREBOOT] Host environment prepared with ${hostEnv.size} variables (isolated from Android)")

        // 7. Command handover construction
        val cmd = commandBuilder.build(spec.copy(workingDirectory = finalGuestCwd), proot)
        val sanitizedCmd = cmd.map { arg ->
            if (arg.contains("password", ignoreCase = true) ||
                arg.contains("token", ignoreCase = true) ||
                arg.contains("secret", ignoreCase = true)) {
                "[REDACTED]"
            } else {
                arg
            }
        }
        log.info("[HOST-PREBOOT] Handover command prepared: ${sanitizedCmd.joinToString(" ")}")

        return PrebootLaunchPlan(
            commandLine = cmd,
            environment = hostEnv,
            workingDirectory = rootfs,
            guestWorkingDirectory = finalGuestCwd,
            prootExecutable = proot,
            logFile = logFile,
        )
    }

    private fun validateRootfs(spec: RuntimeSpec, rootfs: File) {
        if (!rootfs.exists() || !rootfs.isDirectory) {
            log.error("[HOST-PREBOOT] Rootfs directory invalid or missing at ${rootfs.path}")
            throw PrebootError(
                code = PrebootErrorCode.HOST_PREBOOT_ROOTFS_INVALID,
                environmentId = spec.environmentId,
                detail = "Rootfs directory missing or not a directory: ${rootfs.path}",
            )
        }

        if (spec.executionTarget == ExecutionTarget.GUEST) {
            val requiredStructure = listOf("etc", "usr")
            val missingDirs = requiredStructure.filter { !File(rootfs, it).exists() }
            if (missingDirs.isNotEmpty()) {
                log.error("[HOST-PREBOOT] Rootfs missing essential directories: $missingDirs")
                throw PrebootError(
                    code = PrebootErrorCode.HOST_PREBOOT_ROOTFS_INVALID,
                    environmentId = spec.environmentId,
                    detail = "Rootfs at ${rootfs.path} is incomplete, missing: $missingDirs",
                )
            }
        }
        log.info("[HOST-PREBOOT] Rootfs validated at ${rootfs.path}")
    }

    private fun validateProot(spec: RuntimeSpec, proot: File) {
        if (!proot.exists() || !proot.canExecute()) {
            log.error("[HOST-PREBOOT] PRoot binary missing or not executable: ${proot.path}")
            throw PrebootError(
                code = PrebootErrorCode.HOST_PREBOOT_PROOT_MISSING,
                environmentId = spec.environmentId,
                detail = "PRoot executable missing or non-executable: ${proot.path}",
            )
        }
        log.info("[HOST-PREBOOT] PRoot executable verified: ${proot.path}")
    }

    private fun validateGuestInit(spec: RuntimeSpec, rootfs: File) {
        val guestInitPath = spec.guestInitPath ?: GuestInit.GUEST_INIT_PATH
        val initFile = File(rootfs, guestInitPath.removePrefix("/"))

        if (!initFile.exists()) {
            log.error("[HOST-PREBOOT] Guest init '$guestInitPath' missing in rootfs at ${initFile.path}")
            throw PrebootError(
                code = PrebootErrorCode.HOST_PREBOOT_INIT_MISSING,
                environmentId = spec.environmentId,
                detail = "Required guest initialization executable '$guestInitPath' not found in rootfs at ${initFile.path}. Environment must be reinstalled or repaired.",
            )
        }

        if (!initFile.canRead() || !initFile.canExecute()) {
            log.error("[HOST-PREBOOT] Guest init '$guestInitPath' is not executable: ${initFile.path}")
            throw PrebootError(
                code = PrebootErrorCode.HOST_PREBOOT_INIT_NOT_EXECUTABLE,
                environmentId = spec.environmentId,
                detail = "Guest initialization executable '$guestInitPath' is not executable (readable=${initFile.canRead()}, executable=${initFile.canExecute()})",
            )
        }
        log.info("[HOST-PREBOOT] Persistent guest init verified: ${initFile.path}")
    }

    private fun validateRuntimeDirs(spec: RuntimeSpec, tmpDir: File, logFile: File?) {
        try {
            if (!tmpDir.exists() && !tmpDir.mkdirs()) {
                throw PrebootError(
                    code = PrebootErrorCode.HOST_PREBOOT_RUNTIME_INVALID,
                    environmentId = spec.environmentId,
                    detail = "Failed to create runtime temporary directory at ${tmpDir.path}",
                )
            }
            logFile?.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    throw PrebootError(
                        code = PrebootErrorCode.HOST_PREBOOT_RUNTIME_INVALID,
                        environmentId = spec.environmentId,
                        detail = "Failed to create runtime log directory at ${parent.path}",
                    )
                }
            }
        } catch (e: PrebootError) {
            throw e
        } catch (e: Exception) {
            throw PrebootError(
                code = PrebootErrorCode.HOST_PREBOOT_RUNTIME_INVALID,
                environmentId = spec.environmentId,
                detail = "Failed to initialize runtime directories: ${e.message}",
                cause = e,
            )
        }
    }

    /**
     * Assembles the host environment used by the PRoot process.
     * Host-only PRoot variables remain host-side, while Android Bionic / library
     * variables (e.g. `LD_PRELOAD`, `LD_LIBRARY_PATH`) are strictly excluded.
     */
    fun assembleHostEnvironment(
        spec: RuntimeSpec,
        loader: File?,
        tmpDir: File,
        logFile: File? = spec.logFilePath?.let { File(it) },
    ): Map<String, String> = buildMap {
        put("PROOT_TMP_DIR", tmpDir.absolutePath)
        if (loader?.exists() == true) {
            put("PROOT_LOADER", loader.absolutePath)
        }
        val targetLog = logFile?.absolutePath ?: spec.logFilePath
        if (targetLog != null && LogConfig.generate_log) {
            File(targetLog).parentFile?.mkdirs()
            put("PROOT_LOG_FILE", targetLog)
        }
        if (LogConfig.generate_log) {
            put("LINUXDROID_INIT_VERBOSE", "1")
            if (!spec.environmentVariables.containsKey("PROOT_VERBOSE")) {
                put("PROOT_VERBOSE", LogConfig.prootVerboseLevel.toString())
            }
        } else {
            put("LINUXDROID_INIT_VERBOSE", "0")
            if (!spec.environmentVariables.containsKey("PROOT_VERBOSE")) {
                put("PROOT_VERBOSE", "0")
            }
        }
        // Seccomp mode 2 is enabled by default for optimal syscall filtering performance.
        // PROOT_NO_SECCOMP is only set if explicitly specified in spec.environmentVariables.
        if (spec.environmentVariables.containsKey("PROOT_NO_SECCOMP")) {
            put("PROOT_NO_SECCOMP", spec.environmentVariables["PROOT_NO_SECCOMP"]!!)
        }

        // Propagate only explicitly specified variables, excluding Android host library variables
        spec.environmentVariables.forEach { (k, v) ->
            if (k != "LD_PRELOAD" && k != "LD_LIBRARY_PATH" && !k.startsWith("ANDROID_") && k != "PROOT_NO_SECCOMP") {
                put(k, v)
            }
        }
    }
}
