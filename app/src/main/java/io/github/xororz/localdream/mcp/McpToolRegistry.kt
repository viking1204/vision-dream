package io.github.xororz.localdream.mcp

import java.security.MessageDigest
import java.util.Locale
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
        val canonical = allowedArguments.sorted().filter(arguments::has).joinToString("&") { key ->
            "$key=${arguments.opt(key)?.toString().orEmpty()}"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
    }

    companion object {
        val definitions: Map<String, McpToolDefinition> = listOf(
            definition("models.list", setOf("models.read"), McpToolRisk.READ),
            definition("models.get", setOf("models.read"), McpToolRisk.READ, setOf("modelId"), setOf("modelId")),
            definition("runtime.status", setOf("diagnostics.read"), McpToolRisk.READ),
            definition(
                "generation.create",
                setOf("generation.run"),
                McpToolRisk.MUTATE,
                setOf("modelId", "presetId", "prompt", "negativePrompt", "seed", "width", "height", "scheduler", "steps", "cfg", "denoiseStrength"),
                setOf("modelId", "prompt"),
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
            definition("presets.update", setOf("presets.write"), McpToolRisk.MUTATE, setOf("presetId", "revision"), setOf("presetId", "name", "selector", "configJson", "revision")),
            definition("presets.reorder", setOf("presets.write"), McpToolRisk.MUTATE, setOf("presetIds"), setOf("presetIds")),
            definition("presets.export", setOf("presets.read"), McpToolRisk.READ),
            definition("presets.import", setOf("presets.write"), McpToolRisk.MUTATE, setOf("envelope"), setOf("envelope")),
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
        ) = McpToolDefinition(name, scopes, risk, required, allowed, argumentTypes = argumentTypes)

        private fun destructive(name: String, scope: String, target: String) = McpToolDefinition(
            name = name,
            requiredScopes = setOf(scope),
            risk = McpToolRisk.DESTRUCTIVE,
            requiredArguments = setOf(target),
            allowedArguments = setOf(target),
            targetArgument = target,
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
