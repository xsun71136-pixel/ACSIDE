/*
 *  This file is part of the ACSIDE MCP Server plugin.
 *  Licensed under the GNU General Public License v3.0.
 */

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "McpServer"

// Pin the module explicitly. Without this Gradle silently treated ":main" as an
// empty project in CI (":main:buildEnvironment" reported "classpath: No
// dependencies" and assemble* did not exist), i.e. main/build.gradle.kts was
// never applied.
include(":main")
project(":main").projectDir = file("main")
project(":main").buildFileName = "build.gradle.kts"
