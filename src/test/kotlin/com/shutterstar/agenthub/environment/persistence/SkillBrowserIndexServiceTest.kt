package com.shutterstar.agenthub.environment.persistence

import com.intellij.util.xmlb.XmlSerializer
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillIdentity
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.model.SkillSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SkillBrowserIndexServiceTest {
    @Test
    fun `global skills survive XML state reload`() {
        val skill = AgentSkill(
            identity = SkillIdentity("global-skill"),
            name = "Global skill",
            description = "Cached description",
            scope = SkillScope.GLOBAL,
            sources = emptyList(),
            compatibleAgents = setOf("claude"),
            consistency = SkillConsistency.SINGLE_SOURCE,
        )
        val recorded = SkillBrowserIndexService()
        recorded.record("host:GLOBAL:", listOf(skill))
        val xml = XmlSerializer.serialize(recorded.state)
        val reloaded = SkillBrowserIndexService()
        reloaded.loadState(XmlSerializer.deserialize(xml, EnvironmentIndexState::class.java))

        assertEquals(listOf(skill), reloaded.cachedSkills("host:GLOBAL:"))
        assertEquals(emptyList<AgentSkill>(), reloaded.cachedSkills("host:PROJECT:elsewhere"))
    }

    @Test
    fun `system flag, real path and link paths survive XML state reload`() {
        val source = SkillSource("claude", "/h/.claude/skills/synced/x/pdf", SkillScope.GLOBAL, false, "fp", "PDF", null, true, "/real/pdf")
        val skill = AgentSkill(SkillIdentity("pdf"), "pdf", null, SkillScope.GLOBAL, listOf(source), setOf("claude"), SkillConsistency.SINGLE_SOURCE)
        val recorded = SkillBrowserIndexService()
        recorded.record("host:GLOBAL:", listOf(skill), setOf("/h/.codex/skills/pdf"))
        val reloaded = SkillBrowserIndexService()
        reloaded.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(recorded.state), EnvironmentIndexState::class.java))

        assertEquals(listOf(skill), reloaded.cachedSkills("host:GLOBAL:"))
        assertEquals(setOf("/h/.codex/skills/pdf"), reloaded.cachedLinkPaths("host:GLOBAL:"))
    }
}
