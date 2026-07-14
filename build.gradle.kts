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
    // The `groundsgg/*` wildcard below does NOT serve gg.grounds.vanilla — GitHub Packages
    // answered it with nothing in CI, and the build fell through to Maven Central and died.
    // Name the publishing repo explicitly, exactly as plugin-permissions does above.
    maven {
        url = uri("https://maven.pkg.github.com/groundsgg/grounds-vanilla")
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
    implementation("gg.grounds:plugin-permissions-minestom:0.5.0")
    implementation("gg.grounds.vanilla:vanilla-maps:0.2.0")
    implementation("gg.grounds.vanilla:vanilla-core:0.2.0")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    runtimeOnly("org.slf4j:slf4j-simple")
}
