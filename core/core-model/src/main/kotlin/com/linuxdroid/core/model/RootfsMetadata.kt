package com.linuxdroid.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Metadata recorded for an installed Linux root filesystem environment.
 * Persisted inside `<env-dir>/metadata/rootfs-manifest.json`.
 */
@Serializable
data class RootfsMetadata(
    val distribution: String,
    val release: String,
    val architecture: String,
    val variant: String = "minimal",
    val source: String,
    val artifact: String,
    @SerialName("checksum_algorithm")
    val checksumAlgorithm: String,
    val checksum: String,
    @SerialName("bootstrap_version")
    val bootstrapVersion: String = "1.0.0",
    val status: String = "ready",
    @SerialName("deployment_state")
    val deploymentState: String = "ROOTFS_READY",
    @SerialName("lddm_version")
    val lddmVersion: String? = null,
    @SerialName("ldde_version")
    val lddeVersion: String? = null,
    @SerialName("weston_version")
    val westonVersion: String? = null,
    @SerialName("wayland_version")
    val waylandVersion: String? = null,
    @SerialName("installed_at")
    val installedAt: Long = System.currentTimeMillis(),
)

