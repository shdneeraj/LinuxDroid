package com.linuxdroid.core.model

import kotlinx.serialization.Serializable

/**
 * Authoritative, canonical representation of requested Linux startup mode.
 *
 * Only [GUI] and [CLI] are valid modes.
 */
@Serializable
enum class StartMode {
    GUI,
    CLI;

    companion object {
        /**
         * Resolves a [StartMode] from string representation.
         * Deterministically throws [IllegalArgumentException] for invalid or empty inputs.
         */
        fun fromString(value: String?): StartMode = when (value?.trim()?.uppercase()) {
            "GUI" -> GUI
            "CLI" -> CLI
            else -> throw IllegalArgumentException("Invalid startMode: '$value'. Only 'GUI' and 'CLI' are allowed.")
        }

        /**
         * Resolves a [StartMode] from string representation with fallback default.
         */
        fun fromStringOrDefault(value: String?, default: StartMode = GUI): StartMode = when (value?.trim()?.uppercase()) {
            "GUI" -> GUI
            "CLI" -> CLI
            else -> default
        }
    }
}

