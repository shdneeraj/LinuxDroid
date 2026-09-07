package com.linuxdroid.linux.bootstrap

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.model.Architecture
import com.linuxdroid.core.model.Distribution
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths

class RootfsValidatorTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private val validator = RootfsValidator()

    /**
     * Helper to synthesize a minimal mock ELF64 AArch64 executable with optional PT_INTERP program header.
     */
    private fun createMockElf(
        destFile: File,
        isArm64: Boolean = true,
        interpreter: String? = null,
    ) {
        destFile.parentFile?.mkdirs()
        FileOutputStream(destFile).use { fos ->
            val header = ByteArray(64)
            // ELF Magic
            header[0] = 0x7F.toByte()
            header[1] = 'E'.code.toByte()
            header[2] = 'L'.code.toByte()
            header[3] = 'F'.code.toByte()
            // 64-bit (2), Little Endian (1), Version 1
            header[4] = 0x02
            header[5] = 0x01
            header[6] = 0x01
            // e_type: ET_DYN (3)
            header[16] = 0x03
            header[17] = 0x00
            // e_machine: EM_AARCH64 (0xB7 = 183) or EM_X86_64 (0x3E = 62)
            header[18] = if (isArm64) 0xB7.toByte() else 0x3E.toByte()
            header[19] = 0x00
            // Entry point
            header[24] = 0x00
            header[25] = 0x10

            if (interpreter != null) {
                // e_phoff = 64
                header[32] = 64
                // e_phentsize = 56
                header[54] = 56
                // e_phnum = 1
                header[56] = 1

                fos.write(header)

                // Phdr (56 bytes)
                val phdr = ByteArray(56)
                // p_type: PT_INTERP (3)
                phdr[0] = 3
                // p_offset: 120 (64 + 56)
                phdr[8] = 120
                // p_filesz: interpreter length + 1 (null terminator)
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

    private fun populateStandardMockRootfs(
        rootfsDir: File,
        distro: Distribution,
        interpPath: String = "/lib/ld-linux-aarch64.so.1",
    ) {
        listOf("etc/apt", "usr/bin", "bin", "lib", "tmp", "dev", "proc", "sys").forEach {
            File(rootfsDir, it).mkdirs()
        }

        // Create standard dynamic interpreter
        val interpFile = File(rootfsDir, interpPath.removePrefix("/"))
        createMockElf(interpFile, isArm64 = true)

        // Create required binaries with interpreter
        createMockElf(File(rootfsDir, "usr/bin/bash"), isArm64 = true, interpreter = interpPath)
        createMockElf(File(rootfsDir, "usr/bin/env"), isArm64 = true, interpreter = interpPath)
        createMockElf(File(rootfsDir, "usr/bin/true"), isArm64 = true, interpreter = interpPath)

        // Merged-usr symlink for /bin/sh -> /usr/bin/bash
        val binSh = File(rootfsDir, "bin/sh")
        Files.createSymbolicLink(binSh.toPath(), Paths.get("/usr/bin/bash"))

        // Apt sources
        File(rootfsDir, "etc/apt/sources.list").writeText("deb http://deb.debian.org/debian trixie main\n")

        // Guest Init (/sbin/linuxdroid-init)
        val sbin = File(rootfsDir, "sbin").apply { mkdirs() }
        val initFile = File(sbin, "linuxdroid-init")
        initFile.writeText("#!/bin/sh\nexec \"$@\"\n")
        initFile.setExecutable(true, false)
        initFile.setReadable(true, false)
    }

    @Test
    fun `validate passes for valid Debian rootfs`() {
        val rootfs = tempFolder.newFolder("debian-rootfs")
        populateStandardMockRootfs(rootfs, Distribution.DEBIAN)

        val report = validator.validate(rootfs, Distribution.DEBIAN, Architecture.ARM64)
        assertThat(report.isValid).isTrue()
        assertThat(report.errors).isEmpty()
    }

    @Test
    fun `validate passes for valid Ubuntu rootfs`() {
        val rootfs = tempFolder.newFolder("ubuntu-rootfs")
        populateStandardMockRootfs(rootfs, Distribution.UBUNTU)

        val report = validator.validate(rootfs, Distribution.UBUNTU, Architecture.ARM64)
        assertThat(report.isValid).isTrue()
        assertThat(report.errors).isEmpty()
    }

    @Test
    fun `validate passes for valid Kali rootfs`() {
        val rootfs = tempFolder.newFolder("kali-rootfs")
        populateStandardMockRootfs(rootfs, Distribution.KALI)

        val report = validator.validate(rootfs, Distribution.KALI, Architecture.ARM64)
        assertThat(report.isValid).isTrue()
        assertThat(report.errors).isEmpty()
    }

    @Test
    fun `validate detects missing shell`() {
        val rootfs = tempFolder.newFolder("no-shell-rootfs")
        populateStandardMockRootfs(rootfs, Distribution.DEBIAN)
        File(rootfs, "usr/bin/bash").delete()
        File(rootfs, "bin/sh").delete()

        val report = validator.validate(rootfs, Distribution.DEBIAN, Architecture.ARM64)
        assertThat(report.isValid).isFalse()
        assertThat(report.errors.any { it.contains("shell") }).isTrue()
    }

    @Test
    fun `validate detects missing dynamic interpreter`() {
        val rootfs = tempFolder.newFolder("no-interp-rootfs")
        populateStandardMockRootfs(rootfs, Distribution.DEBIAN, interpPath = "/lib/ld-linux-aarch64.so.1")
        File(rootfs, "lib/ld-linux-aarch64.so.1").delete()

        val report = validator.validate(rootfs, Distribution.DEBIAN, Architecture.ARM64)
        assertThat(report.isValid).isFalse()
        assertThat(report.errors.any { it.contains("Dynamic interpreter") && it.contains("missing") }).isTrue()
    }

    @Test
    fun `validate detects ELF ABI architecture mismatch`() {
        val rootfs = tempFolder.newFolder("x86-rootfs")
        populateStandardMockRootfs(rootfs, Distribution.DEBIAN)
        // Overwrite bash with x86_64 ELF
        createMockElf(File(rootfs, "usr/bin/bash"), isArm64 = false)

        val report = validator.validate(rootfs, Distribution.DEBIAN, Architecture.ARM64)
        assertThat(report.isValid).isFalse()
        assertThat(report.errors.any { it.contains("ELF validation failed") }).isTrue()
    }

    @Test
    fun `resolveGuestSymlink resolves guest absolute symlinks relative to rootfs`() {
        val rootfs = tempFolder.newFolder("symlink-rootfs")
        val binDir = File(rootfs, "bin").apply { mkdirs() }
        val usrBinDir = File(rootfs, "usr/bin").apply { mkdirs() }

        val target = File(usrBinDir, "bash").apply { writeText("#!/bin/sh\n") }
        val symlink = File(binDir, "sh")
        Files.createSymbolicLink(symlink.toPath(), Paths.get("/usr/bin/bash"))

        val resolved = validator.resolveGuestSymlink(rootfs, "/bin/sh")
        assertThat(resolved).isNotNull()
        assertThat(resolved?.canonicalPath).isEqualTo(target.canonicalPath)
    }

    @Test
    fun `resolveGuestSymlink detects circular symlink loops`() {
        val rootfs = tempFolder.newFolder("loop-rootfs")
        val binDir = File(rootfs, "bin").apply { mkdirs() }

        val linkA = File(binDir, "linkA")
        val linkB = File(binDir, "linkB")
        Files.createSymbolicLink(linkA.toPath(), Paths.get("/bin/linkB"))
        Files.createSymbolicLink(linkB.toPath(), Paths.get("/bin/linkA"))

        val resolved = validator.resolveGuestSymlink(rootfs, "/bin/linkA")
        assertThat(resolved).isNull()
    }

    @Test
    fun `resolveGuestSymlink detects dangling symlinks`() {
        val rootfs = tempFolder.newFolder("dangling-rootfs")
        val binDir = File(rootfs, "bin").apply { mkdirs() }

        val symlink = File(binDir, "sh")
        Files.createSymbolicLink(symlink.toPath(), Paths.get("/usr/bin/nonexistent"))

        val resolved = validator.resolveGuestSymlink(rootfs, "/bin/sh")
        assertThat(resolved).isNull()
    }

    @Test
    fun `validate detects missing guest init`() {
        val rootfs = tempFolder.newFolder("no-init-rootfs")
        populateStandardMockRootfs(rootfs, Distribution.DEBIAN)
        File(rootfs, "sbin/linuxdroid-init").delete()

        val report = validator.validate(rootfs, Distribution.DEBIAN, Architecture.ARM64)
        assertThat(report.isValid).isFalse()
        assertThat(report.errors.any { it.contains("guest init") }).isTrue()
    }

    @Test
    fun `validateExtraction fails when guest init is absent and succeeds after guest init is injected`() {
        val stagingRootfs = tempFolder.newFolder("staging-rootfs")
        populateStandardMockRootfs(stagingRootfs, Distribution.DEBIAN)
        File(stagingRootfs, "sbin/linuxdroid-init").delete()

        // 1. Prior to injection: Stage A fails because guest init is missing
        val reportBefore = validator.validateExtraction(stagingRootfs, Distribution.DEBIAN, Architecture.ARM64)
        assertThat(reportBefore.isValid).isFalse()
        assertThat(reportBefore.errors.any { it.contains("guest_init") || it.contains("linuxdroid-init") }).isTrue()

        // 2. Inject guest init via RuntimeEnvironmentSetup
        val runtimeSetup = RuntimeEnvironmentSetup()
        runtimeSetup.setup(stagingRootfs)

        // 3. After injection: Stage A passes
        val reportAfter = validator.validateExtraction(stagingRootfs, Distribution.DEBIAN, Architecture.ARM64)
        assertThat(reportAfter.isValid).isTrue()
        assertThat(reportAfter.errors).isEmpty()
    }
}


