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
