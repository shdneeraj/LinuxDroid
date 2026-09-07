import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.linuxdroid.app"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "com.linuxdroid.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // ABI filter: arm64-v8a primary target
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "linuxdroid"
            keyAlias = "linuxdroid"
            keyPassword = "linuxdroid"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add("src/main/jniLibs")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf(
                "**/libgl-renderer.so",
                "**/libproot.so",
                "**/libproot_loader.so",
            )
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.addAll(
            listOf(
                "androidx.compose.material3.ExperimentalMaterial3Api",
                "androidx.compose.foundation.ExperimentalFoundationApi",
                "kotlinx.coroutines.ExperimentalCoroutinesApi",
            ),
        )
    }
}

dependencies {
    // Core modules
    implementation(project(":core:core-model"))
    implementation(project(":core:core-logging"))
    implementation(project(":core:core-database"))
    implementation(project(":core:core-runtime"))
    implementation(project(":core:core-process"))
    implementation(project(":core:core-session"))
    implementation(project(":core:core-filesystem"))
    implementation(project(":core:core-storage"))
    implementation(project(":core:core-display"))
    implementation(project(":core:core-gpu"))
    implementation(project(":core:core-input"))
    implementation(project(":core:core-audio"))
    implementation(project(":core:core-network"))
    implementation(project(":core:core-package"))
    implementation(project(":core:core-diagnostics"))
    implementation(project(":core:core-host"))

    // Native modules
    implementation(project(":native:bridge"))

    // Vendor modules
    implementation(project(":vendor:proot"))

    // Linux modules
    implementation(project(":linux:bootstrap"))

    // Room - explicitly declared in app so DI module can reference RoomDatabase
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.startup)
    implementation(libs.androidx.datastore.preferences)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    implementation(libs.androidx.navigation.compose)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Logging
    implementation(libs.timber)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.mockk.android)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}

val syncProotArtifacts = tasks.register("syncProotArtifacts") {
    group = "distribution"
    description = "Synchronizes standalone PRoot binaries, loader, MANIFEST, and JNI libs from vendor/proot"
    dependsOn(":vendor:proot:assembleRelease")

    val assetsTarget = file("src/main/assets/proot/arm64-v8a")
    val jniLibsTarget = file("src/main/jniLibs/arm64-v8a")

    outputs.dirs(assetsTarget, jniLibsTarget)

    doLast {
        assetsTarget.mkdirs()
        jniLibsTarget.mkdirs()

        // Locate CMake native build artifacts in vendor/proot
        val prootIntermediates = rootProject.file("vendor/proot/build/intermediates/cxx")
        val arm64Dir = prootIntermediates.walkTopDown()
            .filter { it.isDirectory && it.name == "arm64-v8a" }
            .firstOrNull { File(it, "proot-bin").exists() }
            ?: rootProject.file("vendor/proot/dist/android/arm64-v8a")

        if (arm64Dir.exists()) {
            val prootBin = File(arm64Dir, "proot-bin").takeIf { it.exists() } ?: File(arm64Dir, "proot")
            val loaderBin = File(arm64Dir, "prootloader-bin").takeIf { it.exists() } ?: File(arm64Dir, "loader")

            if (prootBin.exists()) {
                val dst = File(assetsTarget, "proot")
                prootBin.copyTo(dst, overwrite = true)
                dst.setExecutable(true, false)
            }
            if (loaderBin.exists()) {
                val dst = File(assetsTarget, "loader")
                loaderBin.copyTo(dst, overwrite = true)
                dst.setExecutable(true, false)
            }

            listOf("libproot.so", "libproot_loader.so").forEach { name ->
                val src = File(arm64Dir, name)
                if (src.exists()) {
                    src.copyTo(File(jniLibsTarget, name), overwrite = true)
                }
            }
            // Ensure no obsolete talloc or shmem dynamic libraries remain in jniLibs
            File(jniLibsTarget, "libtalloc.so").takeIf { it.exists() }?.delete()
            File(jniLibsTarget, "libandroid-shmem.so").takeIf { it.exists() }?.delete()
        }

        val prootFile = File(assetsTarget, "proot")
        val loaderFile = File(assetsTarget, "loader")
        fun sha256(file: File): String {
            if (!file.exists()) return "unknown"
            val md = MessageDigest.getInstance("SHA-256")
            return file.inputStream().use { input ->
                val buf = ByteArray(8192)
                var read: Int
                while (input.read(buf).also { read = it } != -1) md.update(buf, 0, read)
                md.digest().joinToString("") { b -> "%02x".format(b) }
            }
        }

        val manifest = File(assetsTarget, "MANIFEST.txt")
        manifest.writeText(
            """
            LinuxDroid-PRoot v5.1.107.92
            commit:  caadcae0e7697ec29f02e231a3a88866561aacd0
            ABI:     arm64-v8a
            arch:    aarch64
            sha256:
              proot:    ${sha256(prootFile)}
              loader:   ${sha256(loaderFile)}
            """.trimIndent() + "\n"
        )
    }
}

