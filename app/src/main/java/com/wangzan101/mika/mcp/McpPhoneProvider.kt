package com.wangzan101.mika.mcp

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.Display
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.a11y.ScreenReader
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.shell.HelperHub
import com.wangzan101.mika.shell.IMikaShell
import com.wangzan101.mika.core.mcp.McpPhoneTools
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * MCP 手机控制工具提供者。
 *
 * 监听无障碍服务连接状态，就绪且 shell 可用时创建 [McpPhoneTools] 并交给 [McpRuntime]。
 * 优先在 [AgentDisplay] 虚拟屏上操作（不抢占用户主屏）；虚拟屏建不出时回退主屏。
 */
class McpPhoneProvider(context: Context) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val packageManager = context.packageManager
    private val appContext = context.applicationContext

    private val _phoneTools = AtomicReference<McpPhoneTools?>(null)
    /** 最近一次快照的 ref -> 元素中心坐标(像素)。 */
    private val refMap = ConcurrentHashMap<String, Pair<Int, Int>>()

    /** Agent 虚拟屏；建不出时为 null，回退到主屏。 */
    @Volatile private var display: AgentDisplay? = null

    /** 当前可用的手机控制 MCP 工具，未就绪时为 null。 */
    val phoneTools: McpPhoneTools? get() = _phoneTools.get()

    val isA11yReady: Boolean get() = ScreenAccessibility.instance.value != null

    /** 监听无障碍服务；就绪且 shell 可用时构建工具集（优先使用 Agent 虚拟屏）。 */
    fun startListening(helperHub: () -> HelperHub? = { null }) {
        scope.launch {
            // 同时监听无障碍与 shell 流：任何一个就绪都会触发重新评估；
            // 只 watch 无障碍会错过 shell 晚到、或壳先就绪无障碍后连上的情况。
            val hubFlow = helperHub()?.shell ?: kotlinx.coroutines.flow.emptyFlow<IMikaShell?>()
            combine(ScreenAccessibility.instance, hubFlow) { a11y, shell -> Pair(a11y, shell) }.collect { (a11y, shell) ->
                if (a11y != null && shell != null) {
                    val d = ensureDisplay(shell)
                    _phoneTools.set(McpPhoneTools(createScreenPort(a11y, shell, d)) { getInstalledApps() })
                    Log.d(TAG, if (d != null) "MCP 手机控制已启用（虚拟屏 ${d.displayId}）" else "MCP 手机控制已启用（回退主屏）")
                } else {
                    // 任一组件断开：释放虚拟屏，工具下线
                    _phoneTools.set(null)
                    releaseDisplay()
                    Log.w(TAG, "无障碍或壳未就绪，MCP 手机控制已停用")
                }
            }
        }
    }

    /** 按需创建 Agent 虚拟屏；失败时记录并返回 null（调用方回退主屏，不崩溃）。 */
    private fun ensureDisplay(shell: IMikaShell): AgentDisplay? {
        display?.let { return it }
        return try {
            val metrics = appContext.resources.displayMetrics
            AgentDisplay(shell, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi).also {
                display = it
            }
        } catch (t: Throwable) {
            Log.w(TAG, "创建虚拟屏失败，回退主屏", t)
            null
        }
    }

    private fun releaseDisplay() {
        display?.let { runCatching { it.release() }.onFailure { e -> Log.w(TAG, "释放虚拟屏失败", e) } }
        display = null
    }

    private fun getInstalledApps(): List<String> =
        packageManager.getInstalledPackages(0)
            .map { it.packageName }
            .filter { it != appContext.packageName }

    private fun createScreenPort(a11y: ScreenAccessibility, shell: IMikaShell, agent: AgentDisplay?): ScreenPort =
        object : ScreenPort {
            // 优先虚拟屏；建不出时回退主屏
            private val displayId get() = agent?.displayId ?: Display.DEFAULT_DISPLAY
            private val width get() = agent?.width ?: android.content.res.Resources.getSystem().displayMetrics.widthPixels
            private val height get() = agent?.height ?: android.content.res.Resources.getSystem().displayMetrics.heightPixels

            override suspend fun snapshot(): ScreenSnapshot {
                val windows = a11y.windowsOn(displayId)
                val read = ScreenReader.read(windows, width, height)
                refMap.clear()
                read.entries.forEach { e ->
                    refMap[e.element.ref] = Pair(e.element.centerX, e.element.centerY)
                }
                return ScreenSnapshot(
                    snapshotId = "mcp_${System.currentTimeMillis()}",
                    packageName = ScreenReader.topPackage(windows),
                    elements = read.entries.map { it.element },
                    width = width,
                    height = height,
                    windows = read.windows,
                )
            }

            override suspend fun screenshotPng(): ByteArray? = try {
                // 虚拟屏：直接取 AgentDisplay 的最新帧；回退主屏时走无障碍截图
                val virtual = agent?.latestFramePng()
                if (virtual != null) {
                    virtual
                } else when (val shot = a11y.takeMainScreenshot()) {
                    is ScreenAccessibility.MainShot.Ok -> {
                        val out = ByteArrayOutputStream()
                        shot.bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                        shot.bitmap.recycle()
                        out.toByteArray()
                    }
                    is ScreenAccessibility.MainShot.Secure -> {
                        Log.w(TAG, "screenshot blocked by secure window")
                        null
                    }
                    is ScreenAccessibility.MainShot.Failed -> {
                        Log.e(TAG, "screenshot failed code=${shot.code}")
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "screenshot error", e)
                null
            }

            override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
                val c = refMap[ref] ?: return ActionResult.Failed("ref_expired")
                return runAction { shell.injectTap(displayId, c.first, c.second) }
            }

            override suspend fun tapXY(x: Int, y: Int): ActionResult = runAction {
                shell.injectTap(displayId, x * width / 999, y * height / 999)
            }

            override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult = runAction {
                // shell 无直接输入：设置剪贴板后 Ctrl+V 粘贴
                shell.setClipText(text)
                delay(120)
                shell.injectCtrlKey(displayId, android.view.KeyEvent.KEYCODE_V)
            }

            override suspend fun clearText(ref: String?, snapshotId: String?): ActionResult = runAction {
                shell.injectCtrlKey(displayId, android.view.KeyEvent.KEYCODE_A)
                delay(60)
                shell.injectKey(displayId, android.view.KeyEvent.KEYCODE_DEL)
            }

            override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult = runAction {
                val cx = width / 2f
                val cy = height / 2f
                val dist = height / 3f
                val fromY = if (down) cy + dist else cy - dist
                val toY = if (down) cy - dist else cy + dist
                val path = android.graphics.Path().apply {
                    moveTo(cx, fromY)
                    lineTo(cx, toY)
                }
                a11y.gesture(displayId, path, 300L)
            }

            override suspend fun back(): ActionResult = runAction {
                shell.injectKey(displayId, android.view.KeyEvent.KEYCODE_BACK)
            }

            override suspend fun pressKey(keyCode: Int): ActionResult = runAction {
                shell.injectKey(displayId, keyCode)
            }

            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult = runAction {
                val path = android.graphics.Path().apply {
                    moveTo(x1.toFloat(), y1.toFloat())
                    lineTo(x2.toFloat(), y2.toFloat())
                }
                a11y.gesture(displayId, path, durationMs.coerceAtLeast(1))
            }

            override suspend fun waitStable(timeoutMs: Long): Boolean {
                // 主屏没有虚拟屏的事件时钟：简单等待后认为稳定
                delay(timeoutMs.coerceAtLeast(0))
                return true
            }

            override suspend fun awaitChange(timeoutMs: Long): Boolean {
                // 主屏不追踪界面事件：等待后返回 false 表示未检测到变化
                delay(timeoutMs.coerceAtLeast(0))
                return false
            }

            override suspend fun openApp(packageName: String, cold: Boolean): ActionResult = try {
                if (agent != null) {
                    // 虚拟屏：由 shell 在指定 display 上启动，不打扰用户主屏
                    when (shell.startApp(displayId, packageName)) {
                        0 -> ActionResult.Ok()
                        1 -> ActionResult.Failed("app_in_use_on_main_screen")
                        2 -> ActionResult.Failed("app_not_found: $packageName")
                        else -> ActionResult.Failed("launch_failed")
                    }
                } else {
                    val intent = packageManager.getLaunchIntentForPackage(packageName)
                    if (intent != null) {
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        appContext.startActivity(intent)
                        ActionResult.Ok()
                    } else {
                        ActionResult.Failed("app_not_found: $packageName")
                    }
                }
            } catch (e: Exception) {
                ActionResult.Failed(e.message ?: "launch_failed")
            }

            override suspend fun listApps(): List<Pair<String, String>> =
                getInstalledApps().map { pkg ->
                    val label = runCatching {
                        packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
                    }.getOrDefault(pkg)
                    Pair(pkg, label)
                }
        }

    fun clear() {
        _phoneTools.set(null)
        refMap.clear()
        releaseDisplay()
        Log.d(TAG, "MCP 手机控制已清除")
    }

    private suspend fun runAction(block: suspend () -> Boolean): ActionResult = try {
        if (block()) ActionResult.Ok() else ActionResult.Failed("action_failed")
    } catch (e: Exception) {
        Log.e(TAG, "action failed", e)
        ActionResult.Failed(e.message ?: "action_error")
    }

    companion object {
        private const val TAG = "McpPhoneProvider"
    }
}
