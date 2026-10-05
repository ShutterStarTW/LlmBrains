package com.shutterstar.agenthub.environment.discovery

import java.nio.file.Path

/**
 * MiMo Code's base directories (source `packages/shared/src/global.ts`, `resolveMimocodeHome`): with `MIMOCODE_HOME` set
 * the four directories are `<MIMOCODE_HOME>/{data,cache,config,state}`; otherwise the XDG defaults
 * `$XDG_DATA_HOME/mimocode` (`~/.local/share/mimocode`) and `$XDG_CONFIG_HOME/mimocode` (`~/.config/mimocode`), on
 * Windows too. The environment is honoured only while the home directory is the system home (test isolation).
 */
internal object MimoHomeSupport {
    private const val APP = "mimocode"
    private const val HOME_ENV = "MIMOCODE_HOME"

    fun dataDirectory(homeDirectory: Path): Path =
        EnvHomeDirectorySupport.configuredDirectoryGuarded(HOME_ENV, homeDirectory)?.resolve("data")
            ?: EnvHomeDirectorySupport.resolveXdgGuarded("XDG_DATA_HOME", homeDirectory, ".local/share", APP)

    fun configDirectory(homeDirectory: Path): Path =
        EnvHomeDirectorySupport.configuredDirectoryGuarded(HOME_ENV, homeDirectory)?.resolve("config")
            ?: EnvHomeDirectorySupport.resolveXdgGuarded("XDG_CONFIG_HOME", homeDirectory, ".config", APP)
}
