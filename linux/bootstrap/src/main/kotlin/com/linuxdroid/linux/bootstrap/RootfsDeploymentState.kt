package com.linuxdroid.linux.bootstrap

import kotlinx.serialization.Serializable

/**
 * Formal lifecycle states for the LinuxDroid rootfs CLI deployment pipeline.
 *
 * Sequence:
 * ```
 * ROOTFS_CREATING
 *       ↓
 * ROOTFS_EXTRACTED
 *       ↓
 * ROOTFS_CONFIGURING
 *       ↓
 * ROOTFS_RUNTIME_READY
 *       ↓
 * ROOTFS_PACKAGES_INSTALLING
 *       ↓
 * ROOTFS_VALIDATING
 *       ↓
 * ROOTFS_READY
 * ```
 *
 * The base deployment guarantees a stable, fully functional Linux CLI environment.
 * The graphical layer (GUI) is optional and installed independently via [GuiInstaller].
 *
 * Failure states:
 * Any failure during archive creation, extraction, configuration, packaging, or validation
 * transitions to [ROOTFS_DEPLOYMENT_FAILED]. An incomplete rootfs is never marked [ROOTFS_READY].
 */
@Serializable
enum class RootfsDeploymentState(val displayName: String) {
    /** Initial archive acquisition and verification. */
    ROOTFS_CREATING("Creating root filesystem"),

    /** Archive successfully unpacked into staging area. */
    ROOTFS_EXTRACTED("Root filesystem extracted"),

    /** System files, DNS, hostname, environment, and APT sources configured. */
    ROOTFS_CONFIGURING("Configuring guest system files"),

    /** Core runtime infrastructure, persistent guest init, and mounts prepared. */
    ROOTFS_RUNTIME_READY("Runtime infrastructure ready"),

    /** Standard CLI package baseline and user configuration installing in guest userspace. */
    ROOTFS_PACKAGES_INSTALLING("Installing standard Linux packages"),

    /** Comprehensive end-to-end validation of the CLI Linux stack. */
    ROOTFS_VALIDATING("Validating Linux root filesystem"),

    /** Rootfs is complete, fully verified, and ready for CLI operations. */
    ROOTFS_READY("Root filesystem ready"),

    /** Deployment failed; rootfs is incomplete and unusable. */
    ROOTFS_DEPLOYMENT_FAILED("Root filesystem deployment failed");

    fun isReady(): Boolean = this == ROOTFS_READY
    fun isFailed(): Boolean = this == ROOTFS_DEPLOYMENT_FAILED
}
