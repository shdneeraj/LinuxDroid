package com.linuxdroid.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.test.assertFailsWith

class StartModeTest {

    @Test
    fun `StartMode fromString parses valid modes case-insensitively`() {
        assertThat(StartMode.fromString("GUI")).isEqualTo(StartMode.GUI)
        assertThat(StartMode.fromString("gui")).isEqualTo(StartMode.GUI)
        assertThat(StartMode.fromString(" Gui ")).isEqualTo(StartMode.GUI)
        assertThat(StartMode.fromString("CLI")).isEqualTo(StartMode.CLI)
        assertThat(StartMode.fromString("cli")).isEqualTo(StartMode.CLI)
        assertThat(StartMode.fromString("  cli  ")).isEqualTo(StartMode.CLI)
    }

    @Test
    fun `StartMode fromString rejects invalid values deterministically`() {
        val err1 = assertFailsWith<IllegalArgumentException> {
            StartMode.fromString("DESKTOP")
        }
        assertThat(err1.message).contains("Only 'GUI' and 'CLI' are allowed")

        val err2 = assertFailsWith<IllegalArgumentException> {
            StartMode.fromString("")
        }
        assertThat(err2.message).contains("Only 'GUI' and 'CLI' are allowed")

        val err3 = assertFailsWith<IllegalArgumentException> {
            StartMode.fromString(null)
        }
        assertThat(err3.message).contains("Only 'GUI' and 'CLI' are allowed")
    }

    @Test
    fun `StartMode fromStringOrDefault handles defaults correctly`() {
        assertThat(StartMode.fromStringOrDefault("gui", StartMode.CLI)).isEqualTo(StartMode.GUI)
        assertThat(StartMode.fromStringOrDefault("cli", StartMode.GUI)).isEqualTo(StartMode.CLI)
        assertThat(StartMode.fromStringOrDefault("unknown", StartMode.CLI)).isEqualTo(StartMode.CLI)
        assertThat(StartMode.fromStringOrDefault(null, StartMode.GUI)).isEqualTo(StartMode.GUI)
    }

    @Test
    fun `Session model holds StartMode and new session states`() {
        val sessionId = SessionId.generate()
        val envId = EnvironmentId.generate()

        val guiSession = Session(
            id = sessionId,
            environmentId = envId,
            state = SessionState.GUI_READY,
            startMode = StartMode.GUI,
        )
        assertThat(guiSession.startMode).isEqualTo(StartMode.GUI)
        assertThat(guiSession.state.isActive()).isTrue()

        val cliSession = Session(
            id = sessionId,
            environmentId = envId,
            state = SessionState.CLI_READY,
            startMode = StartMode.CLI,
        )
        assertThat(cliSession.startMode).isEqualTo(StartMode.CLI)
        assertThat(cliSession.state.isActive()).isTrue()

        val failedSession = Session(
            id = sessionId,
            environmentId = envId,
            state = SessionState.GUI_FAILED,
            startMode = StartMode.GUI,
            failureMessage = "Compositor failed to launch",
        )
        assertThat(failedSession.state.isActive()).isFalse()
        assertThat(failedSession.failureMessage).isEqualTo("Compositor failed to launch")
    }

    @Test
    fun `RuntimeSpec fromEnvironment propagates StartMode`() {
        val envId = EnvironmentId.generate()
        val environment = Environment(
            metadata = EnvironmentMetadata(
                id = envId,
                name = "Debian 12",
                distribution = Distribution.DEBIAN,
                architecture = Architecture.ARM64,
            ),
            state = EnvironmentState.READY,
            rootfsPath = "/data/environments/${envId.value}/rootfs",
            metadataPath = "/data/environments/${envId.value}/metadata",
        )

        val guiSpec = RuntimeSpec.fromEnvironment(
            environment = environment,
            startMode = StartMode.GUI,
        )
        assertThat(guiSpec.startMode).isEqualTo(StartMode.GUI)
        assertThat(guiSpec.environmentVariables["LINUXDROID_START_MODE"]).isEqualTo("GUI")

        val cliSpec = RuntimeSpec.fromEnvironment(
            environment = environment,
            startMode = StartMode.CLI,
        )
        assertThat(cliSpec.startMode).isEqualTo(StartMode.CLI)
        assertThat(cliSpec.environmentVariables["LINUXDROID_START_MODE"]).isEqualTo("CLI")
    }
}

