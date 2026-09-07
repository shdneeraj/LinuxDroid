package com.linuxdroid.linux.bootstrap

import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.runtime.GuestInit
import com.linuxdroid.native_bridge.NativeBridge
import java.io.File
import java.nio.file.Files

/**
 * Prepares the runtime environment infrastructure within the Linux root filesystem.
 *
 * Installs persistent guest init (/sbin/linuxdroid-init), initializes guest hooks
 * (/etc/linuxdroid/init.d), prepares user home directories (/home/user), and ensures
 * runtime mount points (/tmp, /run, /dev/shm) exist with correct permissions.
 */
class RuntimeEnvironmentSetup(
    private val log: LinuxDroidLogger = LinuxDroidLogger(LogSubsystem.BOOTSTRAP),
) {

    /**
     * Sets up the runtime environment infrastructure in [rootfsDir].
     */
    fun setup(rootfsDir: File) {
        log.info("[RUNTIME_SETUP] Preparing runtime environment infrastructure in ${rootfsDir.path}")

        // 1. Persistent Guest Init (/sbin/linuxdroid-init)
        val sbinDir = File(rootfsDir, "sbin").apply { mkdirs() }
        val initFile = File(sbinDir, "linuxdroid-init")
        try {
            Files.deleteIfExists(initFile.toPath())
        } catch (_: Exception) {
            initFile.delete()
        }
        initFile.writeText(GuestInit.SCRIPT_CONTENT)
        initFile.setReadable(true, false)
        initFile.setExecutable(true, false)
        NativeBridge.setExecutable(initFile.absolutePath)

        // 2. Guest Init Hooks Directory (/etc/linuxdroid/init.d)
        File(rootfsDir, "etc/linuxdroid/init.d").mkdirs()

        // 3. User Directories
        File(rootfsDir, "home/user").mkdirs()
        File(rootfsDir, "home/user/Android").mkdirs()

        // 4. Runtime directories
        val tmpDir = File(rootfsDir, "tmp").apply { mkdirs() }
        tmpDir.setReadable(true, false)
        tmpDir.setWritable(true, false)
        tmpDir.setExecutable(true, false)

        File(rootfsDir, "run").mkdirs()
        File(rootfsDir, "run/lddm").mkdirs()
        File(rootfsDir, "dev/shm").mkdirs()
        File(rootfsDir, "var/tmp").mkdirs()
    }
}

