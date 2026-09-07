package com.linuxdroid.linux.bootstrap

import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.Environment
import com.linuxdroid.core.model.RuntimeError
import com.linuxdroid.core.runtime.RuntimeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Result of graphical dependency verification and installation.
 */
data class GraphicalDependencyResult(
    val waylandReady: Boolean,
    val westonReady: Boolean,
    val waylandVersion: String? = null,
    val westonVersion: String? = null,
    val reusedExisting: Boolean = false,
    val detail: String = "",
) {
    val waylandSuccess: Boolean get() = waylandReady
    val westonSuccess: Boolean get() = westonReady
}

/**
 * Inspection status of graphical stack components.
 */
data class GraphicalStackStatus(
    val waylandClientPresent: Boolean,
    val waylandServerPresent: Boolean,
    val westonPresent: Boolean,
    val westonHeadlessBackendPresent: Boolean,
    val isComplete: Boolean,
)

/**
 * Manages detection, verification, and installation of Linux distribution
 * Wayland and Weston packages inside the Linux rootfs.
 *
 * Strict Ownership:
 *  - Wayland is distribution infrastructure.
 *  - Weston is the distribution-provided Wayland compositor.
 *  - No Weston or Wayland binaries are bundled inside the Android APK.
 *  - Reuses compatible distribution packages if already present.
 */
