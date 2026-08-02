import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.file.DuplicatesStrategy

plugins {
    id("gg.grounds.root") version "0.1.1"
    id("com.gradleup.shadow") version "8.3.6"
    application
}

application { mainClass.set("gg.grounds.minestom.lobby.MainKt") }

tasks.named<ShadowJar>("shadowJar") {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
}

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/groundsgg/plugin-permissions")
        credentials {
            username = providers.gradleProperty("github.user").get()
            password = providers.gradleProperty("github.token").get()
        }
    }
    maven {
        url = uri("https://maven.pkg.github.com/groundsgg/plugin-lobby")
        credentials {
            username = providers.gradleProperty("github.user").get()
            password = providers.gradleProperty("github.token").get()
        }
    }
    maven {
        url = uri("https://maven.pkg.github.com/groundsgg/*")
        credentials {
            username = providers.gradleProperty("github.user").get()
            password = providers.gradleProperty("github.token").get()
        }
    }
}

dependencies {
    implementation(platform("gg.grounds:grounds-dependencies:1.0.0"))

    implementation("gg.grounds:grounds-minestom-runtime-runtime-core:0.4.0")
    implementation("net.minestom:minestom")
    implementation("gg.grounds:plugin-agones-minestom:0.6.0")
    implementation("gg.grounds:plugin-permissions-minestom:0.8.0")
    // Reaches the runtime through the SPI, like the two above, so there is no call site here —
    // but being on the classpath is not enough. Discovery only *lists* providers; a provider runs
    // only if LobbyServer names it in useProvider(), and for a long time this one was not named.
    // 0.2.0 is also the first version with the shared chat line, so a message looks the same
    // whether it crossed the proxy or was broadcast inside this lobby.
    implementation("gg.grounds:plugin-chat-minestom:0.2.0")
    // The locked inventory and the slot-9 navigator. Selected unconditionally in
    // LobbyServer: it needs no backing service, and a lobby without it is a lobby a
    // player cannot leave except by disconnecting.
    implementation("gg.grounds:plugin-lobby-minestom:0.2.1")
    // Reads the map's map.json sidecar (the spawn). Minestom pulls gson in transitively;
    // declare it because we use it directly.
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    runtimeOnly("org.slf4j:slf4j-simple")
}
