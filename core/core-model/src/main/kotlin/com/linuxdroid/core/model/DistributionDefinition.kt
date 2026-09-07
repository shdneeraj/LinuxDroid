package com.linuxdroid.core.model

import kotlinx.serialization.Serializable

/**
 * Supported archive formats for distribution rootfs packages.
 */
@Serializable
enum class ArchiveFormat {
    TAR_XZ,
    TAR_GZ,
    TAR_BZ2,
}

/**
 * Declares the source format and location of a Linux distribution root filesystem.
 */
@Serializable
data class DistributionSource(
    val url: String,
    val checksumUrl: String? = null,
    val expectedChecksum: String? = null,
    val checksumAlgorithm: String = "SHA-256",
    val format: ArchiveFormat = ArchiveFormat.TAR_XZ,
    val stripComponents: Int = 0,
)

/**
 * Manifest describing distribution metadata, capabilities, and requirements.
 */
@Serializable
data class DistributionManifest(
    val version: String,
    val release: String = "",
    val variant: String = "minimal",
    val defaultUser: String = "user",
    val defaultShell: String = "/bin/bash",
    val defaultHome: String = "/home/user",
    val requiredStorageMb: Long = 512,
    val releaseDate: String = "",
)

/**
 * A formal definition of an available Linux distribution.
 */
@Serializable
data class DistributionDefinition(
    val id: String,
    val name: String,
    val distribution: Distribution,
    val architecture: Architecture,
    val release: String,
    val variant: String = "minimal",
    val source: DistributionSource,
    val aptSources: String = "",
    val manifest: DistributionManifest = DistributionManifest(version = "1.0"),
)

/**
 * Metadata describing a specific release version of a distribution.
 */
@Serializable
data class DistroRelease(
    val releaseCode: String,
    val displayName: String,
    val isDefault: Boolean = false,
)

/**
 * Built-in distribution catalog containing official distribution sources.
 */
object DistributionCatalog {

    fun getDefaultCatalog(): List<DistributionDefinition> = listOf(
        getDefinition(Distribution.DEBIAN, Architecture.ARM64),
        getDefinition(Distribution.UBUNTU, Architecture.ARM64),
    )

    fun getAvailableReleases(distribution: Distribution): List<DistroRelease> {
        return when (distribution) {
            Distribution.DEBIAN -> listOf(
                DistroRelease("trixie", "Debian 13 (Trixie)", isDefault = true),
                DistroRelease("bookworm", "Debian 12 (Bookworm)", isDefault = false),
            )
            Distribution.UBUNTU -> listOf(
                DistroRelease("noble", "Ubuntu 24.04 LTS (Noble)", isDefault = true),
                DistroRelease("jammy", "Ubuntu 22.04 LTS (Jammy)", isDefault = false),
            )
            else -> listOf(DistroRelease("default", "Default", isDefault = true))
        }
    }

