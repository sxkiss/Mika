package com.wangzan101.mika.mcp

import android.content.Context
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
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
                    UiElement(
                        ref = "e${node.hashCode()}",
                        role = node.className?.toString()?.substringAfterLast('.') ?: "",
                        name = node.text?.toString()?.takeIf { it.isNotEmpty() } ?: "",
                        centerX = (node.boundsInScreen.left + node.boundsInScreen.right) / 2,
                        centerY = (node.boundsInScreen.top + node.boundsInScreen.bottom) / 2,
                        bounds = listOf(
                            node.boundsInScreen.left, node.boundsInScreen.top,
                            node.boundsInScreen.right, node.boundsInScreen.bottom
                        ),
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
            val (width, height) = getDisplaySize(a11y)
            return ScreenSnapshot(
                snapshotId = "mcp_${System.currentTimeMillis()}",
                packageName = null,
                elements = elements,
                width = width,
                height = height,
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
        
        override suspend fun screenshotPng(): ByteArray? = null
        
        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult = ActionResult.Ok
        
        override suspend fun tapXY(x: Int, y: Int): ActionResult = ActionResult.Ok
        
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult = ActionResult.Ok
        
        override suspend fun clearText(ref: String?, snapshotId: String?): ActionResult = ActionResult.Ok
        
        override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult = ActionResult.Ok
        
        override suspend fun back(): ActionResult = ActionResult.Ok
        
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
        
        private fun getDisplaySize(a11y: ScreenAccessibility): Pair<Int, Int> {
            val dm = android.content.res.Resources.getSystem().displayMetrics
            return Pair(dm.widthPixels, dm.heightPixels)
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
}
