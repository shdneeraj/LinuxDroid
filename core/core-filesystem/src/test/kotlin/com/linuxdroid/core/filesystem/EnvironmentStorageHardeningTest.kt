package com.linuxdroid.core.filesystem

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.model.EnvironmentId
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EnvironmentStorageHardeningTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storage: EnvironmentStorage
    private val envId = EnvironmentId("hardening-test-env")

    @Before
    fun setup() {
        storage = EnvironmentStorage(tempFolder.newFolder("environments"))
    }

    private fun createMinimalRootfs(dir: File) {
        File(dir, "bin").mkdirs()
        File(dir, "etc").mkdirs()
        File(dir, "usr").mkdirs()
        File(dir, "etc/os-release").writeText("NAME=LinuxDroid")
    }

    @Test
    fun `writeAtomic successfully writes and updates content`() = runTest {
        storage.initializeEnvironmentDirs(envId)
        val target = File(storage.metadataDir(envId), "test-atomic.json")

        // Initial write
        storage.writeAtomic(target, "{\"version\": 1}")
        assertThat(target.exists()).isTrue()
        assertThat(target.readText()).isEqualTo("{\"version\": 1}")

        // Overwrite
        storage.writeAtomic(target, "{\"version\": 2}")
        assertThat(target.readText()).isEqualTo("{\"version\": 2}")

        // Ensure no leftover temp files
        val leftoverTemps = target.parentFile?.listFiles { _, name -> name.contains(".tmp.") }
        assertThat(leftoverTemps).isEmpty()
    }

    @Test
    fun `verifyRootfs rejects empty or corrupted manifest`() = runTest {
        storage.initializeEnvironmentDirs(envId)
        val rootfs = storage.rootfsDir(envId)
        createMinimalRootfs(rootfs)

        // Valid minimal rootfs without manifest
        assertThat(storage.verifyRootfs(envId)).isTrue()

        // Write a valid manifest
        val manifest = File(storage.metadataDir(envId), "rootfs-manifest.json")
        storage.writeAtomic(manifest, "{\"status\": \"ready\"}")
        assertThat(storage.verifyRootfs(envId)).isTrue()

        // Empty (corrupted) manifest
        manifest.writeText("")
        assertThat(storage.verifyRootfs(envId)).isFalse()
    }

    @Test
    fun `verifyRootfs rejects missing core rootfs directories`() = runTest {
        storage.initializeEnvironmentDirs(envId)
        val rootfs = storage.rootfsDir(envId)

        // Empty rootfs
        rootfs.mkdirs()
        assertThat(storage.verifyRootfs(envId)).isFalse()

        // Partial rootfs (missing usr)
        File(rootfs, "bin").mkdirs()
        File(rootfs, "etc").mkdirs()
        assertThat(storage.verifyRootfs(envId)).isFalse()

        // Complete minimal rootfs
        File(rootfs, "usr").mkdirs()
        assertThat(storage.verifyRootfs(envId)).isTrue()
    }
}

