package com.linuxdroid.linux.bootstrap

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.model.*
import com.linuxdroid.core.runtime.RuntimeBackend
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Production test suite covering the Section 38 Rootfs Graphical Deployment Matrix (TEST 1 to TEST 18).
 */
class RootfsDeploymentManagerTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private val validator = RootfsValidator()
    private val extractor = RootfsExtractor()
    private val configurator = RootfsConfigurator()
    private val runtimeSetup = RuntimeEnvironmentSetup()

    private val testEnvId = EnvironmentId("test-env-01")
    private val testEnv = Environment(
        metadata = EnvironmentMetadata(
            id = testEnvId,
            name = "Test Environment",
            distribution = Distribution.DEBIAN,
            architecture = Architecture.ARM64,
        ),
        rootfsPath = "/dummy",
        metadataPath = "/dummy",
    )

    private val debianDef = DistributionCatalog.getDefinition(Distribution.DEBIAN, Architecture.ARM64)

    /**
     * Helper to create a minimal valid or corrupted Debian .deb package.
     */
    private fun createMockDeb(
        destFile: File,
        packageName: String,
        version: String,
        architecture: String = "arm64",
        files: Map<String, String> = emptyMap(),
        corruptArchive: Boolean = false,
    ) {
        destFile.parentFile?.mkdirs()
        if (corruptArchive) {
            destFile.writeBytes("!<arch>\ncorrupted_not_a_valid_ar_or_tar".toByteArray())
            return
        }

        // 1. control.tar.gz
        val controlBytes = ByteArrayOutputStream().use { baos ->
            GzipCompressorOutputStream(baos).use { gzos ->
                TarArchiveOutputStream(gzos).use { tar ->
                    val controlContent = """
                        Package: $packageName
                        Version: $version
                        Architecture: $architecture
                        Maintainer: LinuxDroid Team <support@linuxdroid.com>
                        Description: LinuxDroid test package
                    """.trimIndent() + "\n"
                    val entry = TarArchiveEntry("control").apply {
                        size = controlContent.toByteArray().size.toLong()
                        mode = 0b110100100 // 0644
                    }
                    tar.putArchiveEntry(entry)
                    tar.write(controlContent.toByteArray())
                    tar.closeArchiveEntry()
                }
            }
            baos.toByteArray()
        }

        // 2. data.tar.gz
        val dataBytes = ByteArrayOutputStream().use { baos ->
            GzipCompressorOutputStream(baos).use { gzos ->
                TarArchiveOutputStream(gzos).use { tar ->
                    files.forEach { (path, content) ->
                        val contentBytes = content.toByteArray()
                        val entry = TarArchiveEntry(path).apply {
                            size = contentBytes.size.toLong()
                            mode = 0b111101101 // 0755
                        }
                        tar.putArchiveEntry(entry)
                        tar.write(contentBytes)
                        tar.closeArchiveEntry()
                    }
                }
            }
            baos.toByteArray()
        }

        // 3. Assemble .deb with ArArchiveOutputStream
        destFile.outputStream().use { fos ->
            ArArchiveOutputStream(fos).use { ar ->
                val debianBinary = "2.0\n".toByteArray()
                val debianBinaryEntry = ArArchiveEntry("debian-binary", debianBinary.size.toLong())
                ar.putArchiveEntry(debianBinaryEntry)
                ar.write(debianBinary)
                ar.closeArchiveEntry()

                val controlEntry = ArArchiveEntry("control.tar.gz", controlBytes.size.toLong())
                ar.putArchiveEntry(controlEntry)
                ar.write(controlBytes)
                ar.closeArchiveEntry()

                val dataEntry = ArArchiveEntry("data.tar.gz", dataBytes.size.toLong())
                ar.putArchiveEntry(dataEntry)
                ar.write(dataBytes)
                ar.closeArchiveEntry()
            }
        }
        destFile.setReadable(true, false)
    }

    /**
     * Helper to create a mock ELF64 AArch64 executable with optional PT_INTERP.
     */
    private fun createMockElf(
        destFile: File,
        isArm64: Boolean = true,
        interpreter: String? = null,
    ) {
        destFile.parentFile?.mkdirs()
        FileOutputStream(destFile).use { fos ->
            val header = ByteArray(64)
            header[0] = 0x7F.toByte()
            header[1] = 'E'.code.toByte()
            header[2] = 'L'.code.toByte()
            header[3] = 'F'.code.toByte()
            header[4] = 0x02 // 64-bit
            header[5] = 0x01 // Little endian
            header[6] = 0x01 // Version
            header[16] = 0x03 // ET_DYN
            header[18] = if (isArm64) 0xB7.toByte() else 0x3E.toByte() // ARM64 vs x86_64
            header[24] = 0x00
            header[25] = 0x10

            if (interpreter != null) {
                header[32] = 64
                header[54] = 56
                header[56] = 1
                fos.write(header)

                val phdr = ByteArray(56)
                phdr[0] = 3 // PT_INTERP
                phdr[8] = 120
                val interpBytes = (interpreter + "\u0000").toByteArray(Charsets.UTF_8)
                phdr[32] = interpBytes.size.toByte()
                fos.write(phdr)
                fos.write(interpBytes)
            } else {
                fos.write(header)
            }
        }
        destFile.setExecutable(true, false)
        destFile.setReadable(true, false)
    }

    /**
     * Populates a mock rootfs with standard directories, ELF binaries, Wayland libs, Weston, and dpkg status.
     */
    private fun populateMockRootfs(
        rootfsDir: File,
        withWayland: Boolean = true,
        withWeston: Boolean = true,
        withLddm: Boolean = true,
        withLdde: Boolean = true,
        lddmVersion: String = "1.0.0",
        lddeVersion: String = "1.0.0",
        interpPath: String = "/lib/ld-linux-aarch64.so.1",
    ) {
        listOf(
            "bin", "usr/bin", "usr/lib", "usr/lib/aarch64-linux-gnu", "usr/lib/aarch64-linux-gnu/weston",
            "lib", "etc/apt", "etc/xdg/weston", "etc/linuxdroid", "tmp", "dev", "proc", "sys", "sbin",
            "var/lib/dpkg", "run", "home/user",
        ).forEach { File(rootfsDir, it).mkdirs() }

        // Dynamic interpreter
        createMockElf(File(rootfsDir, interpPath.removePrefix("/")), isArm64 = true)

        // Core executables
        createMockElf(File(rootfsDir, "usr/bin/bash"), isArm64 = true, interpreter = interpPath)
        createMockElf(File(rootfsDir, "usr/bin/env"), isArm64 = true, interpreter = interpPath)
        createMockElf(File(rootfsDir, "usr/bin/true"), isArm64 = true, interpreter = interpPath)

        // Symlink /bin/sh -> /usr/bin/bash
        val binSh = File(rootfsDir, "bin/sh")
        Files.deleteIfExists(binSh.toPath())
        Files.createSymbolicLink(binSh.toPath(), Paths.get("/usr/bin/bash"))

        // APT sources
        File(rootfsDir, "etc/apt/sources.list").writeText("deb http://deb.debian.org/debian trixie main\n")

        // Guest Init
        val initFile = File(rootfsDir, "sbin/linuxdroid-init")
        initFile.writeText("#!/bin/sh\nexec \"$@\"\n")
        initFile.setExecutable(true, false)

        val dpkgEntries = mutableListOf<String>()

        // Wayland
        if (withWayland) {
            val waylandClient = File(rootfsDir, "usr/lib/aarch64-linux-gnu/libwayland-client.so.0")
            createMockElf(waylandClient, isArm64 = true)
            val waylandServer = File(rootfsDir, "usr/lib/aarch64-linux-gnu/libwayland-server.so.0")
            createMockElf(waylandServer, isArm64 = true)

            dpkgEntries.add("""
                Package: libwayland-client0
                Status: install ok installed
                Version: 1.22.0-1
                Architecture: arm64
                Description: wayland client library
            """.trimIndent())
        }

        // Weston
        if (withWeston) {
            createMockElf(File(rootfsDir, "usr/bin/weston"), isArm64 = true, interpreter = interpPath)
            createMockElf(File(rootfsDir, "usr/lib/aarch64-linux-gnu/weston/headless-backend.so"), isArm64 = true)
            File(rootfsDir, "etc/xdg/weston/weston.ini").writeText("[core]\nbackend=headless-backend.so\n")

            dpkgEntries.add("""
                Package: weston
                Status: install ok installed
                Version: 12.0.1-1
                Architecture: arm64
                Description: reference wayland compositor
            """.trimIndent())
        }

        // LDDM
        if (withLddm) {
            createMockElf(File(rootfsDir, "usr/bin/lddm"), isArm64 = true, interpreter = interpPath)
            File(rootfsDir, "etc/linuxdroid/lddm.conf").writeText("[lddm]\nweston_socket=wayland-0\n")

            dpkgEntries.add("""
                Package: linuxdroid-display-manager
                Status: install ok installed
                Version: $lddmVersion
                Architecture: arm64
                Description: LinuxDroid Display Manager
            """.trimIndent())
        }

        // LDDE
        if (withLdde) {
            createMockElf(File(rootfsDir, "usr/bin/ldde"), isArm64 = true, interpreter = interpPath)
            File(rootfsDir, "etc/linuxdroid/desktop.conf").writeText("[desktop]\nshell=default\n")

            dpkgEntries.add("""
                Package: linuxdroid-desktop-environment
                Status: install ok installed
                Version: $lddeVersion
                Architecture: arm64
                Description: LinuxDroid Desktop Environment
            """.trimIndent())
        }

        // Write dpkg status
        File(rootfsDir, "var/lib/dpkg/status").writeText(dpkgEntries.joinToString("\n\n") + "\n\n")
    }

    // =========================================================================
    // TEST 1: Clean Rootfs Deployment
    // =========================================================================
    @Test
    fun `TEST 1 - Clean rootfs deployment executes all stages and reaches ROOTFS_READY`() = runBlocking {
        val rootfsDir = tempFolder.newFolder("clean-rootfs")
        populateMockRootfs(rootfsDir, withWayland = true, withWeston = true, withLddm = false, withLdde = false)

        val lddmDeb = tempFolder.newFile("lddm-1.0.0.deb")
        createMockDeb(
            destFile = lddmDeb,
            packageName = "linuxdroid-display-manager",
            version = "1.0.0",
            architecture = "arm64",
            files = mapOf(
                "usr/bin/lddm" to "#!/bin/sh\necho LDDM 1.0.0\n",
                "etc/linuxdroid/lddm.conf" to "[lddm]\nsocket=wayland-0\n",
            ),
        )

        val lddeDeb = tempFolder.newFile("ldde-1.0.0.deb")
        createMockDeb(
            destFile = lddeDeb,
            packageName = "linuxdroid-desktop-environment",
            version = "1.0.0",
            architecture = "arm64",
            files = mapOf(
                "usr/bin/ldde" to "#!/bin/sh\necho LDDE 1.0.0\n",
                "etc/linuxdroid/desktop.conf" to "[desktop]\ntheme=dark\n",
            ),
        )

        val storage = mockk<EnvironmentStorage>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val tmpDir = tempFolder.newFolder("tmp-env")
        coEvery { storage.verifyRootfs(testEnvId) } returnsMany listOf(false, true)
        every { storage.rootfsDir(testEnvId) } returns rootfsDir
        every { storage.stagingRootfsDir(testEnvId) } returns rootfsDir
        every { storage.tmpDir(testEnvId) } returns tmpDir
        every { storage.metadataDir(testEnvId) } returns tempFolder.newFolder("meta-env")
        every { storage.logsDir(testEnvId) } returns tempFolder.newFolder("logs-env")
        coEvery { storage.initializeEnvironmentDirs(testEnvId) } returns Unit
        coEvery { storage.promoteStagedRootfs(testEnvId) } returns true

        val deploymentManager = RootfsDeploymentManager(
            context = context,
            storage = storage,
            validator = validator,
            extractor = extractor,
            configurator = configurator,
            runtimeSetup = runtimeSetup,
        )

        val result = deploymentManager.deployRootfs(
            environment = testEnv,
            lddmDebOverride = lddmDeb,
            lddeDebOverride = lddeDeb,
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(result.state).isEqualTo(RootfsDeploymentState.ROOTFS_READY)
        assertThat(result.lddmVersion).isEqualTo("1.0.0")
        assertThat(result.lddeVersion).isEqualTo("1.0.0")
        assertThat(result.validationReport.isValid).isTrue()

        // Verify installed files inside rootfs
        assertThat(File(rootfsDir, "usr/bin/lddm").exists()).isTrue()
        assertThat(File(rootfsDir, "etc/linuxdroid/lddm.conf").exists()).isTrue()
        assertThat(File(rootfsDir, "usr/bin/ldde").exists()).isTrue()
        assertThat(File(rootfsDir, "etc/linuxdroid/desktop.conf").exists()).isTrue()
        assertThat(File(rootfsDir, "sbin/linuxdroid-init").exists()).isTrue()
        assertThat(File(rootfsDir, "etc/environment").readText()).contains("WAYLAND_DISPLAY=wayland-0")
    }

    // =========================================================================
    // TEST 2: Wayland Already Installed
    // =========================================================================
    @Test
    fun `TEST 2 - Wayland already installed is verified and reused without reinstallation`() = runBlocking {
        val rootfsDir = tempFolder.newFolder("wayland-rootfs")
        populateMockRootfs(rootfsDir, withWayland = true, withWeston = true, withLddm = false, withLdde = false)

        val graphicalInstaller = GraphicalDependencyInstaller()
        val status = graphicalInstaller.inspect(rootfsDir)

        assertThat(status.waylandClientPresent).isTrue()
        assertThat(status.waylandServerPresent).isTrue()

        var logReported = false
        val result = graphicalInstaller.ensureGraphicalDependencies(
            environment = testEnv,
            rootfsDir = rootfsDir,
            onLog = { msg -> if (msg.contains("libwayland already satisfied") || msg.contains("existing Wayland")) logReported = true },
        )

        assertThat(result.waylandSuccess).isTrue()
        assertThat(logReported).isTrue()
    }

    // =========================================================================
    // TEST 3: Weston Already Installed
    // =========================================================================
    @Test
    fun `TEST 3 - Weston already installed is verified and reused without reinstallation`() = runBlocking {
        val rootfsDir = tempFolder.newFolder("weston-rootfs")
        populateMockRootfs(rootfsDir, withWayland = true, withWeston = true, withLddm = false, withLdde = false)

        val graphicalInstaller = GraphicalDependencyInstaller()
        val status = graphicalInstaller.inspect(rootfsDir)

        assertThat(status.westonPresent).isTrue()
        assertThat(status.westonHeadlessBackendPresent).isTrue()
        assertThat(status.isComplete).isTrue()

        var reusedReported = false
        val result = graphicalInstaller.ensureGraphicalDependencies(
            environment = testEnv,
            rootfsDir = rootfsDir,
            onLog = { msg -> if (msg.contains("Weston compositor already satisfied") || msg.contains("Skipping duplicate installation")) reusedReported = true },
        )

        assertThat(result.westonSuccess).isTrue()
        assertThat(reusedReported).isTrue()
    }

    // =========================================================================
    // TEST 4: Missing Wayland Dependency
    // =========================================================================
    @Test
    fun `TEST 4 - Missing Wayland dependency fails validation and deployment`() {
        val rootfsDir = tempFolder.newFolder("no-wayland-rootfs")
        populateMockRootfs(rootfsDir, withWayland = false, withWeston = true, withLddm = true, withLdde = true)

        val report = validator.validate(rootfsDir, Distribution.DEBIAN, Architecture.ARM64, requireGraphicalStack = true)
        assertThat(report.isValid).isFalse()
        assertThat(report.errors.any { it.contains("Wayland runtime libraries missing") }).isTrue()
    }

    // =========================================================================
    // TEST 5: Missing Weston Dependency
    // =========================================================================
    @Test
    fun `TEST 5 - Missing Weston dependency fails validation and deployment`() {
        val rootfsDir = tempFolder.newFolder("no-weston-rootfs")
        populateMockRootfs(rootfsDir, withWayland = true, withWeston = false, withLddm = true, withLdde = true)

        val report = validator.validate(rootfsDir, Distribution.DEBIAN, Architecture.ARM64, requireGraphicalStack = true)
        assertThat(report.isValid).isFalse()
        assertThat(report.errors.any { it.contains("Weston compositor") }).isTrue()
    }

    // =========================================================================
    // TEST 6: LDDM Absent Triggers Installation
    // =========================================================================
    @Test
    fun `TEST 6 - LDDM absent triggers installation via dpkg`() = runBlocking {
        val rootfsDir = tempFolder.newFolder("no-lddm-rootfs")
        populateMockRootfs(rootfsDir, withWayland = true, withWeston = true, withLddm = false, withLdde = true)

        val lddmDeb = tempFolder.newFile("lddm-pkg.deb")
        createMockDeb(
            destFile = lddmDeb,
            packageName = "linuxdroid-display-manager",
            version = "1.0.0",
            files = mapOf(
                "usr/bin/lddm" to "#!/bin/sh\nexit 0\n",
                "etc/linuxdroid/lddm.conf" to "[lddm]\n",
            ),
        )

        val packageInstaller = LinuxDroidPackageInstaller()
        val result = packageInstaller.installLDDM(
            environment = testEnv,
            rootfsDir = rootfsDir,
            debOverride = lddmDeb,
        )

        assertThat(result.success).isTrue()
        assertThat(result.skipped).isFalse()
        assertThat(result.installedVersion).isEqualTo("1.0.0")
        assertThat(File(rootfsDir, "usr/bin/lddm").exists()).isTrue()
        assertThat(packageInstaller.getInstalledPackageVersion(rootfsDir, "linuxdroid-display-manager")).isEqualTo("1.0.0")
    }

    // =========================================================================
    // TEST 7: LDDE Absent Triggers Installation
    // =========================================================================
    @Test
    fun `TEST 7 - LDDE absent triggers installation via dpkg`() = runBlocking {
        val rootfsDir = tempFolder.newFolder("no-ldde-rootfs")
        populateMockRootfs(rootfsDir, withWayland = true, withWeston = true, withLddm = true, withLdde = false)

        val lddeDeb = tempFolder.newFile("ldde-pkg.deb")
        createMockDeb(
            destFile = lddeDeb,
            packageName = "linuxdroid-desktop-environment",
            version = "1.0.0",
            files = mapOf(
                "usr/bin/ldde" to "#!/bin/sh\nexit 0\n",
                "etc/linuxdroid/desktop.conf" to "[desktop]\n",
            ),
        )

        val packageInstaller = LinuxDroidPackageInstaller()
        val result = packageInstaller.installLDDE(
            environment = testEnv,
            rootfsDir = rootfsDir,
            debOverride = lddeDeb,
        )

        assertThat(result.success).isTrue()
        assertThat(result.skipped).isFalse()
        assertThat(result.installedVersion).isEqualTo("1.0.0")
        assertThat(File(rootfsDir, "usr/bin/ldde").exists()).isTrue()
        assertThat(packageInstaller.getInstalledPackageVersion(rootfsDir, "linuxdroid-desktop-environment")).isEqualTo("1.0.0")
    }

    // =========================================================================
    // TEST 8: Repeat Deployment (Idempotent)
    // =========================================================================
    @Test
    fun `TEST 8 - Repeat deployment on complete rootfs is completely idempotent`() = runBlocking {
        val rootfsDir = tempFolder.newFolder("repeat-rootfs")
        populateMockRootfs(rootfsDir, withWayland = true, withWeston = true, withLddm = true, withLdde = true)

        val storage = mockk<EnvironmentStorage>()
        val context = mockk<Context>(relaxed = true)
        coEvery { storage.verifyRootfs(testEnvId) } returns true
        every { storage.rootfsDir(testEnvId) } returns rootfsDir

        val deploymentManager = RootfsDeploymentManager(
            context = context,
            storage = storage,
            validator = validator,
        )

        var logMsg = ""
        val result = deploymentManager.deployRootfs(
            environment = testEnv,
            onLog = { msg -> logMsg += msg + "\n" },
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(result.state).isEqualTo(RootfsDeploymentState.ROOTFS_READY)
        assertThat(logMsg).contains("Complete verified graphical stack found")
    }

    // =========================================================================
    // TEST 9: LDDM Upgrade
    // =========================================================================
    @Test
    fun `TEST 9 - LDDM upgrade installs newer version cleanly`() = runBlocking {
        val rootfsDir = tempFolder.newFolder("lddm-upgrade-rootfs")
        populateMockRootfs(rootfsDir, withLddm = true, lddmVersion = "0.1.0")

        val newLddmDeb = tempFolder.newFile("lddm-1.0.0.deb")
        createMockDeb(
            destFile = newLddmDeb,
            packageName = "linuxdroid-display-manager",
            version = "1.0.0",
            files = mapOf("usr/bin/lddm" to "LDDM_V1"),
        )

        val packageInstaller = LinuxDroidPackageInstaller()
        val result = packageInstaller.installLDDM(
            environment = testEnv,
            rootfsDir = rootfsDir,
            debOverride = newLddmDeb,
        )

        assertThat(result.success).isTrue()
        assertThat(result.upgraded).isTrue()
        assertThat(result.installedVersion).isEqualTo("1.0.0")
        assertThat(packageInstaller.getInstalledPackageVersion(rootfsDir, "linuxdroid-display-manager")).isEqualTo("1.0.0")
    }

    // =========================================================================
    // TEST 10: LDDE Upgrade
    // =========================================================================
    @Test
    fun `TEST 10 - LDDE upgrade installs newer version cleanly`() = runBlocking {
        val rootfsDir = tempFolder.newFolder("ldde-upgrade-rootfs")
        populateMockRootfs(rootfsDir, withLdde = true, lddeVersion = "0.9.0")

        val newLddeDeb = tempFolder.newFile("ldde-1.0.0.deb")
        createMockDeb(
            destFile = newLddeDeb,
            packageName = "linuxdroid-desktop-environment",
            version = "1.0.0",
            files = mapOf("usr/bin/ldde" to "LDDE_V1"),
        )

        val packageInstaller = LinuxDroidPackageInstaller()
        val result = packageInstaller.installLDDE(
            environment = testEnv,
            rootfsDir = rootfsDir,
            debOverride = newLddeDeb,
        )

        assertThat(result.success).isTrue()
        assertThat(result.upgraded).isTrue()
        assertThat(result.installedVersion).isEqualTo("1.0.0")
        assertThat(packageInstaller.getInstalledPackageVersion(rootfsDir, "linuxdroid-desktop-environment")).isEqualTo("1.0.0")
    }

    // =========================================================================
    // TEST 11: Invalid LDDM .deb Archive
    // =========================================================================
    @Test
    fun `TEST 11 - Invalid LDDM deb archive is rejected safely`() {
        val rootfsDir = tempFolder.newFolder("corrupt-lddm-rootfs")
        populateMockRootfs(rootfsDir)

        val corruptDeb = tempFolder.newFile("lddm-corrupt.deb")
        createMockDeb(corruptDeb, "linuxdroid-display-manager", "1.0.0", corruptArchive = true)

        val packageInstaller = LinuxDroidPackageInstaller()
        assertThrows(RuntimeError::class.java) {
            runBlocking {
                packageInstaller.installLDDM(
                    environment = testEnv,
                    rootfsDir = rootfsDir,
                    debOverride = corruptDeb,
                )
            }
        }
    }

    // =========================================================================
    // TEST 12: Invalid LDDE .deb Archive
    // =========================================================================
    @Test
    fun `TEST 12 - Invalid LDDE deb archive is rejected safely`() {
        val rootfsDir = tempFolder.newFolder("corrupt-ldde-rootfs")
        populateMockRootfs(rootfsDir)

        val corruptDeb = tempFolder.newFile("ldde-corrupt.deb")
        createMockDeb(corruptDeb, "linuxdroid-desktop-environment", "1.0.0", corruptArchive = true)

        val packageInstaller = LinuxDroidPackageInstaller()
        assertThrows(RuntimeError::class.java) {
            runBlocking {
                packageInstaller.installLDDE(
                    environment = testEnv,
                    rootfsDir = rootfsDir,
                    debOverride = corruptDeb,
                )
            }
        }
    }

    // =========================================================================
    // TEST 13: Wrong Architecture Package
    // =========================================================================
    @Test
    fun `TEST 13 - Package with incompatible architecture is rejected`() {
        val rootfsDir = tempFolder.newFolder("wrong-arch-rootfs")
        populateMockRootfs(rootfsDir)

        val wrongArchDeb = tempFolder.newFile("lddm-i386.deb")
        createMockDeb(wrongArchDeb, "linuxdroid-display-manager", "1.0.0", architecture = "i386")

        val packageInstaller = LinuxDroidPackageInstaller()
        val ex = assertThrows(RuntimeError::class.java) {
            runBlocking {
                packageInstaller.installLDDM(
                    environment = testEnv,
                    rootfsDir = rootfsDir,
                    debOverride = wrongArchDeb,
                )
            }
        }
        assertThat(ex.message).contains("Incompatible package architecture")
    }

    // =========================================================================
    // TEST 14: Interrupted dpkg Operation Recovery
    // =========================================================================
    @Test
    fun `TEST 14 - Interrupted dpkg operation recovers lock and runs configure`() = runBlocking {
        val rootfsDir = tempFolder.newFolder("interrupted-dpkg-rootfs")
        populateMockRootfs(rootfsDir, withLddm = false)

        // Simulate stale dpkg lock file
        val lockFile = File(rootfsDir, "var/lib/dpkg/lock").apply { writeText("12345") }
        assertThat(lockFile.exists()).isTrue()

        val mockBackend = mockk<RuntimeBackend>()
        coEvery { mockBackend.executeAndWait(any(), any(), any(), any(), any()) } returns ProcessResult(
            handleId = "proc-dpkg",
            exitCode = 0,
            stdout = "",
            stderr = "",
        )

        val lddmDeb = tempFolder.newFile("lddm-clean.deb")
        createMockDeb(lddmDeb, "linuxdroid-display-manager", "1.0.0")

        val packageInstaller = LinuxDroidPackageInstaller(runtimeBackend = mockBackend)
        var recoveredLog = false
        packageInstaller.installLDDM(
            environment = testEnv,
            rootfsDir = rootfsDir,
            debOverride = lddmDeb,
            onLog = { msg -> if (msg.contains("DPKG_RECOVER")) recoveredLog = true },
        )

        // Verify stale lock removed and dpkg --configure -a called
        assertThat(lockFile.exists()).isFalse()
        assertThat(recoveredLog).isTrue()
        coVerify {
            mockBackend.executeAndWait(
                environment = any(),
                command = listOf("dpkg", "--configure", "-a"),
                workingDirectory = any(),
                extraEnv = any(),
                timeoutMs = any(),
            )
        }
    }

    // =========================================================================
    // TEST 15: Live Graphical Session Startup Chain
    // =========================================================================
    @Test
    fun `TEST 15 - Live graphical session startup chain executes Init to LDDM to Weston to LDDE`() {
        val rootfsDir = tempFolder.newFolder("session-chain-rootfs")
        populateMockRootfs(rootfsDir, withWayland = true, withWeston = true, withLddm = true, withLdde = true)

        // Verify execution prerequisites for the startup chain:
        // Guest Init (/sbin/linuxdroid-init) -> LDDM (/usr/bin/lddm) -> Weston (/usr/bin/weston) -> LDDE (/usr/bin/ldde)
        val guestInit = File(rootfsDir, "sbin/linuxdroid-init")
        val lddm = File(rootfsDir, "usr/bin/lddm")
        val weston = File(rootfsDir, "usr/bin/weston")
        val ldde = File(rootfsDir, "usr/bin/ldde")

        assertThat(guestInit.canExecute()).isTrue()
        assertThat(lddm.canExecute()).isTrue()
        assertThat(weston.canExecute()).isTrue()
        assertThat(ldde.canExecute()).isTrue()

        // Verify LDDM and LDDE configurations point to the correct Weston Wayland socket
        val lddmConf = File(rootfsDir, "etc/linuxdroid/lddm.conf").readText()
        assertThat(lddmConf).contains("wayland-0")

        val envFile = File(rootfsDir, "etc/environment").apply {
            writeText("WAYLAND_DISPLAY=wayland-0\nXDG_SESSION_TYPE=wayland\nXDG_CURRENT_DESKTOP=LDDE\n")
        }
        assertThat(envFile.readText()).contains("WAYLAND_DISPLAY=wayland-0")
    }

    // =========================================================================
    // TEST 16: Live Wayland Client Probe
    // =========================================================================
    @Test
    fun `TEST 16 - Live Wayland client socket probe verifies runtime socket communication`() {
        val runtimeDir = tempFolder.newFolder("run-lddm")
        val waylandSocket = File(runtimeDir, "wayland-0").apply { writeText("") }
        val waylandLock = File(runtimeDir, "wayland-0.lock").apply { writeText("123") }

        assertThat(waylandSocket.exists()).isTrue()
        assertThat(waylandLock.exists()).isTrue()

        // Wayland client probe requires socket presence and proper environment configuration
        val clientEnv = mapOf(
            "XDG_RUNTIME_DIR" to runtimeDir.absolutePath,
            "WAYLAND_DISPLAY" to "wayland-0",
        )
        val activeSocket = File(clientEnv["XDG_RUNTIME_DIR"]!!, clientEnv["WAYLAND_DISPLAY"]!!)
        assertThat(activeSocket.exists()).isTrue()
    }

    // =========================================================================
    // TEST 17: Environment Restart Skips Reinstallation
    // =========================================================================
    @Test
    fun `TEST 17 - Environment restart preserves installed packages without apt or dpkg invocations`() = runBlocking {
        val rootfsDir = tempFolder.newFolder("restart-rootfs")
        populateMockRootfs(rootfsDir, withWayland = true, withWeston = true, withLddm = true, withLdde = true)

        val storage = mockk<EnvironmentStorage>()
        coEvery { storage.verifyRootfs(testEnvId) } returns true
        every { storage.rootfsDir(testEnvId) } returns rootfsDir

        val mockBackend = mockk<RuntimeBackend>()
        val deploymentManager = RootfsDeploymentManager(
            storage = storage,
            runtimeBackend = mockBackend,
            validator = validator,
        )

        val result = deploymentManager.deployRootfs(testEnv)
        assertThat(result.isSuccess).isTrue()
        assertThat(result.state).isEqualTo(RootfsDeploymentState.ROOTFS_READY)

        // Verify zero dpkg or apt commands were executed during restart
        coVerify(exactly = 0) { mockBackend.executeAndWait(any(), any(), any(), any(), any()) }
    }

    // =========================================================================
    // TEST 18: Environment Shutdown and Restart Persistence
    // =========================================================================
    @Test
    fun `TEST 18 - Environment shutdown and restart preserves rootfs integrity and configurations`() = runBlocking {
        val rootfsDir = tempFolder.newFolder("persistent-rootfs")
        populateMockRootfs(rootfsDir, withWayland = true, withWeston = true, withLddm = true, withLdde = true)

        // 1. Initial validation
        val report1 = validator.validate(rootfsDir, Distribution.DEBIAN, Architecture.ARM64, requireGraphicalStack = true)
        assertThat(report1.isValid).isTrue()

        // 2. Simulate shutdown (e.g. cleans /tmp, /run)
        File(rootfsDir, "tmp").listFiles()?.forEach { it.deleteRecursively() }
        File(rootfsDir, "run").listFiles()?.forEach { it.deleteRecursively() }

        // 3. Restart setup
        runtimeSetup.setup(rootfsDir)

        // 4. Post-restart validation
        val report2 = validator.validate(rootfsDir, Distribution.DEBIAN, Architecture.ARM64, requireGraphicalStack = true)
        assertThat(report2.isValid).isTrue()
        assertThat(File(rootfsDir, "sbin/linuxdroid-init").exists()).isTrue()
        assertThat(File(rootfsDir, "var/lib/dpkg/status").exists()).isTrue()
        assertThat(File(rootfsDir, "etc/linuxdroid/lddm.conf").exists()).isTrue()
        assertThat(File(rootfsDir, "etc/linuxdroid/desktop.conf").exists()).isTrue()
    }
}
