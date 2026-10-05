package com.shutterstar.agenthub.environment.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class OmpHomeSupportTest {
    private val systemHome: Path = Path.of(System.getProperty("user.home"))

    private fun env(vararg values: Pair<String, String>): (String) -> String? = values.toMap()::get

    @Test
    fun `default agent directory is dot omp agent`() {
        assertEquals(systemHome.resolve(".omp/agent"), OmpHomeSupport.agentDirectory(systemHome, env()))
    }

    @Test
    fun `PI_CONFIG_DIR renames the dot omp directory`() {
        assertEquals(systemHome.resolve(".ompx/agent"), OmpHomeSupport.agentDirectory(systemHome, env("PI_CONFIG_DIR" to ".ompx")))
    }

    @Test
    fun `PI_CODING_AGENT_DIR replaces the agent directory for the default profile only`() {
        val override = systemHome.resolve("custom-agent")
        assertEquals(override, OmpHomeSupport.agentDirectory(systemHome, env("PI_CODING_AGENT_DIR" to override.toString())))
        assertEquals(
            systemHome.resolve(".omp/profiles/work/agent"),
            OmpHomeSupport.agentDirectory(systemHome, env("PI_CODING_AGENT_DIR" to override.toString(), "OMP_PROFILE" to "work")),
        )
    }

    @Test
    fun `OMP_PROFILE wins over the legacy PI_PROFILE even when empty or default`() {
        assertEquals(systemHome.resolve(".omp/profiles/legacy/agent"), OmpHomeSupport.agentDirectory(systemHome, env("PI_PROFILE" to "legacy")))
        assertEquals(systemHome.resolve(".omp/agent"), OmpHomeSupport.agentDirectory(systemHome, env("OMP_PROFILE" to "", "PI_PROFILE" to "legacy")))
        assertEquals(systemHome.resolve(".omp/agent"), OmpHomeSupport.agentDirectory(systemHome, env("OMP_PROFILE" to "default")))
    }

    @Test
    fun `environment is ignored when the home directory is not the system home`() {
        val home = systemHome.resolve("isolated-test-home")

        assertEquals(
            home.resolve(".omp/agent"),
            OmpHomeSupport.agentDirectory(home, env("PI_CODING_AGENT_DIR" to "/elsewhere", "OMP_PROFILE" to "work", "PI_CONFIG_DIR" to ".x")),
        )
    }
}
