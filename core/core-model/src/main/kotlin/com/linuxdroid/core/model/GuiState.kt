package com.linuxdroid.core.model

import kotlinx.serialization.Serializable

/**
 * Represents the installation state of the optional GUI layer within a Linux environment.
 *
 * The GUI is independent from the CLI foundation. A failed or missing GUI state
 * never invalidates the base CLI environment.
 *
 * State machine:
 * ```
 * NOT_INSTALLED ──► INSTALLING ──► INSTALLED
 *       ▲               │                │
 *       │               ▼                │
 *       │            FAILED ◄────────────┘
 *       │               │
 *       └───────────────┘ (retry)
 *
 * INSTALLED ──► REPAIRING ──► INSTALLED
 *                    │
 *                    ▼
 *                 FAILED
 * ```
 */
@Serializable
enum class GuiState(val displayName: String) {
    /** GUI packages have never been installed in this environment. */
    NOT_INSTALLED("Not installed"),

    /** GUI installation is currently in progress. */
    INSTALLING("Installing GUI"),

    /** GUI packages are installed and validated. */
    INSTALLED("Installed"),

    /** A GUI repair operation is currently in progress. */
    REPAIRING("Repairing GUI"),

    /** The last GUI installation or repair attempt failed. CLI environment is unaffected. */
    FAILED("Installation failed");

    /** Returns true if the GUI is fully installed and ready to boot. */
    val isInstalled: Boolean get() = this == INSTALLED

    /** Returns true if GUI needs to be installed or reinstalled. */
    val needsInstall: Boolean get() = this == NOT_INSTALLED || this == FAILED

    /** Returns true if a GUI installation or repair operation is currently in progress. */
    val isInProgress: Boolean get() = this == INSTALLING || this == REPAIRING

    companion object {
        /**
         * Parses a [GuiState] from its serialized name string.
         * Returns [NOT_INSTALLED] if the string is null, blank, or unrecognized.
         */
        fun fromString(value: String?): GuiState {
            if (value.isNullOrBlank()) return NOT_INSTALLED
            return runCatching { valueOf(value.uppercase()) }.getOrDefault(NOT_INSTALLED)
        }
    }
}
