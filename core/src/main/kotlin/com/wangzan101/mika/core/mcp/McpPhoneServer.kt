package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.remote.HttpRequest
import com.wangzan101.mika.core.remote.HttpResponse
import com.wangzan101.mika.core.uistate.ScreenPort

/**
 * 轻量版MCP服务器：只暴露手机控制原语工具，不依赖Agent/Group能力。
 *
 * 与 McpServer 互补：McpServer 提供高层任务接口（mika_run_task 等），
 * 本类只提供底层操作（phone_tap、phone_type、phone_screenshot 等）。
 *
 * 适用场景：
 * - 只想用电脑直接控制手机，不需要 Mika 自行决策
 * - 作为独立服务部署，剥离完整 Agent 依赖
 */
class McpPhoneServer(
    screen: ScreenPort,
    serverVersion: String = "0.1.0",
) {
    private val phoneTools = McpPhoneTools(screen)
    private val rpc = McpRpc(
        tools = null,  // 不提供高层任务能力
        phoneTools = phoneTools,
        serverVersion = serverVersion,
    )

    /** 处理 MCP JSON-RPC 请求，返回响应（可被 HTTP handler 直接调用）。 */
    fun handle(req: HttpRequest): HttpResponse = rpc.handle(req)

    /** 暴露给外层 HTTP 框架的工具列表（用于 tools/list）。 */
    val tools: List<McpPhoneTools.Tool> get() = phoneTools.tools
}

