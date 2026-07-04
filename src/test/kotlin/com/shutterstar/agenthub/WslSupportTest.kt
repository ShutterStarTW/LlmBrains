package com.shutterstar.agenthub

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WslSupportTest {

    @AfterEach
    fun resetSettings() {
        WslSupport.settings = WslSupport.Settings()
    }

    @Test
    fun `toWslPath converts drive paths to mnt form`() {
        assertEquals("/mnt/c/Users/x/AppData/tmp.txt", WslSupport.toWslPath("C:\\Users\\x\\AppData\\tmp.txt"))
        assertEquals("/mnt/k/IdeaProjects/LlmBrains", WslSupport.toWslPath("K:/IdeaProjects/LlmBrains"))
        assertEquals("/mnt/d/", WslSupport.toWslPath("D:\\"))
    }

    @Test
    fun `toWslPath leaves non-path arguments unchanged`() {
        assertEquals("claude,codex,aider", WslSupport.toWslPath("claude,codex,aider"))
        assertEquals("https://example.com/x", WslSupport.toWslPath("https://example.com/x"))
    }

    @Test
    fun `wrapArgv includes selected distro`() {
        WslSupport.settings = WslSupport.Settings(useWsl = true, distro = "Ubuntu-22.04")
        assertEquals(
            listOf("wsl.exe", "-d", "Ubuntu-22.04", "--exec", "bash", "-lic", "claude"),
            WslSupport.wrapArgv("claude"),
        )
    }

    @Test
    fun `wrapArgv omits -d for the default distro`() {
        WslSupport.settings = WslSupport.Settings(useWsl = true, distro = "")
        assertEquals(
            listOf("wsl.exe", "--exec", "bash", "-lic", "claude"),
            WslSupport.wrapArgv("claude"),
        )
    }

    @Test
    fun `wrapForTerminal quotes distro and escapes single quotes for PowerShell`() {
        WslSupport.settings = WslSupport.Settings(useWsl = true, distro = "Ubuntu")
        assertEquals(
            "wsl.exe -d 'Ubuntu' --exec bash -lic 'echo ''hi'''",
            WslSupport.wrapForTerminal("echo 'hi'"),
        )
    }

    @Test
    fun `wrapForTerminal without distro has no -d flag`() {
        WslSupport.settings = WslSupport.Settings(useWsl = true, distro = "")
        assertEquals("wsl.exe --exec bash -lic 'claude'", WslSupport.wrapForTerminal("claude"))
    }

    @Test
    fun `withToolchainGuard prefixes pip commands with pip3 fallback and apt hint`() {
        val guarded = WslSupport.withToolchainGuard("pip install mistral-vibe")
        assertTrue(guarded.endsWith(" pip install mistral-vibe"))
        assertTrue(guarded.startsWith("__lb_has() {"))
        assertTrue("pip() { pip3 \$@; }" in guarded)
        assertTrue("pip is not installed in this distro" in guarded)
        // Windows npm/pip reachable via /mnt interop gets its own explanation.
        assertTrue("pip here is the Windows one (WSL interop)" in guarded)
        assertTrue("sudo apt install python3-pip" in guarded)
    }

    @Test
    fun `guard and nativeCheck snippets contain no double quotes`() {
        // Java's ProcessBuilder escapes embedded `"` on the Windows command line and wsl.exe
        // does not unescape it — a double quote anywhere in these snippets reaches bash mangled
        // ("unexpected EOF") and every in-process WSL call silently fails.
        assertFalse('"' in WslSupport.nativeCheck("claude"))
        assertFalse('"' in WslSupport.withToolchainGuard("pip install x"))
        assertFalse('"' in WslSupport.withToolchainGuard("npm install -g x"))
    }

    @Test
    fun `nativeCheck rejects Windows interop resolutions under mnt`() {
        val check = WslSupport.nativeCheck("codex")
        assertTrue(check.endsWith(" __lb_has codex"))
        // The helper must treat both "not found" and /mnt/* (Windows interop PATH) as missing.
        assertTrue("''|/mnt/*) return 1" in check)
    }

    @Test
    fun `withToolchainGuard prefixes npm commands with apt hint`() {
        val guarded = WslSupport.withToolchainGuard("npm install --quiet --no-fund -g @openai/codex")
        assertTrue(guarded.endsWith(" npm install --quiet --no-fund -g @openai/codex"))
        assertTrue("npm is not installed in this distro" in guarded)
        assertTrue("npm here is the Windows one (WSL interop)" in guarded)
        assertTrue("sudo apt install nodejs npm" in guarded)
    }

    @Test
    fun `withToolchainGuard leaves other commands unchanged`() {
        assertEquals("claude", WslSupport.withToolchainGuard("claude"))
        assertEquals("command -v aider", WslSupport.withToolchainGuard("command -v aider"))
        // pipx / pip3 are not pip — token match must not fire on them.
        assertEquals("pipx upgrade aider-chat", WslSupport.withToolchainGuard("pipx upgrade aider-chat"))
        assertEquals(
            "curl -fsSL https://example.com/install.sh | bash",
            WslSupport.withToolchainGuard("curl -fsSL https://example.com/install.sh | bash"),
        )
    }

    @Test
    fun `wrapArgv and wrapForTerminal apply the toolchain guard`() {
        WslSupport.settings = WslSupport.Settings(useWsl = true, distro = "")
        val argv = WslSupport.wrapArgv("pip install mistral-vibe")
        assertTrue(argv.last().endsWith(" pip install mistral-vibe"))
        assertTrue("pip3" in argv.last())

        val terminal = WslSupport.wrapForTerminal("pip install mistral-vibe")
        // PowerShell escaping doubles the guard's single quotes.
        assertTrue("''[x] pip is not installed" in terminal)
        assertTrue(terminal.endsWith(" pip install mistral-vibe'"))
    }
}
