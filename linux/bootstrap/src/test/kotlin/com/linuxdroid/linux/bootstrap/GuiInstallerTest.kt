package com.linuxdroid.linux.bootstrap

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.model.*
import com.linuxdroid.core.runtime.GuiInstallScript
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GuiInstallerTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private val testEnvId = EnvironmentId("test-gui-env")
    private val testEnv = Environment(
        metadata = EnvironmentMetadata(
            id = testEnvId,
            name = "Test GUI",
            distribution = Distribution.DEBIAN,
            architecture = Architecture.ARM64,
        ),
        state = EnvironmentState.READY,
        rootfsPath = "/dummy",
        metadataPath = "/dummy",
    )

    @Test
    fun `checkStatus returns NOT_INSTALLED when state file does not exist`() {
        val storage = mockk<EnvironmentStorage>(relaxed = true)
        val nonExistent = File(tempFolder.root, "does-not-exist")
        every { storage.guiStateFile(testEnvId) } returns nonExistent

        val installer = GuiInstaller(storage = storage)
        val status = installer.checkStatus(testEnv)

        assertThat(status).isEqualTo(GuiState.NOT_INSTALLED)
    }

    @Test
    fun `checkStatus returns INSTALLED when state file and marker exist`() {
        val rootfsDir = tempFolder.newFolder("rootfs-installed")
        val metaDir = tempFolder.newFolder("meta-installed")
        val stateFile = File(metaDir, "gui-state").apply { writeText("INSTALLED\n") }
        val marker = File(rootfsDir, "etc/linuxdroid/GUI_INSTALL_COMPLETE").apply {
            parentFile?.mkdirs()
            writeText("STATUS=COMPLETE\n")
        }

        val storage = mockk<EnvironmentStorage>(relaxed = true)
        every { storage.guiStateFile(testEnvId) } returns stateFile
        every { storage.rootfsDir(testEnvId) } returns rootfsDir

        val installer = GuiInstaller(storage = storage)
        val status = installer.checkStatus(testEnv)

        assertThat(status).isEqualTo(GuiState.INSTALLED)
    }

    @Test
    fun `checkStatus returns FAILED when state file is INSTALLED but marker is missing`() {
        val rootfsDir = tempFolder.newFolder("rootfs-missing-marker")
        val metaDir = tempFolder.newFolder("meta-missing-marker")
        val stateFile = File(metaDir, "gui-state").apply { writeText("INSTALLED\n") }

        val storage = mockk<EnvironmentStorage>(relaxed = true)
        every { storage.guiStateFile(testEnvId) } returns stateFile
        every { storage.rootfsDir(testEnvId) } returns rootfsDir

        val installer = GuiInstaller(storage = storage)
        val status = installer.checkStatus(testEnv)

        assertThat(status).isEqualTo(GuiState.FAILED)
    }

    @Test
    fun `install executes simulated GUI installation when runtimeBackend is null`() = runBlocking {
        val rootfsDir = tempFolder.newFolder("rootfs-sim")
        File(rootfsDir, "bin").mkdirs()
        File(rootfsDir, "bin/sh").createNewFile()
        File(rootfsDir, "sbin").mkdirs()
        File(rootfsDir, "sbin/linuxdroid-init").apply {
            createNewFile()
            setExecutable(true)
        }

        val logsDir = tempFolder.newFolder("logs-sim")
        val metaDir = tempFolder.newFolder("meta-sim")
        val guiLogFile = File(logsDir, "gui-install.log")
        val guiStateFile = File(metaDir, "gui-state")

        val storage = mockk<EnvironmentStorage>(relaxed = true)
        every { storage.rootfsDir(testEnvId) } returns rootfsDir
        coEvery { storage.verifyRootfs(testEnvId) } returns true
        every { storage.logsDir(testEnvId) } returns logsDir
        every { storage.guiInstallLogFile(testEnvId) } returns guiLogFile
        every { storage.guiStateFile(testEnvId) } returns guiStateFile
        coEvery { storage.writeAtomic(any(), any()) } coAnswers {
            val f = firstArg<File>()
            f.parentFile?.mkdirs()
            f.writeText(secondArg<String>())
        }

        val packageInstaller = mockk<LinuxDroidPackageInstaller>(relaxed = true)
        val dummyDeb = tempFolder.newFile("dummy.deb")
        every { packageInstaller.resolvePackageDeb(any(), any()) } returns dummyDeb

        val graphicalInstaller = mockk<GraphicalDependencyInstaller>(relaxed = true)
        val validator = mockk<RootfsValidator>(relaxed = true)
        every { validator.validateGraphics(any(), any(), any()) } returns RootfsValidationReport(
            isValid = true,
            distribution = Distribution.DEBIAN,
            architecture = Architecture.ARM64,
            checks = emptyList(),
            errors = emptyList(),
        )

        val installer = GuiInstaller(
            storage = storage,
            runtimeBackend = null,
            packageInstaller = packageInstaller,
            graphicalInstaller = graphicalInstaller,
            validator = validator,
        )

        val logs = mutableListOf<String>()
        val result = installer.install(
            environment = testEnv,
            lddmDebOverride = dummyDeb,
            lddeDebOverride = dummyDeb,
            onLog = { logs.add(it) }
        )

        assertThat(result).isEqualTo(GuiState.INSTALLED)
        assertThat(guiStateFile.exists()).isTrue()
        assertThat(guiStateFile.readText().trim()).isEqualTo("INSTALLED")
        assertThat(File(rootfsDir, "etc/linuxdroid/GUI_INSTALL_COMPLETE").exists()).isTrue()
        assertThat(guiLogFile.exists()).isTrue()
        assertThat(guiLogFile.readText()).contains("FULL_INSTALL")
    }

    @Test
    fun `repair fixes missing components and revalidates`() = runBlocking {
        val rootfsDir = tempFolder.newFolder("rootfs-repair")
        val metaDir = tempFolder.newFolder("meta-repair")
        val guiStateFile = File(metaDir, "gui-state")

        val storage = mockk<EnvironmentStorage>(relaxed = true)
        every { storage.rootfsDir(testEnvId) } returns rootfsDir
        every { storage.guiStateFile(testEnvId) } returns guiStateFile
        coEvery { storage.writeAtomic(any(), any()) } coAnswers {
            val f = firstArg<File>()
            f.parentFile?.mkdirs()
            f.writeText(secondArg<String>())
        }

        val packageInstaller = mockk<LinuxDroidPackageInstaller>(relaxed = true)
        val graphicalInstaller = mockk<GraphicalDependencyInstaller>(relaxed = true)
        val validator = mockk<RootfsValidator>()

        // First validation check: wayland is missing
        every { validator.validateGraphics(any(), any(), any()) } returnsMany listOf(
            RootfsValidationReport(
                isValid = false,
                distribution = Distribution.DEBIAN,
                architecture = Architecture.ARM64,
                checks = listOf(ValidationCheckResult("wayland_libs", false, "Missing")),
                errors = listOf("Missing wayland"),
            ),
            // Second validation check after repair: all valid
            RootfsValidationReport(
                isValid = true,
                distribution = Distribution.DEBIAN,
                architecture = Architecture.ARM64,
                checks = listOf(ValidationCheckResult("wayland_libs", true, "Present")),
                errors = emptyList(),
            )
        )

        val installer = GuiInstaller(
            storage = storage,
            runtimeBackend = null,
            packageInstaller = packageInstaller,
            graphicalInstaller = graphicalInstaller,
            validator = validator,
        )

        val result = installer.repair(testEnv)

        assertThat(result).isEqualTo(GuiState.INSTALLED)
        coVerify(exactly = 1) { graphicalInstaller.ensureGraphicalDependencies(any(), any(), any(), any()) }
        assertThat(guiStateFile.readText().trim()).isEqualTo("INSTALLED")
    }
}
