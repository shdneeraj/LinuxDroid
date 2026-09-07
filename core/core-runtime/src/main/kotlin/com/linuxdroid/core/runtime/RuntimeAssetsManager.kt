package com.linuxdroid.core.runtime

import android.content.Context
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.RuntimeError
import com.linuxdroid.native_bridge.NativeBridge
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Externalized PRoot runtime asset identity.
 *
 * This is the metadata contract that LinuxDroid_proot publishes alongside each
 * release artifact. LinuxDroid consumes PRoot as an executable runtime asset,
 * *not* as a fake JNI library. The fields mirror the LinuxDroid_proot
 * `docs/release-artifacts.md` MANIFEST format:
 *
 * ```
 * LinuxDroid-PRoot v0.1.0
 * commit:  abc123
 * ABI:     arm64-v8a
 * arch:    aarch64 (ARM64)
 * android: 16+ (API 36)
 * sha256:
 *   proot:    <hex>
 *   loader:   <hex>
 * ```
 */
data class RuntimeAssetMetadata(
    val version: String,
    val commit: String? = null,
    val abi: String,
    val minAndroid: String? = null,
    val prootSha256: String? = null,
    val loaderSha256: String? = null,
) {
    /** Returns true when the bundled metadata declares a supported ABI. */
    fun supportsAbi(requestedAbi: String): Boolean = abi == requestedAbi
}

/**
 * Result of a PRoot runtime asset installation attempt.
 */
data class RuntimeAssetsInstallResult(
    val prootInstalled: Boolean,
    val loaderInstalled: Boolean,
    val prootPath: String?,
    val loaderPath: String?,
    val metadata: RuntimeAssetMetadata?,
    val detail: String,
)

/**
 * Owns the LinuxDroid_proot runtime artifact lifecycle.
 *
 * This is the *only* place in LinuxDroid that resolves, installs, validates,
 * and versions the PRoot executable and its companion loader. The runtime
 * engine is consumed as a versioned, verifiable executable asset rather than
 * as a genuine JNI library.
 *
 * Responsibilities (see migration Phase E):
 *  - PRoot runtime version identity (D05).
 *  - Device ABI selection (E04).
 *  - Asset identity for `proot` (E02) and `loader` (E03).
 *  - Install paths inside LinuxDroid private storage (E05, E06).
 *  - Existence, executable, ELF, and checksum validation (E07-E11).
 *  - Atomic install so a partially written artifact never becomes active (E12, E13).
 *
 * @property context Android application context used to access packaged assets
 * and private storage.
 */
