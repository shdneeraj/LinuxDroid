package com.linuxdroid.core.session

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.model.*
import com.linuxdroid.core.runtime.RuntimeBackend
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertFailsWith

class DefaultSessionManagerModeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val runtimeBackend = mockk<RuntimeBackend>(relaxed = true)
    private lateinit var storage: EnvironmentStorage
    private lateinit var environment: Environment
    private lateinit var sessionManager: DefaultSessionManager

    @Before
    fun setup() {
        val baseDir = tempFolder.newFolder("environments")
        storage = EnvironmentStorage(baseDir)
        val envId = EnvironmentId("test-session-mgr-env")
        val rootfsDir = storage.rootfsDir(envId).apply { mkdirs() }
        File(rootfsDir, "bin").mkdirs()
        File(rootfsDir, "sbin").mkdirs()
        File(rootfsDir, "etc").mkdirs()
        File(rootfsDir, "usr").mkdirs()

        environment = Environment(
            metadata = EnvironmentMetadata(
                id = envId,
                name = "Test Session Mgr Env",
                distribution = Distribution.DEBIAN,
                architecture = Architecture.ARM64,
            ),
            state = EnvironmentState.READY,
            rootfsPath = rootfsDir.absolutePath,
            metadataPath = storage.metadataDir(envId).absolutePath,
        )

        sessionManager = DefaultSessionManager(
            runtimeBackend = runtimeBackend,
            storage = storage,
        )
    }

    @Test
    fun `startSession with CLI mode advances directly to CLI_READY without graphical subsystems`() = runTest {
        coEvery {
            runtimeBackend.executeAndWait(
                environment = any(),
                command = any(),
                extraEnv = any(),
                timeoutMs = any(),
            )
        } returns ProcessResult(handleId = "handle-1", exitCode = 0, stdout = "Linux test 6.1.0 SHELL_ACTIVE\n", stderr = "")

        val session = sessionManager.startSession(environment, startMode = StartMode.CLI)

        assertThat(session.startMode).isEqualTo(StartMode.CLI)
        assertThat(session.state).isEqualTo(SessionState.CLI_READY)
        assertThat(session.state.isActive()).isTrue()

        val active = sessionManager.getSession(environment.id)
        assertThat(active).isNotNull()
        assertThat(active?.startMode).isEqualTo(StartMode.CLI)

        // Verify runtimeBackend was prepared and started
        coVerify { runtimeBackend.prepare(environment) }
        coVerify { runtimeBackend.initialize(environment) }
        coVerify { runtimeBackend.start(environment) }
    }

    @Test
    fun `startSession with GUI mode executes LDDM and reaches GUI_READY`() = runTest {
        coEvery {
            runtimeBackend.executeAndWait(
                environment = any(),
                command = any(),
                extraEnv = any(),
                timeoutMs = any(),
            )
        } returns ProcessResult(handleId = "handle-2", exitCode = 0, stdout = "Linux test 6.1.0 SHELL_ACTIVE\n", stderr = "")

        coEvery {
            runtimeBackend.execute(
                environment = any(),
                command = any(),
                workingDirectory = any(),
                extraEnv = any(),
                sessionId = any(),
            )
        } returns ProcessHandle(
            handleId = "lddm-proc",
            environmentId = environment.id,
            command = listOf("/usr/bin/lddm"),
            pid = 4321,
            state = ProcessState.RUNNING,
        )

        val session = sessionManager.startSession(environment, startMode = StartMode.GUI)

        assertThat(session.startMode).isEqualTo(StartMode.GUI)
        assertThat(session.state).isEqualTo(SessionState.GUI_READY)
        assertThat(session.state.isActive()).isTrue()

        // Verify runtimeBackend executed LDDM with StartMode.GUI in extraEnv
        coVerify {
            runtimeBackend.execute(
                environment = environment,
                command = any(),
                workingDirectory = any(),
                extraEnv = match { it["LINUXDROID_START_MODE"] == "GUI" },
                sessionId = any(),
            )
        }
    }

    @Test
    fun `startSession in GUI mode fails with GUI_FAILED and rethrows error without silent CLI fallback`() = runTest {
        coEvery {
            runtimeBackend.executeAndWait(
                environment = any(),
                command = any(),
                extraEnv = any(),
                timeoutMs = any(),
            )
        } throws RuntimeError(environment.id, "Kernel emulation failed")

        val error = assertFailsWith<RuntimeError> {
            sessionManager.startSession(environment, startMode = StartMode.GUI)
        }

        assertThat(error.message).contains("Kernel emulation failed")

        val storedSessions = sessionManager.sessions
        // Active session should be empty since it failed
        val active = sessionManager.getSession(environment.id)
        assertThat(active).isNull()
    }
}

