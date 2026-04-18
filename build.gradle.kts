plugins {
    id("gg.grounds.root") version "0.1.1"
    id("com.gradleup.shadow") version "8.3.6"
    application
}

application { mainClass.set("gg.grounds.minestom.lobby.MainKt") }

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/groundsgg/*")
        credentials {
            username = providers.gradleProperty("github.user").get()
            password = providers.gradleProperty("github.token").get()
        }
    }
}

dependencies {
    implementation("net.minestom:minestom:2026.01.08-1.21.11")
    implementation("com.github.ajalt.clikt:clikt:5.0.1")
    implementation("gg.grounds:plugin-agones-minestom:0.2.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
}
