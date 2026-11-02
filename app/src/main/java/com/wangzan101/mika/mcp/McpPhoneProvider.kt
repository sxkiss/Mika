package com.wangzan101.mika.mcp

import android.content.Context
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * MCP 手机控制工具提供者。
 *
 * 监听无障碍服务连接状态，就绪且 shell 可用时创建 [McpPhoneTools] 并交给 [McpRuntime]。
 * 使用主屏模式：微信等 App 在虚拟屏上不上报无障碍窗口，导致元素树为空。
 * 主屏模式下元素树完整，可直接用 ref 点击操作。
 */
class McpPhoneProvider(context: Context) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val packageManager = context.packageManager
    private val appContext = context.applicationContext

    private val _phoneTools = AtomicReference<McpPhoneTools?>(null)
    /** 最近一次快照的 ref -> 元素中心坐标(像素)。 */
    private val refMap = ConcurrentHashMap<String, Pair<Int, Int>>()

    /** 当前可用的手机控制 MCP 工具，未就绪时为 null。 */
    val phoneTools: McpPhoneTools? get() = _phoneTools.get()

    val isA11yReady: Boolean get() = ScreenAccessibility.instance.value != null

    /** 监听无障碍服务；就绪且 shell 可用时构建工具集（使用主屏模式）。 */
    fun startListening(helperHub: () -> HelperHub? = { null }) {
        scope.launch {
            val a11yFlow = ScreenAccessibility.instance
            val shellPolling = kotlinx.coroutines.flow.flow {
                while (true) {
                    emit(helperHub()?.shell?.value)
                    kotlinx.coroutines.delay(500L)
                }
            }
            combine(a11yFlow, shellPolling) { a11y, shell -> Pair(a11y, shell) }.collect { (a11y, shell) ->
                if (a11y != null && shell != null) {
                    _phoneTools.set(McpPhoneTools(createScreenPort(a11y, shell)) { getInstalledApps() })
                    Log.d(TAG, "MCP 手机控制已启用（主屏模式）")
                } else {
                    _phoneTools.set(null)
                    Log.w(TAG, "无障碍或壳未就绪，MCP 手机控制已停用")
                }
            }
        }
    }

    private fun getInstalledApps(): List<String> =
        packageManager.getInstalledPackages(0)
            .map { it.packageName }
            .filter { it != appContext.packageName }

    private fun createScreenPort(a11y: ScreenAccessibility, shell: IMikaShell): ScreenPort =
        object : ScreenPort {
            // 固定使用主屏：无障碍树完整，元素可正常读取
            private val displayId get() = Display.DEFAULT_DISPLAY
            private val width get() = android.content.res.Resources.getSystem().displayMetrics.widthPixels
            private val height get() = android.content.res.Resources.getSystem().displayMetrics.heightPixels

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
                when (val shot = a11y.takeMainScreenshot()) {
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
                shell.setClipText(text)
                kotlinx.coroutines.delay(120)
                shell.injectCtrlKey(displayId, android.view.KeyEvent.KEYCODE_V)
            }

            override suspend fun clearText(ref: String?, snapshotId: String?): ActionResult = runAction {
                shell.injectCtrlKey(displayId, android.view.KeyEvent.KEYCODE_A)
                kotlinx.coroutines.delay(60)
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

            override suspend fun waitStable(timeoutMs: Long): Boolean = true
            override suspend fun awaitChange(timeoutMs: Long): Boolean = false

            override suspend fun openApp(packageName: String, cold: Boolean): ActionResult = try {
                when (shell.startApp(Display.DEFAULT_DISPLAY, packageName)) {
                    0 -> ActionResult.Ok()
                    1 -> ActionResult.Failed("app_in_use_on_main_screen")
                    2 -> ActionResult.Failed("app_not_found: $packageName")
                    else -> ActionResult.Failed("launch_failed")
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
