plugins {
    id("fabric-loom") version "1.10.5"
    id("me.modmuss50.mod-publish-plugin") version "2.1.1"
}

import org.gradle.api.tasks.SourceSetContainer

fun prop(key: String): String = sc.properties[key]

// Common must be evaluated first so its `sourceSets` exist when the jar below
// pulls in the shared classes.
evaluationDependsOn(":common")

group = prop("mod.group")
version = prop("mod.version")
base.archivesName = "${prop("mod.name")}-fabric-${sc.current.version}"

val requiredJava: JavaVersion = JavaVersion.VERSION_17

repositories {
    mavenLocal()
    mavenCentral()
    // ApricityUI: HTML/JS-driven UI library used by the time-tree screen
    maven("https://maven.sighs.cc/repository/maven-public/")
}

dependencies {
    minecraft("com.mojang:minecraft:${sc.current.version}")
    mappings(loom.officialMojangMappings())

    modImplementation("net.fabricmc:fabric-loader:${prop("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${prop("deps.fabric_api")}")
    // UI library (only used by client code; kept here so UI classes resolve everywhere)
    modImplementation(prop("deps.aui"))
    implementation(project(":common"))
    // TODO2 Phase2 P0：zstd-jni 是纯库（非模组），必须 include 进发行 jar——
    // 不内嵌则生产环境静默退化成 Deflate。不要进 fabric.mod.json 的 depends（它不是模组）。
    modImplementation("com.github.luben:zstd-jni:${prop("deps.zstd")}")
    include("com.github.luben:zstd-jni:${prop("deps.zstd")}")
}

loom {
    runs {
        named("server") {
            // loom run configs use `programArgs`, not `programArgument`
            // (the latter is ModDevGradle's DSL and fails the run outright).
            programArgs("--nogui")
        }
    }
}

java {
    withSourcesJar()
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava

    toolchain {
        vendor = JvmVendorSpec.ADOPTIUM
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}

tasks {
    processResources {
        // Metadata contains non-ASCII comments: without an explicit charset,
        // expand uses the platform default (GBK on Chinese Windows) and writes
        // invalid UTF-8 that the loader fails to parse.
        filteringCharset = "UTF-8"
        val replaceProperties = mapOf(
            "minecraft_version" to sc.current.version,
            "fabric_loader_version" to prop("deps.fabric_loader"),
            "mod_id" to prop("mod.id"),
            "mod_name" to prop("mod.name"),
            "mod_license" to prop("mod.license"),
            "mod_version" to prop("mod.version"),
            "mod_authors" to prop("mod.authors"),
            "mod_description" to prop("mod.description")
        )
        inputs.properties(replaceProperties)
        filesMatching("fabric.mod.json") {
            expand(replaceProperties)
        }
    }

    withType<Jar> {
        // Shared common classes go into the final jar (dev runs resolve them
        // via the project dependency above).
        from(project(":common").extensions.getByType<SourceSetContainer>()["main"].output.classesDirs)
        val name = prop("mod.id")
        inputs.property("mod_id", name)
        from("../../LICENSE") { rename { "$it-$name" } }
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(17)
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds mod jars and copies results to `build/libs/{mod version}/`"

        inputs.property("version", prop("mod.version"))
        from(named("remapJar").flatMap { (it as Jar).archiveFile }, named("remapSourcesJar").flatMap { (it as Jar).archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${prop("mod.version")}"))
    }
}

apply(from = rootProject.file("gradle/target-conventions/publish.gradle"))
