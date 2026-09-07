package com.linuxdroid.core.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ComponentProvenanceTest {

    @Test
    fun testAllThreeComponentsPresentAndPinned() {
        val manager = ComponentProvenanceManager()
        val components = manager.getComponents()

        // Wayland, Weston, wayland-protocols, and pixman are Linux rootfs dependencies
        // supplied by the Linux distribution package manager. They are not Android project
        // Git submodules and are not tracked in components_provenance.json.
        assertThat(components.keys).containsExactly(
            "PRoot",
            "LDDM",
            "LDDE"
        )

        val proot = manager.getComponent("PRoot")
        assertThat(proot.repository).isEqualTo("LinuxDroidapp/proot")
        assertThat(proot.revision).isEqualTo("caadcae0e7697ec29f02e231a3a88866561aacd0")

        val lddm = manager.getComponent("LDDM")
        assertThat(lddm.repository).isEqualTo("LinuxDroidapp/LDDM")
        assertThat(lddm.revision).isEqualTo("6ad1e190d76f87e9c4ef6c14155ed09f6f625a24")

        val ldde = manager.getComponent("LDDE")
        assertThat(ldde.repository).isEqualTo("LinuxDroidapp/LDDE")
        assertThat(ldde.revision).isEqualTo("1b8c170081b8c8f7085b3f7457d42f12d963b58c")
    }

    @Test
    fun testFormatComponentsBlockMatchesSpecification() {
        val manager = ComponentProvenanceManager()
        val formatted = manager.formatComponentsBlock()

        val expected = """
=== LINUXDROID COMPONENTS ===
PRoot:            LinuxDroidapp/proot@caadcae0e7697ec29f02e231a3a88866561aacd0
LDDM:             LinuxDroidapp/LDDM@6ad1e190d76f87e9c4ef6c14155ed09f6f625a24
LDDE:             LinuxDroidapp/LDDE@1b8c170081b8c8f7085b3f7457d42f12d963b58c
""".trimIndent()

        assertThat(formatted.trim()).isEqualTo(expected.trim())
    }
}
