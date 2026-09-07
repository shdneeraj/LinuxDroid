package com.linuxdroid.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linuxdroid.core.database.dao.EnvironmentDao
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.Environment
import com.linuxdroid.core.package_mgr.PackageInfo
import com.linuxdroid.core.package_mgr.PackageManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel managing package manager operations (install, remove, update, search)
 * inside a Linux environment.
 */
@HiltViewModel
class PackageManagerViewModel @Inject constructor(
    private val packageManager: PackageManager,
    private val dao: EnvironmentDao,
    private val storage: EnvironmentStorage,
) : ViewModel() {

    private val log = LinuxDroidLogger(LogSubsystem.PACKAGE)

    private val _packageLogs = MutableStateFlow<List<String>>(emptyList())
    val packageLogs: StateFlow<List<String>> = _packageLogs.asStateFlow()

    private val _isOperating = MutableStateFlow(false)
    val isOperating: StateFlow<Boolean> = _isOperating.asStateFlow()

    private val _operationStatus = MutableStateFlow<String?>(null)
    val operationStatus: StateFlow<String?> = _operationStatus.asStateFlow()

    private val _searchResults = MutableStateFlow<List<PackageInfo>>(emptyList())
    val searchResults: StateFlow<List<PackageInfo>> = _searchResults.asStateFlow()

    private fun appendLog(line: String) {
        _packageLogs.update { (it + line).takeLast(500) }
    }

    fun installPackage(environment: Environment, packageName: String) {
        if (packageName.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            _isOperating.value = true
            _operationStatus.value = "Installing $packageName..."
            appendLog("[PACKAGE][START][INSTALL][$packageName]")
            val startTime = System.currentTimeMillis()
            try {
                val success = packageManager.install(environment, packageName.trim())
                val dur = System.currentTimeMillis() - startTime
                if (success) {
                    appendLog("[PACKAGE][SUCCESS][INSTALL][$packageName] duration_ms=$dur")
                    _operationStatus.value = "Successfully installed $packageName"
                } else {
                    appendLog("[PACKAGE][FAIL][INSTALL][$packageName] duration_ms=$dur")
                    _operationStatus.value = "Failed to install $packageName"
                }
            } catch (e: Exception) {
                val dur = System.currentTimeMillis() - startTime
                appendLog("[PACKAGE][FAIL][INSTALL][$packageName] error=${e.message} duration_ms=$dur")
                _operationStatus.value = "Error: ${e.message}"
            } finally {
                _isOperating.value = false
            }
        }
    }

    fun removePackage(environment: Environment, packageName: String) {
        if (packageName.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            _isOperating.value = true
            _operationStatus.value = "Removing $packageName..."
            appendLog("[PACKAGE][START][REMOVE][$packageName]")
            val startTime = System.currentTimeMillis()
            try {
                val success = packageManager.remove(environment, packageName.trim())
                val dur = System.currentTimeMillis() - startTime
                if (success) {
                    appendLog("[PACKAGE][SUCCESS][REMOVE][$packageName] duration_ms=$dur")
                    _operationStatus.value = "Successfully removed $packageName"
                } else {
                    appendLog("[PACKAGE][FAIL][REMOVE][$packageName] duration_ms=$dur")
                    _operationStatus.value = "Failed to remove $packageName"
                }
            } catch (e: Exception) {
                val dur = System.currentTimeMillis() - startTime
                appendLog("[PACKAGE][FAIL][REMOVE][$packageName] error=${e.message} duration_ms=$dur")
                _operationStatus.value = "Error: ${e.message}"
            } finally {
                _isOperating.value = false
            }
        }
    }

    fun updatePackages(environment: Environment) {
        viewModelScope.launch(Dispatchers.IO) {
            _isOperating.value = true
            _operationStatus.value = "Updating package index and packages..."
            appendLog("[PACKAGE][START][UPDATE][all]")
            val startTime = System.currentTimeMillis()
            try {
                val success = packageManager.update(environment)
                val dur = System.currentTimeMillis() - startTime
                if (success) {
                    appendLog("[PACKAGE][SUCCESS][UPDATE][all] duration_ms=$dur")
                    _operationStatus.value = "Packages updated successfully"
                } else {
                    appendLog("[PACKAGE][FAIL][UPDATE][all] duration_ms=$dur")
                    _operationStatus.value = "Package update failed"
                }
            } catch (e: Exception) {
                val dur = System.currentTimeMillis() - startTime
                appendLog("[PACKAGE][FAIL][UPDATE][all] error=${e.message} duration_ms=$dur")
                _operationStatus.value = "Error: ${e.message}"
            } finally {
                _isOperating.value = false
            }
        }
    }

    fun searchPackages(environment: Environment, query: String) {
        if (query.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            _isOperating.value = true
            _operationStatus.value = "Searching for '$query'..."
            try {
                val results = packageManager.search(environment, query.trim())
                _searchResults.value = results
                _operationStatus.value = "Found ${results.size} packages"
            } catch (e: Exception) {
                _operationStatus.value = "Search error: ${e.message}"
            } finally {
                _isOperating.value = false
            }
        }
    }

    fun clearLogs() {
        _packageLogs.value = emptyList()
    }
}
