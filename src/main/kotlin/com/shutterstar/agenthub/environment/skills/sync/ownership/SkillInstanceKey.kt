package com.shutterstar.agenthub.environment.skills.sync.ownership

import com.shutterstar.agenthub.environment.skills.model.SkillScope
import java.io.File
import java.nio.file.Path
import java.util.Locale

/**
 * Mutation identity for a concrete skill context. Discovery's name-based `SkillIdentity` remains
 * useful for grouping, but is deliberately insufficient for persisted ownership and undo.
 */
data class SkillInstanceKey(
    val runtimeId: String,
    val scope: SkillScope,
    val contextPath: String,
    val skillId: String,
) {
    companion object {
        fun host(
            skillId: String,
            scope: SkillScope,
            canonicalPath: Path,
            projectRoot: Path? = null,
            runtimeId: String = "host",
        ): SkillInstanceKey {
            val context = when (scope) {
                SkillScope.GLOBAL -> canonicalPath.parent ?: canonicalPath
                SkillScope.PROJECT -> projectRoot ?: canonicalPath.parent ?: canonicalPath
            }
            return SkillInstanceKey(runtimeId, scope, normalize(context), skillId)
        }

        fun legacy(skillId: String): SkillInstanceKey =
            SkillInstanceKey("host", SkillScope.GLOBAL, "", skillId)

        /**
         * Re-applies [normalize] to a `contextPath` string that was decoded from persisted state.
         * Needed because a value round-tripped through IntelliJ's `@State` XML persistence gets
         * macro-collapsed (e.g. to `$USER_HOME$/...`) on save and macro-expanded back to the
         * platform's own (original-case, backslash) rendering of that macro on load — which does
         * not reproduce the lowercase/forward-slash form [normalize] produces. Without re-applying
         * it here, a [SkillInstanceKey] decoded after an IDE restart would never `==` a freshly
         * computed one for the same skill, silently emptying history/ownership lookups.
         */
        fun normalizeContextPath(raw: String): String = if (raw.isBlank()) raw else normalize(Path.of(raw))

        private fun normalize(path: Path): String {
            val normalized = path.toAbsolutePath().normalize().toString().replace('\\', '/')
            return if (File.separatorChar == '\\') normalized.lowercase(Locale.ROOT) else normalized
        }
    }
}
