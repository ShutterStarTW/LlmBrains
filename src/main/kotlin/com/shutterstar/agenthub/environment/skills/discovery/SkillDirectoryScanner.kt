package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.environment.discovery.PROJECT_WALK_EXCLUDED_DIRECTORY_NAMES
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal class SkillDirectoryScanner(
    private val fingerprint: SkillFingerprint = SkillFingerprint(),
    private val maximumScanEntries: Int = MAXIMUM_SCAN_ENTRIES,
) {
    fun discover(
        root: Path,
        agentId: String?,
        scope: SkillScope,
        shared: Boolean,
        projectName: String?,
        requireValidMetadata: Boolean = false,
    ): List<SkillSourceRecord> = discover(
        root,
        agentId,
        scope,
        shared,
        projectName,
        requireValidMetadata,
        newBudget(),
    )

    fun discover(
        root: Path,
        agentId: String?,
        scope: SkillScope,
        shared: Boolean,
        projectName: String?,
        requireValidMetadata: Boolean,
        budget: ScanBudget,
    ): List<SkillSourceRecord> {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return emptyList()
        }

        val skillDirectories = mutableListOf<Path>()
        runCatching {
            Files.walkFileTree(
                root,
                emptySet(),
                MAXIMUM_SCAN_DEPTH,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        directory: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult {
                        if (!budget.consume()) return FileVisitResult.TERMINATE
                        if (directory != root && directory.fileName.toString().startsWith('.')) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        if (Files.isRegularFile(directory.resolve(SKILL_FILE_NAME), LinkOption.NOFOLLOW_LINKS)) {
                            skillDirectories.add(directory)
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult {
                        if (!budget.consume()) return FileVisitResult.TERMINATE
                        if (
                            attributes.isSymbolicLink &&
                            Files.isDirectory(file) &&
                            Files.isRegularFile(file.resolve(SKILL_FILE_NAME), LinkOption.NOFOLLOW_LINKS)
                        ) {
                            skillDirectories.add(file)
                        }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }.getOrElse { return emptyList() }

        return skillDirectories
            .sortedBy { it.toString().lowercase() }
            .mapNotNull { skillDirectory ->
                readSkill(skillDirectory, agentId, scope, shared, projectName, requireValidMetadata)
            }
    }

    fun discoverNestedProjectSkills(
        projectRoot: Path,
        ownerDirectoryName: String,
        agentId: String?,
        shared: Boolean,
        projectName: String,
        requireValidMetadata: Boolean = false,
        budget: ScanBudget = newBudget(),
    ): List<SkillSourceRecord> {
        if (!Files.isDirectory(projectRoot, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val records = mutableListOf<SkillSourceRecord>()
        runCatching {
            Files.walkFileTree(
                projectRoot,
                emptySet(),
                MAXIMUM_SCAN_DEPTH,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        directory: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult {
                        if (!budget.consume()) return FileVisitResult.TERMINATE
                        if (
                            directory != projectRoot &&
                            directory.fileName.toString().lowercase() in EXCLUDED_PROJECT_DIRECTORIES
                        ) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        if (
                            directory != projectRoot &&
                            directory.fileName.toString().startsWith('.') &&
                            !directory.fileName.toString().equals(ownerDirectoryName, ignoreCase = true)
                        ) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        if (
                            directory.fileName?.toString().equals(SKILLS_DIRECTORY, ignoreCase = true) &&
                            directory.parent?.fileName?.toString().equals(ownerDirectoryName, ignoreCase = true)
                        ) {
                            records += discover(
                                root = directory,
                                agentId = agentId,
                                scope = SkillScope.PROJECT,
                                shared = shared,
                                projectName = projectName,
                                requireValidMetadata = requireValidMetadata,
                                budget = budget,
                            )
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult =
                        if (!budget.consume()) {
                            FileVisitResult.TERMINATE
                        } else {
                            FileVisitResult.CONTINUE
                        }
                },
            )
        }.getOrElse { return emptyList() }

        return records
            .distinctBy { it.path }
            .sortedBy { it.path.lowercase() }
    }

    fun newBudget(): ScanBudget = ScanBudget(maximumScanEntries)

    private fun readSkill(
        skillDirectory: Path,
        agentId: String?,
        scope: SkillScope,
        shared: Boolean,
        projectName: String?,
        requireValidMetadata: Boolean,
    ): SkillSourceRecord? {
        val skillFile = skillDirectory.resolve(SKILL_FILE_NAME)
        if (!Files.isRegularFile(skillFile, LinkOption.NOFOLLOW_LINKS)) {
            return null
        }

        val contentFingerprint = fingerprint.calculate(skillFile) ?: return null
        val metadata = readMetadata(skillFile)
        val directoryName = skillDirectory.fileName?.toString()?.trim().orEmpty()
        if (requireValidMetadata && !isValidMetadata(metadata, directoryName)) return null
        val skillName = metadata.name ?: directoryName
        if (skillName.isBlank()) {
            return null
        }

        return SkillSourceRecord(
            name = skillName,
            path = skillDirectory.toAbsolutePath().normalize().toString(),
            description = metadata.description,
            agentId = agentId,
            scope = scope,
            shared = shared,
            fingerprint = contentFingerprint,
            displayTitle = metadata.displayTitle,
            projectName = projectName,
        )
    }

    private fun isValidMetadata(metadata: SkillMetadata, directoryName: String): Boolean =
        metadata.name == directoryName &&
            metadata.name.matches(SKILL_NAME_PATTERN) &&
            !metadata.description.isNullOrBlank()

    private fun readMetadata(skillFile: Path): SkillMetadata = runCatching {
        Files.newInputStream(skillFile).use { input ->
            val bytes = input.readNBytes(MAXIMUM_METADATA_BYTES)
            SkillMetadataParser.parse(String(bytes, StandardCharsets.UTF_8))
        }
    }.getOrDefault(SkillMetadata(name = null, description = null))

    private companion object {
        const val SKILL_FILE_NAME = "SKILL.md"
        const val SKILLS_DIRECTORY = "skills"
        const val MAXIMUM_METADATA_BYTES = 128 * 1024
        const val MAXIMUM_SCAN_DEPTH = 12
        const val MAXIMUM_SCAN_ENTRIES = 20_000
        val SKILL_NAME_PATTERN = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
        val EXCLUDED_PROJECT_DIRECTORIES = PROJECT_WALK_EXCLUDED_DIRECTORY_NAMES
    }

    class ScanBudget(
        private var remaining: Int = MAXIMUM_SCAN_ENTRIES,
    ) {
        fun consume(): Boolean {
            if (remaining <= 0) return false
            remaining--
            return true
        }
    }
}
