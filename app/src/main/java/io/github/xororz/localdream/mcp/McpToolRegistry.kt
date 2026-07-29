package io.github.xororz.localdream.mcp

import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 的唯一工具目录。协议层在访问领域服务前在这里完成名称、参数和 scope 校验，
 * 因而任何客户端输入都不能被当作可执行的设备命令或文件路径。
 */
class McpToolRegistry {
    fun validate(name: String, arguments: JSONObject, grantedScopes: Set<String>): McpToolValidation {
        val definition = definitions[name] ?: return McpToolValidation.Rejected("TOOL_NOT_FOUND")
        val keys = arguments.keys().asSequence().toSet()
        if (!keys.all(definition.allowedArguments::contains) || !definition.requiredArguments.all(arguments::has)) {
            return McpToolValidation.Rejected("INVALID_PARAMS")
        }
        if (!definition.argumentTypes.all { (key, type) -> !arguments.has(key) || matchesJsonType(arguments.opt(key), type) }) {
            return McpToolValidation.Rejected("INVALID_PARAMS")
        }
        if (definition.risk != McpToolRisk.READ && arguments.opt(IDEMPOTENCY_KEY) !is String) {
            return McpToolValidation.Rejected("INVALID_PARAMS")
        }
        if (definition.risk != McpToolRisk.READ && arguments.optString(IDEMPOTENCY_KEY).isBlank()) {
            return McpToolValidation.Rejected("INVALID_PARAMS")
        }
        if (definition.risk == McpToolRisk.DESTRUCTIVE && arguments.opt(DRY_RUN) !is Boolean) {
            return McpToolValidation.Rejected("INVALID_PARAMS")
        }
        if (!grantedScopes.containsAll(definition.requiredScopes)) {
            return McpToolValidation.Rejected("SCOPE_DENIED")
        }
        val targetIds = definition.targetArgument?.let { target -> setOf(arguments.optString(target)) } ?: emptySet()
        if (targetIds.any(String::isBlank)) return McpToolValidation.Rejected("INVALID_PARAMS")
        return McpToolValidation.Allowed(
            McpToolInvocation(
                definition = definition,
                targetIds = targetIds,
                parameterDigest = digest(arguments, definition.allowedArguments),
            ),
        )
    }

    private fun digest(arguments: JSONObject, allowedArguments: Set<String>): String {
        // Idempotency keys identify a retry, not a distinct domain request.
        // Excluding them makes a reused key with different mutation arguments
        // detectable by the transport's replay store.
        val canonical = allowedArguments.sorted()
            .filter { it != IDEMPOTENCY_KEY && arguments.has(it) }
            .joinToString("&") { key -> "$key=${canonicalJson(arguments.opt(key))}" }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
    }

