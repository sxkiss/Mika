package com.wangzan101.mika.mcp

import com.wangzan101.mika.core.mcp.McpPhoneTools
import com.wangzan101.mika.core.uistate.ScreenPort

/**
 * MCP手机控制工具提供者。
 * 
 * 当无障碍服务就绪时返回真正的McpPhoneTools，否则返回null。
 * 调用方应轮询此属性或在适当时机获取。
 */
class McpPhoneProvider {
    private var _phoneTools: McpPhoneTools? = null
    
    /** 当前可用的手机控制MCP工具，无障碍服务未就绪时为null */
    val phoneTools: McpPhoneTools? get() = _phoneTools
    
    /**
     * 设置屏幕端口以激活MCP手机控制。
     * @param screen 屏幕端口（来自无障碍服务）
     * @param listApps 获取已安装应用列表的函数
     */
    fun setPhoneTools(screen: ScreenPort, listApps: () -> List<String> = emptyList) {
        _phoneTools = McpPhoneTools(screen, listApps)
    }
    
    /** 清除屏幕端口（当无障碍服务断开时调用） */
    fun clear() {
        _phoneTools = null
    }
}
