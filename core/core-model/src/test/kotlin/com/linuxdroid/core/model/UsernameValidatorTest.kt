package com.linuxdroid.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for UsernameValidator.
 * Validates Linux username formats, reserved accounts, and path traversal prevention.
 */
class UsernameValidatorTest {

    @Test
    fun `root username is always valid`() {
        assertThat(UsernameValidator.isValid("root")).isTrue()
        assertThat(UsernameValidator.validate("root")).isNull()
    }

    @Test
    fun `standard user names are valid`() {
        val validNames = listOf("droid", "user", "developer", "john_doe", "linux-user", "a123")
        for (name in validNames) {
            assertThat(UsernameValidator.isValid(name)).isTrue()
            assertThat(UsernameValidator.validate(name)).isNull()
        }
    }

    @Test
    fun `reserved system accounts are rejected`() {
        val reserved = listOf("daemon", "bin", "sys", "nobody", "systemd-network", "sudo", "www-data")
        for (name in reserved) {
            assertThat(UsernameValidator.isValid(name)).isFalse()
            assertThat(UsernameValidator.validate(name)).isNotNull()
        }
    }

    @Test
    fun `empty or blank usernames are rejected`() {
        assertThat(UsernameValidator.isValid("")).isFalse()
        assertThat(UsernameValidator.isValid("   ")).isFalse()
        assertThat(UsernameValidator.validate("")).isNotNull()
    }

    @Test
    fun `invalid characters and formats are rejected`() {
        val invalid = listOf("1user", "User", "droid@home", "user name", "user#1", "very_long_username_that_exceeds_thirty_two_characters_limit")
        for (name in invalid) {
            assertThat(UsernameValidator.isValid(name)).isFalse()
            assertThat(UsernameValidator.validate(name)).isNotNull()
        }
    }

    @Test
    fun `sanitizeHomeDir handles root user correctly`() {
        assertThat(UsernameValidator.sanitizeHomeDir("root", "/home/root")).isEqualTo("/root")
        assertThat(UsernameValidator.sanitizeHomeDir("root", null)).isEqualTo("/root")
    }

    @Test
    fun `sanitizeHomeDir sanitizes valid custom path`() {
        assertThat(UsernameValidator.sanitizeHomeDir("droid", "/home/droid")).isEqualTo("/home/droid")
        assertThat(UsernameValidator.sanitizeHomeDir("droid", "/home/droid/")).isEqualTo("/home/droid")
        assertThat(UsernameValidator.sanitizeHomeDir("droid", "/custom/work")).isEqualTo("/custom/work")
    }

    @Test
    fun `sanitizeHomeDir prevents directory traversal attacks`() {
        // Must reject .. traversal and default to /home/<user>
        assertThat(UsernameValidator.sanitizeHomeDir("droid", "/home/droid/../../etc")).isEqualTo("/home/droid")
        assertThat(UsernameValidator.sanitizeHomeDir("droid", "../etc/shadow")).isEqualTo("/home/droid")
        assertThat(UsernameValidator.sanitizeHomeDir("droid", "relative/path")).isEqualTo("/home/droid")
        assertThat(UsernameValidator.sanitizeHomeDir("droid", "/home//droid")).isEqualTo("/home/droid")
        assertThat(UsernameValidator.sanitizeHomeDir("droid", null)).isEqualTo("/home/droid")
        assertThat(UsernameValidator.sanitizeHomeDir("droid", "")).isEqualTo("/home/droid")
    }
}