    /**
     * Idempotency compares JSON values, not the insertion order used by a
     * particular client.  Keep arrays ordered because their order is domain
     * input, while recursively sorting object keys makes retry serialization
     * stable at every nesting level.
     */
    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"

        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}") { key ->
            "${JSONObject.quote(key)}:${canonicalJson(value.opt(key))}"
        }

        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { index ->
            canonicalJson(value.opt(index))
        }

        is String -> JSONObject.quote(value)

        is Boolean -> value.toString()

        is Number -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString()

        else -> JSONObject.quote(value.toString())
    }

    private fun matchesJsonType(value: Any?, type: String): Boolean = when (type) {
        "string" -> value is String
        "boolean" -> value is Boolean
        "integer" -> value is Number && value.toDouble().isFinite() && value.toDouble() % 1.0 == 0.0
        "number" -> value is Number && value.toDouble().isFinite()
        "array" -> value is JSONArray
        "object" -> value is JSONObject
        else -> false
    }

    companion object {
        const val IDEMPOTENCY_KEY = "idempotencyKey"
        const val DRY_RUN = "dryRun"

        val definitions: Map<String, McpToolDefinition> = listOf(
            definition("models.list", setOf("models.read"), McpToolRisk.READ),
            definition("models.get", setOf("models.read"), McpToolRisk.READ, setOf("modelId"), setOf("modelId")),
            definition("runtime.status", setOf("diagnostics.read"), McpToolRisk.READ),
            definition(
                "generation.create",
                setOf("generation.run"),
                McpToolRisk.MUTATE,
                setOf("modelId", "prompt"),
                setOf("modelId", "presetId", "prompt", "negativePrompt", "seed", "width", "height", "scheduler", "steps", "cfg", "denoiseStrength"),
                argumentTypes = mapOf(
                    "seed" to "integer",
                    "width" to "integer",
                    "height" to "integer",
                    "steps" to "integer",
                    "cfg" to "number",
                    "denoiseStrength" to "number",
                ),
            ),
            definition("jobs.get", setOf("jobs.read"), McpToolRisk.READ, setOf("jobId"), setOf("jobId")),
            definition("jobs.list", setOf("jobs.read"), McpToolRisk.READ),
            definition("presets.list", setOf("presets.read"), McpToolRisk.READ),
            definition("presets.get", setOf("presets.read"), McpToolRisk.READ, setOf("presetId"), setOf("presetId")),
            definition("presets.create", setOf("presets.write"), McpToolRisk.MUTATE, setOf("name", "selector", "configJson"), setOf("name", "selector", "configJson")),
            definition(
                "presets.update",
                setOf("presets.write"),
                McpToolRisk.MUTATE,
                setOf("presetId", "revision"),
                setOf("presetId", "name", "selector", "configJson", "revision"),
                argumentTypes = mapOf("revision" to "integer"),
            ),
            definition("presets.reorder", setOf("presets.write"), McpToolRisk.MUTATE, setOf("presetIds"), setOf("presetIds"), argumentTypes = mapOf("presetIds" to "array")),
            definition("presets.export", setOf("presets.read"), McpToolRisk.READ),
            definition("presets.import", setOf("presets.write"), McpToolRisk.MUTATE, setOf("envelope"), setOf("envelope"), argumentTypes = mapOf("envelope" to "object")),
            definition("prompts.list", setOf("prompts.read"), McpToolRisk.READ),
            definition("prompts.get", setOf("prompts.read"), McpToolRisk.READ, setOf("promptId"), setOf("promptId")),
            definition("prompts.create", setOf("prompts.write"), McpToolRisk.MUTATE, setOf("title", "prompt"), setOf("title", "prompt", "negativePrompt")),
            definition("prompts.update", setOf("prompts.write"), McpToolRisk.MUTATE, setOf("promptId"), setOf("promptId", "title", "prompt", "negativePrompt")),
            definition("downloads.list", setOf("downloads.read"), McpToolRisk.READ),
            definition("downloads.create", setOf("downloads.write"), McpToolRisk.MUTATE, setOf("modelId"), setOf("modelId")),
            definition("assets.list", setOf("assets.read"), McpToolRisk.READ),
            destructive("jobs.cancel", "jobs.write", "jobId"),
            destructive("presets.delete", "presets.write", "presetId"),
            destructive("prompts.delete", "prompts.write", "promptId"),
            destructive("assets.delete", "assets.write", "assetId"),
            destructive("downloads.cancel", "downloads.write", "downloadId"),
            destructive("runtime.unload", "diagnostics.write", "runtimeId"),
            destructive("server.stop", "server.write", "serverId"),
            destructive("client.revoke", "clients.write", "clientId"),
            destructive("token.rotate", "clients.write", "clientId"),
        ).associateBy(McpToolDefinition::name)

        private fun definition(
            name: String,
            scopes: Set<String>,
            risk: McpToolRisk,
            required: Set<String> = emptySet(),
            allowed: Set<String> = emptySet(),
            argumentTypes: Map<String, String> = emptyMap(),
        ) = McpToolDefinition(
            name = name,
            requiredScopes = scopes,
            risk = risk,
            requiredArguments = if (risk == McpToolRisk.MUTATE) required + IDEMPOTENCY_KEY else required,
            allowedArguments = if (risk == McpToolRisk.MUTATE) allowed + IDEMPOTENCY_KEY else allowed,
            argumentTypes = allowed.associateWith { "string" } + argumentTypes + if (risk == McpToolRisk.MUTATE) mapOf(IDEMPOTENCY_KEY to "string") else emptyMap(),
        )

        private fun destructive(name: String, scope: String, target: String) = McpToolDefinition(
            name = name,
            requiredScopes = setOf(scope),
            risk = McpToolRisk.DESTRUCTIVE,
            requiredArguments = setOf(target, IDEMPOTENCY_KEY, DRY_RUN),
            allowedArguments = setOf(target, IDEMPOTENCY_KEY, DRY_RUN),
            targetArgument = target,
            argumentTypes = mapOf(target to "string", IDEMPOTENCY_KEY to "string", DRY_RUN to "boolean"),
        )
    }
}

data class McpToolDefinition(
    val name: String,
    val requiredScopes: Set<String>,
    val risk: McpToolRisk,
    val requiredArguments: Set<String>,
    val allowedArguments: Set<String>,
    val targetArgument: String? = null,
    val argumentTypes: Map<String, String> = emptyMap(),
)

enum class McpToolRisk { READ, MUTATE, DESTRUCTIVE }

data class McpToolInvocation(
    val definition: McpToolDefinition,
    val targetIds: Set<String>,
    val parameterDigest: String,
)

sealed interface McpToolValidation {
    data class Allowed(val invocation: McpToolInvocation) : McpToolValidation
    data class Rejected(val code: String) : McpToolValidation
}
