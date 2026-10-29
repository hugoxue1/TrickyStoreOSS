/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationVariant
import java.util.zip.ZipFile

plugins { alias(libs.plugins.android.application) }

val gitCommitCount =
    providers
        .exec {
            commandLine("git", "rev-list", "HEAD", "--count")
            workingDir = rootDir
        }
        .standardOutput
        .asText
        .map { it.trim().toInt() }
        .get()

val gitCommitHash =
    providers
        .exec {
            commandLine("git", "rev-parse", "--verify", "--short", "HEAD")
            workingDir = rootDir
        }
        .standardOutput
        .asText
        .map { it.trim() }
        .get()

  val verName = "v3.1.1-ckb1"

android {
    namespace = "io.github.beakthoven.TrickyStoreOSS"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "TrickyStoreDebug"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "io.github.beakthoven.TrickyStoreOSS"
        minSdk = 29
        targetSdk = 37
        versionCode = gitCommitCount
        versionName = verName

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                arguments += "-DANDROID_ALLOW_UNDEFINED_SYMBOLS=ON"
                arguments += "-DCMAKE_CXX_STANDARD=23"
                arguments += "-DCMAKE_C_STANDARD=23"
                arguments += "-DCMAKE_INTERPROCEDURAL_OPTIMIZATION=ON"

                cppFlags += "-std=c++23"
                cppFlags += "-fno-exceptions"
                cppFlags += "-fno-rtti"
                cppFlags += "-fvisibility=hidden"
                cppFlags += "-fvisibility-inlines-hidden"
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_24
        targetCompatibility = JavaVersion.VERSION_24
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.28.0+"
        }
    }
    buildFeatures { prefab = true }
    packaging { resources { pickFirsts += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/INDEX.LIST") } }
}

dependencies {
    compileOnly(project(":stub"))
    compileOnly(libs.annotation)
    implementation(libs.org.bouncycastle.bcpkix.jdk18on)
    implementation(libs.org.lsposed.libcxx.libcxx)
}

androidComponents {
    onVariants { variant: ApplicationVariant ->
        val variantName = variant.name
        val capitalized = variantName.replaceFirstChar { it.uppercase() }
        val sourceDir = project.rootDir.resolve("module")
        val tempModuleDir = project.layout.buildDirectory.dir("tmp/module-${variantName}")
        val apkFile = variant.artifacts.get(SingleArtifact.APK)

        tasks.register("copyFiles${capitalized}") {
            val moduleFolder = project.rootDir.resolve("module")
            val buildDir = project.layout.buildDirectory

            doLast {
                val isDebug = variantName.contains("debug", ignoreCase = true)

                listOf("service.apk", "classes.dex").forEach { fileName ->
                    val oldFile = moduleFolder.resolve(fileName)
                    if (oldFile.exists()) oldFile.delete()
                }

                val sourceFile =
                    buildDir.get().asFile.resolve("outputs/apk/$variantName/app-$variantName-unsigned.apk")

                val destFileName = "service.apk"
                sourceFile.copyTo(moduleFolder.resolve(destFileName), overwrite = true)

                val soDir =
                    buildDir
                        .get()
                        .asFile
                        .resolve("intermediates/stripped_native_libs/$variantName/strip${capitalized}DebugSymbols/out/lib")

                val allowedLibs = setOf("libinject.so", "libTrickyStoreOSS.so")
                soDir
                    .walk()
                    .filter { it.isFile && it.name in allowedLibs }
                    .forEach { soFile ->
                        val abiFolder = soFile.parentFile.name
                        val destination = moduleFolder.resolve("lib/$abiFolder/${soFile.name}")
                        soFile.copyTo(destination, overwrite = true)
                    }
            }
        }

        // [P0-2 升级] copyFiles：真 APK 做 service.apk（Android 11 ClassNotFound:MainKt 修复，
//         见 ea90741/dba14c4）——保留我们的部署管线；apkFile 供上游 prepareModuleFiles 使用
        // Prepare temp directory with all files

        tasks.register("prepareModuleFiles${capitalized}") {
            notCompatibleWithConfigurationCache("stages APK contents into the variant module directory")
            inputs.dir(sourceDir)
            inputs.dir(apkFile)
            outputs.dir(tempModuleDir)

            doLast {
                val tempDir = tempModuleDir.get().asFile
                tempDir.deleteRecursively()
                tempDir.mkdirs()

                sourceDir.walkTopDown()
                    .filter { file ->
                        file.isFile &&
                            file.name !in setOf("module.prop", "service.apk", "classes.dex") &&
                            !file.relativeTo(sourceDir).invariantSeparatorsPath.startsWith("lib/")
                    }
                    .forEach { file ->
                        val destination = tempDir.resolve(file.relativeTo(sourceDir))
                        destination.parentFile.mkdirs()
                        file.copyTo(destination, overwrite = true)
                    }

                tempDir.resolve("module.prop").writeText(
                    sourceDir.resolve("module.prop")
                        .readText()
                        .replace("REPLACEMEVERCODE", gitCommitCount.toString())
                        .replace("REPLACEMEVER", "$verName ($gitCommitCount-$gitCommitHash-$variantName)"),
                )

                val apks = apkFile.get().asFile.walkTopDown()
                    .filter { it.isFile && it.extension == "apk" }
                    .sortedBy { it.invariantSeparatorsPath }
                    .toList()
                check(apks.size == 1) { "Expected exactly one APK output for $variantName, found ${apks.size}" }
                val apk = apks.single()

                ZipFile(apk).use { zip ->
                    if (variantName.contains("debug", ignoreCase = true)) {
                        apk.copyTo(tempDir.resolve("service.apk"), overwrite = true)
                    } else {
                        val dex = zip.getEntry("classes.dex") ?: error("Release APK does not contain classes.dex")
                        zip.getInputStream(dex).use { input ->
                            tempDir.resolve("classes.dex").outputStream().use(input::copyTo)
                        }
                    }

                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (!entry.isDirectory && entry.name.startsWith("lib/") &&
                            entry.name.substringAfterLast('/') in setOf("libinject.so", "libTrickyStoreOSS.so")
                        ) {
                            val destination = tempDir.resolve(entry.name)
                            destination.parentFile.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                destination.outputStream().use(input::copyTo)
                            }
                        }
                    }
                }
            }
        }

        tasks.register<Zip>("zip${capitalized}") {
            dependsOn("prepareModuleFiles${capitalized}")
            archiveFileName.set("Tricky-Store-OSS-$verName-$gitCommitCount-$gitCommitHash-${capitalized}.zip")
            destinationDirectory.set(project.rootDir.resolve("out"))
            from(tempModuleDir)
        }

        tasks.matching { it.name == "assemble${capitalized}" }.configureEach { finalizedBy("zip${capitalized}") }
    }
}
