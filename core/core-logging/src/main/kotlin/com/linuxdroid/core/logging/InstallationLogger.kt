package com.linuxdroid.core.logging

import com.linuxdroid.core.model.EnvironmentId
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Dedicated persistent rootfs installation logger.
 *
 * Maintains:
 * - `<environmentDir>/installation/install.log`
 * - `<environmentDir>/installation/install-state`
 * - `<environmentDir>/installation/install-metadata`
 *
 * Guarantees strict password redaction across all markers, commands, and outputs.
 */
class InstallationLogger(
    val environmentId: EnvironmentId,
    val installLogFile: File,
    val installStateFile: File,
    val installMetadataFile: File? = null,
    val distribution: String = "unknown",
    val release: String = "unknown",
    val architecture: String = "arm64",
) {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val lock = Any()

    init {
        installLogFile.parentFile?.mkdirs()
        installStateFile.parentFile?.mkdirs()
        installMetadataFile?.parentFile?.mkdirs()
    }

    private fun now(): String = synchronized(dateFormat) {
        dateFormat.format(Date())
    }

    /**
     * Writes the official rootfs installation log header.
     */
    fun initLogHeader() {
        synchronized(lock) {
            val header = """
                ================================================================================
                LINUXDROID ROOTFS INSTALLATION LOG
                Environment: ${environmentId.value}
                Distribution: $distribution
                Release: $release
                Architecture: $architecture
                Timestamp: ${now()}
                ================================================================================

            """.trimIndent()
            installLogFile.writeText(header + "\n", Charsets.UTF_8)
        }
    }

    /**
     * Appends a raw or formatted line to the installation log with redaction.
     */
    fun appendLog(text: String) {
        synchronized(lock) {
            val sanitized = redactSensitive(text)
            PrintWriter(FileWriter(installLogFile, true)).use { out ->
                out.println(sanitized)
            }
        }
    }

    fun logPreInstallStart(operation: String) {
        val ts = now()
        appendLog("[PREINSTALL][START][$operation]\ntimestamp=$ts")
    }

    fun logPreInstallSuccess(operation: String, durationMs: Long) {
        val ts = now()
        appendLog("[PREINSTALL][SUCCESS][$operation]\nduration_ms=$durationMs\ntimestamp=$ts")
    }

    fun logPreInstallFail(operation: String, durationMs: Long, reason: String) {
        val ts = now()
        appendLog("[PREINSTALL][FAIL][$operation]\nreason=${redactSensitive(reason)}\nduration_ms=$durationMs\ntimestamp=$ts")
    }

    fun logPostInstallStart(operation: String, command: String? = null) {
        val ts = now()
        val cmdStr = if (command != null) "\ncommand=${redactSensitive(command)}" else ""
        appendLog("[POSTINSTALL][START][$operation]$cmdStr\ntimestamp=$ts")
    }

    fun logPostInstallSuccess(operation: String, durationMs: Long, exitCode: Int = 0) {
        val ts = now()
        appendLog("[POSTINSTALL][SUCCESS][$operation]\nexit_code=$exitCode\nduration_ms=$durationMs\ntimestamp=$ts")
    }

    fun logPostInstallFail(operation: String, durationMs: Long, exitCode: Int, stderr: String) {
        val ts = now()
        appendLog("[POSTINSTALL][FAIL][$operation]\nexit_code=$exitCode\nduration_ms=$durationMs\nstderr=${redactSensitive(stderr)}\ntimestamp=$ts")
    }

    // ─── GUI Installation Phase Markers ──────────────────────────────────────

    /**
     * Logs the start of a GUI installation operation.
     *
     * Marker format: `[GUI_INSTALL][START][OPERATION]`
     */
    fun logGuiInstallStart(operation: String, command: String? = null) {
        val ts = now()
        val cmdStr = if (command != null) "\ncommand=${redactSensitive(command)}" else ""
        appendLog("[GUI_INSTALL][START][$operation]$cmdStr\ntimestamp=$ts")
    }

    /**
     * Logs the successful completion of a GUI installation operation.
     *
     * Marker format: `[GUI_INSTALL][SUCCESS][OPERATION]`
     */
    fun logGuiInstallSuccess(operation: String, durationMs: Long = 0, detail: String? = null) {
        val ts = now()
        val detailStr = if (detail != null) "\ndetail=${redactSensitive(detail)}" else ""
        appendLog("[GUI_INSTALL][SUCCESS][$operation]\nduration_ms=$durationMs$detailStr\ntimestamp=$ts")
    }

    /**
     * Logs the failure of a GUI installation operation.
     * GUI failures never invalidate the CLI environment.
     *
     * Marker format: `[GUI_INSTALL][FAIL][OPERATION]`
     */
    fun logGuiInstallFail(operation: String, durationMs: Long = 0, exitCode: Int? = null, error: String? = null) {
        val ts = now()
        val codeStr = if (exitCode != null) "\nexit_code=$exitCode" else ""
        val errStr = if (error != null) "\nstderr=${redactSensitive(error)}" else ""
        appendLog("[GUI_INSTALL][FAIL][$operation]$codeStr$errStr\nduration_ms=$durationMs\ntimestamp=$ts")
    }

    // ─── Package Operation Markers ────────────────────────────────────────────

    /**
     * Logs the start of a manual package manager operation.
     *
     * Marker format: `[PACKAGE][START][OPERATION][packageName]`
     */
    fun logPackageOperationStart(operation: String, packageName: String) {
        val ts = now()
        appendLog("[PACKAGE][START][$operation][${redactSensitive(packageName)}]\ntimestamp=$ts")
    }

    /**
     * Logs the successful completion of a manual package manager operation.
     *
     * Marker format: `[PACKAGE][SUCCESS][OPERATION][packageName]`
     */
    fun logPackageOperationSuccess(operation: String, packageName: String, durationMs: Long = 0) {
        val ts = now()
        appendLog("[PACKAGE][SUCCESS][$operation][${redactSensitive(packageName)}]\nduration_ms=$durationMs\ntimestamp=$ts")
    }

    /**
     * Logs the failure of a manual package manager operation.
     *
     * Marker format: `[PACKAGE][FAIL][OPERATION][packageName]`
     */
    fun logPackageOperationFail(operation: String, packageName: String, exitCode: Int? = null, durationMs: Long = 0) {
        val ts = now()
        val codeStr = if (exitCode != null) "\nexit_code=$exitCode" else ""
        appendLog("[PACKAGE][FAIL][$operation][${redactSensitive(packageName)}]$codeStr\nduration_ms=$durationMs\ntimestamp=$ts")
    }

    /**
     * Atomically updates `<environmentDir>/installation/install-state`.
     */
    fun updateState(
        state: String,
        phase: String? = null,
        operation: String? = null,
        exitCode: Int? = null,
        error: String? = null,
    ) {
        synchronized(lock) {
            val builder = StringBuilder()
            builder.append("state=").append(state).append("\n")
            if (phase != null) builder.append("phase=").append(phase).append("\n")
            if (operation != null) builder.append("operation=").append(operation).append("\n")
            if (exitCode != null) builder.append("exit_code=").append(exitCode).append("\n")
            if (error != null) builder.append("error=").append(redactSensitive(error)).append("\n")
            builder.append("timestamp=").append(now()).append("\n")

            val content = builder.toString()
            val temp = File(installStateFile.parentFile, "${installStateFile.name}.tmp.${System.nanoTime()}")
            temp.writeText(content, Charsets.UTF_8)
            if (!temp.renameTo(installStateFile)) {
                installStateFile.delete()
                temp.renameTo(installStateFile)
            }
        }
    }

    /**
     * Writes `<environmentDir>/installation/install-metadata`.
     */
    fun writeMetadata(extraProperties: Map<String, String> = emptyMap()) {
        val metadataFile = installMetadataFile ?: return
        synchronized(lock) {
            val lines = mutableListOf(
                "environment_id=${environmentId.value}",
                "distribution=$distribution",
                "release=$release",
                "architecture=$architecture",
                "created_at=${now()}",
            )
            extraProperties.forEach { (k, v) ->
                val sanitizedVal = if (k.contains("password", ignoreCase = true) || k.contains("secret", ignoreCase = true)) {
                    "[REDACTED]"
                } else {
                    redactSensitive(v)
                }
                lines.add("$k=$sanitizedVal")
            }
            metadataFile.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        }
    }

    companion object {
        private val PASSWORD_ARG_REGEX = Regex("""--password(?:=|\s+)[^\s]+""", RegexOption.IGNORE_CASE)
        private val CHPASSWD_LINE_REGEX = Regex("""(?<!https)(?<!http)\b[a-zA-Z0-9_-]+:(?!//)[^\s|]+""", RegexOption.IGNORE_CASE)
        private val PASSWORD_KEY_VAL_REGEX = Regex("""((?:password|secret)\s*[:=]\s*)[^\n\r\t ]+""", RegexOption.IGNORE_CASE)

        /**
         * Strictly redacts passwords and credentials from log strings.
         */
        fun redactSensitive(input: String): String {
            var result = input
            result = PASSWORD_ARG_REGEX.replace(result, "--password [REDACTED]")
            result = CHPASSWD_LINE_REGEX.replace(result, "[REDACTED_USER_CREDENTIAL]")
            result = PASSWORD_KEY_VAL_REGEX.replace(result, "$1[REDACTED]")
            return result
        }
    }
}
