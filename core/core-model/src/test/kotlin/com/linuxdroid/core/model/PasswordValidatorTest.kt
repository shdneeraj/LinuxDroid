package com.linuxdroid.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PasswordValidatorTest {

    @Test
    fun `valid password passes validation`() {
        assertThat(PasswordValidator.isValid("secret123")).isTrue()
        assertThat(PasswordValidator.validate("secret123")).isNull()
    }

    @Test
    fun `empty password fails validation`() {
        assertThat(PasswordValidator.isValid("")).isFalse()
        assertThat(PasswordValidator.validate("")).contains("empty")
    }

    @Test
    fun `whitespace-only password fails validation`() {
        assertThat(PasswordValidator.isValid("    ")).isFalse()
        assertThat(PasswordValidator.validate("    ")).contains("whitespace")
    }

    @Test
    fun `short password fails validation`() {
        assertThat(PasswordValidator.isValid("abc")).isFalse()
        assertThat(PasswordValidator.validate("abc")).contains("at least 4 characters")
    }

    @Test
    fun `mismatched confirmation password fails validation`() {
        assertThat(PasswordValidator.validate("secret123", "different123")).contains("match")
    }

    @Test
    fun `matching confirmation password passes validation`() {
        assertThat(PasswordValidator.validate("secret123", "secret123")).isNull()
    }
}

