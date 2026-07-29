package io.github.xororz.localdream.mcp

import org.json.JSONObject

/**
 * 已通过协议安全边界的 MCP Tool 领域入口。
 *
 * HTTP 层负责认证、会话、schema 和 scope；实现方只会收到已
 * 规范化的 invocation，不能接收客户端提供的任意命令、文件路径或 Token。
 */
fun interface McpToolGateway {
    fun execute(
        client: McpAuthenticatedClient,
        invocation: McpToolInvocation,
        arguments: JSONObject,
    ): McpToolGatewayResult

    /**
     * A transport advertises only tools its installed domain gateway can
     * execute.  The default keeps small test gateways useful; production
     * gateways must override it with their explicit product whitelist.
     */
    fun supports(definition: McpToolDefinition): Boolean = true

    object Unavailable : McpToolGateway {
        override fun execute(
            client: McpAuthenticatedClient,
            invocation: McpToolInvocation,
            arguments: JSONObject,
        ): McpToolGatewayResult = McpToolGatewayResult.Rejected("TOOL_UNAVAILABLE")

        override fun supports(definition: McpToolDefinition): Boolean = false
    }
}

sealed interface McpToolGatewayResult {
    data class Completed(
        val result: JSONObject,
        val jobId: String? = null,
    ) : McpToolGatewayResult

    data class Rejected(val code: String) : McpToolGatewayResult
}
