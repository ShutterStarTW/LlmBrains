package com.shutterstar.agenthub.projects.ide

import com.intellij.openapi.application.PathManager
import com.shutterstar.agenthub.OsDetector
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.name

class JetBrainsIdeDetector(
    private val environment: Map<String, String> = System.getenv(),
    private val userHome: Path = Path.of(System.getProperty("user.home")),
    private val currentHome: Path = Path.of(PathManager.getHomePath()),
    private val osType: OsDetector.OsType = OsDetector.currentOs,
) {
    fun currentInstallation(): JetBrainsIdeInstallation? = installationFromHome(currentHome, currentHome)

    fun detect(): List<JetBrainsIdeInstallation> = detectIn(
        roots = standardRoots(),
        pathEntries = environment["PATH"].orEmpty()
            .split(System.getProperty("path.separator"))
            .filter(String::isNotBlank)
            .mapNotNull { value -> runCatching { Path.of(value) }.getOrNull() },
        currentHome = currentHome,
    )

    internal fun detectIn(
        roots: List<Path>,
        pathEntries: List<Path> = emptyList(),
        currentHome: Path = this.currentHome,
    ): List<JetBrainsIdeInstallation> {
        val installations = buildList {
            installationFromHome(currentHome, currentHome)?.let(::add)
            roots.distinct().forEach { root ->
                findProductInfoFiles(root).forEach { productInfo ->
                    installationFromProductInfo(productInfo, currentHome)?.let(::add)
                }
            }
            pathEntries.forEach { binDirectory ->
                JetBrainsIdeProduct.entries.forEach { product ->
                    candidateLauncherNames(product).asSequence()
                        .map(binDirectory::resolve)
                        .firstOrNull(Files::isRegularFile)
                        ?.let { launcher ->
                            val home = binDirectory.parent ?: binDirectory
                            add(
                                JetBrainsIdeInstallation(
                                    product = product,
                                    name = product.displayName,
                                    home = home,
                                    launcher = launcher,
                                    isCurrent = samePath(home, currentHome),
                                ),
                            )
                        }
                }
            }
        }

        return installations
            .filter { Files.isRegularFile(it.launcher) }
            .groupBy(JetBrainsIdeInstallation::product)
            .values
            .map { sameProduct ->
                sameProduct.sortedWith(
                    compareByDescending<JetBrainsIdeInstallation> { it.isCurrent }
                        .thenBy { it.home.toString() },
                ).first()
            }
            .sortedWith(
                compareByDescending<JetBrainsIdeInstallation> { it.isCurrent }
                    .thenBy { it.name.lowercase(Locale.ENGLISH) },
            )
    }

    private fun standardRoots(): List<Path> = buildList {
        add(currentHome)
        when (osType) {
            OsDetector.OsType.WINDOWS -> {
                environment["LOCALAPPDATA"]?.let { localAppData ->
                    addAll(likelyProductDirectories(Path.of(localAppData, "Programs")))
                    add(Path.of(localAppData, "JetBrains", "Toolbox", "apps"))
                }
                environment["ProgramFiles"]?.let { add(Path.of(it, "JetBrains")) }
                environment["ProgramFiles(x86)"]?.let { add(Path.of(it, "JetBrains")) }
            }
            OsDetector.OsType.MAC -> {
                addAll(likelyProductDirectories(Path.of("/Applications")))
                addAll(likelyProductDirectories(userHome.resolve("Applications")))
                add(userHome.resolve("Library/Application Support/JetBrains/Toolbox/apps"))
            }
            OsDetector.OsType.LINUX -> {
                add(Path.of("/opt/jetbrains"))
                add(userHome.resolve(".local/share/JetBrains/Toolbox/apps"))
            }
            OsDetector.OsType.OTHER -> Unit
        }
    }

    private fun likelyProductDirectories(root: Path): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        return runCatching {
            Files.list(root).use { children ->
                children.filter { child ->
                    Files.isDirectory(child) && PRODUCT_DIRECTORY_MARKERS.any { marker ->
                        child.name.contains(marker, ignoreCase = true)
                    }
                }.toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun findProductInfoFiles(root: Path): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        val direct = root.resolve(PRODUCT_INFO_FILE)
        if (Files.isRegularFile(direct)) return listOf(direct)
        return runCatching {
            Files.find(root, MAX_SEARCH_DEPTH, { path, attributes ->
                attributes.isRegularFile && path.name == PRODUCT_INFO_FILE
            }).use { paths -> paths.limit(MAX_PRODUCT_INFOS.toLong()).toList() }
        }.getOrDefault(emptyList())
    }

    private fun installationFromHome(home: Path, currentHome: Path): JetBrainsIdeInstallation? {
        val productInfo = sequenceOf(
            home.resolve(PRODUCT_INFO_FILE),
            home.resolve("Resources").resolve(PRODUCT_INFO_FILE),
            home.resolve("Contents").resolve("Resources").resolve(PRODUCT_INFO_FILE),
        ).firstOrNull(Files::isRegularFile) ?: return null
        return installationFromProductInfo(productInfo, currentHome)
    }

    private fun installationFromProductInfo(
        productInfo: Path,
        currentHome: Path,
    ): JetBrainsIdeInstallation? {
        val json = runCatching {
            if (Files.size(productInfo) > MAX_PRODUCT_INFO_BYTES) return null
            Files.readString(productInfo)
        }.getOrNull() ?: return null
        val productCode = JSON_STRING_FIELD.find(json, "productCode") ?: return null
        val product = JetBrainsIdeProduct.fromProductCode(productCode) ?: return null
        val declaredName = JSON_STRING_FIELD.find(json, "name")?.takeIf(String::isNotBlank) ?: product.displayName
        val home = productHome(productInfo)
        val launcher = launcherCandidates(home, product).firstOrNull(Files::isRegularFile) ?: return null
        return JetBrainsIdeInstallation(
            product = product,
            name = declaredName,
            home = home,
            launcher = launcher,
            isCurrent = samePath(home, currentHome),
        )
    }

    private fun productHome(productInfo: Path): Path {
        val parent = productInfo.parent
        return if (parent?.fileName?.toString() == "Resources" && parent.parent?.fileName?.toString() == "Contents") {
            parent.parent
        } else {
            parent
        }
    }

    private fun launcherCandidates(home: Path, product: JetBrainsIdeProduct): Sequence<Path> = sequence {
        candidateLauncherNames(product).forEach { launcherName ->
            yield(home.resolve("bin").resolve(launcherName))
            yield(home.resolve("MacOS").resolve(launcherName))
        }
    }

    /**
     * Restricts [JetBrainsIdeProduct.launcherNames] to names that are actually launchable as a
     * native process on the current OS. On Windows this excludes the `.sh` and bare (no
     * extension) entries — those exist for Linux/macOS real installs, but on Windows the bare
     * name also matches JetBrains Toolbox's `Toolbox\scripts\<name>` shell-script shim, which
     * `ProcessBuilder` cannot execute directly (`CreateProcess error=193`, not a Win32 image).
     */
    private fun candidateLauncherNames(product: JetBrainsIdeProduct): List<String> = when (osType) {
        OsDetector.OsType.WINDOWS -> product.launcherNames.filter { it.endsWith(".exe", ignoreCase = true) }
        else -> product.launcherNames
    }

    private fun samePath(first: Path, second: Path): Boolean =
        first.toAbsolutePath().normalize() == second.toAbsolutePath().normalize()

    private object JSON_STRING_FIELD {
        fun find(json: String, field: String): String? {
            val expression = Regex("\"${Regex.escape(field)}\"\\s*:\\s*\"([^\"]+)\"")
            return expression.find(json)?.groupValues?.get(1)
        }
    }

    companion object {
        private const val PRODUCT_INFO_FILE = "product-info.json"
        private const val MAX_SEARCH_DEPTH = 6
        private const val MAX_PRODUCT_INFOS = 100
        private const val MAX_PRODUCT_INFO_BYTES = 2L * 1024 * 1024
        private val PRODUCT_DIRECTORY_MARKERS = listOf(
            "android studio",
            "clion",
            "datagrip",
            "goland",
            "intellij",
            "jetbrains",
            "phpstorm",
            "pycharm",
            "rider",
            "rubymine",
            "rustrover",
            "webstorm",
        )
    }
}
