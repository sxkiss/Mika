# Phone MCP 原语暴露 — 改造方案

> 目标：把 `phone_tap` / `phone_swipe` / `phone_observe` 等屏幕操作原语直接注册为 MCP tools，让外部 AI 客户端（Claude Desktop、Cursor、CodeArts IDE 等）能控制手机，而不只是"提交任务"。

---

## 一、现状分析

### 1.1 现有 MCP 能力（任务级）

```
McpTaskTools (core/mcp/McpTaskTools.kt)
├── mika_run_task    → 提交任务到 AgentLoop
├── mika_wait_task   → 轮询任务状态
├── mika_answer      → 回答问题
├── mika_cancel_task → 取消任务
└── mika_list_tasks  → 列出历史任务
```

**问题**：高层任务接口，外部模型无法控制具体操作。

### 1.2 Phone 能力工具集（已定义但未暴露）

```
PhoneGroup.tools (core/cap/phone/PhoneGroup.kt)
├── ScreenTool.of(PHONE_FAMILY)  // 14个基础屏幕工具
│   ├── phone_observe           // 读UI树
│   ├── phone_list_elements
│   ├── phone_screenshot        // 截屏
│   ├── phone_describe          // 看图
│   ├── phone_find_visually     // 视觉定位
│   ├── phone_tap               // 点击
│   ├── phone_scroll
│   ├── phone_swipe
│   ├── phone_type_text
│   ├── phone_clear_text
│   ├── phone_back
│   ├── phone_press_key
│   └── phone_diagnose
├── PhoneQuery / PhoneFind / PhoneExtract
├── PhoneCollect / PhoneWait / PhoneEventTool
├── PhoneDialog / PhoneGesture
├── SetWheel / PhoneRun / PhoneCommand
├── ListApps / LaunchApp / AppAfterTask
├── ShareText / OpenLink
└── SetAlarm / SetTimer / ShowAlarms / AddCalendarEvent
```

这些工具通过 `ToolDispatcher` 分发给 AgentLoop，但未暴露给 MCP。

### 1.3 关键抽象（解耦点）

```
ScreenPort (core/uistate/ScreenPort.kt)  ← 接口
     ↑
AndroidScreen (app/.../a11y/AndroidScreen.kt)  ← 实现（45处无障碍依赖）
```

**设计要点**：`ScreenPort` 是纯接口，可独立注入。

---

## 二、改造目标

### 2.1 新增 MCP 工具集（原语级）

```
McpPhoneTools (新增)
├── phone_observe           // 读取当前屏幕UI树
├── phone_tap              // 点击指定元素
├── phone_type             // 输入文字
├── phone_scroll           // 滚动
├── phone_screenshot       // 截屏（base64）
├── phone_back             // 返回
├── phone_list_apps        // 列出已安装App
├── phone_launch_app       // 启动App
└── phone_get_status       // 获取设备状态
```

### 2.2 保留原有任务级 MCP

不破坏现有 `mika_run_task` 等接口，新增 `phone_*` 工具与之并行。

---

## 三、实现方案

### 3.1 新增文件

#### `core/src/main/kotlin/com/wangzan101/mika/core/mcp/McpPhoneTools.kt`

