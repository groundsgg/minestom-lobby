rootProject.name = "minestom-lobby"

pluginManagement {
    repositories {
        mavenLocal()
        maven {
            url = uri("https://maven.pkg.github.com/groundsgg/*")
            credentials {
                username = providers.gradleProperty("github.user").get()
                password = providers.gradleProperty("github.token").get()
            }
        }
        gradlePluginPortal()
    }
}

// Licensed model inputs stay outside this repository and public Maven artifacts.
// Scene and every other dependency continue to resolve from their pinned releases.
providers.gradleProperty("npcResourcepacksDirectory").orNull?.let { directory ->
    require(providers.gradleProperty("npcSourceDirectory").isPresent) {
        "Private NPC builds require -PnpcSourceDirectory"
    }
    require(providers.gradleProperty("npcAddonZip").isPresent) {
        "Private NPC builds require -PnpcAddonZip"
    }
    includeBuild(file(directory)) {
        dependencySubstitution {
            substitute(module("gg.grounds:resourcepacks-catalog"))
                .using(project(":resourcepacks-catalog"))
            substitute(module("gg.grounds:resourcepacks-npc-runtime"))
                .using(project(":resourcepacks-npc-runtime"))
        }
    }
}

// Preserve the accepted Stage announcement-ownership implementation until its release.
providers.gradleProperty("npcLobbySourceDirectory").orNull?.let { directory ->
    require(providers.gradleProperty("npcResourcepacksDirectory").isPresent) {
        "Pinned Lobby sources belong to the private NPC distribution"
    }
    includeBuild(file(directory)) {
        dependencySubstitution {
            substitute(module("gg.grounds:plugin-lobby-minestom")).using(project(":minestom"))
            substitute(module("gg.grounds:plugin-lobby-scene-catalog"))
                .using(project(":scene-catalog"))
        }
    }
}

// Local qualification uses the exact reviewed sources before the library release exists.
providers.gradleProperty("lodSourceDirectory").orNull?.let { directory ->
    includeBuild(file(directory)) {
        dependencySubstitution {
            substitute(module("gg.grounds:service-maps-lod-runtime")).using(project(":lod-runtime"))
            substitute(module("gg.grounds:service-maps-lod-format")).using(project(":lod-format"))
        }
    }
}
