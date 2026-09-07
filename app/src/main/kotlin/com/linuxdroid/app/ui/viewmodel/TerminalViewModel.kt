package com.linuxdroid.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linuxdroid.core.database.EnvironmentMapper
import com.linuxdroid.core.database.dao.EnvironmentDao
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogCategory
import com.linuxdroid.core.logging.LogConfig
import com.linuxdroid.core.logging.LogFileManager
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.Environment
import com.linuxdroid.core.model.EnvironmentState
import com.linuxdroid.core.model.ProcessHandle
import com.linuxdroid.core.model.ProcessState
import com.linuxdroid.core.process.DefaultProcessManager
import com.linuxdroid.core.process.ProcessManager
import com.linuxdroid.core.runtime.PtySession
import com.linuxdroid.core.runtime.RuntimeBackend
import com.linuxdroid.core.runtime.TerminalBuffer
import com.linuxdroid.core.runtime.TerminalLineData
import com.linuxdroid.app.service.LinuxSessionService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class TerminalViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val dao: EnvironmentDao,
    private val runtimeBackend: RuntimeBackend,
    private val logExporter: com.linuxdroid.core.diagnostics.RuntimeLogExporter,
    private val processManager: ProcessManager,
) : ViewModel() {

    private val log = LinuxDroidLogger(LogSubsystem.APPLICATION)
    val environmentId: String = checkNotNull(savedStateHandle["environmentId"])

    val environment: StateFlow<Environment?> = dao.observeById(environmentId)
        .map { entity -> entity?.let { EnvironmentMapper.toDomain(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val terminalBuffer = TerminalBuffer(maxScrollbackLines = 2000)
    val lines: StateFlow<List<TerminalLineData>> = terminalBuffer.lines
    val cursorRow: StateFlow<Int> = terminalBuffer.activeCursorRow
    val cursorCol: StateFlow<Int> = terminalBuffer.activeCursorCol

    private val _isShellActive = MutableStateFlow(false)
    val isShellActive: StateFlow<Boolean> = _isShellActive.asStateFlow()

    private val _isStarting = MutableStateFlow(false)
    val isStarting: StateFlow<Boolean> = _isStarting.asStateFlow()

    private val _shellExitCode = MutableStateFlow<Int?>(null)
    val shellExitCode: StateFlow<Int?> = _shellExitCode.asStateFlow()

    private var ptySession: PtySession? = null
    private var readJob: Job? = null
    private val sessionMutex = Mutex()
    private val inputChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private var isCtrlActive = false
    private var isAltActive = false

    private var currentRows = 24
    private var currentCols = 80

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (bytes in inputChannel) {
                val session = ptySession
                if (session?.isAlive() == true) {
                    if (LogConfig.generate_log) {
                        LogFileManager.appendTerminalBytes(com.linuxdroid.core.model.EnvironmentId(environmentId), bytes, 0, bytes.size)
                    }
                    session.write(bytes)
                }
            }
        }

        viewModelScope.launch {
            environment.filterNotNull().first { env ->
                startInteractiveShellSession(env)
                true
            }
        }
    }

    /**
     * Initializes and starts the persistent interactive shell in PTY.
     */
    fun startInteractiveShellSession(env: Environment) {
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock {
                if (ptySession?.isAlive() == true) return@withLock
                _isStarting.value = true
                _shellExitCode.value = null

                try {
                    // Ensure runtime backend is prepared and started
                    if (env.state != EnvironmentState.RUNNING) {
                        log.info("[RUNTIME] Start requested: env=${env.id} startMode=CLI")
                        terminalBuffer.append("Starting Linux runtime (CLI mode)…\r\n".toByteArray(), "Starting Linux runtime (CLI mode)…\r\n".length)
                        val readyEnv = when (env.state) {
                            EnvironmentState.FAILED -> {
                                dao.updateState(
                                    id = env.id.value,
                                    state = EnvironmentState.RECOVERING.name,
                                    timestamp = System.currentTimeMillis(),
                                    failureMessage = null,
                                )
                                dao.updateState(
                                    id = env.id.value,
                                    state = EnvironmentState.READY.name,
                                    timestamp = System.currentTimeMillis(),
                                    failureMessage = null,
                                )
                                env.withState(EnvironmentState.RECOVERING).withState(EnvironmentState.READY)
                            }
                            EnvironmentState.STARTING -> env.withState(EnvironmentState.READY)
                            else -> env
                        }
                        runtimeBackend.prepare(readyEnv)
                        runtimeBackend.initialize(readyEnv)
                        runtimeBackend.start(readyEnv)
                        dao.updateState(
                            id = env.id.value,
                            state = EnvironmentState.RUNNING.name,
                            timestamp = System.currentTimeMillis(),
                            failureMessage = null,
                        )
                        LinuxSessionService.start(context, env.name)
                        log.info("[RUNTIME] CLI_READY")
                    } else {
                        log.info("Linux environment already active for ${env.id}; attaching terminal shell without restarting runtime")
                    }

                    // Close existing session if any
                    closeSession()

                    val targetShell = env.configuration.shell.ifBlank { "/bin/bash" }
                    val shellCommand = listOf(targetShell, "-l")

                    val session = runtimeBackend.startInteractiveShell(
                        environment = env,
                        rows = currentRows,
                        cols = currentCols,
                        command = shellCommand
                    )
                    ptySession = session
                    terminalBuffer.resize(currentRows, currentCols)
                    _isShellActive.value = true
                    _isStarting.value = false

                    val procHandle = ProcessHandle(
                        handleId = session.sessionId,
                        environmentId = env.id,
                        pid = session.pid,
                        command = shellCommand,
                        processRole = "terminal",
                        state = ProcessState.RUNNING,
                        startedAt = System.currentTimeMillis()
                    )
                    (processManager as? DefaultProcessManager)?.registerProcess(procHandle)

                    log.info("Interactive shell session connected for ${env.id} (pid=${session.pid})")

                    // Start continuous IO reader job
                    readJob = launch(Dispatchers.IO) {
                        val buffer = ByteArray(4096)
                        while (isActive) {
                            val bytesRead = session.read(buffer)
                            if (bytesRead > 0) {
                                terminalBuffer.append(buffer, bytesRead)
                                if (LogConfig.generate_log) {
                                    LogFileManager.appendTerminalBytes(env.id, buffer, 0, bytesRead)
                                }
                            } else if (bytesRead < 0) {
                                break
                            }
                        }

                        _isShellActive.value = false
                        val exitCode = session.getExitCode() ?: 0
                        _shellExitCode.value = exitCode
                        val exitMsg = "\r\n[Process completed (exit=$exitCode)]\r\n"
                        terminalBuffer.append(exitMsg.toByteArray(), exitMsg.length)
                        if (LogConfig.generate_log) {
                            LogFileManager.appendTerminalText(env.id, exitMsg)
                        }
                        log.info("Shell process exited with code $exitCode")

                        (processManager as? DefaultProcessManager)?.updateProcess(
                            procHandle.copy(
                                state = ProcessState.EXITED,
                                exitCode = exitCode,
                                exitedAt = System.currentTimeMillis()
                            )
                        )
                    }
                } catch (e: Exception) {
                    log.error("Failed to spawn interactive shell", e)
                    _isStarting.value = false
                    _isShellActive.value = false
                    val errorMsg = "\r\n[Error launching shell: ${e.message}]\r\n"
                    terminalBuffer.append(errorMsg.toByteArray(), errorMsg.length)
                    if (LogConfig.generate_log) {
                        LogFileManager.appendTerminalText(env.id, errorMsg)
                    }
                }
            }
        }
    }

    /**
     * Sends keyboard input string directly to the active shell stdin in strictly ordered sequence.
     */
    fun sendInput(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        inputChannel.trySend(bytes)
    }

    /**
     * Sends raw bytes to the active shell stdin in strictly ordered sequence.
     */
    fun sendBytes(bytes: ByteArray) {
        inputChannel.trySend(bytes)
    }

    /**
     * Pastes raw text into the active shell, normalizing carriage returns without distorting letters or spaces.
     */
    fun pasteText(text: String) {
        if (text.isEmpty()) return
        val normalized = text.replace("\r\n", "\r").replace("\n", "\r")
        sendInput(normalized)
    }

    /**
     * Sends predefined shortcut command to the current shell session.
     */
    fun runCommand(cmd: String) {
        sendInput("$cmd\n")
    }

    // ─── Control key helpers ────────────────────────────────────────────────────────

    fun sendCtrlC() = sendBytes(byteArrayOf(0x03)) // SIGINT (ETX)
    fun sendCtrlD() = sendBytes(byteArrayOf(0x04)) // EOF (EOT)
    fun sendCtrlL() = sendBytes(byteArrayOf(0x0C)) // Clear (FF)
    fun sendCtrlZ() = sendBytes(byteArrayOf(0x1A)) // Suspend (SUB)
    fun sendCtrlA() = sendBytes(byteArrayOf(0x01)) // Start of line
    fun sendCtrlE() = sendBytes(byteArrayOf(0x05)) // End of line
    fun sendCtrlU() = sendBytes(byteArrayOf(0x15)) // Kill line
    fun sendCtrlW() = sendBytes(byteArrayOf(0x17)) // Word rubout

    fun sendTab() = sendBytes(byteArrayOf(0x09)) // Tab
    fun sendEscape() = sendBytes(byteArrayOf(0x1B)) // ESC
    fun sendBackspace() = sendBytes(byteArrayOf(0x7F)) // DEL / Backspace
    fun sendEnter() = sendBytes(byteArrayOf(0x0D)) // CR / Enter

    fun sendArrowUp() = sendInput("\u001B[A")
    fun sendArrowDown() = sendInput("\u001B[B")
    fun sendArrowRight() = sendInput("\u001B[C")
    fun sendArrowLeft() = sendInput("\u001B[D")

    fun sendHome() = sendInput("\u001B[H")
    fun sendEnd() = sendInput("\u001B[F")
    fun sendPageUp() = sendInput("\u001B[5~")
    fun sendPageDown() = sendInput("\u001B[6~")
    fun sendInsert() = sendInput("\u001B[2~")
    fun sendDelete() = sendInput("\u001B[3~")

    fun sendFunctionKey(num: Int) {
        val seq = when (num) {
            1 -> "\u001BOP"
            2 -> "\u001BOQ"
            3 -> "\u001BOR"
            4 -> "\u001BOS"
            5 -> "\u001B[15~"
            6 -> "\u001B[17~"
            7 -> "\u001B[18~"
            8 -> "\u001B[19~"
            9 -> "\u001B[20~"
            10 -> "\u001B[21~"
            11 -> "\u001B[23~"
            12 -> "\u001B[24~"
            else -> return
        }
        sendInput(seq)
    }

    /**
     * Resizes the terminal PTY window.
     */
    fun resize(rows: Int, cols: Int) {
        if (rows <= 0 || cols <= 0) return
        currentRows = rows
        currentCols = cols
        terminalBuffer.resize(rows, cols)
        viewModelScope.launch(Dispatchers.IO) {
            ptySession?.resize(rows, cols)
        }
    }

    /**
     * Restarts the interactive shell session.
     */
    fun restartShell() {
        val env = environment.value ?: return
        startInteractiveShellSession(env)
    }

    fun clear() {
        terminalBuffer.clear()
        sendCtrlL()
    }

    private fun closeSession() {
        readJob?.cancel()
        readJob = null
        ptySession?.close()
        ptySession = null
        _isShellActive.value = false
    }

    /**
     * Extracts the full terminal scrollback buffer as plain text.
     */
    fun getTerminalPlainText(): String = terminalBuffer.getPlainText()

    /**
     * Exports and shares runtime failure reports or diagnostic logs for this environment.
     */
    fun exportLogs(
        context: Context,
        exportType: com.linuxdroid.core.model.LogExportType = com.linuxdroid.core.model.LogExportType.TERMINAL_FAILURE_LOG,
        asJson: Boolean = false,
    ) {
        val env = environment.value ?: return
        val textBuffer = terminalBuffer.getPlainText()
        val exitCode = _shellExitCode.value
        val isAlive = ptySession?.isAlive() == true
        viewModelScope.launch(Dispatchers.IO) {
            val shareIntent = logExporter.createShareIntent(
                context = context,
                environment = env,
                exportType = exportType,
                asJson = asJson,
                terminalOutput = textBuffer,
                exitCode = exitCode,
                isPtyActive = isAlive,
            )
            shareIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(shareIntent)
        }
    }

    override fun onCleared() {
        super.onCleared()
        closeSession()
    }
}
