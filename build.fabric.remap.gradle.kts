@file:Suppress("UnstableApiUsage")

import groovy.json.JsonSlurper
import java.text.SimpleDateFormat
import java.util.*

plugins {
    id("mod-plugin")
    id("maven-publish")
    id("net.fabricmc.fabric-loom-remap")
    id("com.replaymod.preprocess")
}

version = artifactVersion
group = modMavenGroup

repositories {
    mavenCentral()
    fun strictMaven(url: String, vararg groups: String) = exclusiveContent {
        forRepository { maven(url) }
        filter {
            groups.forEach {
                includeGroupAndSubgroups(it)
                includeGroupAndSubgroups("$it.*")
            }
        }
    }
    strictMaven("https://maven.fabricmc.net")
    strictMaven("https://maven.fallenbreath.me/releases")
    strictMaven("https://masa.dy.fi/maven/sakura-ryoko", "fi.dy.masa")
    strictMaven("https://www.cursemaven.com", "curse.maven")
    strictMaven("https://api.modrinth.com/maven", "maven.modrinth")

    if (mcVersionInt <= 12006) {
        strictMaven("https://maven.kyrptonaught.dev", "net.kyrptonaught")  // KyrptConfig依赖
    }

    strictMaven("https://maven.terraformersmc.com/releases", "com.terraformersmc")  // ModMenu
    strictMaven("https://maven.nucleoid.xyz", "eu.pb4") // ModMenu依赖TextPlaceholderAPI
    strictMaven("https://repo.maven.apache.org/maven2", "blue.endless", "io.github.juuxel") // Jankson / LibNinePatch
    strictMaven("https://staging.alexiil.uk/maven/", "io.github.cottonmc") // LibGui 依赖
    strictMaven("https://maven.shedaniel.me")  // Cloth API/Config 官方源
    strictMaven("https://jitpack.io")
}

fun masaDependency(mod: String): String {
    val artifact = propStrOrNull("${mod}_artifact")?.takeIf { it.isNotBlank() }
    return artifact?.let { "fi.dy.masa.$mod:$it:${prop(mod)}" }
        ?: "maven.modrinth:$mod:${prop(mod)}"
}

val malilibDependency = masaDependency("malilib")
val litematicaDependency = masaDependency("litematica")
val tweakerooDependency = masaDependency("tweakeroo")
val modMenuDependency = "maven.modrinth:modmenu:${prop("modmenu")}"

// https://github.com/FabricMC/fabric-loader/issues/783
configurations.all {
    resolutionStrategy {
        dependencySubstitution {
            substitute(module("com.terraformersmc:modmenu"))
                .using(module(modMenuDependency))
                .because("Use one Mod Menu coordinate when dependencies request the official Maven module")
        }
        force("net.fabricmc:fabric-loader:$fabricLoaderVersion")
        force(malilibDependency)
        force(litematicaDependency)
        force(tweakerooDependency)
        force(modMenuDependency)
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    modImplementation("com.belerweb:pinyin4j:${prop("pinyin_version")}")?.let { include(it) }

    modImplementation(modMenuDependency)

    modImplementation(malilibDependency)
    modImplementation(litematicaDependency)
    modImplementation(tweakerooDependency)

    // 快捷潜影盒
    if (mcVersionInt >= 12006) {
        val quickshulkerUrl = prop("quickshulker").toString()
        if (quickshulkerUrl.isNotEmpty()) {
            val quickshulkerFile = downloadDependencyMod(quickshulkerUrl)
            if (quickshulkerFile != null && quickshulkerFile.exists()) {
                modImplementation(files(quickshulkerFile))
            }
        }
        if (mcVersionInt == 12006) {  // 1.20.6 是 Haocen2004/quickshulker 分支, 所以还是使用之前老版本的依赖
            modImplementation("net.kyrptonaught:kyrptconfig:${prop("kyrptconfig")}")
        } else {
            modImplementation("me.fallenbreath:conditional-mixin-fabric:0.6.4")
        }
    } else {
        modImplementation("curse.maven:quick-shulker-362669:${prop("quick_shulker")}")
        modImplementation("net.kyrptonaught:kyrptconfig:${prop("kyrptconfig")}")
    }
}

loom {
    val commonVmArgs = listOf("-Dmixin.debug.export=true", "-Dmixin.debug.verbose=true", "-Dmixin.env.remapRefMap=true")
    var programArgs = listOf("--width", "1280", "--height", "720")
    val profileFile = file("../../profile.json")
    if (profileFile.exists()) {
        @Suppress("UNCHECKED_CAST")
        val profile = JsonSlurper().parseText(profileFile.readText()) as Map<String, List<String>>
        val username = profile["username"].toString()
        val uuid = profile["uuid"].toString()
        val xuid = profile["xuid"].toString()
        val accessToken = profile["accessToken"].toString()
        programArgs = programArgs + listOf(
            "--username", username,
            "--uuid", uuid,
            "--xuid", xuid,
            "--accessToken", accessToken,
            "--userType", "msa",
            "--versionType", "release"
        )
    } else {
        programArgs = programArgs + listOf("--username", "PrinterTest")
    }
    runs {
        named("client") {
            ideConfigGenerated(true)
            vmArgs(commonVmArgs)
            programArgs(programArgs)
            runDir = "../../run/client"
        }
    }
}

tasks {
    register<Copy>("buildAndCollect") {
        group = "build"
        val collectedJarDir = rootProject.layout.buildDirectory.dir("libs/$modVersion/${project.name}")
        from(remapJar.map { it.archiveFile })
        into(collectedJarDir)
        doFirst {
            delete(collectedJarDir)
        }
        dependsOn("build")
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = modId
            version = modVersion
        }
    }
    repositories {
        mavenLocal()
        maven {
            url = uri("$rootDir/publish")
        }
    }
}
