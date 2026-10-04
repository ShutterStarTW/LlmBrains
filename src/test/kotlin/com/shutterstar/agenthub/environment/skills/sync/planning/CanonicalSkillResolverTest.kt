package com.shutterstar.agenthub.environment.skills.sync.planning

import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillIdentity
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.model.SkillSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CanonicalSkillResolverTest {
    @TempDir
    lateinit var root: Path

    private val resolver = CanonicalSkillResolver()

    @Test
    fun `resolves canonical path and fingerprint from a shared source`() {
        val sharedDir = root.resolve("shared").also(Files::createDirectories)
        Files.writeString(sharedDir.resolve("SKILL.md"), "content")

        val skill = AgentSkill(
            identity = SkillIdentity("id"),
            name = "review",
            description = null,
            scope = SkillScope.GLOBAL,
            sources = listOf(
                SkillSource(agentId = "claude", path = root.resolve("claude").toString(), scope = SkillScope.GLOBAL, shared = false, fingerprint = "fixture"),
                SkillSource(agentId = null, path = sharedDir.toString(), scope = SkillScope.GLOBAL, shared = true, fingerprint = "fixture"),
            ),
            compatibleAgents = emptySet(),
            consistency = SkillConsistency.IDENTICAL,
        )

        val resolved = resolver.resolve(skill)

        assertNotNull(resolved)
        assertEquals(sharedDir, resolved!!.canonicalPath)
        assertNotNull(resolved.fingerprint)
    }

    @Test
    fun `returns null when no source is shared`() {
        val skill = AgentSkill(
            identity = SkillIdentity("id"),
            name = "review",
            description = null,
            scope = SkillScope.GLOBAL,
            sources = listOf(
                SkillSource(agentId = "claude", path = root.resolve("claude").toString(), scope = SkillScope.GLOBAL, shared = false, fingerprint = "fixture"),
            ),
            compatibleAgents = emptySet(),
            consistency = SkillConsistency.SINGLE_SOURCE,
        )

        assertNull(resolver.resolve(skill))
    }
}
