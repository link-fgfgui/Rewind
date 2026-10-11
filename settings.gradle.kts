pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "FabricMC" }
        maven("https://maven.minecraftforge.net/") { name = "Forge" }
        maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
    }
}

plugins {
    // Check the latest version on https://stonecutter.kikugie.dev/blog/changes/0.9
    id("dev.kikugie.stonecutter") version "0.9.8"

    // Provisions JDK 17/21/25 toolchains for the versioned builds below.
    // (https://github.com/gradle/foojay-toolchains)
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    create(rootProject) {
        /**
         * One node per supported (Minecraft version, loader) pair.
         *
         * Layout note: each node keeps its version-specific sources under
         * `versions/<project>/src` (moved verbatim from the old
         * `targets/<loader>-<mc>/src`). The cross-version files that are shared
         * by all four nodes live in the root `src/` (11 files) and use `//?`
         * Stonecutter preprocessing; `stonecutterGenerate` merges and resolves
         * them into each node's generated source set at build time.
         */
        fun match(project: String, vararg loaders: String, version: String = project) {
            for (loader in loaders) version("$project-$loader", version).buildscript("build.$loader.gradle.kts")
        }

        match("1.20.1", "fabric", "forge")
        match("1.21.1", "neoforge")
        match("26.1", "neoforge")
        vcsVersion = "1.21.1-neoforge"
    }
}

include("common")

rootProject.name = "Rewind"