class RuntimeAssetsManager(
    private val context: Context,
) {
    private val log = LinuxDroidLogger(LogSubsystem.RUNTIME)

    /** Asset root under which the ABI-specific PRoot artifacts are packaged. */
    val assetRoot: String = "proot"

    /** Absolute install directory for a given ABI. */
    fun installDir(abi: String): File = File(context.filesDir, "runtime/$abi")

    /** Absolute on-disk path of the installed PRoot executable. */
    fun installedProotFile(abi: String): File = File(installDir(abi), "proot")

    /** Absolute on-disk path of the installed loader executable. */
    fun installedLoaderFile(abi: String): File = File(installDir(abi), "loader")

    /** Absolute on-disk path of a partially written (not yet active) PRoot. */
    private fun stagingProotFile(abi: String): File = File(installDir(abi), ".proot.part")

    /** Absolute on-disk path of a partially written (not yet active) loader. */
    private fun stagingLoaderFile(abi: String): File = File(installDir(abi), ".loader.part")

    /**
     * Returns the ABI this device should run PRoot for, or throws a clear
     * [RuntimeError] if the device ABI is unsupported.
     */
    fun resolveAbi(): String {
        val supported = android.os.Build.SUPPORTED_ABIS.toList()
        val abi = supported.firstOrNull { it in setOf("arm64-v8a") }
            ?: throw RuntimeError(
                message = "Unsupported device ABI: $supported (supported: arm64-v8a)",
            )
        return abi
    }

    /**
     * Resolves the currently viable PRoot executable.
     *
     * Resolution order:
     *  1. A previously installed and validated runtime asset under
     *     `filesDir/runtime/<abi>/proot` (the target architecture).
     *  2. Install/extract the packaged artifact from `assets/proot/<abi>/proot`.
     *  3. Legacy fallback to `nativeLibraryDir/libproot.so` (the frozen
     *     pre-migration bundle). This fallback is retained only as a reference
     *     until the external LinuxDroid_proot artifact is independently verified
     *     on device; it must not be considered the target source of truth.
     */
    fun resolveProot(): File {
        val abi = resolveAbi()
        synchronized(this) {
            val installedResult = installForAbi(abi).also { result ->
                if (!result.prootInstalled) {
                    log.warn("Runtime asset install reported not installed: ${result.detail}")
                }
            }
            val assetProot = installedResult.prootPath
            if (assetProot != null) {
                val file = File(assetProot)
                if (isValidProot(file)) {
                    val metadata = installedResult.metadata
                    if (metadata != null && !isVersionCompatible(metadata.version)) {
                        throw RuntimeError(
                            message = "Installed PRoot runtime version ${metadata.version} is incompatible. " +
                                "LinuxDroid requires >= $requiredProotVersion.",
                        )
                    }
                    return file
                }
            }

            val installed = installedProotFile(abi)
            if (isValidProot(installed)) return installed

            // Legacy fallback (frozen baseline reference, not the target).
            legacyProot(abi)?.let { legacy ->
                if (isValidProot(legacy)) {
                    log.info("Using legacy embedded PRoot baseline: ${legacy.path}")
                    return legacy
                }
            }

            throw RuntimeError(
                message = "PRoot runtime could not be resolved for ABI $abi. " +
                    "No valid executable found under ${installed.path}, packaged assets, or nativeLibraryDir.",
            )
        }
    }

    /**
     * Resolves the companion loader executable, or `null` when none is available.
     */
    fun resolveLoader(): File? {
        val abi = resolveAbi()
        synchronized(this) {
            val assetLoader = installForAbi(abi).loaderPath
            if (assetLoader != null) {
                val file = File(assetLoader)
                if (isExecutable(file)) return file
            }

            val installed = installedLoaderFile(abi)
            if (isExecutable(installed)) return installed

            legacyLoader(abi)?.let { legacy ->
                if (isExecutable(legacy)) {
                    log.info("Using legacy embedded loader baseline: ${legacy.path}")
                    return legacy
                }
            }

            return null
        }
    }

    /**
     * Installs (or re-validates) the packaged PRoot + loader artifacts for the
     * current device ABI. Installation is atomic: each artifact is first written
     * to a `.part` file, validated, then renamed into place so a partially
     * downloaded/copied artifact can never become the active runtime.
     */
    fun install(): RuntimeAssetsInstallResult {
        val abi = resolveAbi()
        return installForAbi(abi)
    }

    /**
     * Installs the runtime artifacts for an explicit ABI.
     */
    fun installForAbi(abi: String): RuntimeAssetsInstallResult {
        val metadata = readManifest(abi)

        val proot = installedProotFile(abi)
        val loader = installedLoaderFile(abi)

        val prootInstalled = installSingleArtifact(abi, "proot", proot, stagingProotFile(abi), metadata?.prootSha256)
        val loaderInstalled = installSingleArtifact(abi, "loader", loader, stagingLoaderFile(abi), metadata?.loaderSha256)

        return RuntimeAssetsInstallResult(
            prootInstalled = prootInstalled,
            loaderInstalled = loaderInstalled,
            prootPath = if (prootInstalled) proot.absolutePath else null,
            loaderPath = if (loaderInstalled) loader.absolutePath else null,
            metadata = metadata,
            detail = "install[$abi] proot=${prootInstalled} loader=${loaderInstalled}",
        )
    }

    /**
     * Returns the release metadata bundled with the runtime, or `null` when the
     * build has no manifest (development builds).
     */
    fun readManifest(abi: String): RuntimeAssetMetadata? {
        return try {
            val manifestPath = "$assetRoot/$abi/MANIFEST.txt"
            val input = context.assets.open(manifestPath)
            val text = input.bufferedReader().use { it.readText() }
            parseManifest(text, abi)
        } catch (e: Exception) {
            log.debug("No runtime manifest bundled for $abi: ${e.message}")
            null
        }
    }

    /**
     * Validates that [file] is a plausible PRoot executable for the current ABI:
     * exists, non-empty, executable, and a matching ELF architecture.
     */
    fun isValidProot(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        if (file.length() == 0L) return false
        if (!file.canExecute()) return false
        val abi = runCatching { resolveAbi() }.getOrNull() ?: return false
        val elf = ElfValidator.readElfInfo(file, abi)
        if (!elf.isValid) {
            log.warn("PRoot ELF validation failed for ${file.path}: ${elf.detail}")
            return false
        }
        return true
    }

    /**
     * Validates that [file] is a plausible loader executable: exists, non-empty,
     * and executable.
     */
    fun isValidLoader(file: File): Boolean = isExecutable(file)

    private fun isExecutable(file: File): Boolean =
        file.exists() && file.isFile && file.length() > 0L && file.canExecute()

    /**
     * Atomically installs a single artifact from packaged assets.
     *
     * @param artifactName the asset/install filename ("proot" or "loader").
     */
    private fun installSingleArtifact(
        abi: String,
        artifactName: String,
        target: File,
        staging: File,
        expectedSha256: String?,
    ): Boolean {
        val assetPath = "$assetRoot/$abi/$artifactName"
        val resolvedSha256 = expectedSha256 ?: computeAssetSha256(assetPath)

        // Already installed and valid? Check if checksum matches.
        if (target.exists() && target.length() > 0L && target.canExecute()) {
            if (resolvedSha256 != null) {
                if (verifyChecksum(target, resolvedSha256)) {
                    return true
                }
                log.info("$artifactName checksum mismatch for ${target.path}; updating to latest bundled asset.")
            } else {
                return true
            }
        }

        return try {
            context.assets.open(assetPath).use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            }

            // Make executable before promotion.
            staging.setReadable(true, false)
            staging.setExecutable(true, false)
            NativeBridge.setExecutable(staging.absolutePath)

            if (expectedSha256 != null && !verifyChecksum(staging, expectedSha256)) {
                staging.delete()
                log.error("$artifactName checksum validation failed for ABI $abi; aborted install.")
                return false
            }

            // Promote the freshly validated artifact. The previously working
            // artifact is first moved to a backup so that a failed promotion
            // can be rolled back (a failed update never destroys the last good
            // runtime). Promotion only happens after the new artifact has passed
            // checksum + executable validation.
            val backup = File(target.parentFile, ".${target.name}.prev")
            if (backup.exists()) backup.delete()
            if (target.exists() && !target.renameTo(backup)) {
                staging.delete()
                log.error("Failed to back up existing $artifactName before promotion")
                return false
            }

            val renamed = staging.renameTo(target)
            if (!renamed) {
                staging.delete()
                if (backup.exists()) backup.renameTo(target) // rollback
                log.error("Failed to promote staged $artifactName to ${target.path}; rolled back")
                return false
            }
            if (backup.exists()) backup.delete()
            log.info("Installed $artifactName -> ${target.path} (abi=$abi)")
            true
        } catch (e: Exception) {
            staging.delete()
            log.warn("$artifactName asset install failed for $abi: ${e.message}")
            false
        }
    }

    private fun legacyProot(abi: String): File? {
        return try {
            val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
            val candidate = File(nativeLibDir, "libproot.so")
            if (candidate.exists() && candidate.length() > 0L) candidate else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun legacyLoader(abi: String): File? {
        return try {
            val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
            val candidate = File(nativeLibDir, "libproot_loader.so")
            if (candidate.exists() && candidate.length() > 0L) candidate else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun computeAssetSha256(assetPath: String): String? {
        return try {
            context.assets.open(assetPath).use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun verifyChecksum(file: File, expected: String): Boolean {
        val actual = sha256(file)
        val ok = actual.equals(expected.trim().lowercase(Locale.US), ignoreCase = true)
        if (!ok) {
            log.warn("SHA-256 mismatch for ${file.path}: expected=$expected actual=$actual")
        }
        return ok
    }

    companion object {

        /**
         * The LinuxDroid_proot minimum accepted runtime version.
         *
         * LinuxDroid refuses to launch a PRoot runtime older than this. Version
         * comparison is best-effort against the bundled MANIFEST metadata; when
         * no metadata is bundled (e.g. a development build) the runtime is
         * accepted on existence + architecture, since checksum/version metadata
         * only exists on real releases.
         */
        const val requiredProotVersion: String = "0.1.0"

        /**
         * Returns true when [version] satisfies the minimum accepted runtime
         * version. Unknown/missing metadata (development builds) is accepted.
         */
        fun isVersionCompatible(version: String): Boolean {
            if (version.isBlank() || version.equals("unknown", ignoreCase = true)) return true
            return compareVersions(version, requiredProotVersion) >= 0
        }

        /**
         * Compares two dot-separated semantic versions.
         * @return >0 if [a] is newer than [b], <0 if older, 0 if equal.
         */
        internal fun compareVersions(a: String, b: String): Int {
            fun parts(v: String): List<Int> =
                v.trim().removePrefix("v").split(".", "-").mapNotNull { it.toIntOrNull() }

            val pa = parts(a)
            val pb = parts(b)
            val len = maxOf(pa.size, pb.size)
            for (i in 0 until len) {
                val av = pa.getOrElse(i) { 0 }
                val bv = pb.getOrElse(i) { 0 }
                if (av != bv) return av.compareTo(bv)
            }
            return 0
        }

        /** Parses a LinuxDroid_proot MANIFEST.txt payload (no Android context needed). */
        internal fun parseManifest(text: String, abi: String): RuntimeAssetMetadata {
            var version = "unknown"
            var commit: String? = null
            var minAndroid: String? = null
            var prootSha: String? = null
            var loaderSha: String? = null
            var inSha256 = false

            text.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isBlank()) return@forEach
                if (line.startsWith("sha256:", ignoreCase = true)) {
                    inSha256 = true
                    return@forEach
                }
                if (line.startsWith("built:", ignoreCase = true)) {
                    inSha256 = false
                    return@forEach
                }
                // Documented release header (e.g. "LinuxDroid-PRoot v0.1.0").
                // It carries no key: the value after "LinuxDroid-PRoot" is the
                // authoritative artifact version.
                val header = Regex("""^LinuxDroid-PRoot\s+v?(\S+)$""", RegexOption.IGNORE_CASE).matchEntire(line)
                if (header != null) {
                    version = header.groupValues[1].trim().removePrefix("v").trim()
                    return@forEach
                }
                if (inSha256) {
                    val parts = line.split(Regex("\\s+"), limit = 2)
                    if (parts.size == 2) {
                        when (parts[0].trim().lowercase(Locale.US)) {
                            "proot:" -> prootSha = parts[1].trim()
                            "loader:" -> loaderSha = parts[1].trim()
                        }
                    }
                    return@forEach
                }
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim().lowercase(Locale.US)
                    val value = line.substring(idx + 1).trim()
                    when (key) {
                        "version", "linuxdroid-proot" -> version = value.removePrefix("v").trim()
                        "commit" -> commit = value
                        "android", "min-android" -> minAndroid = value
                    }
                }
            }

            return RuntimeAssetMetadata(
                version = version,
                commit = commit,
                abi = abi,
                minAndroid = minAndroid,
                prootSha256 = prootSha,
                loaderSha256 = loaderSha,
            )
        }

        /** Computes the lower-case SHA-256 hex digest of [file]. */
        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var read = fis.read(buffer)
                while (read > 0) {
                    digest.update(buffer, 0, read)
                    read = fis.read(buffer)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * Parses a PACKAGES_MANIFEST.txt payload into a list of [GraphicalPackageAssetMetadata].
         */
        fun parsePackagesManifest(text: String): List<GraphicalPackageAssetMetadata> {
            val list = mutableListOf<GraphicalPackageAssetMetadata>()
            var currentPkg: String? = null
            var currentVer: String? = null
            var currentArch: String? = null
            var currentFile: String? = null
            var currentSha: String? = null

            fun flush() {
                if (currentPkg != null && currentFile != null) {
                    list.add(
                        GraphicalPackageAssetMetadata(
                            packageName = currentPkg!!,
                            version = currentVer ?: "unknown",
                            architecture = currentArch ?: "arm64",
                            fileName = currentFile!!,
                            sha256 = currentSha ?: "",
                        ),
                    )
                }
                currentPkg = null
                currentVer = null
                currentArch = null
                currentFile = null
                currentSha = null
            }

            text.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank()) {
                    flush()
                    return@forEach
                }
                val idx = trimmed.indexOf(':')
                if (idx > 0) {
                    val key = trimmed.substring(0, idx).trim().lowercase(Locale.US)
                    val value = trimmed.substring(idx + 1).trim()
                    when (key) {
                        "package" -> {
                            if (currentPkg != null) flush()
                            currentPkg = value
                        }
                        "version" -> currentVer = value
                        "architecture", "arch" -> currentArch = value
                        "file", "filename" -> currentFile = value
                        "sha256" -> currentSha = value
                    }
                }
            }
            flush()
            return list
        }
    }

    /** Asset root under which bundled .deb packages are packaged. */
    val packagesAssetRoot: String = "packages"

    /** Asset root under which installer scripts are packaged. */
    val scriptsAssetRoot: String = "scripts"

    /** Absolute directory where extracted .deb packages are staged on device storage. */
    fun packagesInstallDir(): File = File(context.filesDir, "packages").apply { mkdirs() }

    /** Absolute directory where extracted scripts are staged on device storage. */
    fun scriptsInstallDir(): File = File(context.filesDir, "scripts").apply { mkdirs() }

    /**
     * Reads the bundled PACKAGES_MANIFEST.txt if present.
     */
    fun readPackagesManifest(): List<GraphicalPackageAssetMetadata> {
        val searchRoots = listOf(packagesAssetRoot, "linux")
        for (root in searchRoots) {
            try {
                val manifestPath = "$root/PACKAGES_MANIFEST.txt"
                val text = context.assets.open(manifestPath).bufferedReader().use { it.readText() }
                val parsed = parsePackagesManifest(text)
                if (parsed.isNotEmpty()) return parsed
            } catch (_: Exception) {}
        }
        return emptyList()
    }

    /**
     * Extracts all bundled .deb packages to private storage, verifying checksums.
     * Returns the list of extracted package files.
     */
    fun extractPackages(): List<File> {
        val targetDir = packagesInstallDir()
        val manifest = readPackagesManifest().associateBy { it.fileName }
        val extracted = mutableListOf<File>()
        val seen = mutableSetOf<String>()

        val searchRoots = listOf(packagesAssetRoot, "linux")
        for (root in searchRoots) {
            try {
                val assetList = context.assets.list(root) ?: emptyArray()
                for (item in assetList) {
                    if (!item.endsWith(".deb") || !seen.add(item)) continue
                    val targetFile = File(targetDir, item)
                    val expectedMeta = manifest[item]
                    val expectedSha = expectedMeta?.sha256

                    if (targetFile.exists() && targetFile.length() > 0L) {
                        if (expectedSha == null || verifyChecksum(targetFile, expectedSha)) {
                            extracted.add(targetFile)
                            continue
                        }
                    }

                    val staging = File(targetDir, ".$item.part")
                    try {
                        context.assets.open("$root/$item").use { input ->
                            staging.outputStream().use { output -> input.copyTo(output) }
                        }
                        if (expectedSha != null && !verifyChecksum(staging, expectedSha)) {
                            staging.delete()
                            log.error("Package $item checksum verification failed; aborting extraction.")
                            continue
                        }
                        if (targetFile.exists()) targetFile.delete()
                        if (staging.renameTo(targetFile)) {
                            targetFile.setReadable(true, false)
                            extracted.add(targetFile)
                            log.info("Extracted packaged deb: $item -> ${targetFile.absolutePath}")
                        } else {
                            staging.delete()
                        }
                    } catch (e: Exception) {
                        staging.delete()
                        log.warn("Failed extracting package $item: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                log.warn("Failed listing package assets in $root: ${e.message}")
            }
        }
        return extracted
    }

    /**
     * Resolves the extracted LDDM .deb package file, or null if unavailable.
     */
    fun getLddmPackage(): File? {
        val pkgs = extractPackages()
        return pkgs.firstOrNull { it.name.startsWith("linuxdroid-display-manager") }
            ?: File(packagesInstallDir(), "linuxdroid-display-manager_0.1.0_arm64.deb").takeIf { it.exists() }
    }

    /**
     * Resolves the extracted LDDE .deb package file, or null if unavailable.
     */
    fun getLddePackage(): File? {
        val pkgs = extractPackages()
        return pkgs.firstOrNull { it.name.startsWith("linuxdroid-desktop-environment") }
            ?: File(packagesInstallDir(), "linuxdroid-desktop-environment_1.0.0_arm64.deb").takeIf { it.exists() }
    }

    /**
     * Extracts and marks executable the unified rootfs installer script.
     */
    fun extractInstallerScript(): File? {
        val targetDir = scriptsInstallDir()
        val scriptName = "install_rootfs.sh"
        val targetFile = File(targetDir, scriptName)

        return try {
            val staging = File(targetDir, ".$scriptName.part")
            context.assets.open("$scriptsAssetRoot/$scriptName").use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            }
            staging.setReadable(true, false)
            staging.setExecutable(true, false)
            NativeBridge.setExecutable(staging.absolutePath)

            if (targetFile.exists()) targetFile.delete()
            if (staging.renameTo(targetFile)) {
                targetFile.setReadable(true, false)
                targetFile.setExecutable(true, false)
                NativeBridge.setExecutable(targetFile.absolutePath)
                log.info("Extracted installer script -> ${targetFile.absolutePath}")
                targetFile
            } else {
                staging.delete()
                null
            }
        } catch (e: Exception) {
            log.warn("Failed extracting $scriptName: ${e.message}")
            if (targetFile.exists() && targetFile.canExecute()) targetFile else null
        }
    }
}

/**
 * Metadata for a bundled LinuxDroid graphical .deb package (LDDM, LDDE).
 */
data class GraphicalPackageAssetMetadata(
    val packageName: String,
    val version: String,
    val architecture: String,
    val fileName: String,
    val sha256: String,
)

