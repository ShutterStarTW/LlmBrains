import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import proguard.gradle.ProGuardTask

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.commonmark:commonmark:0.22.0")
        // Plugin ZIP size (ProGuard phase 1): shrink-only,
        // no obfuscation. Only used by the standalone shrinkPluginJar/buildShrunkPlugin tasks below -
        // the everyday build/test/runIde path never depends on it.
        classpath("com.guardsquare:proguard-gradle:7.10.0")
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
    toolVersion = "0.8.14"
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
        // Plugin ZIP size: drop the null-check bytecode Kotlin injects for public parameters/receivers.
        // Safe here because AgentHub is an application (not a library consumed by other Kotlin modules
        // that rely on these checks for contract enforcement) - measured ~1.5-3% smaller .class files.
        freeCompilerArgs.addAll(
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions",
        )
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
    testRuntimeOnly(kotlin("stdlib"))
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
    named<JavaExec>("runIde") {
        systemProperty("agenthub.home", layout.buildDirectory.dir("agenthub-home").get().asFile.absolutePath)
    }

    // Ensure `./gradlew build` also produces the plugin ZIP
    named("build") {
        dependsOn("buildPlugin")
    }

    test {
        useJUnitPlatform()
        systemProperty("agenthub.test.mode", "true")
        // JetBrains Swing components use GraphicsUtil's reflective access in standalone UI tests.
        jvmArgs(
            "--add-opens=java.desktop/javax.swing=ALL-UNNAMED",
            "--add-opens=java.desktop/javax.swing.plaf.basic=ALL-UNNAMED",
        )
        finalizedBy(jacocoTestReport)
    }

    jacocoTestReport {
        dependsOn(test)
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    register<JavaExec>("runProjectDiscovery") {
        group = "verification"
        description = "Run the read-only project discovery smoke CLI"
        dependsOn("testClasses")
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("com.shutterstar.agenthub.projects.discovery.ProjectDiscoverySmokeCliKt")
        if (providers.gradleProperty("showPaths").orNull.equals("true", ignoreCase = true)) {
            args("--show-paths")
        }
    }

    register<JavaExec>("runMcpDiscovery") {
        group = "verification"
        description = "Run the read-only MCP discovery smoke CLI without displaying configuration values"
        dependsOn("testClasses")
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("com.shutterstar.agenthub.environment.mcp.discovery.McpDiscoverySmokeCliKt")
    }

    processResources {
        filesMatching("agenthub-version.properties") {
            expand("version" to project.version)
        }
    }
}

// --- Plugin ZIP size: ProGuard "Fázis 1" (shrink only, no obfuscation) -----------------
// Phase 1 only shrinks (about 15% smaller ZIP); full obfuscation was deliberately not adopted. Deliberately NOT wired into `build`/`test`/
// `runIde`/`buildPlugin` - the everyday dev loop stays on the unshrunk jar; these are opt-in
// tasks for evaluating/producing a smaller release artifact, run explicitly before a release
// together with the manual validation checklist from the proposal doc.
val shrinkPluginJar = tasks.register<ProGuardTask>("shrinkPluginJar") {
    group = "distribution"
    description = "Shrinks the composed plugin jar with ProGuard (dead code + Kotlin metadata removal, no obfuscation)."
    dependsOn("composedJar")

    injars(tasks.named("composedJar").map { it.outputs.files.singleFile })
    outjars(layout.buildDirectory.file("proguard/agenthub-${project.version}-shrunk.jar"))

    // Everything our code compiles against (IntelliJ Platform + bundled Terminal plugin +
    // Kotlin stdlib) is treated as "library" - present for reference, never shrunk/altered.
    libraryjars(sourceSets.main.get().compileClasspath)

    // JDK runtime classes (java.base + the modules our Swing UI / java.util.logging code
    // needs) - required so ProGuard can resolve java.lang/java.util/javax.swing/etc. references.
    val javaHome = System.getProperty("java.home")
    listOf("java.base", "java.desktop", "java.logging", "java.net.http", "java.datatransfer").forEach { module ->
        libraryjars(
            mapOf("jarfilter" to "!**.jar", "filter" to "!module-info.class"),
            "$javaHome/jmods/$module.jmod",
        )
    }

    configuration(file("proguard-rules.pro"))
}

val buildShrunkPlugin = tasks.register<Zip>("buildShrunkPlugin") {
    group = "distribution"
    description = "Packages the ProGuard-shrunk jar into a plugin ZIP for size comparison (not a release artifact)."
    dependsOn(shrinkPluginJar)
    archiveFileName.set("agenthub-${project.version}-shrunk.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(shrinkPluginJar.map { it.outputs.files.singleFile }) {
        into("agenthub/lib")
        rename { "agenthub-${project.version}.jar" }
    }
}
// --- end ProGuard "Fázis 1" ---------------------------------------------------------------
