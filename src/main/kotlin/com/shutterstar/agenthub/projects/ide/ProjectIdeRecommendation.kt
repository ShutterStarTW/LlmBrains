package com.shutterstar.agenthub.projects.ide

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

object ProjectIdeRecommendation {
    /**
     * Dominates the tech-stack heuristics below: a [JetBrainsIdeProduct] actually referenced by
     * the project's `.idea` directory is direct evidence of prior use, not a guess.
     */
    private const val USED_IDE_BONUS = 500

    fun recommend(
        projectDirectory: Path,
        installations: List<JetBrainsIdeInstallation>,
    ): JetBrainsIdeInstallation? {
        if (installations.isEmpty()) return null
        val markers = ProjectMarkers.read(projectDirectory)
        return installations.maxWithOrNull(
            compareBy<JetBrainsIdeInstallation> { score(it.product, markers) }
                .thenBy { it.isCurrent },
        )
    }

    internal fun score(product: JetBrainsIdeProduct, markers: ProjectMarkers): Int {
        var score = if (product == JetBrainsIdeProduct.INTELLIJ_IDEA) 10 else 1
        if (product in markers.usedIdeHints) score += USED_IDE_BONUS
        if (markers.android) {
            score += when (product) {
                JetBrainsIdeProduct.ANDROID_STUDIO -> 260
                JetBrainsIdeProduct.INTELLIJ_IDEA -> 80
                else -> 0
            }
        }
        if (markers.php) score += capabilityScore(product, JetBrainsIdeProduct.PHPSTORM, 140, 45)
        if (markers.dotNet) score += capabilityScore(product, JetBrainsIdeProduct.RIDER, 140, 20)
        if (markers.go) score += capabilityScore(product, JetBrainsIdeProduct.GOLAND, 140, 40)
        if (markers.python) score += capabilityScore(product, JetBrainsIdeProduct.PYCHARM, 140, 40)
        if (markers.ruby) score += capabilityScore(product, JetBrainsIdeProduct.RUBYMINE, 140, 35)
        if (markers.rust) {
            score += when (product) {
                JetBrainsIdeProduct.RUSTROVER -> 140
                JetBrainsIdeProduct.CLION -> 60
                JetBrainsIdeProduct.INTELLIJ_IDEA -> 35
                else -> 0
            }
        }
        if (markers.cpp) score += capabilityScore(product, JetBrainsIdeProduct.CLION, 140, 25)
        if (markers.database) score += capabilityScore(product, JetBrainsIdeProduct.DATAGRIP, 120, 45)
        if (markers.jvm) {
            score += if (product == JetBrainsIdeProduct.INTELLIJ_IDEA) 120 else 0
        }
        if (markers.javascript) {
            score += when (product) {
                JetBrainsIdeProduct.WEBSTORM -> 120
                JetBrainsIdeProduct.PHPSTORM -> 55
                JetBrainsIdeProduct.INTELLIJ_IDEA -> 50
                else -> 0
            }
        }
        return score
    }

    private fun capabilityScore(
        product: JetBrainsIdeProduct,
        specialist: JetBrainsIdeProduct,
        specialistScore: Int,
        ideaScore: Int,
    ): Int = when (product) {
        specialist -> specialistScore
        JetBrainsIdeProduct.INTELLIJ_IDEA -> ideaScore
        else -> 0
    }

    internal data class ProjectMarkers(
        val android: Boolean = false,
        val cpp: Boolean = false,
        val database: Boolean = false,
        val dotNet: Boolean = false,
        val go: Boolean = false,
        val javascript: Boolean = false,
        val jvm: Boolean = false,
        val php: Boolean = false,
        val python: Boolean = false,
        val ruby: Boolean = false,
        val rust: Boolean = false,
        val usedIdeHints: Set<JetBrainsIdeProduct> = emptySet(),
    ) {
        companion object {
            fun read(root: Path): ProjectMarkers {
                if (!Files.isDirectory(root)) return ProjectMarkers()
                val names = runCatching {
                    Files.list(root).use { children -> children.map { it.name }.toList().toSet() }
                }.getOrDefault(emptySet())
                val extensions = runCatching {
                    Files.list(root).use { children -> children.map { it.extension.lowercase() }.toList().toSet() }
                }.getOrDefault(emptySet())
                fun has(vararg markers: String): Boolean = markers.any(names::contains)
                return ProjectMarkers(
                    android = has("AndroidManifest.xml") || Files.exists(root.resolve("app/src/main/AndroidManifest.xml")),
                    cpp = has("CMakeLists.txt", "Makefile") || extensions.any { it in setOf("c", "cc", "cpp", "h", "hpp") },
                    database = Files.exists(root.resolve(".idea/dataSources.xml")) || extensions.any { it in setOf("sql", "ddl") },
                    dotNet = extensions.any { it in setOf("sln", "csproj", "fsproj") },
                    go = has("go.mod", "go.work"),
                    javascript = has("package.json", "yarn.lock", "pnpm-lock.yaml", "bun.lockb"),
                    jvm = has("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"),
                    php = has("composer.json") || "php" in extensions,
                    python = has("pyproject.toml", "requirements.txt", "Pipfile", "setup.py", "tox.ini"),
                    ruby = has("Gemfile", "Rakefile") || "gemspec" in extensions,
                    rust = has("Cargo.toml"),
                    usedIdeHints = UsedIdeDetector.detect(root),
                )
            }
        }
    }

