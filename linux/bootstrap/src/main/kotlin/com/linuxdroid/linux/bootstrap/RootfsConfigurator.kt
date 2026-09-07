package com.linuxdroid.linux.bootstrap

import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.DistributionDefinition
import java.io.File
import java.nio.file.Files

/**
 * Owns the base configuration of Linux guest filesystem configuration files.
 *
 * Configures DNS (/etc/resolv.conf), hostnames (/etc/hostname, /etc/hosts),
 * session environment variables (/etc/environment), and APT sources.
 */
class RootfsConfigurator(
    private val log: LinuxDroidLogger = LinuxDroidLogger(LogSubsystem.BOOTSTRAP),
) {

    /**
     * Configures essential guest system files in [rootfsDir] based on [definition].
     */
    fun configure(rootfsDir: File, definition: DistributionDefinition) {
        log.info("[ROOTFS_CONFIG] Configuring guest system files for ${definition.name} (${definition.release})")

        // 1. DNS Configuration
        // In modern distributions, /etc/resolv.conf may be extracted as a dangling symlink
        // (e.g. pointing to ../run/systemd/resolve/stub-resolv.conf).
        // Safely remove any pre-existing symlink or file before writing the static nameserver configuration.
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        resolvConf.parentFile?.mkdirs()
        try {
            Files.deleteIfExists(resolvConf.toPath())
        } catch (_: Exception) {
            resolvConf.delete()
        }
        resolvConf.writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 1.1.1.1\n")

        // 2. Hostname and Hosts
        val hostname = File(rootfsDir, "etc/hostname")
        hostname.parentFile?.mkdirs()
        try {
            Files.deleteIfExists(hostname.toPath())
        } catch (_: Exception) {
            hostname.delete()
        }
        hostname.writeText("linuxdroid\n")

        val hostsFile = File(rootfsDir, "etc/hosts")
        hostsFile.parentFile?.mkdirs()
        try {
            Files.deleteIfExists(hostsFile.toPath())
        } catch (_: Exception) {
            hostsFile.delete()
        }
        hostsFile.writeText("127.0.0.1 localhost linuxdroid\n::1 localhost ip6-localhost ip6-loopback\n")

        // 3. Environment Variables (/etc/environment)
        val envFile = File(rootfsDir, "etc/environment")
        envFile.parentFile?.mkdirs()
        try {
            Files.deleteIfExists(envFile.toPath())
        } catch (_: Exception) {
            envFile.delete()
        }
        envFile.writeText(
            """
            WAYLAND_DISPLAY=wayland-0
            XDG_RUNTIME_DIR=/tmp
            DISPLAY=:0
            XDG_SESSION_TYPE=wayland
            XDG_CURRENT_DESKTOP=LDDE
            XDG_SESSION_DESKTOP=LDDE
            GDK_BACKEND=wayland,x11
            QT_QPA_PLATFORM=wayland;xcb
            CLUTTER_BACKEND=wayland
            SDL_VIDEODRIVER=wayland
            """.trimIndent() + "\n"
        )

        // 4. Distribution APT Sources Configuration
        // Existing APT configuration provided by the rootfs MUST be preserved.
        // Only write fallback sources if etc/apt/sources.list does not exist or is empty,
        // and etc/apt/sources.list.d/ contains no source files.
        val sourcesFile = File(rootfsDir, "etc/apt/sources.list")
        val sourcesDir = File(rootfsDir, "etc/apt/sources.list.d")
        val hasExistingSources = (sourcesFile.exists() && sourcesFile.length() > 0) ||
                (sourcesDir.isDirectory && sourcesDir.listFiles()?.any { it.name.endsWith(".list") || it.name.endsWith(".sources") } == true)

        if (!hasExistingSources && definition.aptSources.isNotBlank()) {
            log.info("[ROOTFS_CONFIG] No existing APT sources found; writing fallback sources for ${definition.distribution.displayName}")
            sourcesFile.parentFile?.mkdirs()
            sourcesFile.writeText(definition.aptSources.trimIndent() + "\n")
        } else {
            log.info("[ROOTFS_CONFIG] Preserving existing rootfs APT configuration in ${sourcesFile.path}")
        }
    }
}

