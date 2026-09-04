package com.shutterstar.agenthub.projects.ide

enum class JetBrainsIdeProduct(
    val displayName: String,
    val productCodes: Set<String>,
    val launcherNames: List<String>,
) {
    ANDROID_STUDIO("Android Studio", setOf("AI"), listOf("studio64.exe", "studio.exe", "studio.sh", "studio")),
    CLION("CLion", setOf("CL"), listOf("clion64.exe", "clion.exe", "clion.sh", "clion")),
    DATAGRIP("DataGrip", setOf("DB"), listOf("datagrip64.exe", "datagrip.exe", "datagrip.sh", "datagrip")),
    GOLAND("GoLand", setOf("GO"), listOf("goland64.exe", "goland.exe", "goland.sh", "goland")),
    INTELLIJ_IDEA("IntelliJ IDEA", setOf("IU", "IC"), listOf("idea64.exe", "idea.exe", "idea.sh", "idea")),
    PHPSTORM("PhpStorm", setOf("PS"), listOf("phpstorm64.exe", "phpstorm.exe", "phpstorm.sh", "phpstorm")),
    PYCHARM("PyCharm", setOf("PY", "PC"), listOf("pycharm64.exe", "pycharm.exe", "pycharm.sh", "pycharm")),
    RIDER("Rider", setOf("RD"), listOf("rider64.exe", "rider.exe", "rider.sh", "rider")),
    RUBYMINE("RubyMine", setOf("RM"), listOf("rubymine64.exe", "rubymine.exe", "rubymine.sh", "rubymine")),
    RUSTROVER("RustRover", setOf("RR"), listOf("rustrover64.exe", "rustrover.exe", "rustrover.sh", "rustrover")),
    WEBSTORM("WebStorm", setOf("WS"), listOf("webstorm64.exe", "webstorm.exe", "webstorm.sh", "webstorm")),
    ;

    companion object {
        fun fromProductCode(productCode: String?): JetBrainsIdeProduct? = entries.firstOrNull { product ->
            productCode?.uppercase() in product.productCodes
        }

        fun fromLauncherName(fileName: String?): JetBrainsIdeProduct? = entries.firstOrNull { product ->
            product.launcherNames.any { it.equals(fileName, ignoreCase = true) }
        }
    }
}
