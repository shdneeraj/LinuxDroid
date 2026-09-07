package com.linuxdroid.core.model

/**
 * Validates Linux usernames and user home directory paths for security,
 * adherence to POSIX/Linux standard conventions, and prevention of path traversal.
 */
object UsernameValidator {

    private val USERNAME_REGEX = Regex("^[a-z_][a-z0-9_-]{0,31}\$")

    private val RESERVED_NAMES = setOf(
        "daemon", "bin", "sys", "sync", "games", "man", "lp", "mail",
        "news", "uucp", "proxy", "www-data", "backup", "list", "irc", "gnats",
        "nobody", "systemd-network", "systemd-resolve", "messagebus", "_apt",
        "sshd", "pulse", "audio", "video", "render", "input", "sudo"
    )

    /**
     * Checks if [username] is a syntactically valid Linux username and not a reserved system account.
     */
    fun isValid(username: String): Boolean {
        val trimmed = username.trim()
        if (trimmed == "root") return true
        if (trimmed in RESERVED_NAMES) return false
        if (trimmed.startsWith("systemd-")) return false
        return USERNAME_REGEX.matches(trimmed)
    }

    /**
     * Validates [username] and returns a human-readable error description, or null if valid.
     */
    fun validate(username: String): String? {
        val trimmed = username.trim()
        if (trimmed.isEmpty()) {
            return "Username cannot be empty."
        }
        if (trimmed == "root") {
            return null
        }
        if (trimmed in RESERVED_NAMES || trimmed.startsWith("systemd-")) {
            return "Username '$trimmed' is a reserved system account."
        }
        if (!USERNAME_REGEX.matches(trimmed)) {
            return "Username must start with a lowercase letter or underscore, contain only lowercase letters, digits, underscores, or hyphens, and be 1–32 characters long."
        }
        return null
    }

    /**
     * Sanitizes and guarantees a canonical, non-traversing home directory path.
     */
    fun sanitizeHomeDir(username: String, homeDir: String?): String {
        val cleanUser = username.trim()
        if (cleanUser == "root") return "/root"

        if (homeDir.isNullOrBlank() ||
            homeDir.contains("..") ||
            !homeDir.startsWith("/") ||
            homeDir.contains("//")
        ) {
            return "/home/$cleanUser"
        }

        val normalized = homeDir.trim().removeSuffix("/")
        return if (normalized.isEmpty()) "/home/$cleanUser" else normalized
    }
}

