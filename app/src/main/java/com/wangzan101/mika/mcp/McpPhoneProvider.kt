package com.wangzan101.mika.mcp

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.a11y.ScreenReader
import com.wangzan101.mika.cap.phone.shell.HelperHub
import com.wangzan101.mika.core.mcp.McpPhoneTools
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * MCP 手机控制工具提供者。
 *
 * 监听无障碍服务连接状态，就绪且 shell 可用时创建 [McpPhoneTools] 并交给 [McpRuntime]。
 * MCP 直接操作「用户主屏」(Display.DEFAULT_DISPLAY)，不走任务的虚拟屏。
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

    /** 监听无障碍服务；就绪且 shell 可用时构建工具集。 */
    fun startListening(helperHub: () -> HelperHub? = { null }) {
        scope.launch {
            ScreenAccessibility.instance.filterNotNull().collect { a11y ->
                val shell = helperHub()?.shell?.value
                if (a11y != null && shell != null) {
                    _phoneTools.set(McpPhoneTools(createScreenPort(a11y, shell)) { getInstalledApps() })
                    Log.d(TAG, "MCP 手机控制已启用")
                } else {
                    Log.w(TAG, "无障碍服务就绪但 shell 未连接")
                }
            }
        }
    }

    private fun getInstalledApps(): List<String> =
        packageManager.getInstalledPackages(0)
            .map { it.packageName }
            .filter { it != appContext.packageName }

    private fun createScreenPort(a11y: ScreenAccessibility, shell: com.wangzan101.mika.shell.IMikaShell): ScreenPort =
        object : ScreenPort {
            private val displayId get() = android.view.Display.DEFAULT_DISPLAY

            override suspend fun snapshot(): ScreenSnapshot {
                val dm = android.content.res.Resources.getSystem().displayMetrics
                val windows = a11y.windowsOn(displayId)
                val read = ScreenReader.read(windows, dm.widthPixels, dm.heightPixels)
                refMap.clear()
                read.entries.forEach { e ->
                    refMap[e.element.ref] = Pair(e.element.centerX, e.element.centerY)
                }
                return ScreenSnapshot(
                    snapshotId = "mcp_${System.currentTimeMillis()}",
                    packageName = ScreenReader.topPackage(windows),
                    elements = read.entries.map { it.element },
                    width = dm.widthPixels,
                    height = dm.heightPixels,
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
                val dm = android.content.res.Resources.getSystem().displayMetrics
                shell.injectTap(displayId, x * dm.widthPixels / 999, y * dm.heightPixels / 999)
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
                val dm = android.content.res.Resources.getSystem().displayMetrics
                val cx = dm.widthPixels / 2f
                val cy = dm.heightPixels / 2f
                val dist = dm.heightPixels / 3f
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

            override suspend fun openApp(packageName: String, cold: Boolean): ActionResult = try {
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(intent)
                    ActionResult.Ok()
                } else {
                    ActionResult.Failed("app_not_found: $packageName")
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