val syncLinuxDroidAssets = tasks.register("syncLinuxDroidAssets") {
    group = "distribution"
    description = "Validates and synchronizes LDDM and LDDE ARM64 .deb packages and installer scripts into APK runtime assets"

    val packagesAssetsDir = file("src/main/assets/packages")
    val scriptsAssetsDir = file("src/main/assets/scripts")
    val lddmPackagesDir = rootProject.file("vendor/LDDM/build-release/packages")
    val lddeDistDir = rootProject.file("vendor/LDDE/dist")
    val bootstrapScript = rootProject.file("linux/bootstrap/install_rootfs.sh")

    outputs.dirs(packagesAssetsDir, scriptsAssetsDir)

    doLast {
        packagesAssetsDir.mkdirs()
        scriptsAssetsDir.mkdirs()

        // 1. Sync install_rootfs.sh
        if (bootstrapScript.exists()) {
            val destScript = File(scriptsAssetsDir, "install_rootfs.sh")
            bootstrapScript.copyTo(destScript, overwrite = true)
            destScript.setExecutable(true, false)
        } else {
            throw GradleException("Cannot build LinuxDroid APK: linux/bootstrap/install_rootfs.sh is missing.")
        }

        // 2. Find and validate LDDM deb
        val lddmDeb = lddmPackagesDir.listFiles()?.firstOrNull {
            it.name.startsWith("linuxdroid-display-manager") && it.name.endsWith(".deb") && it.name.contains("arm64")
        } ?: File(packagesAssetsDir, "linuxdroid-display-manager_0.1.0_arm64.deb").takeIf { it.exists() }

        if (lddmDeb == null || !lddmDeb.exists() || lddmDeb.length() == 0L) {
            throw GradleException("Cannot build LinuxDroid APK: Missing ARM64 LDDM package (linuxdroid-display-manager_*_arm64.deb). Please run ./scripts/build-packages.sh.")
        }

        // 3. Find and validate LDDE deb
        val lddeDeb = lddeDistDir.listFiles()?.firstOrNull {
            it.name.startsWith("linuxdroid-desktop-environment") && it.name.endsWith(".deb") && it.name.contains("arm64")
        } ?: File(packagesAssetsDir, "linuxdroid-desktop-environment_1.0.0_arm64.deb").takeIf { it.exists() }

        if (lddeDeb == null || !lddeDeb.exists() || lddeDeb.length() == 0L) {
            throw GradleException("Cannot build LinuxDroid APK: Missing ARM64 LDDE package (linuxdroid-desktop-environment_*_arm64.deb). Please run ./scripts/build-packages.sh.")
        }

        // Copy to assets if coming from vendor dirs
        val lddmAsset = File(packagesAssetsDir, lddmDeb.name)
        if (lddmDeb.absolutePath != lddmAsset.absolutePath) {
            lddmDeb.copyTo(lddmAsset, overwrite = true)
        }

        val lddeAsset = File(packagesAssetsDir, lddeDeb.name)
        if (lddeDeb.absolutePath != lddeAsset.absolutePath) {
            lddeDeb.copyTo(lddeAsset, overwrite = true)
        }

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            return file.inputStream().use { input ->
                val buf = ByteArray(8192)
                var read: Int
                while (input.read(buf).also { read = it } != -1) md.update(buf, 0, read)
                md.digest().joinToString("") { b -> "%02x".format(b) }
            }
        }

        val manifestFile = File(packagesAssetsDir, "PACKAGES_MANIFEST.txt")
        manifestFile.writeText(
            """
            Package: linuxdroid-display-manager
            Version: 0.1.0
            Architecture: arm64
            File: ${lddmAsset.name}
            SHA256: ${sha256(lddmAsset)}

            Package: linuxdroid-desktop-environment
            Version: 1.0.0
            Architecture: arm64
            File: ${lddeAsset.name}
            SHA256: ${sha256(lddeAsset)}
            """.trimIndent() + "\n"
        )
    }
}

tasks.named("preBuild") {
    dependsOn(syncProotArtifacts)
    dependsOn(syncLinuxDroidAssets)
}



