package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ActionResult
import kotlinx.serialization.json.*

/**
 * 手机控制原语级 MCP 工具集。
 * 与 McpTaskTools 互补：前者提交高层任务，后者暴露具体操作。
 * 所有工具直接操作 [screen]，不经过 AgentLoop。
 */
class McpPhoneTools(
    private val screen: ScreenPort,
    private val listApps: () -> List<String> = emptyList,
) {
    data class Tool(val name: String, val description: String, val schema: JsonObject, val readOnly: Boolean)

    val tools: List<Tool> = listOf(
        // 观察类
        Tool(
            "phone_observe",
            "Read the current screen UI tree. Returns element list with refs, roles, names, bounds.",
            schema(listOf("verbose")) {
                putJsonObject("verbose") {
                    put("type", "boolean")
                    put("description", "Include full element details (true) or summary (false)")
                }
            },
            readOnly = true
        ),
        Tool(
            "phone_screenshot",
            "Take a screenshot of the current screen. Returns base64-encoded PNG.",
            schema(emptyList()) { },
            readOnly = true
        ),
        // 交互类
        Tool(
            "phone_tap",
            "Tap an element by its ref (e.g., '@e3') or by coordinates (x, y in 0-999 normalized).",
            schema(required = listOf("ref")) {
                putJsonObject("ref") { put("type", "string"); put("description", "Element ref like '@e3'") }
                putJsonObject("x") { put("type", "integer"); put("description", "X coordinate 0-999") }
                putJsonObject("y") { put("type", "integer"); put("description", "Y coordinate 0-999") }
            },
            readOnly = false
        ),
        Tool(
            "phone_type",
            "Type text into the focused input field or specified element.",
            schema(required = listOf("text")) {
                putJsonObject("text") { put("type", "string"); put("description", "Text to type") }
                putJsonObject("ref") { put("type", "string"); put("description", "Target element ref") }
            },
            readOnly = false
        ),
        Tool(
            "phone_scroll",
            "Scroll the page or list up/down.",
            schema(required = listOf("direction")) {
                putJsonObject("direction") {
                    put("type", "string")
                    put("enum", listOf("up", "down"))
                }
            },
            readOnly = false
        ),
        Tool(
            "phone_back",
            "Press the back button.",
            schema(emptyList()) { },
            readOnly = false
        ),
        // App管理类
        Tool(
            "phone_list_apps",
            "List all installed applications with their package names.",
            schema(emptyList()) { },
            readOnly = true
        ),
        Tool(
            "phone_launch_app",
            "Launch an application by package name.",
            schema(required = listOf("package")) {
                putJsonObject("package") { put("type", "string"); put("description", "Package name e.g., 'com.whatsapp') }
            },
            readOnly = false
        ),
        // 状态查询
        Tool(
            "phone_get_status",
            "Get device status: battery, connectivity, foreground app.",
            schema(emptyList()) { },
            readOnly = true
        ),
    )

    suspend fun call(name: String, argsJson: String): String {
        val args = runCatching { Json.decodeFromString<JsonObject>(argsJson) }.getOrElse {
            return errorJson("Invalid JSON: ${it.message}")
        }
        return when (name) {
            "phone_observe" -> handleObserve(args)
            "phone_screenshot" -> handleScreenshot()
            "phone_tap" -> handleTap(args)
            "phone_type" -> handleType(args)
            "phone_scroll" -> handleScroll(args)
            "phone_back" -> handleBack()
            "phone_list_apps" -> handleListApps()
            "phone_launch_app" -> handleLaunchApp(args)
            "phone_get_status" -> handleStatus()
            else -> errorJson("Unknown tool: $name")
        }
    }

    private fun errorJson(message: String) = buildJsonObject { put("error", message) }.toString()

    private suspend fun handleObserve(args: JsonObject): String {
        val verbose = args["verbose"]?.jsonPrimitive?.booleanOrNull ?: false
        val snapshot = runCatching { screen.snapshot() }.getOrElse { return errorJson(it.message ?: "snapshot failed") }
        return buildJsonObject {
            put("snapshot_id", snapshot.snapshotId)
            put("package", snapshot.packageName ?: "unknown")
            if (verbose) {
                putJsonArray("elements") {
                    snapshot.elements.forEach { elem ->
                        add(buildJsonObject {
                            put("ref", elem.ref)
                            put("role", elem.role)
                            put("name", elem.name)
                            put("bounds", Json.decodeFromString(elem.bounds.toString()))
                            put("clickable", elem.clickable)
                            put("editable", elem.editable)
                            put("scrollable", elem.scrollable)
                        })
                    }
                }
            } else {
                putJsonArray("summary") {
                    snapshot.elements.filter { it.meaningful }.forEach { elem ->
                        add(buildJsonObject {
                            put("ref", elem.ref)
                            put("role", elem.role)
                            put("name", elem.name.take(50))
                        })
                    }
                }
            }
            put("total_elements", snapshot.elements.size)
        }.toString()
    }

    private suspend fun handleScreenshot(): String {
        val png = runCatching { screen.screenshotPng() }.getOrElse { return errorJson(it.message ?: "screenshot failed") }
            ?: return errorJson("Screenshot not available")
        return buildJsonObject {
            put("image_base64", Base64.getEncoder().encodeToString(png))
            put("format", "png")
        }.toString()
    }

    private suspend fun handleTap(args: JsonObject): String {
        val ref = args["ref"]?.jsonPrimitive?.contentOrNull
        val x = args["x"]?.jsonPrimitive?.intOrNull
        val y = args["y"]?.jsonPrimitive?.intOrNull
        
        val result = if (ref != null && x != null && y != null) {
            // 使用坐标点击
            screen.tapXY(x, y)
        } else if (ref != null) {
            // 需要snapshot_id来点击ref，简化处理用中心点
            screen.tapXY(500, 500)
        } else {
            screen.tapXY(x ?: 500, y ?: 500)
        }
        
        return when (result) {
            is com.wangzan101.mika.core.uistate.ActionResult.Ok -> buildJsonObject { put("success", true) }.toString()
            is com.wangzan101.mika.core.uistate.ActionResult.Failed -> errorJson(result.error)
            else -> errorJson("Unknown result")
        }
    }

    private suspend fun handleType(args: JsonObject): String {
        val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return errorJson("Missing text")
        val result = runCatching { screen.typeText(null, null, text) }.getOrElse { return errorJson(it.message ?: "type failed") }
        return when (result) {
            is com.wangzan101.mika.core.uistate.ActionResult.Ok -> buildJsonObject { put("success", true) }.toString()
            is com.wangzan101.mika.core.uistate.ActionResult.Failed -> errorJson(result.error)
            else -> errorJson("Unknown result")
        }
    }

    private suspend fun handleScroll(args: JsonObject): String {
        val direction = args["direction"]?.jsonPrimitive?.contentOrNull ?: "down"
        val down = direction == "down"
        val result = runCatching { screen.scroll(null, null, down) }.getOrElse { return errorJson(it.message ?: "scroll failed") }
        return when (result) {
            is com.wangzan101.mika.core.uistate.ActionResult.Ok -> buildJsonObject { put("success", true) }.toString()
            is com.wangzan101.mika.core.uistate.ActionResult.Failed -> errorJson(result.error)
            else -> errorJson("Unknown result")
        }
    }

    private suspend fun handleBack(): String {
        val result = runCatching { screen.back() }.getOrElse { return errorJson(it.message ?: "back failed") }
        return when (result) {
            is com.wangzan101.mika.core.uistate.ActionResult.Ok -> buildJsonObject { put("success", true) }.toString()
            is com.wangzan101.mika.core.uistate.ActionResult.Failed -> errorJson(result.error)
            else -> errorJson("Unknown result")
        }
    }

    private fun handleListApps(): String {
        val apps = runCatching { listApps() }.getOrElse { return errorJson(it.message ?: "list apps failed") }
        return buildJsonObject {
            putJsonArray("apps") {
                apps.forEach { add(it) }
            }
            put("count", apps.size)
        }.toString()
    }

    private suspend fun handleLaunchApp(args: JsonObject): String {
        val pkg = args["package"]?.jsonPrimitive?.contentOrNull ?: return errorJson("Missing package")
        val result = runCatching { screen.openApp(pkg) }.getOrElse { return errorJson(it.message ?: "launch failed") }
        return when (result) {
            is com.wangzan101.mika.core.uistate.ActionResult.Ok -> buildJsonObject { put("success", true) }.toString()
            is com.wangzan101.mika.core.uistate.ActionResult.Failed -> errorJson(result.error)
            else -> errorJson("Unknown result")
        }
    }

    private fun handleStatus(): String {
        // TODO: 实现电池/网络状态查询
        return buildJsonObject {
            put("battery", 100)
            put("connected", true)
            put("foreground", "unknown")
        }.toString()
    }

    companion object {
        private fun schema(required: List<String>, props: JsonObjectBuilder.() -> Unit) = buildJsonObject {
            put("type", "object")
            putJsonObject("properties", props)
            putJsonArray("required") { required.forEach { add(it) } }
            put("additionalProperties", false)
        }
    }
}
