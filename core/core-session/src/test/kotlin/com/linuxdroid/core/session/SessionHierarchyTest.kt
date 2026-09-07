package com.linuxdroid.core.session

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.model.*
import com.linuxdroid.core.runtime.PtySession
import com.linuxdroid.core.runtime.RuntimeManager
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionHierarchyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val runtimeManager = mockk<RuntimeManager>(relaxed = true)
    private lateinit var storage: EnvironmentStorage
    private lateinit var environment: Environment

    @Before
    fun setup() {
        val baseDir = tempFolder.newFolder("environments")
        storage = EnvironmentStorage(baseDir)
        environment = Environment(
            metadata = EnvironmentMetadata(
                id = EnvironmentId("test-session-env"),
                name = "Test Session Env",
                distribution = Distribution.DEBIAN,
                architecture = Architecture.ARM64,
            ),
            rootfsPath = storage.rootfsDir(EnvironmentId("test-session-env")).absolutePath,
            metadataPath = storage.metadataDir(EnvironmentId("test-session-env")).absolutePath,
        )
    }

    @Test
    fun `RuntimeSession prepares and executes command via RuntimeManager`() = runTest {
        val sessionId = SessionId.generate()
        val session = RuntimeSession(sessionId, environment, runtimeManager)

        coEvery { runtimeManager.execute(any(), any()) } returns ProcessHandle(
            handleId = "proc-1",
            environmentId = environment.id,
            sessionId = sessionId,
            command = listOf("/bin/ls"),
            workingDirectory = "/",
            pid = 1234,
            state = ProcessState.RUNNING,
        )

        val handle = session.execute(listOf("/bin/ls"))
        assertThat(handle.pid).isEqualTo(1234)
        coVerify { runtimeManager.execute(any(), sessionId) }
    }

    @Test
    fun `TerminalSession opens interactive shell via RuntimeManager`() = runTest {
        val sessionId = SessionId.generate()
        val session = TerminalSession(sessionId, environment, runtimeManager)

        val pty = PtySession("test-pty", environment.id, 5555, 3)
        coEvery { runtimeManager.startInteractiveShell(any(), any(), any()) } returns pty

        val openedPty = session.open(rows = 30, cols = 100)
        assertThat(openedPty.pid).isEqualTo(5555)
        assertThat(session.ptySession).isEqualTo(pty)
    }

    @Test
    fun `DesktopSession initializes subsystems and starts session script`() = runTest {
        val sessionId = SessionId.generate()
        val session = DesktopSession(sessionId, environment, runtimeManager, storage)

        coEvery { runtimeManager.execute(any(), any()) } returns ProcessHandle(
            handleId = "desktop-proc",
            environmentId = environment.id,
            sessionId = sessionId,
            command = listOf("/bin/sh"),
            workingDirectory = "/home/user",
            pid = 9999,
            state = ProcessState.RUNNING,
        )

        val activeSession = session.start()
        assertThat(activeSession.state).isEqualTo(SessionState.GUI_READY)
        assertThat(activeSession.startMode).isEqualTo(StartMode.GUI)
        assertThat(activeSession.runtimePid).isEqualTo(9999)
        assertThat(activeSession.waylandSocket).isEqualTo("wayland-0")
    }
}
