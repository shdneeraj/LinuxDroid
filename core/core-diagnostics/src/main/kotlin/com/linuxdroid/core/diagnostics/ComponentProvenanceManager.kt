package com.linuxdroid.core.diagnostics

import android.content.Context
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.StackProvenance
import com.linuxdroid.core.model.SubmoduleComponent
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Manages runtime discovery, auditing, and diagnostic rendering of LinuxDroid core
 * Git submodule components (PRoot, LDDM, LDDE).
 *
 * Note: Wayland, Weston, wayland-protocols, and pixman are Linux rootfs dependencies
 * supplied by the Linux distribution package manager. They are not Android project
 * Git submodules and are not tracked here.
 */
class ComponentProvenanceManager(
    private val context: Context? = null,
) {
    private val log = LinuxDroidLogger(LogSubsystem.DIAGNOSTICS)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        const val PROOT_COMMIT = "caadcae0e7697ec29f02e231a3a88866561aacd0"
        const val LDDM_COMMIT = "6ad1e190d76f87e9c4ef6c14155ed09f6f625a24"
        const val LDDE_COMMIT = "1b8c170081b8c8f7085b3f7457d42f12d963b58c"

        val DEFAULT_COMPONENTS: Map<String, SubmoduleComponent> = linkedMapOf(
            "PRoot" to SubmoduleComponent("PRoot", "LinuxDroidapp/proot", "vendor/proot", PROOT_COMMIT),
            "LDDM" to SubmoduleComponent("LDDM", "LinuxDroidapp/LDDM", "vendor/LDDM", LDDM_COMMIT),
            "LDDE" to SubmoduleComponent("LDDE", "LinuxDroidapp/LDDE", "vendor/LDDE", LDDE_COMMIT),
        )
    }

    private val cachedComponents: Map<String, SubmoduleComponent> by lazy {
        loadComponents()
    }

    fun getComponents(): Map<String, SubmoduleComponent> = cachedComponents

    fun getComponent(name: String): SubmoduleComponent {
        return cachedComponents[name]
            ?: DEFAULT_COMPONENTS[name]
            ?: SubmoduleComponent(name, "LinuxDroidapp/$name", "vendor/$name", "unknown")
    }

    private fun loadComponents(): Map<String, SubmoduleComponent> {
        // 1. Try reading from context assets
        try {
            context?.assets?.open("components_provenance.json")?.use { stream ->
                val text = stream.bufferedReader().use { it.readText() }
                val parsed = json.decodeFromString<StackProvenance>(text)
                if (parsed.components.isNotEmpty()) {
                    return parsed.components.mapValues { (k, v) ->
                        if (v.name.isEmpty()) v.copy(name = k) else v
                    }
                }
            }
        } catch (e: Throwable) {
            log.debug("Assets provenance load skipped: ${e.message}")
        }

        // 2. Try filesystem paths (e.g. during testing or desktop runs)
        for (candidatePath in listOf(
            "app/src/main/assets/components_provenance.json",
            "../app/src/main/assets/components_provenance.json",
            "../../app/src/main/assets/components_provenance.json"
        )) {
            val file = File(candidatePath)
            if (file.exists()) {
                try {
                    val parsed = json.decodeFromString<StackProvenance>(file.readText())
                    if (parsed.components.isNotEmpty()) {
                        return parsed.components.mapValues { (k, v) ->
                            if (v.name.isEmpty()) v.copy(name = k) else v
                        }
                    }
                } catch (e: Throwable) {
                    log.debug("File provenance load error: ${e.message}")
                }
            }
        }

        return DEFAULT_COMPONENTS
    }

    /**
     * Formats the LINUXDROID COMPONENTS text block for diagnostics logs and reports.
     * Only includes LinuxDroid-owned Git submodule components (PRoot, LDDM, LDDE).
     * Wayland/Weston/Pixman are Linux rootfs dependencies and are not listed here.
     */
    fun formatComponentsBlock(): String {
        val proot = getComponent("PRoot")
        val lddm = getComponent("LDDM")
        val ldde = getComponent("LDDE")

        return buildString {
            appendLine("=== LINUXDROID COMPONENTS ===")
            appendLine("PRoot:            ${proot.repository}@${proot.revision}")
            appendLine("LDDM:             ${lddm.repository}@${lddm.revision}")
            appendLine("LDDE:             ${ldde.repository}@${ldde.revision}")
        }
    }
}
