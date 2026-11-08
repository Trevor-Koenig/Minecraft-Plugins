plugins {
    java
    
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "tech.shulker.thebox"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.4.6")
    // Vault's economy interface. Not transitive: VaultAPI's pom drags in an ancient Bukkit.
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
    // LuckPerms API (from Maven Central): the rank ladder is a LuckPerms track.
    compileOnly("net.luckperms:api:5.5")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.runServer {
    minecraftVersion("26.3")
    downloadPlugins {
        modrinth("worldedit", "YFnqiUlL")       // WorldEdit 7.4.6 (Bukkit/Paper build)
        modrinth("vaultunlocked", "qZgRzoYs")   // VaultUnlocked 2.20.3, registers as "Vault"
        modrinth("luckperms", "b0mk8uS6")       // LuckPerms 5.5.71-bukkit (Bukkit/Paper build)
        modrinth("playerparticles", "pNqks5Iv") // PlayerParticles 8.13
        // EssentialsX dev build #1833 (2.22.1-dev): its last release predates 26.3 support
        url("https://ci.ender.zone/job/EssentialsX/1833/artifact/jars/EssentialsX-2.22.1-dev%2B28-f8724e2.jar")
    }
}