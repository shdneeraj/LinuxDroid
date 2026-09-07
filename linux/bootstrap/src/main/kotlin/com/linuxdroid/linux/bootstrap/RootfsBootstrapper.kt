package com.linuxdroid.linux.bootstrap

import android.content.Context
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.*
import com.linuxdroid.core.runtime.ProotRuntimeBackend
import com.linuxdroid.core.runtime.RuntimeBackend
import com.linuxdroid.native_bridge.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Production-ready multi-distribution rootfs bootstrapper for LinuxDroid.
 *
 * Implements hardened rootfs acquisition, verification, staging extraction,
 * deep validation, configuration, atomic promotion, and live PRoot runtime validation.
 *
 * Supports:
 *  - Debian ARM64 Minimal
 *  - Ubuntu ARM64 Minimal
 *  - Kali Linux ARM64 Minimal
 */
class RootfsBootstrapper(
    private val context: Context,
    private val storage: EnvironmentStorage,
    private val runtimeBackend: RuntimeBackend? = null,
    private val validator: RootfsValidator = RootfsValidator(),
    private val dynamicResolver: DynamicDistributionResolver = DynamicDistributionResolver(),
    private val deploymentManager: RootfsDeploymentManager = RootfsDeploymentManager(
        context = context,
        storage = storage,
        runtimeBackend = runtimeBackend,
        validator = validator,
        dynamicResolver = dynamicResolver,
    ),
) {
    private val log = LinuxDroidLogger(LogSubsystem.BOOTSTRAP)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val extractor = RootfsExtractor(log)
    private val configurator = RootfsConfigurator(log)
    private val runtimeSetup = RuntimeEnvironmentSetup(log)

    /**
     * Downloads, verifies, stages, validates, configures, and promotes a Linux root filesystem
     * with the complete graphical stack.
     */
    suspend fun bootstrapRootfs(
        environment: Environment,
        onProgress: suspend (Float, String) -> Unit = { _, _ -> },
        onLog: suspend (String) -> Unit = { _ -> },
    ) {
        bootstrapRootfs(environment, null, onProgress, onLog)
    }

    suspend fun bootstrapRootfs(
        environment: Environment,
        installConfig: InstallConfig?,
        onProgress: suspend (Float, String) -> Unit = { _, _ -> },
        onLog: suspend (String) -> Unit = { _ -> },
    ) {
        deploymentManager.deployRootfs(
            environment = environment,
            installConfig = installConfig,
            onProgress = onProgress,
            onLog = onLog,
        )
    }

    private suspend fun downloadFile(
        url: String,
        dest: File,
        onProgress: suspend (Float, String) -> Unit,
        onLog: suspend (String) -> Unit = { _ -> },
    ) = withContext(Dispatchers.IO) {
        var currentUrl = url
        var connection: HttpURLConnection
        var redirectCount = 0
        while (true) {
            connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "LinuxDroid/1.0 (Android; ARM64)")
            }
            connection.connect()
            val status = connection.responseCode
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: throw IOException("HTTP $status redirect without Location header from $currentUrl")
                currentUrl = if (location.startsWith("http")) location else URL(URL(currentUrl), location).toString()
                redirectCount++
                if (redirectCount > 8) throw IOException("Too many redirects: $redirectCount (last: $currentUrl)")
                connection.disconnect()
                continue
            }
            if (status !in 200..299) {
                throw IOException("HTTP $status error downloading $currentUrl")
            }
            break
        }

        val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: -1L

        connection.inputStream.use { input ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var downloaded = 0L
                var lastLogMb = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    val currentMb = downloaded / (1024 * 1024)
                    if (totalBytes > 0) {
                        val fraction = downloaded.toFloat() / totalBytes
                        onProgress(0.05f + fraction * 0.60f, "Downloading… ${downloaded / 1_048_576}MB / ${totalBytes / 1_048_576}MB")
                        if (currentMb >= lastLogMb + 10) {
                            lastLogMb = currentMb
                            onLog(">>> [DOWNLOAD] Transfer progress: $currentMb MB / ${totalBytes / 1_048_576} MB (${(fraction * 100).toInt()}%)")
                        }
                    } else {
                        if (currentMb >= lastLogMb + 10) {
                            lastLogMb = currentMb
                            onProgress(0.10f + (currentMb % 100) * 0.005f, "Downloading… ${currentMb}MB")
                            onLog(">>> [DOWNLOAD] Transfer progress: $currentMb MB downloaded")
                        }
                    }
                }
            }
        }
    }

    internal suspend fun extractArchive(
        tarball: File,
        destDir: File,
        format: ArchiveFormat,
        stripComponents: Int,
        onProgress: suspend (Float, String) -> Unit,
        onLog: suspend (String) -> Unit = { _ -> },
    ) {
        extractor.extract(tarball, destDir, format, stripComponents, onProgress, onLog)
    }

    internal fun configureStagingRootfs(rootfsDir: File, definition: DistributionDefinition) {
        configurator.configure(rootfsDir, definition)
        runtimeSetup.setup(rootfsDir)
    }
}
