import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.HexFormat
import org.gradle.api.file.DuplicatesStrategy

plugins {
    id("gg.grounds.root") version "0.1.1"
    id("com.gradleup.shadow") version "8.3.6"
    application
}

// Keep private resources out of subsequent ordinary builds, including incremental ones.
if (providers.gradleProperty("npcResourcepacksDirectory").isPresent) {
    layout.buildDirectory.set(layout.projectDirectory.dir("build/private-npc"))
}

tasks.register<JavaExec>("generateLobbySceneFixture") {
    group = "verification"
    description =
        "Generate a new, test-only scene JSON under build/fixtures at explicit coordinates"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("gg.grounds.minestom.lobby.scene.LobbySceneFixtureKt")
    workingDir = project.projectDir
    val filename = providers.gradleProperty("sceneFixtureName").orElse("lobby-scene.json")
    val npc = providers.gradleProperty("sceneNpc")
    val marker = providers.gradleProperty("sceneMarker")
    doFirst {
        require(npc.isPresent && marker.isPresent) {
            "Provide explicit -PsceneNpc=x,y,z and -PsceneMarker=x,y,z coordinates"
        }
        args(filename.get(), npc.get(), marker.get())
    }
}

tasks.register<JavaExec>("migrateQueueNpcScene") {
    group = "scene"
    description =
        "Adds Scene queue NPCs to an explicit input and writes a new sidecar; never overwrites."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("gg.grounds.minestom.lobby.scene.QueueNpcSceneMigrationKt")
    doFirst {
        args(
            listOf("sceneInput", "sceneOutput", "sceneNpcBody").map { key ->
                providers.gradleProperty(key).orNull ?: error("Missing -P$key")
            } +
                listOf(
                    providers.gradleProperty("sceneIdleAnimation").orNull ?: "-",
                    providers.gradleProperty("sceneGreetingAnimation").orNull ?: "-",
                )
        )
    }
}

application { mainClass.set("gg.grounds.minestom.lobby.MainKt") }

providers.gradleProperty("npcAddonZip").orNull?.let { addon ->
    require(providers.gradleProperty("npcResourcepacksDirectory").isPresent) {
        "NPC addon binding requires the private Resourcepacks source build"
    }
    val bindingDirectory = layout.buildDirectory.dir("generated/resources/npc-distribution")
    val bindingFile =
        bindingDirectory.map { it.file("gg/grounds/minestom/lobby/npc-addon-sha1.txt") }
    val generateNpcAddonBinding =
        tasks.register("generateNpcAddonBinding") {
            inputs.file(file(addon))
            outputs.file(bindingFile)
            doLast {
                val digest = MessageDigest.getInstance("SHA-1")
                file(addon).inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val target = bindingFile.get().asFile
                target.parentFile.mkdirs()
                target.writeText(HexFormat.of().formatHex(digest.digest()) + "\n")
            }
        }
    sourceSets.main { resources.srcDir(bindingDirectory) }
    tasks.processResources { dependsOn(generateNpcAddonBinding) }
}

val permissionManifestVersion =
    providers
        .gradleProperty("versionOverride")
        .orElse(
            providers
                .fileContents(layout.projectDirectory.file(".release-please-manifest.json"))
                .asText
                .map { text ->
                    val manifest = JsonSlurper().parseText(text) as Map<*, *>
                    requireNotNull((manifest["."] as? String)?.takeIf { it.isNotBlank() }) {
                        "Missing Lobby release version for permission catalog"
                    }
                }
        )

tasks.processResources {
    inputs.property("permissionManifestVersion", permissionManifestVersion)
    filesMatching("META-INF/grounds/permissions/minestom-lobby.json") {
        expand("version" to permissionManifestVersion.get())
    }
}

tasks.test {
    doFirst {
        systemProperty("lobby.test.runtimeClasspath", sourceSets.test.get().runtimeClasspath.asPath)
    }
    systemProperty("org.slf4j.simpleLogger.cacheOutputStream", "false")
}

