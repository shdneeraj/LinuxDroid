package com.linuxdroid.linux.bootstrap

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.model.Architecture
import com.linuxdroid.core.model.Distribution
import com.linuxdroid.core.model.Environment
import com.linuxdroid.core.model.EnvironmentId
import com.linuxdroid.core.model.EnvironmentMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StandardPackageInstallerTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private val testEnv = Environment(
        metadata = EnvironmentMetadata(
            id = EnvironmentId("test-env"),
            name = "Test Env",
            distribution = Distribution.DEBIAN,
            architecture = Architecture.ARM64,
        ),
        rootfsPath = "/dummy",
        metadataPath = "/dummy",
    )

    @Test
    fun `standard packages list contains all mandatory baseline groups`() {
        val pkgs = StandardPackageInstaller.STANDARD_PACKAGES

        // Core
        assertThat(pkgs).containsAtLeast("bash", "zsh", "coreutils", "sudo", "procps")
        // Networking
        assertThat(pkgs).containsAtLeast("curl", "wget", "openssl", "ca-certificates")
        // Package Management
        assertThat(pkgs).containsAtLeast("apt", "gnupg")
        // Development
        assertThat(pkgs).containsAtLeast("git", "build-essential", "pkg-config", "python3")
        // Archive
        assertThat(pkgs).containsAtLeast("tar", "gzip", "xz-utils", "zip", "unzip")
        // Terminal / Docs
        assertThat(pkgs).containsAtLeast("nano", "man-db")
        // Desktop runtime
        assertThat(pkgs).contains("dbus")
    }

    @Test
    fun `installStandardPackages in simulated mode registers packages into dpkg status`() = runBlocking {
        val rootfsDir = tempFolder.newFolder("rootfs")
        val installer = StandardPackageInstaller(runtimeBackend = null)

        val result = installer.installStandardPackages(testEnv, rootfsDir)
        assertThat(result.success).isTrue()
        assertThat(result.installedCount).isEqualTo(StandardPackageInstaller.STANDARD_PACKAGES.size)

        val dpkgStatus = File(rootfsDir, "var/lib/dpkg/status")
        assertThat(dpkgStatus.exists()).isTrue()
        val text = dpkgStatus.readText()
        assertThat(text).contains("Package: bash")
        assertThat(text).contains("Package: zsh")
        assertThat(text).contains("Package: sudo")
        assertThat(text).contains("Package: git")
        assertThat(text).contains("Package: python3")
    }
}