```kotlin
package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.serialization.json.*

/**
 * 手机控制原语级 MCP 工具集。
 * 与 McpTaskTools 互补：前者提交高层任务，后者暴露具体操作。
 */
class McpPhoneTools(
    private val screen: ScreenPort,
    private val listApps: () -> List<String> = emptyList(),
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

    suspend fun call(name: String, argsJson: String): JsonObject {
        val args = Json.decodeFromString<JsonObject>(argsJson)
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
            else -> error("Unknown tool: $name")
        }
    }

    private suspend fun handleObserve(args: JsonObject): JsonObject {
        val verbose = args["verbose"]?.jsonPrimitive?.booleanOrNull ?: false
        val snapshot = screen.snapshot()
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
        }
    }

    private suspend fun handleScreenshot(): JsonObject {
        val png = screen.screenshotPng() ?: return errorJsonObject("Screenshot not available")
        return buildJsonObject {
            put("image_base64", Base64.getEncoder().encodeToString(png))
            put("format", "png")
        }
    }

    private suspend fun handleTap(args: JsonObject): JsonObject {
        val ref = args["ref"]?.jsonPrimitive?.contentOrNull
        val x = args["x"]?.jsonPrimitive?.intOrNull
        val y = args["y"]?.jsonPrimitive?.intOrNull
        
        val result = if (ref != null) {
            // 需要从snapshot中获取坐标，这里简化处理
            screen.tapXY(x ?: 500, y ?: 500)
        } else {
            screen.tapXY(x ?: 500, y ?: 500)
        }
        
        return when (result) {
            is com.wangzan101.mika.core.uistate.ActionResult.Ok -> buildJsonObject { put("success", true) }
            is com.wangzan101.mika.core.uistate.ActionResult.Failed -> errorJsonObject(result.error)
            else -> errorJsonObject("Unknown result")
        }
    }

    private suspend fun handleType(args: JsonObject): JsonObject {
        val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return errorJsonObject("Missing text")
        val result = screen.typeText(null, null, text)
        return when (result) {
            is com.wangzan101.mika.core.uistate.ActionResult.Ok -> buildJsonObject { put("success", true) }
            is com.wangzan101.mika.core.uistate.ActionResult.Failed -> errorJsonObject(result.error)
            else -> errorJsonObject("Unknown result")
        }
    }

    private suspend fun handleScroll(args: JsonObject): JsonObject {
        val direction = args["direction"]?.jsonPrimitive?.contentOrNull ?: "down"
        val down = direction == "down"
        val result = screen.scroll(null, null, down)
        return when (result) {
            is com.wangzan101.mika.core.uistate.ActionResult.Ok -> buildJsonObject { put("success", true) }
            is com.wangzan101.mika.core.uistate.ActionResult.Failed -> errorJsonObject(result.error)
            else -> errorJsonObject("Unknown result")
        }
    }

    private suspend fun handleBack(): JsonObject {
        val result = screen.back()
        return when (result) {
            is com.wangzan101.mika.core.uistate.ActionResult.Ok -> buildJsonObject { put("success", true) }
            is com.wangzan101.mika.core.uistate.ActionResult.Failed -> errorJsonObject(result.error)
            else -> errorJsonObject("Unknown result")
        }
    }

    private fun handleListApps(): JsonObject {
        val apps = listApps()
        return buildJsonObject {
            putJsonArray("apps") {
                apps.forEach { add(it) }
            }
            put("count", apps.size)
        }
    }

    private suspend fun handleLaunchApp(args: JsonObject): JsonObject {
        val pkg = args["package"]?.jsonPrimitive?.contentOrNull ?: return errorJsonObject("Missing package")
        val result = screen.openApp(pkg)
        return when (result) {
            is com.wangzan101.mika.core.uistate.ActionResult.Ok -> buildJsonObject { put("success", true) }
            is com.wangzan101.mika.core.uistate.ActionResult.Failed -> errorJsonObject(result.error)
            else -> errorJsonObject("Unknown result")
        }
    }

    private fun handleStatus(): JsonObject {
        // TODO: 实现电池/网络状态查询
        return buildJsonObject {
            put("battery", 100)
            put("connected", true)
        }
    }

    private fun errorJsonObject(message: String): JsonObject =
        buildJsonObject { put("error", message) }

    companion object {
        private fun schema(required: List<String>, props: JsonObjectBuilder.() -> Unit) = buildJsonObject {
            put("type", "object")
            putJsonObject("properties", props)
            putJsonArray("required") { required.forEach { add(it) } }
            put("additionalProperties", false)
        }
    }
}
```

#### `app/src/main/java/com/wangzan101/mika/mcp/McpPhoneRuntime.kt`

