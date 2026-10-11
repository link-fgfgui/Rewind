plugins {
    id("java-library")
    id("net.neoforged.moddev") version "2.0.147"
    id("me.modmuss50.mod-publish-plugin") version "2.1.1"
    id("neoforge-mutex")
}

import org.gradle.api.tasks.SourceSetContainer

fun prop(key: String): String = sc.properties[key]

// Common must be evaluated first so its `sourceSets` exist when the jar below
// pulls in the shared classes.
evaluationDependsOn(":common")

group = prop("mod.group")
version = prop("mod.version")
base.archivesName = "${prop("mod.name")}-neoforge-${sc.current.version}"

val requiredJava: JavaVersion = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    else -> JavaVersion.VERSION_21
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
}

neoForge {
    version = prop("deps.neo_loader") as String

    runs {
        register("client") {
            client()
        }
        register("server") {
            server()
            programArgument("--nogui")
        }
    }

    mods {
        register(prop("mod.id") as String) {
            sourceSet(sourceSets["main"])
        }
    }
}

repositories {
    maven("https://maven.sighs.cc/repository/maven-public/")
    maven("https://www.cursemaven.com") {
        name = "Curse Maven"
        content {
            includeGroup("curse.maven")
        }
    }
    // zstd-jni（纯库）：JarJar 内嵌用。有网走中央库；本机 ~/.m2 有就近用。
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation(project(":common"))
    if (sc.current.version == "1.21.1") {
        // Dev runs on 1.21.1 use FML's legacy classpath: only source sets
        // declared in `neoForge.mods` reach MOD_CLASSES, so the shared common
        // project must be added explicitly, or dev fails with
        // NoClassDefFoundError. (Release jars embed common classes below.)
        // 26.1 removed the legacy classpath entirely
        // ("there is no additional classpath anymore"), where the standard
        // runtimeClasspath already covers the project dependency.
        add("additionalRuntimeClasspath", project(":common"))
    }
    implementation(prop("deps.aui"))
    // TODO2 Phase2 P0：zstd-jni 是纯库（非模组），必须 JarJar 进发行 jar——
    // 发行 jar 只拷 common 的类，不带它的外部依赖；不内嵌则生产环境静默退化成 Deflate。
    // metadata 里不声明它（它不是模组，玩家无需另装）。
    add("jarJar", "com.github.luben:zstd-jni:${prop("deps.zstd")}")
}

tasks {
    processResources {
        // Metadata contains non-ASCII comments: without an explicit charset,
        // expand uses the platform default (GBK on Chinese Windows) and writes
        // invalid UTF-8 that the loader fails to parse.
        filteringCharset = "UTF-8"
        val replaceProperties = mapOf(
            "minecraft_version" to prop("deps.neo_mc"),
            "neoforge_version" to prop("deps.neo_loader"),
            "neoforge_version_range" to prop("deps.neo_version_range"),
            "mod_id" to prop("mod.id"),
            "mod_name" to prop("mod.name"),
            "mod_license" to prop("mod.license"),
            "mod_version" to prop("mod.version"),
            "mod_authors" to prop("mod.authors"),
            "mod_description" to prop("mod.description")
        )
        inputs.properties(replaceProperties)
        filesMatching("META-INF/neoforge.mods.toml") {
            expand(replaceProperties)
        }
    }

    named("createMinecraftArtifacts") {
        dependsOn("stonecutterGenerate")
    }

    named<Jar>("jar") {
        // Common resources already merge via
        // gradle/target-conventions/publish.gradle's resources srcDir; only
        // the classes are added here to avoid duplicates.
        from(project(":common").extensions.getByType<SourceSetContainer>()["main"].output.classesDirs)
        val name = prop("mod.id")
        inputs.property("mod_id", name)
        from("../../LICENSE") { rename { "$it-$name" } }
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds mod jars and copies results to `build/libs/{mod version}/`"

        inputs.property("version", prop("mod.version"))
        from(named("jar").flatMap { (it as Jar).archiveFile }, named("sourcesJar").flatMap { (it as Jar).archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${prop("mod.version")}"))
    }
}

apply(from = rootProject.file("gradle/target-conventions/publish.gradle"))
