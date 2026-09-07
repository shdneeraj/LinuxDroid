package com.linuxdroid.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linuxdroid.app.service.LinuxSessionService
import com.linuxdroid.core.database.EnvironmentMapper
import com.linuxdroid.core.database.dao.EnvironmentDao
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.*
import com.linuxdroid.core.runtime.RuntimeBackend
import com.linuxdroid.core.session.SessionManager
import com.linuxdroid.linux.bootstrap.DynamicDistributionResolver
import com.linuxdroid.linux.bootstrap.RootfsBootstrapper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class DistributionFetchState {
    object Idle : DistributionFetchState()
    data class Fetching(val distro: Distribution, val message: String) : DistributionFetchState()
    data class Ready(
        val distro: Distribution,
        val releases: List<DistroRelease>,
        val definition: DistributionDefinition,
    ) : DistributionFetchState()
    data class Failed(val distro: Distribution, val error: String) : DistributionFetchState()
}

@HiltViewModel
class EnvironmentViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: EnvironmentDao,
    private val storage: EnvironmentStorage,
    private val runtimeBackend: RuntimeBackend,
    private val bootstrapper: RootfsBootstrapper,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val log = LinuxDroidLogger(LogSubsystem.APPLICATION)

    val environments: StateFlow<List<Environment>> = dao.observeAll()
        .map { entities -> entities.map { EnvironmentMapper.toDomain(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _distroFetchState = MutableStateFlow<DistributionFetchState>(DistributionFetchState.Idle)
    val distroFetchState: StateFlow<DistributionFetchState> = _distroFetchState.asStateFlow()

    private val _installProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val installProgress: StateFlow<Map<String, Float>> = _installProgress.asStateFlow()

    private val _installStatusText = MutableStateFlow<Map<String, String>>(emptyMap())
    val installStatusText: StateFlow<Map<String, String>> = _installStatusText.asStateFlow()

    private val _installerLogs = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val installerLogs: StateFlow<Map<String, List<String>>> = _installerLogs.asStateFlow()

    private val _errorMessage = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()

    fun prepareDistribution(distribution: Distribution, release: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            _distroFetchState.value = DistributionFetchState.Fetching(distribution, "Resolving release metadata for ${distribution.displayName}...")
            try {
                val releases = DistributionCatalog.getAvailableReleases(distribution)
                val selectedRelease = release ?: releases.firstOrNull { it.isDefault }?.releaseCode ?: releases.firstOrNull()?.releaseCode
                val baseDef = DistributionCatalog.getDefinition(distribution, Architecture.current(), selectedRelease)
                val resolvedDef = try {
                    DynamicDistributionResolver().resolveLatest(baseDef) { msg ->
                        _distroFetchState.value = DistributionFetchState.Fetching(distribution, msg)
                    }
                } catch (e: Exception) {
                    baseDef
                }
                _distroFetchState.value = DistributionFetchState.Ready(distribution, releases, resolvedDef)
            } catch (e: Exception) {
                log.warn("Distribution prefetch metadata check: ${e.message}")
                val releases = DistributionCatalog.getAvailableReleases(distribution)
                val baseDef = DistributionCatalog.getDefinition(distribution, Architecture.current(), releases.firstOrNull()?.releaseCode)
                _distroFetchState.value = DistributionFetchState.Ready(distribution, releases, baseDef)
            }
        }
    }

    fun createEnvironmentWithConfig(
        installConfig: InstallConfig,
        environmentName: String? = null,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val distro = installConfig.distro
                val arch = installConfig.architecture
                val defaultName = "${distro.displayName} (${arch.abiName})"
                val name = environmentName?.trim()?.ifEmpty { defaultName } ?: defaultName
                val id = EnvironmentId.generate()
                log.info("Creating environment '$name' ($id) with install config for user ${installConfig.username}")

                storage.initializeEnvironmentDirs(id)

                val metadata = EnvironmentMetadata(
                    id = id,
                    name = name,
                    distribution = distro,
                    architecture = arch,
                )

                val environment = Environment(
                    metadata = metadata,
                    configuration = EnvironmentConfiguration(linuxUser = installConfig.username),
                    state = EnvironmentState.CREATED,
                    rootfsPath = storage.rootfsDir(id).absolutePath,
                    metadataPath = storage.metadataDir(id).absolutePath,
                )

                dao.insert(EnvironmentMapper.toEntity(environment))

                installRootfsWithConfig(environment, installConfig)
            } catch (e: Exception) {
                log.error("Failed to create environment with install config", e)
                _errorMessage.tryEmit(e.message ?: "Failed to create environment")
            }
        }
    }

    fun installRootfsWithConfig(environment: Environment, installConfig: InstallConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            val envId = environment.id.value
            try {
                log.info("Starting rootfs installation with config for $envId (user=${installConfig.username}, distro=${installConfig.distro.displayName})")
                dao.updateState(
                    id = envId,
                    state = EnvironmentState.INSTALLING.name,
                    timestamp = System.currentTimeMillis(),
                    failureMessage = null,
                )

                _installerLogs.update { it + (envId to listOf(">>> Starting ${installConfig.distro.displayName} (${installConfig.release}) rootfs installation...")) }

                bootstrapper.bootstrapRootfs(
                    environment = environment,
                    installConfig = installConfig,
                    onProgress = { progress, status ->
                        _installProgress.update { it + (envId to progress) }
                        _installStatusText.update { it + (envId to status) }
                    },
                    onLog = { line ->
                        _installerLogs.update { map ->
                            val current = map[envId] ?: emptyList()
                            map + (envId to (current + line).takeLast(500))
                        }
                    }
                )

                // Verify and update to READY
                dao.updateState(
                    id = envId,
                    state = EnvironmentState.READY.name,
                    timestamp = System.currentTimeMillis(),
                    failureMessage = null,
                )
                _installProgress.update { it - envId }
                _installStatusText.update { it - envId }
                log.info("Rootfs installed with config and environment $envId is READY")
            } catch (e: Exception) {
                log.error("Failed to install rootfs with config for $envId", e)
                dao.updateState(
                    id = envId,
                    state = EnvironmentState.FAILED.name,
                    timestamp = System.currentTimeMillis(),
                    failureMessage = e.message ?: "Installation failed",
                )
                _installProgress.update { it - envId }
                _installStatusText.update { it - envId }
                _errorMessage.tryEmit("Bootstrap failed: ${e.message}")
            }
        }
    }

    fun createEnvironment(
        name: String,
        distribution: Distribution = Distribution.DEBIAN,
        architecture: Architecture = Architecture.ARM64,
        autoBootstrap: Boolean = true,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val trimmedName = name.trim().ifEmpty { "${distribution.displayName} (${architecture.abiName})" }
                val id = EnvironmentId.generate()
                log.info("Creating environment '$trimmedName' ($id) with $distribution")

                storage.initializeEnvironmentDirs(id)

                val metadata = EnvironmentMetadata(
                    id = id,
                    name = trimmedName,
                    distribution = distribution,
                    architecture = architecture,
                )

                val environment = Environment(
                    metadata = metadata,
                    configuration = EnvironmentConfiguration(),
                    state = EnvironmentState.CREATED,
                    rootfsPath = storage.rootfsDir(id).absolutePath,
                    metadataPath = storage.metadataDir(id).absolutePath,
                )

                dao.insert(EnvironmentMapper.toEntity(environment))

                if (autoBootstrap) {
                    installRootfs(environment)
                }
            } catch (e: Exception) {
                log.error("Failed to create environment", e)
                _errorMessage.tryEmit(e.message ?: "Failed to create environment")
            }
        }
    }

    fun installRootfs(environment: Environment) {
        viewModelScope.launch(Dispatchers.IO) {
            val envId = environment.id.value
            try {
                log.info("Starting rootfs installation for $envId")
                dao.updateState(
                    id = envId,
                    state = EnvironmentState.INSTALLING.name,
                    timestamp = System.currentTimeMillis(),
                    failureMessage = null,
                )

                _installerLogs.update { it + (envId to listOf(">>> Starting ${environment.distribution.displayName} rootfs installation...")) }

                bootstrapper.bootstrapRootfs(
                    environment = environment,
                    onProgress = { progress, status ->
                        _installProgress.update { it + (envId to progress) }
                        _installStatusText.update { it + (envId to status) }
                    },
                    onLog = { line ->
                        _installerLogs.update { map ->
                            val current = map[envId] ?: emptyList()
                            map + (envId to (current + line).takeLast(500))
                        }
                    }
                )

                // Verify and update to READY
                dao.updateState(
                    id = envId,
                    state = EnvironmentState.READY.name,
                    timestamp = System.currentTimeMillis(),
                    failureMessage = null,
                )
                _installProgress.update { it - envId }
                _installStatusText.update { it - envId }
                log.info("Rootfs installed and environment $envId is READY")
            } catch (e: Exception) {
                log.error("Failed to install rootfs for $envId", e)
                dao.updateState(
                    id = envId,
                    state = EnvironmentState.FAILED.name,
                    timestamp = System.currentTimeMillis(),
                    failureMessage = e.message ?: "Installation failed",
                )
                _installProgress.update { it - envId }
                _installStatusText.update { it - envId }
                _errorMessage.tryEmit("Bootstrap failed: ${e.message}")
            }
        }
    }

    fun startEnvironment(environment: Environment, startMode: StartMode = StartMode.GUI) {
        viewModelScope.launch(Dispatchers.IO) {
            val envId = environment.id.value
            try {
                log.info("[RUNTIME] Start requested: env=$envId startMode=${startMode.name}")
                dao.updateState(
                    id = envId,
                    state = EnvironmentState.STARTING.name,
                    timestamp = System.currentTimeMillis(),
                    failureMessage = null,
                )

                sessionManager.startSession(environment, startMode)

                dao.updateState(
                    id = envId,
                    state = EnvironmentState.RUNNING.name,
                    timestamp = System.currentTimeMillis(),
                    failureMessage = null,
                )

                // Start Foreground Service
                LinuxSessionService.start(context, environment.name)
                log.info("Environment $envId is now RUNNING (startMode=${startMode.name})")
            } catch (e: Exception) {
                log.error("Failed to start environment $envId in ${startMode.name} mode", e)
                dao.updateState(
                    id = envId,
                    state = EnvironmentState.FAILED.name,
                    timestamp = System.currentTimeMillis(),
                    failureMessage = e.message ?: "Startup failed",
                )
                _errorMessage.tryEmit("Failed to start: ${e.message}")
            }
        }
    }

    fun stopEnvironment(environment: Environment) {
        viewModelScope.launch(Dispatchers.IO) {
            val envId = environment.id.value
            try {
                log.info("Stopping runtime for $envId")
                dao.updateState(
                    id = envId,
                    state = EnvironmentState.STOPPING.name,
                    timestamp = System.currentTimeMillis(),
                    failureMessage = null,
                )

                val activeSession = sessionManager.getSession(environment.id)
                if (activeSession != null) {
                    sessionManager.stopSession(activeSession.id)
                } else {
                    runtimeBackend.stop(environment)
                }

                dao.updateState(
                    id = envId,
                    state = EnvironmentState.STOPPED.name,
                    timestamp = System.currentTimeMillis(),
                    failureMessage = null,
                )

                // Stop foreground service if no environments are running
                LinuxSessionService.stop(context, envId)
                log.info("Environment $envId is now STOPPED")
            } catch (e: Exception) {
                log.error("Failed to stop environment $envId", e)
                dao.updateState(
                    id = envId,
                    state = EnvironmentState.FAILED.name,
                    timestamp = System.currentTimeMillis(),
                    failureMessage = e.message ?: "Stop failed",
                )
            }
        }
    }

    fun restartEnvironment(environment: Environment, startMode: StartMode = StartMode.GUI) {
        viewModelScope.launch(Dispatchers.IO) {
            val envId = environment.id.value
            try {
                log.info("Restarting environment $envId (startMode=${startMode.name})")
                val activeSession = sessionManager.getSession(environment.id)
                if (activeSession != null) {
                    sessionManager.stopSession(activeSession.id)
                } else {
                    runtimeBackend.stop(environment)
                }
                runtimeBackend.initialize(environment)

                if (environment.state == EnvironmentState.FAILED) {
                    dao.updateState(
                        id = envId,
                        state = EnvironmentState.RECOVERING.name,
                        timestamp = System.currentTimeMillis(),
                        failureMessage = null,
                    )
                    dao.updateState(
                        id = envId,
                        state = EnvironmentState.READY.name,
                        timestamp = System.currentTimeMillis(),
                        failureMessage = null,
                    )
                }

                dao.updateState(
                    id = envId,
                    state = EnvironmentState.STARTING.name,
                    timestamp = System.currentTimeMillis(),
                    failureMessage = null,
                )

                val readyEnv = if (environment.state == EnvironmentState.FAILED) {
                    environment.withState(EnvironmentState.RECOVERING).withState(EnvironmentState.READY)
                } else {
                    environment
                }
                sessionManager.startSession(readyEnv, startMode)

                dao.updateState(
                    id = envId,
                    state = EnvironmentState.RUNNING.name,
                    timestamp = System.currentTimeMillis(),
                    failureMessage = null,
                )
                LinuxSessionService.start(context, environment.name)
                log.info("Environment $envId restarted and is RUNNING (startMode=${startMode.name})")
            } catch (e: Exception) {
                log.error("Failed to restart environment $envId", e)
                dao.updateState(
                    id = envId,
                    state = EnvironmentState.FAILED.name,
                    timestamp = System.currentTimeMillis(),
                    failureMessage = e.message ?: "Restart failed",
                )
                _errorMessage.tryEmit("Failed to restart: ${e.message}")
            }
        }
    }

    fun updateConfiguration(environment: Environment, config: EnvironmentConfiguration) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val updated = environment.copy(configuration = config)
                dao.update(EnvironmentMapper.toEntity(updated))
                log.info("Updated configuration for environment ${environment.id}")
            } catch (e: Exception) {
                log.error("Failed to update environment configuration", e)
                _errorMessage.tryEmit("Failed to save settings: ${e.message}")
            }
        }
    }

    fun deleteEnvironment(environment: Environment) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (environment.state.isActive()) {
                    runtimeBackend.stop(environment)
                }
                dao.deleteById(environment.id.value)
                log.info("Deleted environment record for ${environment.id}")
            } catch (e: Exception) {
                log.error("Failed to delete environment record", e)
            }
        }
    }
}

