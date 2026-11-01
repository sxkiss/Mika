package com.wangzan101.mika.mcp

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.shell.HelperHub
import com.wangzan101.mika.core.mcp.McpPhoneTools
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.UiWindowTree
import com.wangzan101.mika.core.uistate.ActionResult
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
 * MCP手机控制工具提供者。
 *
 * 监听无障碍服务连接状态，当无障碍服务就绪时自动创建McpPhoneTools并注入到McpRuntime。
 */
class McpPhoneProvider(context: Context) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val packageManager = context.packageManager
    private val appContext = context.applicationContext
    
    private val _phoneTools = AtomicReference<McpPhoneTools?>(null)
    
    /** 当前可用的手机控制MCP工具，无障碍服务未就绪时为null */
    val phoneTools: McpPhoneTools? get() = _phoneTools.get()
    
    /** 无障碍服务是否已连接 */
    val isA11yReady: Boolean get() = ScreenAccessibility.instance.value != null

    /** 快照元素引用到中心坐标的映射，按snapshotId缓存 */
    private val refMapCache = ConcurrentHashMap<String, MutableMap<String, Pair<Int, Int>>>()
    
    /** 启动监听，当无障碍服务连接时自动创建McpPhoneTools */
    fun startListening(helperHub: () -> HelperHub? = { null }) {
        scope.launch {
            ScreenAccessibility.instance.filterNotNull().collect { a11y ->
                val hub = helperHub()
                val shell = hub?.shell?.value
                if (a11y != null && shell != null) {
                    val screen = createScreenPort(a11y, shell)
                    val listApps = { getInstalledApps() }
                    _phoneTools.set(McpPhoneTools(screen, listApps))
                    Log.d(TAG, "MCP手机控制已启用")
                } else {
                    Log.w(TAG, "无障碍服务就绪但shell未连接")
                }
            }
        }
    }
    
    private fun createScreenPort(a11y: ScreenAccessibility, shell: com.wangzan101.mika.shell.IMikaShell): ScreenPort = object : ScreenPort {
        override suspend fun snapshot(): ScreenSnapshot {
            val displayId = android.view.Display.DEFAULT_DISPLAY
            val windows = a11y.windowsOn(displayId)
            val elements = windows.flatMap { window ->
                window.root?.children?.map { node ->
                    val rect = android.graphics.Rect()
                    node.getBoundsInScreen(rect)
                    val ref = "e${node.hashCode()}"
                    // 缓存ref->center映射，供tapRef使用
                    runCatching {
                        refMapCache.computeIfAbsent("main") { ConcurrentHashMap() }[ref] = Pair((rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2)
                    }.getOrNull()
                    UiElement(
                        ref = ref,
                        role = node.className?.toString()?.substringAfterLast('.') ?: "",
                        name = node.text?.toString()?.takeIf { it.isNotEmpty() } ?: "",
                        centerX = (rect.left + rect.right) / 2,
                        centerY = (rect.top + rect.bottom) / 2,
                        bounds = listOf(rect.left, rect.top, rect.right, rect.bottom),
                        resourceId = node.viewIdResourceName,
                        clickable = node.isClickable,
                        editable = node.isEditable,
                        scrollable = node.isScrollable,
                        hint = node.isPassword,
                        focused = node.isFocused,
                        secret = node.isPassword,
                    )
                } ?: emptyList()
            }
            val dm = android.content.res.Resources.getSystem().displayMetrics
            val snapshotId = "mcp_${System.currentTimeMillis()}"
            return ScreenSnapshot(
                snapshotId = snapshotId,
                packageName = null,
                elements = elements,
                width = dm.widthPixels,
                height = dm.heightPixels,
                windows = windows.map { w ->
                    UiWindowTree(
                        id = w.windowId,
                        type = w.type.toString(),
                        layer = w.layer,
                        title = w.title?.toString(),
                        packageName = w.root?.packageName?.toString(),
                        active = w.isActive,
                        focused = w.isFocused,
                        nodes = emptyList(),
                    )
                }
            )
        }

        override suspend fun screenshotPng(): ByteArray? = try {
            when (val shot = a11y.takeMainScreenshot()) {
                is ScreenAccessibility.MainShot.Ok -> {
                    val stream = ByteArrayOutputStream()
                    shot.bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
                    shot.bitmap.recycle()
                    stream.toByteArray()
                }
                is ScreenAccessibility.MainShot.Secure -> {
                    Log.w(TAG, "screenshot blocked by secure window")
                    null
                }
                is ScreenAccessibility.MainShot.Failed -> {
                    Log.e(TAG, "screenshot failed with code: ${shot.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "screenshot error", e)
            null
        }

        /** MCP 直接控制用户主屏(不走任务虚拟屏):坐标即屏幕坐标。 */
        private val displayId get() = android.view.Display.DEFAULT_DISPLAY

        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
            // 按snapshotId查找缓存的ref映射
            val mapKey = snapshotId.takeIf { it.isNotEmpty() } ?: "main"
            val coords = refMapCache[mapKey]?.get(ref)
                ?: refMapCache["main"]?.get(ref) // 回退到最新的
            return if (coords != null) {
                runAction {
                    val dm = android.content.res.Resources.getSystem().displayMetrics
                    shell.injectTap(displayId, coords.first, coords.second)
                }
            } else {
                Log.w(TAG, "tap_ref: ref not found: $ref (snapshotId=$snapshotId)")
                ActionResult.Failed("ref_expired or not found: $ref")
            }
        }

        override suspend fun tapXY(x: Int, y: Int): ActionResult = runAction {
            // 屏幕坐标换算:x/y 是 0-999 归一化的(McpPhoneTools 约定),这里换回像素
            val dm = android.content.res.Resources.getSystem().displayMetrics
            shell.injectTap(displayId, x * dm.widthPixels / 999, y * dm.heightPixels / 999)
        }

        override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult = runAction {
            // shell 没有 injectText:走剪贴板粘贴(setClipText 以 shell 身份设置,injectCtrlKey 只放行 A/V)
            shell.setClipText(text)
            delay(100)
            shell.injectCtrlKey(displayId, android.view.KeyEvent.KEYCODE_V)
        }

        override suspend fun clearText(ref: String?, snapshotId: String?): ActionResult = runAction {
            // 全选删除
            shell.injectCtrlKey(displayId, android.view.KeyEvent.KEYCODE_A)
            delay(50)
            shell.injectKey(displayId, android.view.KeyEvent.KEYCODE_DEL)
        }

        override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult = runAction {
            val dm = android.content.res.Resources.getSystem().displayMetrics
            val cx = dm.widthPixels / 2f
            val dist = dm.heightPixels / 3f
            val cy = dm.heightPixels / 2f
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
        
        override suspend fun openApp(packageName: String, cold: Boolean): ActionResult {
            return try {
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    appContext.startActivity(intent)
                    ActionResult.Ok
                } else {
                    ActionResult.Failed("App not found: $packageName")
                }
            } catch (e: Exception) {
                ActionResult.Failed(e.message ?: "Unknown error")
            }
        }
        
        override suspend fun listApps(): List<Pair<String, String>> =
            getInstalledApps().map { pkg ->
                val info = packageManager.getPackageInfo(pkg, 0)
                val label = info.applicationInfo.loadLabel(packageManager).toString()
                Pair(pkg, label)
            }
        
        private fun getInstalledApps(): List<String> =
            packageManager.getInstalledPackages(0)
                .map { it.packageName }
                .filter { !it.contains("com.wangzan101.mika") }
    }
    
    /** 清除屏幕端口（当无障碍服务断开时调用） */
    fun clear() {
        _phoneTools.set(null)
        Log.d(TAG, "MCP手机控制已清除")
    }
    
    companion object {
        private const val TAG = "McpPhoneProvider"
    }

    private suspend fun runAction(block: suspend () -> Boolean): ActionResult {
        return try {
            val ok = block()
            if (ok) ActionResult.Ok else ActionResult.Failed("action failed")
        } catch (e: Exception) {
            Log.e(TAG, "action failed", e)
            ActionResult.Failed(e.message ?: "action error")
        }
    }
}
