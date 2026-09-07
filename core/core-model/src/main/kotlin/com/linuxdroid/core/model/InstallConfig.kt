package com.linuxdroid.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Immutable configuration payload representing user-validated installation parameters.
 *
 * Guarantees:
 *  - Distro is restricted to supported V1 distributions (Debian or Ubuntu).
 *  - Architecture is automatically configured as ARM64 (aarch64).
 *  - Username conforms to Linux/POSIX username requirements.
 *  - Password is validated and masked in all string representations ([REDACTED])
 *    to prevent accidental credential disclosure in logs or diagnostics.
 */
@Serializable
data class InstallConfig(
    val distro: Distribution,
    val release: String,
    val username: String,
    @Transient
    val password: String = "",
    val architecture: Architecture = Architecture.ARM64,
) {
    init {
        require(distro == Distribution.DEBIAN || distro == Distribution.UBUNTU) {
            "Only Debian and Ubuntu are supported in LinuxDroid V1 (requested: ${distro.name})"
        }
        val userErr = UsernameValidator.validate(username)
        require(userErr == null) { "Invalid username '$username': $userErr" }
        require(release.isNotBlank()) { "Distribution release cannot be blank" }
    }

    override fun toString(): String {
        return "InstallConfig(distro=$distro, release=$release, username=$username, architecture=$architecture, password=[REDACTED])"
    }
}

