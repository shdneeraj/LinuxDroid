package com.linuxdroid.core.logging

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.model.EnvironmentId
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InstallationLoggerTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private val envId = EnvironmentId("test-install-env")

    @Test
    fun `initLogHeader writes standardized installation header`() {
        val installDir = tempFolder.newFolder("installation")
        val logFile = File(installDir, "install.log")
        val stateFile = File(installDir, "install-state")
        val metaFile = File(installDir, "install-metadata")

        val logger = InstallationLogger(
            environmentId = envId,
            installLogFile = logFile,
            installStateFile = stateFile,
            installMetadataFile = metaFile,
            distribution = "debian",
            release = "trixie",
            architecture = "arm64",
        )

        logger.initLogHeader()

        assertThat(logFile.exists()).isTrue()
        val content = logFile.readText()
        assertThat(content).contains("LINUXDROID ROOTFS INSTALLATION LOG")
        assertThat(content).contains("Environment: test-install-env")
        assertThat(content).contains("Distribution: debian")
        assertThat(content).contains("Release: trixie")
        assertThat(content).contains("Architecture: arm64")
    }

    @Test
    fun `pre-install markers format correctly`() {
        val installDir = tempFolder.newFolder("pre-install-markers")
        val logFile = File(installDir, "install.log")
        val stateFile = File(installDir, "install-state")

        val logger = InstallationLogger(
            environmentId = envId,
            installLogFile = logFile,
            installStateFile = stateFile,
        )

        logger.logPreInstallStart("ROOTFS_EXTRACT")
        logger.logPreInstallSuccess("ROOTFS_EXTRACT", 1234)
        logger.logPreInstallFail("GUEST_INIT_INJECT", 50, "Init injection failed")

        val logContent = logFile.readText()
        assertThat(logContent).contains("[PREINSTALL][START][ROOTFS_EXTRACT]")
        assertThat(logContent).contains("[PREINSTALL][SUCCESS][ROOTFS_EXTRACT]")
        assertThat(logContent).contains("duration_ms=1234")
        assertThat(logContent).contains("[PREINSTALL][FAIL][GUEST_INIT_INJECT]")
        assertThat(logContent).contains("reason=Init injection failed")
    }

    @Test
    fun `post-install markers format correctly and record exit codes`() {
        val installDir = tempFolder.newFolder("post-install-markers")
        val logFile = File(installDir, "install.log")
        val stateFile = File(installDir, "install-state")

        val logger = InstallationLogger(
            environmentId = envId,
            installLogFile = logFile,
            installStateFile = stateFile,
        )

        logger.logPostInstallStart("APT_UPDATE", command = "apt-get update")
        logger.logPostInstallSuccess("APT_UPDATE", 2500, exitCode = 0)
        logger.logPostInstallFail("INSTALL_LDDM", 4000, exitCode = 100, stderr = "Package not found")

        val logContent = logFile.readText()
        assertThat(logContent).contains("[POSTINSTALL][START][APT_UPDATE]")
        assertThat(logContent).contains("command=apt-get update")
        assertThat(logContent).contains("[POSTINSTALL][SUCCESS][APT_UPDATE]")
        assertThat(logContent).contains("exit_code=0")
        assertThat(logContent).contains("duration_ms=2500")
        assertThat(logContent).contains("[POSTINSTALL][FAIL][INSTALL_LDDM]")
        assertThat(logContent).contains("exit_code=100")
        assertThat(logContent).contains("stderr=Package not found")
    }

    @Test
    fun `updateState writes atomic install-state file`() {
        val installDir = tempFolder.newFolder("state-test")
        val logFile = File(installDir, "install.log")
        val stateFile = File(installDir, "install-state")

        val logger = InstallationLogger(
            environmentId = envId,
            installLogFile = logFile,
            installStateFile = stateFile,
        )

        logger.updateState("PRE_INSTALLING", phase = "PRE_INSTALL", operation = "ROOTFS_EXTRACT")
        var stateContent = stateFile.readText()
        assertThat(stateContent).contains("state=PRE_INSTALLING")
        assertThat(stateContent).contains("phase=PRE_INSTALL")
        assertThat(stateContent).contains("operation=ROOTFS_EXTRACT")

        logger.updateState("PRE_INSTALL_READY", phase = "PRE_INSTALL", operation = "PRE_INSTALL")
        stateContent = stateFile.readText()
        assertThat(stateContent).contains("state=PRE_INSTALL_READY")
        assertThat(stateContent).contains("phase=PRE_INSTALL")
        assertThat(stateContent).contains("operation=PRE_INSTALL")

        logger.updateState("POST_INSTALL_COMPLETE", phase = "POST_INSTALL")
        stateContent = stateFile.readText()
        assertThat(stateContent).contains("state=POST_INSTALL_COMPLETE")
        assertThat(stateContent).contains("phase=POST_INSTALL")
    }

    @Test
    fun `writeMetadata writes distribution metadata with custom properties`() {
        val installDir = tempFolder.newFolder("meta-test")
        val logFile = File(installDir, "install.log")
        val stateFile = File(installDir, "install-state")
        val metaFile = File(installDir, "install-metadata")

        val logger = InstallationLogger(
            environmentId = envId,
            installLogFile = logFile,
            installStateFile = stateFile,
            installMetadataFile = metaFile,
            distribution = "ubuntu",
            release = "noble",
            architecture = "arm64",
        )

        logger.writeMetadata(mapOf("target_user" to "developer", "source" to "https://cloud-images.ubuntu.com"))

        assertThat(metaFile.exists()).isTrue()
        val content = metaFile.readText()
        assertThat(content).contains("environment_id=test-install-env")
        assertThat(content).contains("distribution=ubuntu")
        assertThat(content).contains("release=noble")
        assertThat(content).contains("architecture=arm64")
        assertThat(content).contains("target_user=developer")
        assertThat(content).contains("source=https://cloud-images.ubuntu.com")
    }

    @Test
    fun `sensitive credentials are strictly redacted in logs and metadata`() {
        val installDir = tempFolder.newFolder("redact-test")
        val logFile = File(installDir, "install.log")
        val stateFile = File(installDir, "install-state")
        val metaFile = File(installDir, "install-metadata")

        val logger = InstallationLogger(
            environmentId = envId,
            installLogFile = logFile,
            installStateFile = stateFile,
            installMetadataFile = metaFile,
        )

        // 1. Password flag redaction
        logger.appendLog("Running command useradd --password SuperSecret123 developer")
        // 2. Chpasswd line redaction
        logger.appendLog("echo developer:SuperSecret123 | chpasswd")
        // 3. Key-val password redaction
        logger.appendLog("install.conf generated with password = SuperSecret123")

        logger.writeMetadata(mapOf("password" to "SuperSecret123", "user" to "developer"))
        logger.updateState("FAILED", error = "Failed setting password: SuperSecret123")

        val logContent = logFile.readText()
        assertThat(logContent).doesNotContain("SuperSecret123")
        assertThat(logContent).contains("--password [REDACTED]")
        assertThat(logContent).contains("[REDACTED_USER_CREDENTIAL]")
        assertThat(logContent).contains("password = [REDACTED]")

        val metaContent = metaFile.readText()
        assertThat(metaContent).doesNotContain("SuperSecret123")

        val stateContent = stateFile.readText()
        assertThat(stateContent).doesNotContain("SuperSecret123")
    }
}
