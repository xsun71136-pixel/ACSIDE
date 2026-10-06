/*
 *  ACSIDE MCP Server plugin - main (plugin) module.
 *
 *  This module is packaged by the official ACSIDE packaging plugin into
 *  "McpServer.acp" (an encrypted archive that contains McpServer/plugin.apk
 *  plus McpServer/meta/*).
 *
 *  IMPORTANT: everything the IDE already provides (plugin API, Jetpack Compose)
 *  is declared `compileOnly` so that nothing is bundled into plugin.apk. The
 *  plugin must resolve those classes from the IDE classloader at runtime,
 *  otherwise PluginApi would be a different (unwired) class instance.
 */

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Official ACSIDE .acp packager (Maven Central).
    id("io.github.nullij.acside-gradle-plugin").version("0.2.0")
}

val pluginVersion = "1.0"
val pluginName = "McpServer"
val packageName = "io.github.xsun71136.plugins.mcp"

acpPlugin {
    metaFolderPath = "meta"
    outputFileName = "${pluginName}.acp"
}

android {
    namespace = packageName
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = pluginVersion
    }

    buildFeatures { compose = true }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // Provided by the IDE at runtime - never bundle.
    compileOnly(libs.acside.plugins.api)
    compileOnly(libs.compose.runtime)
    compileOnly(libs.compose.ui)
    compileOnly(libs.compose.ui.graphics)
    compileOnly(libs.compose.foundation)
    compileOnly(libs.compose.material3)

    // The plugin deliberately bundles no libraries: it uses plain java.net /
    // java.io / java.lang.Thread plus the Compose and plugin-API classes the IDE
    // already provides. That keeps plugin.apk small and removes any chance of a
    // duplicated class being resolved from the wrong classloader.
}

/** Rewrite main/meta/plugin.json version + name so the manifest always matches the build. */
tasks.register("updatePluginInfo") {
    doLast {
        val f = file("meta/plugin.json")
        if (!f.exists()) return@doLast
        var text = f.readText()
        text = text.replace(Regex("\"version\"\\s*:\\s*\"[^\"]*\""), "\"version\": \"$pluginVersion\"")
        text = text.replace(Regex("\"name\"\\s*:\\s*\"[^\"]*\""), "\"name\": \"$pluginName\"")
        f.writeText(text)
        println("updatePluginInfo: version=$pluginVersion name=$pluginName")
    }
}

tasks.named("preBuild") { dependsOn("updatePluginInfo") }

/**
 * After an assemble, copy the produced .acp to the plugin root and refresh
 * repository.json (latestVersion / lastUpdated / checksum / size).
 * repository.json is what the ACSIDE plugin repository browser consumes.
 */
tasks.register("updateRepositoryJson") {
    description = "Copies the .acp to the plugin root and updates repository.json metadata."
    group = "publishing"
    doLast {
        val buildDir = file("build")
        val acpFile = buildDir.resolve("${pluginName}.acp").takeIf { it.exists() }
            ?: buildDir.walkTopDown().firstOrNull { it.extension == "acp" }
        if (acpFile == null) {
            println("updateRepositoryJson: no .acp produced under ${buildDir.absolutePath} - skipping")
            return@doLast
        }
        val rootDir = rootProject.projectDir
        val destAcp = rootDir.resolve("${pluginName}.acp")
        acpFile.copyTo(destAcp, overwrite = true)

        val bytes = destAcp.readBytes()
        val checksum = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { b -> "%02x".format(b) }
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())

        val repoFile = rootDir.resolve("repository.json")
        if (repoFile.exists()) {
            var text = repoFile.readText()
            text = text.replace(Regex("\"latestVersion\"\\s*:\\s*\"[^\"]*\""), "\"latestVersion\": \"$pluginVersion\"")
            text = text.replace(Regex("\"lastUpdated\"\\s*:\\s*\"[^\"]*\""), "\"lastUpdated\": \"$timestamp\"")
            text = text.replace(Regex("\"checksum\"\\s*:\\s*\"[^\"]*\""), "\"checksum\": \"$checksum\"")
            text = text.replace(Regex("\"size\"\\s*:\\s*[0-9]+"), "\"size\": ${bytes.size}")
            repoFile.writeText(text)
        }
        println("updateRepositoryJson: acp=${destAcp.name} bytes=${bytes.size} sha256=$checksum updated=$timestamp")
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "assembleDebug" }.configureEach {
    finalizedBy("updateRepositoryJson")
}
