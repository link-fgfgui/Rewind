plugins {
    id("dev.kikugie.stonecutter")
}

// Active node for editing and `runClient`/`runServer`. The 1.21.1 NeoForge
// node is the primary dev target. Shared code in the root `src/` is preprocessed
// with `//?` conditionals per active node.
stonecutter active "1.21.1-neoforge"

// See https://stonecutter.kikugie.dev/wiki/config/params
stonecutter parameters {
    val (version, loader) = current.project.split('-', limit = 2)

    // Makes version- and loader-specific properties apply from `stonecutter.properties.toml`
    properties {
        tags(version, loader)
    }

    // Loader constants for build scripts (no `//?` sources exist yet; kept for future use)
    constants {
        match(loader, "fabric", "forge", "neoforge")
    }
}