class GraphicalDependencyInstaller(
    private val runtimeBackend: RuntimeBackend? = null,
    private val log: LinuxDroidLogger = LinuxDroidLogger(LogSubsystem.BOOTSTRAP),
) {

    /**
     * Inspects and ensures that Wayland runtime libraries and the Weston compositor
     * exist and are functional inside [rootfsDir].
     *
     * @param environment The target Linux environment.
     * @param rootfsDir The filesystem root of the target environment.
     * @param onProgress Status callback.
     * @param onLog Log callback.
     */
    suspend fun ensureGraphicalDependencies(
        environment: Environment,
        rootfsDir: File,
        onProgress: suspend (Float, String) -> Unit = { _, _ -> },
        onLog: suspend (String) -> Unit = { _ -> },
    ): GraphicalDependencyResult = withContext(Dispatchers.IO) {
        log.info("[GRAPHICS_DEPLOY] Checking graphical dependencies for ${environment.id} in ${rootfsDir.path}")
        onLog(">>> [GRAPHICS] Inspecting Wayland and Weston packages in rootfs...")

        // 1. Inspect Wayland
        onProgress(0.75f, "Verifying Wayland runtime libraries…")
        val waylandStatus = inspectWayland(rootfsDir, environment)
        log.info("[GRAPHICS_DEPLOY] Wayland status: present=${waylandStatus.present}, version=${waylandStatus.version}")

        // 2. Inspect Weston
        onProgress(0.78f, "Verifying Weston compositor…")
        val westonStatus = inspectWeston(rootfsDir, environment)
        log.info("[GRAPHICS_DEPLOY] Weston status: present=${westonStatus.present}, version=${westonStatus.version}, backend=${westonStatus.hasHeadlessBackend}")

        var waylandReady = waylandStatus.present
        var westonReady = westonStatus.present && westonStatus.hasHeadlessBackend
        var reused = waylandReady && westonReady

        if (reused) {
            log.info("[GRAPHICS_DEPLOY] Compatible distribution Wayland and Weston already present. Reusing existing packages.")
            onLog(">>> [GRAPHICS_OK] Verified existing Wayland (${waylandStatus.version ?: "detected"}) and Weston (${westonStatus.version ?: "detected"}). Skipping duplicate installation.")
        } else {
            // Need installation or repair
            onLog(">>> [GRAPHICS_INSTALL] Missing or incomplete graphical stack (waylandReady=$waylandReady, westonReady=$westonReady). Installing...")
            val installResult = installDependencies(environment, rootfsDir, onProgress, onLog)
            waylandReady = installResult.waylandReady
            westonReady = installResult.westonReady
        }

        // 3. Ensure minimal /etc/xdg/weston/weston.ini exists
        ensureWestonConfig(rootfsDir)

        if (!waylandReady || !westonReady) {
            val err = "Failed to satisfy graphical dependencies (Wayland ready: $waylandReady, Weston ready: $westonReady)"
            log.error("[GRAPHICS_FAIL] $err")
            onLog(">>> [ERROR] $err")
            throw RuntimeError(environmentId = environment.id, message = err)
        }

        GraphicalDependencyResult(
            waylandReady = true,
            westonReady = true,
            waylandVersion = waylandStatus.version ?: "distribution",
            westonVersion = westonStatus.version ?: "distribution",
            reusedExisting = reused,
            detail = "Wayland and Weston verified",
        )
    }

    /**
     * Inspects the graphical stack status of [rootfsDir].
     */
    fun inspect(rootfsDir: File): GraphicalStackStatus {
        val candidateLibDirs = listOf(
            File(rootfsDir, "usr/lib/aarch64-linux-gnu"),
            File(rootfsDir, "usr/lib/x86_64-linux-gnu"),
            File(rootfsDir, "usr/lib64"),
            File(rootfsDir, "usr/lib"),
            File(rootfsDir, "lib/aarch64-linux-gnu"),
            File(rootfsDir, "lib/x86_64-linux-gnu"),
            File(rootfsDir, "lib64"),
            File(rootfsDir, "lib"),
        )
        val hasClient = candidateLibDirs.any { dir ->
            File(dir, "libwayland-client.so.0").exists() || File(dir, "libwayland-client.so").exists()
        }
        val hasServer = candidateLibDirs.any { dir ->
            File(dir, "libwayland-server.so.0").exists() || File(dir, "libwayland-server.so").exists()
        }
        val westonBin = File(rootfsDir, "usr/bin/weston").exists() || File(rootfsDir, "usr/local/bin/weston").exists()
        val hasHeadless = rootfsDir.walkTopDown().maxDepth(6).any { it.name == "headless-backend.so" }

        return GraphicalStackStatus(
            waylandClientPresent = hasClient,
            waylandServerPresent = hasServer,
            westonPresent = westonBin,
            westonHeadlessBackendPresent = hasHeadless,
            isComplete = hasClient && hasServer && westonBin && hasHeadless,
        )
    }

    private data class ComponentInspection(
        val present: Boolean,
        val version: String? = null,
        val hasHeadlessBackend: Boolean = false,
        val detail: String = "",
    )

    private fun inspectWayland(rootfsDir: File, environment: Environment): ComponentInspection {
        // Check for libwayland-client.so.0 and libwayland-server.so.0
        val candidateLibDirs = listOf(
            File(rootfsDir, "usr/lib/aarch64-linux-gnu"),
            File(rootfsDir, "usr/lib/x86_64-linux-gnu"),
            File(rootfsDir, "usr/lib64"),
            File(rootfsDir, "usr/lib"),
            File(rootfsDir, "lib/aarch64-linux-gnu"),
            File(rootfsDir, "lib/x86_64-linux-gnu"),
            File(rootfsDir, "lib64"),
            File(rootfsDir, "lib"),
        )

        var hasClient = false
        var hasServer = false
        for (dir in candidateLibDirs) {
            if (!dir.isDirectory) continue
            if (File(dir, "libwayland-client.so.0").exists() || File(dir, "libwayland-client.so").exists()) {
                hasClient = true
            }
            if (File(dir, "libwayland-server.so.0").exists() || File(dir, "libwayland-server.so").exists()) {
                hasServer = true
            }
        }

        // Also check dpkg status if available
        val dpkgStatus = File(rootfsDir, "var/lib/dpkg/status")
        var version: String? = null
        if (dpkgStatus.exists()) {
            val statusContent = try { dpkgStatus.readText() } catch (_: Exception) { "" }
            val clientPkg = statusContent.contains("Package: libwayland-client0")
            if (clientPkg) {
                val match = Regex("""Package: libwayland-client0\n(?:.*\n)*?Version:\s*([^\n]+)""").find(statusContent)
                version = match?.groupValues?.get(1)
            }
        }

        return ComponentInspection(
            present = hasClient && hasServer,
            version = version,
            detail = "hasClient=$hasClient, hasServer=$hasServer",
        )
    }

    private fun inspectWeston(rootfsDir: File, environment: Environment): ComponentInspection {
        val westonBin = File(rootfsDir, "usr/bin/weston").takeIf { it.exists() }
            ?: File(rootfsDir, "usr/local/bin/weston").takeIf { it.exists() }

        if (westonBin == null || !westonBin.canExecute()) {
            return ComponentInspection(present = false, detail = "Weston executable missing or non-executable")
        }

        // Find headless-backend.so
        val hasHeadless = rootfsDir.walkTopDown()
            .maxDepth(6)
            .any { it.name == "headless-backend.so" }

        val dpkgStatus = File(rootfsDir, "var/lib/dpkg/status")
        var version: String? = null
        if (dpkgStatus.exists()) {
            val statusContent = try { dpkgStatus.readText() } catch (_: Exception) { "" }
            val westonMatch = Regex("""Package: weston\n(?:.*\n)*?Version:\s*([^\n]+)""").find(statusContent)
            version = westonMatch?.groupValues?.get(1)
        }

        return ComponentInspection(
            present = true,
            version = version,
            hasHeadlessBackend = hasHeadless,
            detail = "Weston binary found at ${westonBin.path}, hasHeadless=$hasHeadless",
        )
    }

    private suspend fun installDependencies(
        environment: Environment,
        rootfsDir: File,
        onProgress: suspend (Float, String) -> Unit,
        onLog: suspend (String) -> Unit,
    ): GraphicalDependencyResult {
        val backend = runtimeBackend ?: return GraphicalDependencyResult(
            waylandReady = inspectWayland(rootfsDir, environment).present,
            westonReady = inspectWeston(rootfsDir, environment).present,
        )

        onProgress(0.80f, "Installing distribution Wayland and Weston packages…")
        onLog(">>> [GRAPHICS_INSTALL] Invoking APT package manager for Wayland and Weston...")

        val packages = listOf(
            "libwayland-client0",
            "libwayland-server0",
            "libwayland-cursor0",
            "wayland-protocols",
            "weston",
        )

        val cmd = listOf(
            "apt-get", "install", "-y", "--no-install-recommends"
        ) + packages

        val extraEnv = mapOf(
            "DEBIAN_FRONTEND" to "noninteractive",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        )

        val result = try {
            backend.executeAndWait(
                environment = environment.copy(rootfsPath = rootfsDir.absolutePath),
                command = cmd,
                workingDirectory = "/root",
                extraEnv = extraEnv,
                timeoutMs = 120_000,
            )
        } catch (e: Exception) {
            log.warn("[GRAPHICS_INSTALL] Package installation exception: ${e.message}")
            onLog(">>> [GRAPHICS_WARN] Package installation returned notice: ${e.message}")
            null
        }

        if (result != null && result.exitCode == 0) {
            onLog(">>> [GRAPHICS_INSTALL] Distribution packages installed successfully.")
        } else {
            onLog(">>> [GRAPHICS_NOTICE] apt-get result exitCode=${result?.exitCode}: ${result?.stderr?.take(200)}")
        }

        val reWayland = inspectWayland(rootfsDir, environment)
        val reWeston = inspectWeston(rootfsDir, environment)

        return GraphicalDependencyResult(
            waylandReady = reWayland.present,
            westonReady = reWeston.present && reWeston.hasHeadlessBackend,
            waylandVersion = reWayland.version,
            westonVersion = reWeston.version,
        )
    }

    private fun ensureWestonConfig(rootfsDir: File) {
        val xdgDir = File(rootfsDir, "etc/xdg/weston").apply { mkdirs() }
        val configFile = File(xdgDir, "weston.ini")
        if (!configFile.exists()) {
            configFile.writeText(
                """
                # LinuxDroid Default Weston Configuration
                [core]
                idle-time=0
                require-input=false
                backend=headless-backend.so

                [shell]
                locking=false
                """.trimIndent() + "\n"
            )
            configFile.setReadable(true, false)
        }
    }
}

