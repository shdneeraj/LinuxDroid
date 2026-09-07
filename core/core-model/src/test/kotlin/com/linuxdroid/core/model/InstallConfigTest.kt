package com.linuxdroid.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class InstallConfigTest {

    @Test
    fun `valid debian install config is constructed successfully`() {
        val config = InstallConfig(
            distro = Distribution.DEBIAN,
            release = "trixie",
            username = "linuxdroid",
            password = "secretpassword",
        )
        assertThat(config.distro).isEqualTo(Distribution.DEBIAN)
        assertThat(config.release).isEqualTo("trixie")
        assertThat(config.username).isEqualTo("linuxdroid")
        assertThat(config.password).isEqualTo("secretpassword")
        assertThat(config.architecture).isEqualTo(Architecture.ARM64)
    }

    @Test
    fun `valid ubuntu install config is constructed successfully`() {
        val config = InstallConfig(
            distro = Distribution.UBUNTU,
            release = "noble",
            username = "ubuntuuser",
            password = "password123",
        )
        assertThat(config.distro).isEqualTo(Distribution.UBUNTU)
        assertThat(config.release).isEqualTo("noble")
        assertThat(config.username).isEqualTo("ubuntuuser")
    }

    @Test
    fun `toString masks password to prevent credential leakage`() {
        val config = InstallConfig(
            distro = Distribution.DEBIAN,
            release = "bookworm",
            username = "admin",
            password = "supersecretplainpassword",
        )
        val str = config.toString()
        assertThat(str).contains("[REDACTED]")
        assertThat(str).doesNotContain("supersecretplainpassword")
    }

    @Test
    fun `rejects unsupported distributions in V1`() {
        assertThrows(IllegalArgumentException::class.java) {
            InstallConfig(
                distro = Distribution.KALI,
                release = "rolling",
                username = "kaliuser",
                password = "password",
            )
        }
    }

    @Test
    fun `rejects invalid usernames`() {
        assertThrows(IllegalArgumentException::class.java) {
            InstallConfig(
                distro = Distribution.DEBIAN,
                release = "trixie",
                username = "123invalid",
                password = "password",
            )
        }
    }

    @Test
    fun `rejects blank release`() {
        assertThrows(IllegalArgumentException::class.java) {
            InstallConfig(
                distro = Distribution.DEBIAN,
                release = "   ",
                username = "validuser",
                password = "password",
            )
        }
    }
}

