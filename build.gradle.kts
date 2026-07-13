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
    implementation("gg.grounds:plugin-permissions-minestom:0.3.0")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    runtimeOnly("org.slf4j:slf4j-simple")
}
