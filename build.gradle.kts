plugins {
    id("gg.grounds.root") version "0.1.1"
    id("com.gradleup.shadow") version "8.3.6"
    application
}

application { mainClass.set("gg.grounds.minestom.lobby.MainKt") }

dependencies {
    implementation("net.minestom:minestom:2026.01.08-1.21.11")
    implementation("com.github.ajalt.clikt:clikt:5.0.1")
}
