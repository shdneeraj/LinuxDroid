package com.linuxdroid.core.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GuestInitModeTest {

    @Test
    fun `GuestInit script contains single canonical branching on StartMode`() {
        val script = GuestInit.SCRIPT_CONTENT

        // Exactly one guest init script with common initialization
        assertThat(script).contains("# /sbin/linuxdroid-init")
        assertThat(script).contains("echo \"[INFO] Guest ready\"")

        // Structured startMode resolution
        assertThat(script).contains("START_MODE=\"\${LINUXDROID_START_MODE:-")
        assertThat(script).contains("echo \"[GUEST-INIT] startMode=\$START_MODE\" >&2")

        // GUI branch
        assertThat(script).contains("echo \"[GUEST-INIT] Handing over to LDDM\" >&2")
        assertThat(script).contains("echo \"[LDDM] Starting graphical session\"")
        assertThat(script).contains("/usr/bin/lddm")

        // CLI branch
        assertThat(script).contains("echo \"[GUEST-INIT] Starting CLI session\" >&2")
        assertThat(script).contains("exec \"\$SHELL\" -l")

        // Deterministic rejection of missing or invalid startMode
        assertThat(script).contains("Missing startMode: LINUXDROID_START_MODE is not set. Deterministic startup requires 'GUI' or 'CLI'.")
        assertThat(script).contains("Invalid startMode: '\$START_MODE'. Only 'GUI' and 'CLI' are allowed.")
    }
}

