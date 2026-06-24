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
    id("org.jetbrains.intellij") version "1.17.3"
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
}

intellij {
    // Use locally installed IDEA if available (no network download needed).
    // Falls back to downloading IC 2024.1 when building in CI or Docker
    val localIdea = file("${System.getProperty("user.home")}/AppData/Local/Programs/IntelliJ IDEA Ultimate")
    if (localIdea.exists()) {
        localPath.set(localIdea.absolutePath)
    } else {
        version.set("2024.1")
        type.set("IC")
    }
    // Only require the built-in Terminal plugin so every JetBrains IDE with a terminal can load us.
    plugins.set(listOf("org.jetbrains.plugins.terminal"))
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // Allow Kotlin 2.1.x compiler to read newer platform metadata (e.g. IDEA compiled with Kotlin 2.3+)
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    // Use IntelliJ Platform's Kotlin stdlib; don't bundle our own
    compileOnly(kotlin("stdlib"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    patchPluginXml {
        // 241 = 2024.1; the plugin uses only stable APIs available since 2024.1+.
        sinceBuild.set("241")
        untilBuild.set("")
        // description and change-notes are maintained in plugin.xml
    }

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

    named("instrumentCode") {
        enabled = false
    }

    named("buildSearchableOptions") {
        enabled = false
    }

    processResources {
        filesMatching("agenthub-version.properties") {
            expand("version" to project.version)
        }
    }
}
