package com.linuxdroid.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GuiStateTest {

    @Test
    fun `GuiState enum values and display names`() {
        assertThat(GuiState.NOT_INSTALLED.displayName).isEqualTo("Not installed")
        assertThat(GuiState.INSTALLING.displayName).isEqualTo("Installing GUI")
        assertThat(GuiState.INSTALLED.displayName).isEqualTo("Installed")
        assertThat(GuiState.REPAIRING.displayName).isEqualTo("Repairing GUI")
        assertThat(GuiState.FAILED.displayName).isEqualTo("Installation failed")
    }

    @Test
    fun `isInstalled returns true only for INSTALLED`() {
        assertThat(GuiState.INSTALLED.isInstalled).isTrue()
        assertThat(GuiState.NOT_INSTALLED.isInstalled).isFalse()
        assertThat(GuiState.INSTALLING.isInstalled).isFalse()
        assertThat(GuiState.REPAIRING.isInstalled).isFalse()
        assertThat(GuiState.FAILED.isInstalled).isFalse()
    }

    @Test
    fun `needsInstall returns true for NOT_INSTALLED and FAILED`() {
        assertThat(GuiState.NOT_INSTALLED.needsInstall).isTrue()
        assertThat(GuiState.FAILED.needsInstall).isTrue()
        assertThat(GuiState.INSTALLED.needsInstall).isFalse()
        assertThat(GuiState.INSTALLING.needsInstall).isFalse()
        assertThat(GuiState.REPAIRING.needsInstall).isFalse()
    }

    @Test
    fun `isInProgress returns true for INSTALLING and REPAIRING`() {
        assertThat(GuiState.INSTALLING.isInProgress).isTrue()
        assertThat(GuiState.REPAIRING.isInProgress).isTrue()
        assertThat(GuiState.NOT_INSTALLED.isInProgress).isFalse()
        assertThat(GuiState.INSTALLED.isInProgress).isFalse()
        assertThat(GuiState.FAILED.isInProgress).isFalse()
    }

    @Test
    fun `fromString correctly parses valid strings case-insensitively`() {
        assertThat(GuiState.fromString("INSTALLED")).isEqualTo(GuiState.INSTALLED)
        assertThat(GuiState.fromString("installed")).isEqualTo(GuiState.INSTALLED)
        assertThat(GuiState.fromString("Installing")).isEqualTo(GuiState.INSTALLING)
        assertThat(GuiState.fromString("repairing")).isEqualTo(GuiState.REPAIRING)
        assertThat(GuiState.fromString("FAILED")).isEqualTo(GuiState.FAILED)
        assertThat(GuiState.fromString("not_installed")).isEqualTo(GuiState.NOT_INSTALLED)
    }

    @Test
    fun `fromString defaults to NOT_INSTALLED for null or invalid inputs`() {
        assertThat(GuiState.fromString(null)).isEqualTo(GuiState.NOT_INSTALLED)
        assertThat(GuiState.fromString("")).isEqualTo(GuiState.NOT_INSTALLED)
        assertThat(GuiState.fromString("   ")).isEqualTo(GuiState.NOT_INSTALLED)
        assertThat(GuiState.fromString("INVALID_STATE")).isEqualTo(GuiState.NOT_INSTALLED)
    }

    @Test
    fun `Environment withGuiState preserves CLI state and updates GUI fields`() {
        val env = Environment(
            metadata = EnvironmentMetadata(
                id = EnvironmentId("test-env"),
                name = "Test",
                distribution = Distribution.DEBIAN,
                architecture = Architecture.ARM64,
            ),
            state = EnvironmentState.READY,
            rootfsPath = "/data/rootfs",
            metadataPath = "/data/meta",
            guiState = GuiState.NOT_INSTALLED,
        )

        val installingEnv = env.withGuiState(GuiState.INSTALLING)
        assertThat(installingEnv.guiState).isEqualTo(GuiState.INSTALLING)
        assertThat(installingEnv.state).isEqualTo(EnvironmentState.READY)
        assertThat(installingEnv.guiFailureMessage).isNull()

        val failedEnv = installingEnv.withGuiState(GuiState.FAILED, "Wayland missing")
        assertThat(failedEnv.guiState).isEqualTo(GuiState.FAILED)
        assertThat(failedEnv.state).isEqualTo(EnvironmentState.READY)
        assertThat(failedEnv.guiFailureMessage).isEqualTo("Wayland missing")

        val installedEnv = failedEnv.withGuiState(GuiState.INSTALLED)
        assertThat(installedEnv.guiState).isEqualTo(GuiState.INSTALLED)
        assertThat(installedEnv.state).isEqualTo(EnvironmentState.READY)
        assertThat(installedEnv.guiFailureMessage).isNull()
    }
}