tasks.named<ShadowJar>("shadowJar") {
    // Project classes win deterministically. Duplicate ZIP directory records can otherwise
    // point at different occurrences on different builds, even with identical payloads.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    mergeServiceFiles()
    append("META-INF/grounds/permissions.index")
    val appendedResources =
        listOf(
            "META-INF/grounds/permissions.index",
            "META-INF/LICENSE",
            "META-INF/NOTICE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE.txt",
        )
    appendedResources.drop(1).forEach { append(it) }
    filesMatching(listOf("META-INF/services/**") + appendedResources) {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
}

repositories {
    mavenCentral()
    // Minestom for Minecraft 26.3: upstream has not released 26.3 yet, so the build comes from
    // our fork's GitHub Packages. Named explicitly, like the other repositories here.
    maven {
        url = uri("https://maven.pkg.github.com/groundsgg/minestom")
        credentials {
            username = providers.gradleProperty("github.user").get()
            password = providers.gradleProperty("github.token").get()
        }
        content { includeGroup("net.minestom") }
    }
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
    testImplementation(kotlin("test"))
    implementation("io.micrometer:micrometer-core:1.16.6")
    implementation("gg.grounds:service-maps-lod-runtime:1.16.0")
    implementation(platform("gg.grounds:grounds-dependencies:1.0.0"))

    implementation("gg.grounds:grounds-minestom-runtime-runtime-core:0.13.0")
    implementation("gg.grounds:grounds-minestom-runtime-runtime-world:0.15.1")
    implementation("gg.grounds:scene-minestom:0.4.5")
    implementation("gg.grounds:scene-interaction:0.4.5")
    implementation("gg.grounds:resourcepacks-catalog:1.3.0")
    implementation("gg.grounds:resourcepacks-npc-runtime:1.3.0")
    implementation("gg.grounds:plugin-lobby-scene-catalog:2.23.0")
    // Minecraft 26.3. Overrides the 26.2 Minestom in grounds-dependencies 1.0.0 until that
    // platform carries an upstream 26.3 release. Keep in step with grounds-vanilla's 26.3 line,
    // which is built on this exact version.
    implementation("net.minestom:minestom:2026.11.08-26.3-grounds")
    implementation("gg.grounds:plugin-agones-minestom:3.2.0")
    implementation("io.nats:jnats")
    implementation("gg.grounds:plugin-permissions-minestom:1.6.1")
    implementation("gg.grounds:plugin-permissions-common:1.6.1")
    // What players do on this server: arrivals, deaths, chat volume, commands. A library, not a
    // provider — unlike the modules around it nothing discovers it, so AnalyticsModule calls it.
    implementation("gg.grounds:plugin-analytics-minestom:0.2.0")
    // Reaches the runtime through the SPI, like the two above, so there is no call site here —
    // but being on the classpath is not enough. Discovery only *lists* providers; a provider runs
    // only if LobbyServer names it in useProvider(), and for a long time this one was not named.
    // 0.2.0 is also the first version with the shared chat line, so a message looks the same
    // whether it crossed the proxy or was broadcast inside this lobby.
    // 2.6.0 applies sender permissions to chat formatting and emoji aliases.
    implementation("gg.grounds:plugin-chat-minestom:2.20.1")
    // Shared Mini-CD context and message-level colours for every loaded module.
    implementation("gg.grounds:library-i18n:0.4.4")
    implementation("gg.grounds:plugin-notifications-minestom:0.4.0")
    // Simple Voice Chat for players with the client mod. Selected in LobbyServer only when the
    // chart's voice block sets VOICE_HOST; without it the provider stays out.
    implementation("gg.grounds:plugin-voice-minestom:1.4.2")
    // The locked inventory and the slot-9 navigator. Selected unconditionally in
    // LobbyServer: it needs no backing service, and a lobby without it is a lobby a
    // player cannot leave except by disconnecting.
    //
    // Since 1.10.0 the navigator, compass and duel menu are declared rather than drawn,
    // so they render as a chest for a Java player and as a native form for a Bedrock one.
    // Before that a Bedrock player reached the lobby and found missing-glyph text where
    // the menu should be — which made this the one dependency a Bedrock rollout waits on.
    implementation("gg.grounds:plugin-lobby-minestom:2.23.0")
    // `/settings` (and a bare `/region`) on this lobby, the same menu every server shows.
    // Selected unconditionally in LobbyServer; since plugin-lobby 2.20.0 the lobby has no menu
    // of its own.
    implementation("gg.grounds:plugin-player-minestom:1.10.1")
    // Reads the map's map.json sidecar (the spawn). Minestom pulls gson in transitively;
    // declare it because we use it directly.
    implementation("com.google.code.gson:gson:2.13.2")
    // Fetching and unpacking the pinned bundle: the same tar.zst the build server produces.
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("com.github.luben:zstd-jni:1.5.7-6")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("gg.grounds:library-jvm-modules-module-core:0.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    runtimeOnly("org.slf4j:slf4j-simple")
}
