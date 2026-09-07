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

class UserConfiguratorTest {

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
    fun `configureUser creates home directory, passwd, group, and sudoers entry`() = runBlocking {
        val rootfsDir = tempFolder.newFolder("rootfs-user")
        val configurator = UserConfigurator(runtimeBackend = null)

        val result = configurator.configureUser(
            environment = testEnv,
            rootfsDir = rootfsDir,
            username = "linuxdroid",
            password = "secretpassword",
            homeDir = "/home/linuxdroid",
            shell = "/usr/bin/zsh",
        )

        assertThat(result.success).isTrue()
        assertThat(result.username).isEqualTo("linuxdroid")
        assertThat(result.shell).isEqualTo("/usr/bin/zsh")

        val homeDir = File(rootfsDir, "home/linuxdroid")
        assertThat(homeDir.exists()).isTrue()
        assertThat(File(homeDir, "Android").exists()).isTrue()

        val passwd = File(rootfsDir, "etc/passwd").readText()
        assertThat(passwd).contains("linuxdroid:x:1000:1000:linuxdroid:/home/linuxdroid:/usr/bin/zsh")

        val sudoers = File(rootfsDir, "etc/sudoers.d/01linuxdroid-linuxdroid").readText()
        assertThat(sudoers).contains("linuxdroid ALL=(ALL:ALL) ALL")
    }
}

