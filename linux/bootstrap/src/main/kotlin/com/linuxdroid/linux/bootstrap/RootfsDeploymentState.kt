package com.linuxdroid.linux.bootstrap

import kotlinx.serialization.Serializable

/**
 * Formal lifecycle states for the LinuxDroid rootfs graphical deployment pipeline.
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
 * ROOTFS_GRAPHICS_DEPLOYING
 *       ↓
 * ROOTFS_PACKAGES_INSTALLING
 *       ↓
 * ROOTFS_VALIDATING
 *       ↓
 * ROOTFS_READY
 * ```
 *
 * Failure states:
 * Any failure during graphics deployment, package installation, or validation
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

    /** Wayland libraries and Weston compositor deployment in progress. */
    ROOTFS_GRAPHICS_DEPLOYING("Deploying Wayland and Weston graphical stack"),

    /** LinuxDroid display manager (LDDM) and desktop environment (LDDE) .deb packages installing. */
    ROOTFS_PACKAGES_INSTALLING("Installing LinuxDroid packages (LDDM & LDDE)"),

    /** Comprehensive end-to-end validation of the complete graphical stack. */
    ROOTFS_VALIDATING("Validating graphical Linux stack"),

    /** Pre-install: Initial archive acquisition, extraction, and base filesystem preparation. */
    PRE_INSTALLING("Pre-installing base root filesystem"),

    /** Pre-install completed: base rootfs extracted, LDDM/LDDE staged, install.conf written. */
    PRE_INSTALL_READY("Pre-install ready"),

    /** Post-install: Starting Linux userspace CLI post-install session. */
    POST_INSTALL_STARTING("Starting post-install environment"),

    /** Post-install: In-guest package and user configuration in progress. */
    POST_INSTALLING("Executing guest post-install configuration"),

    /** Post-install: In-guest execution failed. */
    POST_INSTALL_FAILED("Post-install configuration failed"),

    /** Post-install: Completed successfully inside Linux userspace. */
    POST_INSTALL_COMPLETE("Post-install configuration complete"),

    /** Full installation completed and environment is ready for normal usage. */
    INSTALLATION_COMPLETE("Installation complete"),

    /** Rootfs is complete, fully verified, and ready to run. */
    ROOTFS_READY("Root filesystem ready"),

    /** Deployment failed; rootfs is incomplete and unusable. */
    ROOTFS_DEPLOYMENT_FAILED("Root filesystem deployment failed");

    fun isReady(): Boolean = this == ROOTFS_READY || this == INSTALLATION_COMPLETE
    fun isFailed(): Boolean = this == ROOTFS_DEPLOYMENT_FAILED || this == POST_INSTALL_FAILED
}

