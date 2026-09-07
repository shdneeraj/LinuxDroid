package com.linuxdroid.linux.bootstrap

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.model.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class RootfsBootstrapperTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun `bootstrap skips when rootfs is already valid`() {
        runBlocking {
            val context = mockk<Context>(relaxed = true)
            val storage = mockk<EnvironmentStorage>()
            val validator = mockk<RootfsValidator>()
            val envId = EnvironmentId("test-env")
            val env = Environment(
                metadata = EnvironmentMetadata(
                    id = envId,
                    name = "Test",
                    distribution = Distribution.DEBIAN,
                    architecture = Architecture.ARM64,
                ),
                rootfsPath = "/dummy",
                metadataPath = "/dummy",
            )

            val dummyRootfs = tempFolder.newFolder("dummy-rootfs")
            coEvery { storage.verifyRootfs(envId) } returns true
            every { storage.rootfsDir(envId) } returns dummyRootfs
            every { validator.validate(dummyRootfs, Distribution.DEBIAN, Architecture.ARM64, any()) } returns RootfsValidationReport(
                isValid = true,
                distribution = Distribution.DEBIAN,
                architecture = Architecture.ARM64,
                checks = emptyList(),
                errors = emptyList(),
            )

            val bootstrapper = RootfsBootstrapper(context, storage, validator = validator)
            var progressReported = false
            bootstrapper.bootstrapRootfs(env, onProgress = { p, msg ->
                if (p == 1.0f && msg.contains("already installed")) {
                    progressReported = true
                }
            })

            assertThat(progressReported).isTrue()
        }
    }

    @Test
    fun `tar xz stream compression and decompression works without external binaries`() {
        val testTarXz = tempFolder.newFile("test.tar.xz")
        val extractDir = tempFolder.newFolder("extracted")

        // 1. Create a real .tar.xz archive in-memory/stream
        FileOutputStream(testTarXz).use { fos ->
            XZCompressorOutputStream(fos).use { xzos ->
                TarArchiveOutputStream(xzos).use { tarOut ->
                    val binShData = "#!/bin/sh\necho hello".toByteArray()
                    val entry = TarArchiveEntry("bin/sh").apply {
                        size = binShData.size.toLong()
                        mode = 0b111101101 // rwxr-xr-x (0755)
                    }
                    tarOut.putArchiveEntry(entry)
                    tarOut.write(binShData)
                    tarOut.closeArchiveEntry()

                    val etcData = "nameserver 8.8.8.8\n".toByteArray()
                    val etcEntry = TarArchiveEntry("etc/resolv.conf").apply {
                        size = etcData.size.toLong()
                        mode = 0b110100100 // rw-r--r-- (0644)
                    }
                    tarOut.putArchiveEntry(etcEntry)
                    tarOut.write(etcData)
                    tarOut.closeArchiveEntry()
                }
            }
        }

        // 2. Decompress and extract using pure streaming decompressor
        BufferedInputStream(FileInputStream(testTarXz)).use { bis ->
            XZCompressorInputStream(bis).use { xzin ->
                TarArchiveInputStream(xzin).use { tarIn ->
                    var entry = tarIn.nextEntry
                    while (entry != null) {
                        val targetFile = File(extractDir, entry.name)
                        targetFile.parentFile?.mkdirs()
                        targetFile.outputStream().use { out ->
                            tarIn.copyTo(out)
                        }
                        entry = tarIn.nextEntry
                    }
                }
            }
        }

        // 3. Verify extracted contents
        val extractedSh = File(extractDir, "bin/sh")
        val extractedEtc = File(extractDir, "etc/resolv.conf")
        assertThat(extractedSh.exists()).isTrue()
        assertThat(extractedSh.readText()).isEqualTo("#!/bin/sh\necho hello")
        assertThat(extractedEtc.exists()).isTrue()
        assertThat(extractedEtc.readText()).isEqualTo("nameserver 8.8.8.8\n")
    }

    @Test
    fun `configureStagingRootfs safely overwrites Debian dangling resolv_conf symlink`() {
        val rootfsDir = tempFolder.newFolder("debian-staging-rootfs")
        val etcDir = File(rootfsDir, "etc").apply { mkdirs() }
        val resolvConf = File(etcDir, "resolv.conf")

        // Simulate Debian rootfs where /etc/resolv.conf is a symlink to ../run/systemd/resolve/stub-resolv.conf
        // and /run/systemd/resolve does not yet exist in staging
        java.nio.file.Files.createSymbolicLink(
            resolvConf.toPath(),
            java.nio.file.Paths.get("../run/systemd/resolve/stub-resolv.conf")
        )
        assertThat(java.nio.file.Files.isSymbolicLink(resolvConf.toPath())).isTrue()
        // Verify it is indeed dangling (target does not exist)
        assertThat(resolvConf.exists()).isFalse()

        // Call configureStagingRootfs directly
        val context = mockk<Context>(relaxed = true)
        val storage = mockk<EnvironmentStorage>()
        val bootstrapper = RootfsBootstrapper(context, storage)

        val debianDef = DistributionCatalog.getDefinition(Distribution.DEBIAN, Architecture.ARM64)
        bootstrapper.configureStagingRootfs(rootfsDir, debianDef)

        // Verify /etc/resolv.conf is now a regular readable file, NOT a dangling symlink
        assertThat(java.nio.file.Files.isSymbolicLink(resolvConf.toPath())).isFalse()
        assertThat(resolvConf.exists()).isTrue()
        assertThat(resolvConf.readText()).contains("nameserver 8.8.8.8")

        // Verify other configured files
        val hostnameFile = File(rootfsDir, "etc/hostname")
        assertThat(hostnameFile.exists()).isTrue()
        assertThat(hostnameFile.readText()).contains("linuxdroid")

        val aptSources = File(rootfsDir, "etc/apt/sources.list")
        assertThat(aptSources.exists()).isTrue()
        assertThat(aptSources.readText()).contains("debian")

        // Verify /sbin/linuxdroid-init and /etc/linuxdroid/init.d created
        val initFile = File(rootfsDir, "sbin/linuxdroid-init")
        assertThat(initFile.exists()).isTrue()
        assertThat(initFile.readText()).contains("[GUEST-INIT]")
        assertThat(initFile.canRead()).isTrue()
        assertThat(File(rootfsDir, "etc/linuxdroid/init.d").isDirectory).isTrue()
    }

    @Test
    fun `extractArchive properly extracts symlinks and regular files over symlinks`() {
        val testTarXz = tempFolder.newFile("symlink_test.tar.xz")
        val extractDir = tempFolder.newFolder("symlink_extracted")

        // 1. Create a tar.xz containing a symlink and a regular file
        FileOutputStream(testTarXz).use { fos ->
            XZCompressorOutputStream(fos).use { xzos ->
                TarArchiveOutputStream(xzos).use { tarOut ->
                    // Symlink /bin/sh -> /usr/bin/bash
                    val symlinkEntry = TarArchiveEntry("bin/sh", TarArchiveEntry.LF_SYMLINK).apply {
                        linkName = "/usr/bin/bash"
                    }
                    tarOut.putArchiveEntry(symlinkEntry)
                    tarOut.closeArchiveEntry()

                    // Regular file /usr/bin/bash
                    val bashData = "#!/bin/bash\necho bash".toByteArray()
                    val bashEntry = TarArchiveEntry("usr/bin/bash").apply {
                        size = bashData.size.toLong()
                        mode = 0b111101101 // 0755
                    }
                    tarOut.putArchiveEntry(bashEntry)
                    tarOut.write(bashData)
                    tarOut.closeArchiveEntry()
                }
            }
        }

        // 2. Extract using extractArchive
        val context = mockk<Context>(relaxed = true)
        val storage = mockk<EnvironmentStorage>()
        val bootstrapper = RootfsBootstrapper(context, storage)

        runBlocking {
            bootstrapper.extractArchive(
                tarball = testTarXz,
                destDir = extractDir,
                format = ArchiveFormat.TAR_XZ,
                stripComponents = 0,
                onProgress = { _: Float, _: String -> }
            )
        }

        val extractedSh = File(extractDir, "bin/sh")
        val extractedBash = File(extractDir, "usr/bin/bash")

        assertThat(java.nio.file.Files.isSymbolicLink(extractedSh.toPath())).isTrue()
        assertThat(extractedBash.exists()).isTrue()
        assertThat(extractedBash.canExecute()).isTrue()
    }

    @Test
    fun `extractArchive blocks path traversal attacks in tarball entries`() {
        val testTarXz = tempFolder.newFile("traversal_test.tar.xz")
        val extractDir = tempFolder.newFolder("safe_extracted")

        FileOutputStream(testTarXz).use { fos ->
            XZCompressorOutputStream(fos).use { xzos ->
                TarArchiveOutputStream(xzos).use { tarOut ->
                    val evilData = "malicious".toByteArray()
                    val entry = TarArchiveEntry("../../evil.txt").apply {
                        size = evilData.size.toLong()
                    }
                    tarOut.putArchiveEntry(entry)
                    tarOut.write(evilData)
                    tarOut.closeArchiveEntry()
                }
            }
        }

        val context = mockk<Context>(relaxed = true)
        val storage = mockk<EnvironmentStorage>()
        val bootstrapper = RootfsBootstrapper(context, storage)

        org.junit.Assert.assertThrows(FilesystemError::class.java) {
            runBlocking {
                bootstrapper.extractArchive(
                    tarball = testTarXz,
                    destDir = extractDir,
                    format = ArchiveFormat.TAR_XZ,
                    stripComponents = 0,
                    onProgress = { _, _ -> }
                )
            }
        }
    }
}
