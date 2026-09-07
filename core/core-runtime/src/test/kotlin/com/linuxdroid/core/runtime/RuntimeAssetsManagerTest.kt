package com.linuxdroid.core.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * JVM unit tests for the pure, Android-independent parts of
 * [RuntimeAssetsManager] (manifest parsing, version compatibility, checksums).
 *
 * The installer/resolver paths require an Android [android.content.Context]
 * and are verified on-device / via instrumentation, not here.
 */
class RuntimeAssetsManagerTest {

    private val sampleManifest = """
        LinuxDroid-PRoot v0.2.1
        commit:  abc123def456
        ABI:     arm64-v8a
        arch:    aarch64 (ARM64)
        cc:      Clang 18.0.2 (NDK r27b)
        ndk:     r27b
        android: 16+ (API 36)
        sha256:
          proot:    aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899
          loader:   fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210
        built:   2026-08-30T00:00:00Z
    """.trimIndent()

    @Test
    fun `parseManifest extracts version, commit, ABI, and checksums`() {
        val metadata = RuntimeAssetsManager.parseManifest(sampleManifest, "arm64-v8a")

        assertThat(metadata.version).isEqualTo("0.2.1")
        assertThat(metadata.commit).isEqualTo("abc123def456")
        assertThat(metadata.abi).isEqualTo("arm64-v8a")
        assertThat(metadata.prootSha256).isEqualTo("aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899")
        assertThat(metadata.loaderSha256).isEqualTo("fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210")
        assertThat(metadata.supportsAbi("arm64-v8a")).isTrue()
        assertThat(metadata.supportsAbi("x86_64")).isFalse()
    }

    @Test
    fun `parseManifest handles dev manifest without version to unknown`() {
        val metadata = RuntimeAssetsManager.parseManifest("commit: deadbeef\n", "x86_64")
        assertThat(metadata.version).isEqualTo("unknown")
        assertThat(metadata.commit).isEqualTo("deadbeef")
    }

    @Test
    fun `compareVersions orders versions correctly`() {
        assertThat(RuntimeAssetsManager.compareVersions("0.2.0", "0.1.0") > 0).isTrue()
        assertThat(RuntimeAssetsManager.compareVersions("0.1.0", "0.1.0")).isEqualTo(0)
        assertThat(RuntimeAssetsManager.compareVersions("0.1.0", "0.2.0") < 0).isTrue()
        assertThat(RuntimeAssetsManager.compareVersions("v0.1.0", "0.1.0")).isEqualTo(0)
        assertThat(RuntimeAssetsManager.compareVersions("1.0.0", "0.9.9") > 0).isTrue()
    }

    @Test
    fun `isVersionCompatible accepts newer and unknown but rejects older`() {
        // companion-free: no Android Context needed.
        assertThat(RuntimeAssetsManager.isVersionCompatible("0.2.0")).isTrue()
        assertThat(RuntimeAssetsManager.isVersionCompatible("0.1.0")).isTrue()
        assertThat(RuntimeAssetsManager.isVersionCompatible("unknown")).isTrue()
        assertThat(RuntimeAssetsManager.isVersionCompatible("")).isTrue()
        assertThat(RuntimeAssetsManager.isVersionCompatible("0.0.9")).isFalse()
    }

    @Test
    fun `sha256 computes expected hex digest`() {
        val file = File.createTempFile("runtime-assets-test", ".txt")
        try {
            file.writeText("hello")
            val digest = RuntimeAssetsManager.sha256(file)
            // SHA-256("hello")
            assertThat(digest).isEqualTo(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `parsePackagesManifest parses multi-package manifest entries correctly`() {
        val manifest = """
            Package: linuxdroid-display-manager
            Version: 0.1.0
            Architecture: arm64
            File: linuxdroid-display-manager_0.1.0_arm64.deb
            SHA256: 4a00664135082d811e26a41a5aace0276057c542d0b1b96e2c5e039dc4789e85

            Package: linuxdroid-desktop-environment
            Version: 1.0.0
            Architecture: arm64
            File: linuxdroid-desktop-environment_1.0.0_arm64.deb
            SHA256: e81cc199f340839aa7162f86276ff3a5a3aee3647086ff6a67d2456be8224552
        """.trimIndent()

        val list = RuntimeAssetsManager.parsePackagesManifest(manifest)
        assertThat(list).hasSize(2)

        val lddm = list[0]
        assertThat(lddm.packageName).isEqualTo("linuxdroid-display-manager")
        assertThat(lddm.version).isEqualTo("0.1.0")
        assertThat(lddm.architecture).isEqualTo("arm64")
        assertThat(lddm.fileName).isEqualTo("linuxdroid-display-manager_0.1.0_arm64.deb")
        assertThat(lddm.sha256).isEqualTo("4a00664135082d811e26a41a5aace0276057c542d0b1b96e2c5e039dc4789e85")

        val ldde = list[1]
        assertThat(ldde.packageName).isEqualTo("linuxdroid-desktop-environment")
        assertThat(ldde.version).isEqualTo("1.0.0")
        assertThat(ldde.architecture).isEqualTo("arm64")
        assertThat(ldde.fileName).isEqualTo("linuxdroid-desktop-environment_1.0.0_arm64.deb")
        assertThat(ldde.sha256).isEqualTo("e81cc199f340839aa7162f86276ff3a5a3aee3647086ff6a67d2456be8224552")
    }
}