```kotlin
package com.wangzan101.mika.mcp

import android.content.Context
import com.wangzan101.mika.core.mcp.McpPhoneTools
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen

/**
 * Phone MCP 运行时：连接 ScreenPort 实现和 McpPhoneTools。
 */
class McpPhoneRuntime(
    context: Context,
) {
    private val screenPort: ScreenPort by lazy {
        // 复用现有的 AndroidScreen 实现
        AndroidScreen(context)
    }

    private val phoneTools: McpPhoneTools by lazy {
        McpPhoneTools(
            screen = screenPort,
            listApps = { /* 实现获取已安装App列表 */ emptyList() }
        )
    }

    val tools get() = phoneTools.tools
    
    suspend fun callTool(name: String, args: String): String {
        return phoneTools.call(name, args).toString()
    }
}
```

### 3.2 修改现有文件

#### `app/src/main/java/com/wangzan101/mika/mcp/McpRuntime.kt`

```kotlin
// 在 McpRuntime 中集成 McpPhoneTools
class McpRuntime(
    private val context: Context,
    // ... 现有参数
) {
    // 新增 phone 工具集
    private val phoneRuntime = McpPhoneRuntime(context)
    
    // 在 handleToolsList 中追加 phone 工具
    private fun listTools(): List<McpTaskTools.Tool> = listOf(
        // ... 现有工具
    ) + phoneRuntime.tools.map { phoneTool ->
        McpTaskTools.Tool(
            name = phoneTool.name,  // 需要调整schema格式
            description = phoneTool.description,
            schema = phoneTool.schema,
            readOnly = phoneTool.readOnly
        )
    }
}
```

#### `core/src/main/kotlin/com/wangzan101/mika/core/mcp/McpRpc.kt`

无需修改，RPC 层已支持任意工具名。

---

## 四、使用方式

### 4.1 启动 MCP Server

```bash
# 手机开启 USB 调试 + Mika 设置中打开「电脑连接」
# 电脑端连接
mcp connect --transport usb http://127.0.0.1:PORT
```

### 4.2 Claude Desktop 配置

```json
{
  "mcpServers": {
    "mika-phone": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-http"],
      "env": {
        "MCP_SERVER_URL": "http://PHONE_IP:PORT/mcp"
      }
    }
  }
}
```

### 4.3 工具调用示例

```python
# 观察屏幕
result = mcp.call("phone_observe", {"verbose": true})
# → {"snapshot_id": "p123", "elements": [...], "total_elements": 45}

# 点击元素
result = mcp.call("phone_tap", {"ref": "@e3"})

# 输入文字
result = mcp.call("phone_type", {"text": "Hello World"})

# 截屏
result = mcp.call("phone_screenshot", {})
# → {"image_base64": "iVBORw0KGgo...", "format": "png"}
```

---

## 五、改造步骤

| 步骤 | 文件 | 操作 |
|------|------|------|
| 1 | `core/.../mcp/McpPhoneTools.kt` | **新增** — 定义 phone MCP 工具集 |
| 2 | `app/.../mcp/McpPhoneRuntime.kt` | **新增** — 绑定 AndroidScreen 实现 |
| 3 | `app/.../mcp/McpRuntime.kt` | **修改** — 集成 McpPhoneRuntime |
| 4 | `core/.../uistate/ScreenPort.kt` | **无修改** — 接口已定义 |
| 5 | `app/.../a11y/AndroidScreen.kt` | **无修改** — 已有实现 |

---

## 六、后续优化方向

1. **异步工具结果**：当前同步返回，可改为流式推送屏幕变化事件
2. **操作确认**：敏感操作（支付、删除）仍需用户确认
3. **多设备支持**：通过 `adb devices` 切换目标设备
4. **权限分级**：readOnly vs readWrite 工具分离

---

## 七、风险与注意事项

| 风险 | 缓解措施 |
|------|----------|
| 无障碍服务被系统杀死 | 前台服务保活 + 异常恢复机制 |
| 操作失败无反馈 | 每个工具返回 ActionResult 详细错误 |
| 并发冲突 | ScreenPort 内部加锁或队列化 |
| 坐标映射问题 | 使用 0-999 标准化坐标，避免分辨率依赖 |

---

*方案制定时间：2026-11-01*
*关联 Issue：phone-mcp-primitives-exposure*
