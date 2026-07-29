package io.github.xororz.localdream.mcp

import org.json.JSONArray
import org.json.JSONObject

/** Stable wire helpers for the 2025-11-25 Streamable HTTP baseline. */
object McpProtocol {
    const val VERSION = "2025-11-25"
    const val PATH = "/mcp"
    const val ASSET_PATH_PREFIX = "/assets/"
    const val SESSION_HEADER = "mcp-session-id"

    fun assetPath(assetId: String): String = "$ASSET_PATH_PREFIX$assetId"

    fun result(id: Any?, result: JSONObject): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("result", result)

    fun error(id: Any?, code: Int, stableCode: String, message: String): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put(
            "error",
            JSONObject()
                .put("code", code)
                .put("message", message)
                .put("data", JSONObject().put("code", stableCode)),
        )

    fun initializeResult(): JSONObject = JSONObject()
        .put("protocolVersion", VERSION)
        .put("serverInfo", JSONObject().put("name", "vision-dream").put("version", "1.0"))
        // P5 owns the static Tool registry. Do not promise tools before they exist.
        .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
        .put("instructions", "Use authenticated Streamable HTTP at /mcp.")

    fun sseReadyJson(): String = JSONObject()
        .put("protocolVersion", VERSION)
        .put("events", JSONArray())
        .toString()
}

data class McpAuthenticatedClient(
    val clientId: String,
    val tokenGeneration: Long,
    val scopes: Set<String>,
    val transport: McpTransport,
)
