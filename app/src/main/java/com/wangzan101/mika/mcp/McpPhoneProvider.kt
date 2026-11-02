package com.wangzan101.mika.mcp

import android.content.Context
import android.util.Log
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.display.LazyAgentDisplay
import com.wangzan101.mika.cap.phone.shell.HelperHub
import com.wangzan101.mika.shell.IMikaShell
import com.wangzan101.mika.core.mcp.McpPhoneTools
import com.wangzan101.mika.core.uistate.ScreenPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * MCP 手机控制工具提供者。
 *
 * 监听无障碍服务连接状态，就绪且 shell 可用时创建 [McpPhoneTools] 并交给 [McpRuntime]。
 *
 * 使用原版 Mika 的虚拟屏（小窗）模式：[AndroidScreen] + [LazyAgentDisplay]，
 * 虚拟屏在第一次 launch_app 时按需创建，画面由 Mika 的画中画小窗显示。
 *
 * 为什么不能用主屏：ShellService.startApp 第一行 `if (!ownDisplay(displayId)) return START_ERROR`
 * 只允许往 shell 自己建的虚拟屏上启动，主屏一律拒绝（防止打扰用户正在用的界面），
 * 所以主屏模式能读元素树但 launch_app 必然失败。
 */
class McpPhoneProvider(context: Context) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val packageManager = context.packageManager
    private val appContext = context.applicationContext

    private val _phoneTools = AtomicReference<McpPhoneTools?>(null)

    /** 按需创建的 Agent 虚拟屏（小窗模式）；第一次 launch_app 时才建。 */
    private var lazyDisplay: LazyAgentDisplay? = null

    /** 缓存已创建的 ScreenPort，避免 combine 每次发射都重建虚拟屏。 */
    private var screenPort: ScreenPort? = null

    /** 当前可用的手机控制 MCP 工具，未就绪时为 null。 */
    val phoneTools: McpPhoneTools? get() = _phoneTools.get()

    val isA11yReady: Boolean get() = ScreenAccessibility.instance.value != null

    /** 监听无障碍服务；就绪且 shell 可用时构建工具集（虚拟屏 / 小窗模式）。 */
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
                    // 仅在首次就绪时创建；复用已有实例，避免 combine 每 500ms 重建一块虚拟屏
                    val screen = screenPort ?: createScreenPort(a11y, shell).also { screenPort = it }
                    _phoneTools.set(McpPhoneTools(screen) { getInstalledApps() })
                    Log.d(TAG, "MCP 手机控制已启用（虚拟屏/小窗模式）")
                } else {
                    _phoneTools.set(null)
                    screenPort = null
                    releaseDisplay()
                    Log.w(TAG, "无障碍或壳未就绪，MCP 手机控制已停用")
                }
            }
        }
    }

    private fun releaseDisplay() {
        lazyDisplay?.let { d ->
            runCatching { d.current?.release() }.onFailure { e ->
                Log.w(TAG, "释放虚拟屏失败", e)
            }
        }
        lazyDisplay = null
    }

    private fun getInstalledApps(): List<String> =
        packageManager.getInstalledPackages(0)
            .map { it.packageName }
            .filter { it != appContext.packageName }

    private fun createScreenPort(a11y: ScreenAccessibility, shell: IMikaShell): ScreenPort {
        val metrics = appContext.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi = metrics.densityDpi

        // 按需创建虚拟屏（第一次 launch_app 时才建），失败时不抛出、留待下次重试
        lazyDisplay = LazyAgentDisplay(width, height) {
            try {
                AgentDisplay(shell, width, height, dpi)
            } catch (t: Throwable) {
                Log.w(TAG, "创建虚拟屏失败", t)
                throw t
            }
        }

        // 包一层 McpScreenPort：换算 0–999 坐标，并在微信这类读不出无障碍树的 App 上走 OCR 兜底
        return McpScreenPort(AndroidScreen(a11y, shell, lazyDisplay!!))
    }

    fun clear() {
        _phoneTools.set(null)
        screenPort = null
        releaseDisplay()
        Log.d(TAG, "MCP 手机控制已清除")
    }

    companion object {
        private const val TAG = "McpPhoneProvider"
    }
}
