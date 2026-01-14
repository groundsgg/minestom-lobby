plugins {
    id("gg.grounds.root") version "0.1.1"
    application
}

application {
    mainClass.set("gg.grounds.minestom.lobby.LobbyKt")
}

dependencies {
    implementation("net.minestom:minestom:2026.01.08-1.21.11")
}