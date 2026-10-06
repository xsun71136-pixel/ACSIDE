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
include(":main")
