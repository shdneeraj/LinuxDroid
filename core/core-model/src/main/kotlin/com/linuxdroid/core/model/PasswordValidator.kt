package com.linuxdroid.core.model

/**
 * Validates Linux account passwords for LinuxDroid environment installations.
 *
 * Enforces security requirements:
 *  - Password cannot be empty or solely whitespace.
 *  - Minimum length of 4 characters.
 *  - Optional confirmation password match verification.
 *  - Explicitly does NOT echo password values in error strings or logs.
 */
object PasswordValidator {

    const val MIN_LENGTH = 4

    /**
     * Checks if [password] satisfies the basic password requirements.
     */
    fun isValid(password: String): Boolean {
        return validate(password) == null
    }

    /**
     * Validates [password] and optional [confirmPassword].
     * Returns a human-readable error description, or null if valid.
     */
    fun validate(password: String, confirmPassword: String? = null): String? {
        if (password.isEmpty()) {
            return "Password cannot be empty."
        }
        if (password.trim().isEmpty()) {
            return "Password cannot consist solely of whitespace."
        }
        if (password.length < MIN_LENGTH) {
            return "Password must be at least $MIN_LENGTH characters long."
        }
        if (confirmPassword != null && password != confirmPassword) {
            return "Passwords do not match."
        }
        return null
    }
}