    /**
     * Reads the project's `.idea` directory for direct evidence of which JetBrains IDE was used
     * to create/open it before — module types in `.iml` files and IDE-specific config files are
     * a much stronger signal than guessing from the tech stack alone.
     */
    internal object UsedIdeDetector {
        private const val MAX_FILE_BYTES = 512L * 1024
        private val MODULE_TYPE_REGEX = Regex("module\\s+type=\"([^\"]+)\"")

        private val MODULE_TYPE_PRODUCTS = mapOf(
            "PYTHON_MODULE" to JetBrainsIdeProduct.PYCHARM,
            "RUBY_MODULE" to JetBrainsIdeProduct.RUBYMINE,
            "JAVA_MODULE" to JetBrainsIdeProduct.INTELLIJ_IDEA,
        )

        // WEB_MODULE is deliberately excluded: both WebStorm and PhpStorm emit it, so it is
        // disambiguated below via the PhpStorm/WebStorm-specific signature files instead.
        private val SIGNATURE_FILE_PRODUCTS = mapOf(
            "php.xml" to JetBrainsIdeProduct.PHPSTORM,
            "php-test-framework.xml" to JetBrainsIdeProduct.PHPSTORM,
            "jsLibraryMappings.xml" to JetBrainsIdeProduct.WEBSTORM,
            "watcherTasks.xml" to JetBrainsIdeProduct.WEBSTORM,
            "cmake.xml" to JetBrainsIdeProduct.CLION,
            "go.xml" to JetBrainsIdeProduct.GOLAND,
            "uiDesigner.xml" to JetBrainsIdeProduct.INTELLIJ_IDEA,
            "sqldialects.xml" to JetBrainsIdeProduct.DATAGRIP,
        )

        fun detect(root: Path): Set<JetBrainsIdeProduct> {
            val ideaDir = root.resolve(".idea")
            if (!Files.isDirectory(ideaDir)) return emptySet()

            val entries = runCatching {
                Files.list(ideaDir).use { children -> children.toList() }
            }.getOrDefault(emptyList())
            val entryNames = entries.map { it.name }.toSet()

            val hints = mutableSetOf<JetBrainsIdeProduct>()

            SIGNATURE_FILE_PRODUCTS.forEach { (fileName, product) ->
                if (fileName in entryNames) hints += product
            }

            if ("riderModule.iml" in entryNames || entryNames.any { it.startsWith(".idea.") && it.endsWith(".dir") }) {
                hints += JetBrainsIdeProduct.RIDER
            }

            entries.filter { it.extension.equals("iml", ignoreCase = true) }.forEach { iml ->
                val moduleType = MODULE_TYPE_REGEX.find(readSmallFile(iml).orEmpty())?.groupValues?.get(1)
                MODULE_TYPE_PRODUCTS[moduleType]?.let { hints += it }
            }

            val gradleXmlLooksAndroid = readSmallFile(ideaDir.resolve("gradle.xml"))
                ?.contains("android", ignoreCase = true) == true
            val hasAndroidRootFiles = Files.exists(root.resolve("local.properties")) &&
                Files.exists(root.resolve("build.gradle"))
            if (gradleXmlLooksAndroid || hasAndroidRootFiles) hints += JetBrainsIdeProduct.ANDROID_STUDIO

            return hints
        }

        private fun readSmallFile(path: Path): String? = runCatching {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_FILE_BYTES) return null
            Files.readString(path)
        }.getOrNull()
    }
}
