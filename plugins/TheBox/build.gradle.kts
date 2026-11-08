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
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.4.6") 
    
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
        modrinth("worldedit", "YFnqiUlL")
    }
}