plugins {
    id("gg.grounds.root") version "0.1.1"
    id("com.gradleup.shadow") version "8.3.6"
}

val lobbyMainClass = "gg.grounds.minestom.lobby.MainKt"

// We are attaching to Jar here, because with ShadowJar it does not work for some reason
tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = lobbyMainClass
    }
}

tasks.register<JavaExec>("run") {
    classpath = files(tasks.shadowJar)
    group = "minestom"
    mainClass.set(lobbyMainClass)
    standardInput = System.`in`
    standardOutput = System.out
    errorOutput = System.err
    description = "Runs the Lobby Server"
}

dependencies {
    implementation("net.minestom:minestom:2026.03.03-1.21.11")
    implementation("com.github.ajalt.clikt:clikt:5.0.1")
    implementation("io.github.oshai:kotlin-logging-jvm:5.1.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("com.michael-bull.kotlin-result:kotlin-result:2.3.1")
}
