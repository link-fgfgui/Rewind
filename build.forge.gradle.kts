plugins {
    id("java-library")
    // Must match the moddev version in build.neoforge.gradle.kts: during IDE
    // sync both plugins apply org.jetbrains.gradle.ext.IdeaExtPlugin to the
    // shared ROOT project, and two different MDG versions load two different
    // IdeaExtPlugin classes -> "Cannot add extension with name 'settings'".
    // One version = one class = idempotent apply.
    id("net.neoforged.moddev.legacyforge") version "2.0.147"
    id("me.modmuss50.mod-publish-plugin") version "2.1.1"
}

import org.gradle.api.tasks.SourceSetContainer

fun prop(key: String): String = sc.properties[key]

// Common must be evaluated first so its `sourceSets` exist when the jar below
// pulls in the shared classes.
evaluationDependsOn(":common")

group = prop("mod.group")
version = prop("mod.version")
base.archivesName = "${prop("mod.name")}-forge-${sc.current.version}"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(17)
}

legacyForge {
    version = "${prop("deps.forge_mc")}-${prop("deps.forge")}"
    validateAccessTransformers = true

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
    mavenCentral()
}

dependencies {
    implementation(project(":common"))
    // AUI ships SRG names (1.20.1 methods/fields are m_xxx_/f_xxx_) while dev
    // runs named mappings, so mod jars must go through the remapping
    // `modImplementation` configuration. Plain `implementation` would neither
    // remap nor apply, ending in NoSuchMethodError at dev runtime.
    add("modImplementation", prop("deps.aui"))
    // The Mixin annotation processor must be explicit: the java plugin puts
    // `-processorpath` on this configuration, otherwise no refmap is
    // generated and reobfJar fails for missing mappings.
    annotationProcessor("org.spongepowered:mixin:${prop("deps.mixin")}")
    annotationProcessor("com.google.code.gson:gson:${prop("deps.mixin_gson")}")
    annotationProcessor("com.google.guava:guava:${prop("deps.mixin_guava")}")
    annotationProcessor("org.ow2.asm:asm:${prop("deps.mixin_asm")}")
    annotationProcessor("org.ow2.asm:asm-tree:${prop("deps.mixin_asm")}")
    annotationProcessor("org.ow2.asm:asm-commons:${prop("deps.mixin_asm")}")
    annotationProcessor("org.ow2.asm:asm-util:${prop("deps.mixin_asm")}")
    annotationProcessor("org.ow2.asm:asm-analysis:${prop("deps.mixin_asm")}")
}

// Mixin wiring (Forge 1.20.1 differs from NeoForge):
//   * release jars declare configs via the jar manifest `MixinConfigs`
//     (read by MixinPlatformAgentDefault; FML itself does not parse it);
//   * dev runs load mods from directories (no manifest), so MDG passes
//     `--mixin.config <file>` program args plus refmap remapping flags;
//   * `add` generates the refmap (named -> SRG) at compile time for release.
mixin {
    config("rewind.mixins.json")
    config("rewind.client.mixins.json")
    add(sourceSets["main"], "rewind.refmap.json")
}

tasks {
    processResources {
        // Metadata contains non-ASCII comments: without an explicit charset,
        // expand uses the platform default (GBK on Chinese Windows) and writes
        // invalid UTF-8 that the loader fails to parse.
        filteringCharset = "UTF-8"
        val replaceProperties = mapOf(
            "minecraft_version" to sc.current.version,
            "forge_version" to prop("deps.forge"),
            "forge_version_range" to prop("deps.forge_version_range"),
            "loader_version_range" to prop("deps.forge_loader_range"),
            "mod_id" to prop("mod.id"),
            "mod_name" to prop("mod.name"),
            "mod_license" to prop("mod.license"),
            "mod_version" to prop("mod.version"),
            "mod_authors" to prop("mod.authors"),
            "mod_description" to prop("mod.description")
        )
        inputs.properties(replaceProperties)
        filesMatching("META-INF/mods.toml") {
            expand(replaceProperties)
        }
    }

    named<Jar>("jar") {
        // Forge 1.20.1 discovers mixin configs from the jar manifest.
        manifest {
            attributes("MixinConfigs" to "rewind.mixins.json,rewind.client.mixins.json")
        }
        // Shared common classes go into the final jar (dev runs resolve them
        // via the project dependency above).
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
        // legacyforge publishes the reobfuscated jar; `jar` is the named dev jar.
        from(named("reobfJar").flatMap { (it as Jar).archiveFile }, named("sourcesJar").flatMap { (it as Jar).archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${prop("mod.version")}"))
    }
}

apply(from = rootProject.file("gradle/target-conventions/publish.gradle"))