    fun getDefinition(
        distribution: Distribution,
        architecture: Architecture = Architecture.ARM64,
        release: String? = null,
    ): DistributionDefinition {
        val archSuffix = "arm64"

        return when (distribution) {
            Distribution.DEBIAN -> {
                val targetRelease = release?.lowercase()?.trim()?.ifEmpty { null } ?: "bookworm"
                val releaseName = if (targetRelease == "trixie") "Debian 13 (Trixie)" else "Debian 12 (Bookworm)"
                DistributionDefinition(
                    id = "debian-$targetRelease-$archSuffix",
                    name = "$releaseName (${architecture.linuxArch} Minimal)",
                    distribution = Distribution.DEBIAN,
                    architecture = architecture,
                    release = targetRelease,
                    variant = "default",
                    source = DistributionSource(
                        url = "https://images.linuxcontainers.org/images/debian/$targetRelease/arm64/default/20260904_05:24/rootfs.tar.xz",
                        checksumUrl = "https://images.linuxcontainers.org/images/debian/$targetRelease/arm64/default/20260904_05:24/SHA256SUMS",
                        expectedChecksum = "37617e44b118183b2d47c78b24b523ce7c84729154c21ff075be21b3ec4339f0",
                        checksumAlgorithm = "SHA-256",
                        format = ArchiveFormat.TAR_XZ,
                        stripComponents = 0,
                    ),
                    aptSources = """
                        deb http://deb.debian.org/debian $targetRelease main contrib non-free non-free-firmware
                        deb http://deb.debian.org/debian-security $targetRelease-security main contrib non-free non-free-firmware
                        deb http://deb.debian.org/debian $targetRelease-updates main contrib non-free non-free-firmware
                    """.trimIndent() + "\n",
                    manifest = DistributionManifest(
                        version = targetRelease,
                        release = targetRelease,
                        variant = "default",
                        defaultShell = "/bin/bash",
                    ),
                )
            }

            Distribution.UBUNTU -> {
                val targetRelease = release?.lowercase()?.trim()?.ifEmpty { null } ?: "noble"
                val releaseVersion = if (targetRelease == "jammy") "22.04" else "24.04"
                val releaseDisplayName = if (targetRelease == "jammy") "Ubuntu 22.04 LTS Jammy" else "Ubuntu 24.04 LTS Noble"
                val tarballUrl = if (targetRelease == "jammy") {
                    "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.4-base-arm64.tar.gz"
                } else {
                    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz"
                }
                val checksum = if (targetRelease == "jammy") {
                    "e8c46565538e12a4f488667a7fa38a0f5f654b79b6d85ebbe6b69b6574fcfdfa"
                } else {
                    "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2"
                }
                DistributionDefinition(
                    id = "ubuntu-$targetRelease-$archSuffix",
                    name = "$releaseDisplayName (${architecture.linuxArch} Minimal)",
                    distribution = Distribution.UBUNTU,
                    architecture = architecture,
                    release = targetRelease,
                    variant = "minimal",
                    source = DistributionSource(
                        url = tarballUrl,
                        expectedChecksum = checksum,
                        checksumAlgorithm = "SHA-256",
                        format = ArchiveFormat.TAR_GZ,
                        stripComponents = 0,
                    ),
                    aptSources = """
                        deb http://ports.ubuntu.com/ubuntu-ports $targetRelease main restricted universe multiverse
                        deb http://ports.ubuntu.com/ubuntu-ports $targetRelease-updates main restricted universe multiverse
                        deb http://ports.ubuntu.com/ubuntu-ports $targetRelease-security main restricted universe multiverse
                    """.trimIndent() + "\n",
                    manifest = DistributionManifest(
                        version = releaseVersion,
                        release = targetRelease,
                        variant = "minimal",
                        defaultShell = "/bin/bash",
                    ),
                )
            }

            Distribution.KALI -> {
                DistributionDefinition(
                    id = "kali-rolling-$archSuffix",
                    name = "Kali Linux Rolling (${architecture.linuxArch} Minimal)",
                    distribution = Distribution.KALI,
                    architecture = architecture,
                    release = "current",
                    variant = "default",
                    source = DistributionSource(
                        url = "https://images.linuxcontainers.org/images/kali/current/arm64/default/20260903_17:14/rootfs.tar.xz",
                        checksumUrl = "https://images.linuxcontainers.org/images/kali/current/arm64/default/20260903_17:14/SHA256SUMS",
                        expectedChecksum = "2d1a06f557fab31a55f7a619b6c29c6cc4d036c0e9e3faa8ccf992598318cc8d",
                        checksumAlgorithm = "SHA-256",
                        format = ArchiveFormat.TAR_XZ,
                        stripComponents = 0,
                    ),
                    aptSources = """
                        deb http://http.kali.org/kali kali-rolling main contrib non-free non-free-firmware
                    """.trimIndent() + "\n",
                    manifest = DistributionManifest(
                        version = "rolling",
                        release = "kali-rolling",
                        variant = "default",
                        defaultShell = "/bin/bash",
                    ),
                )
            }

            Distribution.ARCH_LINUX -> {
                DistributionDefinition(
                    id = "archlinux-$archSuffix",
                    name = "Arch Linux (${architecture.linuxArch})",
                    distribution = Distribution.ARCH_LINUX,
                    architecture = architecture,
                    release = "rolling",
                    variant = "minimal",
                    source = DistributionSource(
                        url = "https://images.linuxcontainers.org/images/archlinux/current/arm64/default/rootfs.tar.xz",
                        format = ArchiveFormat.TAR_XZ,
                        stripComponents = 0,
                    ),
                    manifest = DistributionManifest(
                        version = "rolling",
                        release = "rolling",
                        defaultShell = "/bin/bash",
                    ),
                )
            }

            Distribution.ALPINE -> {
                DistributionDefinition(
                    id = "alpine-$archSuffix",
                    name = "Alpine Linux (${architecture.linuxArch})",
                    distribution = Distribution.ALPINE,
                    architecture = architecture,
                    release = "v3.20",
                    variant = "minimal",
                    source = DistributionSource(
                        url = "https://images.linuxcontainers.org/images/alpine/3.20/arm64/default/rootfs.tar.xz",
                        format = ArchiveFormat.TAR_XZ,
                        stripComponents = 0,
                    ),
                    manifest = DistributionManifest(
                        version = "3.20",
                        release = "v3.20",
                        defaultShell = "/bin/sh",
                    ),
                )
            }
        }
    }
}
