import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.commonmark:commonmark:0.22.0")
    }
}

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.18.1"
    jacoco
}

fun markdownToHtml(markdown: String): String {
    val parser = Parser.builder().build()
    val renderer = HtmlRenderer.builder().build()
    return renderer.render(parser.parse(markdown))
}

group = "com.forret"
version = file("VERSION.md").readText().trim()

defaultTasks("build")

jacoco {
    toolVersion = "0.8.12"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // Allow Kotlin 2.1.x compiler to read newer platform metadata (e.g. IDEA compiled with Kotlin 2.3+)
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    // Use locally installed IDEA if available (no network download needed).
    // Falls back to downloading IC 2024.1 when building in CI or Docker
    intellijPlatform {
        val localIdea = file("${System.getProperty("user.home")}/AppData/Local/Programs/IntelliJ IDEA Ultimate")
        if (localIdea.exists()) {
            local(localIdea.absolutePath)
        } else {
            intellijIdeaCommunity("2024.1")
        }
        // Only require the built-in Terminal plugin so every JetBrains IDE with a terminal can load us.
        bundledPlugin("org.jetbrains.plugins.terminal")
    }

    // Use IntelliJ Platform's Kotlin stdlib; don't bundle our own
    compileOnly(kotlin("stdlib"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // 241 = 2024.1; the plugin uses only stable APIs available since 2024.1+.
            sinceBuild = "241"
            untilBuild = provider { null }
        }
        // description and change-notes are maintained in plugin.xml
    }
    buildSearchableOptions = false
    instrumentCode = false
}

tasks {
    // Ensure `./gradlew build` also produces the plugin ZIP
    named("build") {
        dependsOn("buildPlugin")
    }

    test {
        useJUnitPlatform()
        finalizedBy(jacocoTestReport)
    }

    jacocoTestReport {
        dependsOn(test)
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    processResources {
        filesMatching("agenthub-version.properties") {
            expand("version" to project.version)
        }
    }
}
