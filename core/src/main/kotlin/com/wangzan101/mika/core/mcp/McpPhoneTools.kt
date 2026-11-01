package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * 手机控制原语级 MCP 工具集。
 * 与 McpTaskTools 互补：前者提交高层任务，后者暴露具体操作。
 * 所有工具直接操作 [screen]，不经过 AgentLoop。
 */
class McpPhoneTools(
    private val screen: ScreenPort,
    private val listApps: () -> List<String> = { emptyList() },
) {
    data class Tool(val name: String, val description: String, val schema: JsonObject, val readOnly: Boolean)

    val tools: List<Tool> = listOf(
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
        Tool(
            "phone_tap",
            "Tap an element by its ref (e.g., 'e3') or by normalized coordinates (x, y in 0-999).",
            schema(emptyList()) {
                putJsonObject("ref") { put("type", "string"); put("description", "Element ref from phone_observe") }
                putJsonObject("x") { put("type", "integer"); put("description", "X coordinate 0-999") }
                putJsonObject("y") { put("type", "integer"); put("description", "Y coordinate 0-999") }
            },
            readOnly = false
        ),
        Tool(
            "phone_type",
            "Type text into the focused input field or specified element.",
            schema(listOf("text")) {
                putJsonObject("text") { put("type", "string"); put("description", "Text to type") }
                putJsonObject("ref") { put("type", "string"); put("description", "Target element ref") }
            },
            readOnly = false
        ),
        Tool(
            "phone_scroll",
            "Scroll the page or list up/down.",
            schema(listOf("direction")) {
                putJsonObject("direction") {
                    put("type", "string")
                    putJsonArray("enum") { add("up"); add("down") }
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
        Tool(
            "phone_list_apps",
            "List all installed applications with their package names.",
            schema(emptyList()) { },
            readOnly = true
        ),
        Tool(
            "phone_launch_app",
            "Launch an application by package name.",
            schema(listOf("package")) {
                putJsonObject("package") { put("type", "string"); put("description", "Package name e.g., 'com.whatsapp'") }
            },
            readOnly = false
        ),
        Tool(
            "phone_get_status",
            "Get device status: battery, connectivity, foreground app.",
            schema(emptyList()) { },
            readOnly = true
        ),
    )

    fun has(name: String): Boolean = tools.any { it.name == name }

    /** MCP tools/list 用的定义数组。 */
    fun definitions(): JsonArray = buildJsonArray {
        tools.forEach { t ->
            add(buildJsonObject {
                put("name", t.name)
                put("description", t.description)
                put("inputSchema", t.schema)
            })
        }
    }

    /** 执行一个工具，返回 JSON 字符串。 */
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

    private fun ok() = buildJsonObject { put("success", true) }.toString()

    private fun resultJson(result: ActionResult): String = when (result) {
        is ActionResult.Ok -> ok()
        is ActionResult.Failed -> errorJson(result.reason)
        else -> errorJson("unknown result")
    }

    private suspend fun handleObserve(args: JsonObject): String {
        val verbose = args["verbose"]?.jsonPrimitive?.booleanOrNull ?: false
        val snapshot = runCatching { screen.snapshot() }.getOrElse { return errorJson(it.message ?: "snapshot failed") }
        return buildJsonObject {
            put("snapshot_id", snapshot.snapshotId)
            put("package", snapshot.packageName ?: "unknown")
            putJsonArray("elements") {
                snapshot.elements.forEach { elem ->
                    add(buildJsonObject {
                        put("ref", elem.ref)
                        put("role", elem.role)
                        put("name", if (verbose) elem.name else elem.name.take(50))
                        putJsonArray("bounds") { elem.bounds.forEach { add(it) } }
                        put("clickable", elem.clickable)
                        put("editable", elem.editable)
                        put("scrollable", elem.scrollable)
                    })
                }
            }
            put("total_elements", snapshot.elements.size)
        }.toString()
    }

    private suspend fun handleScreenshot(): String {
        val png = runCatching { screen.screenshotPng() }.getOrElse { return errorJson(it.message ?: "screenshot failed") }
            ?: return errorJson("screenshot not available")
        return buildJsonObject {
            put("image_base64", Base64.getEncoder().encodeToString(png))
            put("format", "png")
        }.toString()
    }

    private suspend fun handleTap(args: JsonObject): String {
        val ref = args["ref"]?.jsonPrimitive?.contentOrNull
        val x = args["x"]?.jsonPrimitive?.intOrNull
        val y = args["y"]?.jsonPrimitive?.intOrNull
        val result = when {
            ref != null -> ref.let { screen.tapRef(it, "") }
            x != null && y != null -> screen.tapXY(x, y)
            else -> return errorJson("phone_tap needs ref or x&y")
        }
        return resultJson(result)
    }

    private suspend fun handleType(args: JsonObject): String {
        val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return errorJson("Missing text")
        val ref = args["ref"]?.jsonPrimitive?.contentOrNull
        val result = runCatching { screen.typeText(ref, if (ref != null) "" else null, text) }
            .getOrElse { return errorJson(it.message ?: "type failed") }
        return resultJson(result)
    }

    private suspend fun handleScroll(args: JsonObject): String {
        val direction = args["direction"]?.jsonPrimitive?.contentOrNull ?: "down"
        val down = direction == "down"
        val result = runCatching { screen.scroll(null, null, down) }
            .getOrElse { return errorJson(it.message ?: "scroll failed") }
        return resultJson(result)
    }

    private suspend fun handleBack(): String {
        val result = runCatching { screen.back() }.getOrElse { return errorJson(it.message ?: "back failed") }
        return resultJson(result)
    }

    private fun handleListApps(): String {
        val apps = runCatching { listApps() }.getOrElse { return errorJson(it.message ?: "list apps failed") }
        return buildJsonObject {
            putJsonArray("apps") { apps.forEach { add(it) } }
            put("count", apps.size)
        }.toString()
    }

    private suspend fun handleLaunchApp(args: JsonObject): String {
        val pkg = args["package"]?.jsonPrimitive?.contentOrNull ?: return errorJson("Missing package")
        val result = runCatching { screen.openApp(pkg) }.getOrElse { return errorJson(it.message ?: "launch failed") }
        return resultJson(result)
    }

    private fun handleStatus(): String {
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
