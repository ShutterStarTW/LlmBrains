package com.shutterstar.agenthub.projects.ide

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ProjectIdeRecommendationTest {
    @TempDir
    lateinit var projectDirectory: Path

    @Test
    fun `should recommend PhpStorm for a Composer project`() {
        Files.createFile(projectDirectory.resolve("composer.json"))

        assertEquals(
            JetBrainsIdeProduct.PHPSTORM,
            recommend(JetBrainsIdeProduct.INTELLIJ_IDEA, JetBrainsIdeProduct.PHPSTORM)?.product,
        )
    }

    @Test
    fun `should recommend IntelliJ IDEA for a Gradle project`() {
        Files.createFile(projectDirectory.resolve("build.gradle.kts"))

        assertEquals(
            JetBrainsIdeProduct.INTELLIJ_IDEA,
            recommend(JetBrainsIdeProduct.WEBSTORM, JetBrainsIdeProduct.INTELLIJ_IDEA)?.product,
        )
    }

    @Test
    fun `should prefer Android Studio when an Android manifest exists`() {
        Files.createDirectories(projectDirectory.resolve("app/src/main"))
        Files.createFile(projectDirectory.resolve("app/src/main/AndroidManifest.xml"))
        Files.createFile(projectDirectory.resolve("settings.gradle.kts"))

        assertEquals(
            JetBrainsIdeProduct.ANDROID_STUDIO,
            recommend(JetBrainsIdeProduct.INTELLIJ_IDEA, JetBrainsIdeProduct.ANDROID_STUDIO)?.product,
        )
    }

    @Test
    fun `should prefer PhpStorm when idea directory has a php signature file, even for a JS-looking stack`() {
        Files.createFile(projectDirectory.resolve("package.json"))
        Files.createDirectory(projectDirectory.resolve(".idea"))
        Files.createFile(projectDirectory.resolve(".idea/php.xml"))

        assertEquals(
            JetBrainsIdeProduct.PHPSTORM,
            recommend(JetBrainsIdeProduct.WEBSTORM, JetBrainsIdeProduct.PHPSTORM)?.product,
        )
    }

    @Test
    fun `should prefer PyCharm when an iml file declares a PYTHON_MODULE`() {
        Files.createDirectory(projectDirectory.resolve(".idea"))
        Files.writeString(
            projectDirectory.resolve(".idea/project.iml"),
            """<module type="PYTHON_MODULE" version="4" />""",
        )

        assertEquals(
            JetBrainsIdeProduct.PYCHARM,
            recommend(JetBrainsIdeProduct.INTELLIJ_IDEA, JetBrainsIdeProduct.PYCHARM)?.product,
        )
    }

    @Test
    fun `should prefer Rider when idea directory has a rider cache folder`() {
        Files.createDirectory(projectDirectory.resolve(".idea"))
        Files.createDirectory(projectDirectory.resolve(".idea/.idea.MyProject.dir"))

        assertEquals(
            JetBrainsIdeProduct.RIDER,
            recommend(JetBrainsIdeProduct.INTELLIJ_IDEA, JetBrainsIdeProduct.RIDER)?.product,
        )
    }

    @Test
    fun `should fall back to the current IDE for an unknown project`() {
        val result = ProjectIdeRecommendation.recommend(
            projectDirectory,
            listOf(
                installation(JetBrainsIdeProduct.WEBSTORM),
                installation(JetBrainsIdeProduct.PHPSTORM, isCurrent = true),
            ),
        )

        assertEquals(JetBrainsIdeProduct.PHPSTORM, result?.product)
    }

    private fun recommend(vararg products: JetBrainsIdeProduct): JetBrainsIdeInstallation? =
        ProjectIdeRecommendation.recommend(projectDirectory, products.map(::installation))

    private fun installation(
        product: JetBrainsIdeProduct,
        isCurrent: Boolean = false,
    ) = JetBrainsIdeInstallation(
        product = product,
        name = product.displayName,
        home = projectDirectory.resolve(product.name),
        launcher = projectDirectory.resolve(product.launcherNames.first()),
        isCurrent = isCurrent,
    )
}
