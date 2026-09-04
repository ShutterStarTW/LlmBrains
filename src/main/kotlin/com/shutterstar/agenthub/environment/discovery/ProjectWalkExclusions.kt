package com.shutterstar.agenthub.environment.discovery

/**
 * Directory names (lowercase) skipped while walking a project tree for skills/instructions —
 * build output, VCS metadata, and dependency caches that are never a real source of either.
 */
internal val PROJECT_WALK_EXCLUDED_DIRECTORY_NAMES = setOf(
    ".git",
    ".gradle",
    ".idea",
    "build",
    "dist",
    "node_modules",
    "out",
    "target",
    "vendor",
)
