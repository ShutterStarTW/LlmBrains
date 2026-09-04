package com.shutterstar.agenthub.projects.ide

import java.nio.file.Path

data class JetBrainsIdeInstallation(
    val product: JetBrainsIdeProduct,
    val name: String,
    val home: Path,
    val launcher: Path,
    val isCurrent: Boolean,
) {
    val displayName: String
        get() = if (isCurrent) "$name (current)" else name
}
