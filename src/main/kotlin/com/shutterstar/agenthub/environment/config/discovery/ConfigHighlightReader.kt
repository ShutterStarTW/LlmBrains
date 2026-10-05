package com.shutterstar.agenthub.environment.config.discovery

import com.shutterstar.agenthub.environment.config.model.ConfigFormat
import com.shutterstar.agenthub.environment.config.model.ConfigHighlight
import com.shutterstar.agenthub.environment.config.model.ConfigRisk
import com.shutterstar.agenthub.environment.mcp.discovery.CodexMcpConfigParser
import com.shutterstar.agenthub.environment.mcp.discovery.JsonBoolean
import com.shutterstar.agenthub.environment.mcp.discovery.JsonArray
import com.shutterstar.agenthub.environment.mcp.discovery.JsonObject
import com.shutterstar.agenthub.environment.mcp.discovery.JsonString
import com.shutterstar.agenthub.environment.mcp.discovery.JsonValue
import com.shutterstar.agenthub.environment.mcp.discovery.SafeJsonParser

/** A closed schema at discovery, persistence and UI boundaries. Never serializes raw values. */
internal object ConfigHighlightReader {
    private data class Field(
        val key: String,
        val label: String,
        val values: Set<String> = emptySet(),
        val risks: Set<String> = emptySet(),
        val count: Boolean = false,
        val model: Boolean = false,
        val boolean: Boolean = false,
    )

    private val model = Field("model", "Model", model = true)
    private val hooks = Field("hooks", "Hook groups", count = true)
    private val plugins = Field("enabledPlugins", "Plugins", count = true)
    private val codex = listOf(
        model,
        Field("approval_policy", "Approval policy", setOf("untrusted", "on-failure", "on-request", "never"), setOf("never")),
        Field("sandbox_mode", "Sandbox mode", setOf("read-only", "workspace-write", "danger-full-access"), setOf("danger-full-access")),
    )
    private val fields = mapOf(
        "claude" to listOf(model, hooks, plugins,
            Field("permissions.defaultMode", "Permission mode", setOf("default", "acceptEdits", "plan", "dontAsk", "bypassPermissions", "auto"), setOf("bypassPermissions"))),
        "codex" to codex,
        "grok" to listOf(model,
            Field("ui.permission_mode", "Permission mode", setOf("default", "ask", "auto", "always-approve", "yolo"), setOf("always-approve", "yolo")),
            Field("ui.approval_mode", "Approval mode", setOf("ask", "auto", "yolo"), setOf("yolo")),
            Field("sandbox.profile", "Sandbox profile", setOf("off", "workspace", "read-only", "strict"), setOf("off"))),
        "qwen" to listOf(model,
            Field("model.name", "Model", model = true), hooks,
            Field("tools.approvalMode", "Approval mode", setOf("default", "plan", "auto-edit", "yolo", "auto"), setOf("yolo"))),
        "kiro" to listOf(Field("chat.defaultModel", "Model", model = true)),
        "cursor" to listOf(Field("sandbox.mode", "Sandbox mode", setOf("enabled", "disabled", "workspace-write", "read-only"), setOf("disabled")),
            Field("permissions.allow", "Allowed rules", count = true),
            Field("permissions.deny", "Denied rules", count = true)),
        "copilot" to listOf(model, hooks, plugins),
        "cline" to listOf(Field("actModeApiModelId", "Act model", model = true),
            Field("planModeApiModelId", "Plan model", model = true)),
        "mimo" to listOf(model,
            Field("small_model", "Small model", model = true),
            Field("plugin", "Plugins", count = true),
            Field("permission", "Permission mode", setOf("ask", "allow", "deny"), setOf("allow"))),
        "kilo" to listOf(model,
            Field("small_model", "Small model", model = true),
            Field("plugin", "Plugins", count = true),
            Field("permission", "Permission mode", setOf("ask", "allow", "deny"), setOf("allow"))),
        "opencode" to listOf(model,
            Field("small_model", "Small model", model = true),
            Field("plugin", "Plugins", count = true),
            Field("permission", "Permission mode", setOf("ask", "allow", "deny"), setOf("allow"))),
        "antigravity" to listOf(Field("enableTerminalSandbox", "Terminal sandbox", setOf("true", "false"), setOf("false"), boolean = true), hooks,
            Field("toolPermission", "Tool permission", setOf("request-review", "proceed-in-sandbox", "always-proceed", "strict"), setOf("always-proceed"))),
    )

    fun read(agentId: String, format: ConfigFormat, content: String, jsonc: Boolean = false): List<ConfigHighlight> = runCatching {
        val allowed = fields[agentId].orEmpty()
        when (format) {
            ConfigFormat.JSON -> {
                val root = (if (jsonc || agentId == "copilot") SafeJsonParser.parseJsonc(content) else SafeJsonParser.parse(content)) as? JsonObject
                    ?: return emptyList()
                allowed.mapNotNull { field ->
                    val value = lookup(root, field.key) ?: return@mapNotNull null
                    if (field.count) {
                        val count = when (value) {
                            is JsonObject -> value.fields.size
                            is JsonArray -> value.values.size
                            else -> return@mapNotNull null
                        }
                        highlight(field, count.toString())
                    } else if (field.boolean) {
                        (value as? JsonBoolean)?.value?.toString()?.let { highlight(field, it) }
                    } else (value as? JsonString)?.value?.let { highlight(field, it) }
                }
            }
            ConfigFormat.TOML -> {
                val values = CodexMcpConfigParser.parseValues(content)
                allowed.filterNot { it.count }.mapNotNull { field ->
                    values[field.key.split('.')]
                        ?.let(CodexMcpConfigParser::parseString)
                        ?.let { highlight(field, it) }
                }
            }
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    private fun lookup(root: JsonObject, key: String): JsonValue? {
        // Kiro stores literal dotted keys; other agents generally use nested objects.
        root.fields[key]?.let { return it }
        var value: JsonValue = root
        key.split('.').forEach { part -> value = (value as? JsonObject)?.fields?.get(part) ?: return null }
        return value
    }

    fun sanitize(agentId: String, highlights: List<ConfigHighlight>): List<ConfigHighlight> = highlights
        .mapNotNull { entry -> fields[agentId]?.firstOrNull { it.key == entry.key }?.let { highlight(it, entry.value) } }
        .distinctBy { it.key }

    private fun highlight(field: Field, value: String): ConfigHighlight? {
        val accepted = when {
            field.count -> value.toIntOrNull()?.let { it in 0..100_000 } == true
            field.model -> safeModel(value)
            else -> value in field.values
        }
        if (!accepted) return null
        return ConfigHighlight(field.key, field.label, value, if (value in field.risks) ConfigRisk.WARNING else ConfigRisk.NONE)
    }

    // Reject URLs, paths, credentials and arbitrary text even if placed in a model field.
    // Custom identifiers outside this conservative grammar remain inventory-only.
    private fun safeModel(value: String): Boolean = value.length <= 96 && MODEL.matches(value) &&
        !Regex("(?i)(token|secret|password|api[-_]?key|sk-)").containsMatchIn(value)

    private val MODEL = Regex(
        "(?i)(?:(?:anthropic|openai|google|xai|qwen)/)?" +
            "(?:auto|default|(?:gpt|claude|sonnet|opus|haiku|qwen[0-9]*|grok|gemini|o[1-9])" +
            "(?:[-.](?:[0-9]+b?|sonnet|opus|haiku|mini|nano|pro|flash|lite|thinking|fast|code|coder|plus|max|latest|preview|instruct|turbo|chat|codex))*)",
    )
}
