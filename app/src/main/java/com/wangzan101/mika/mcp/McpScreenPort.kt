package com.wangzan101.mika.mcp

import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * 把 [AndroidScreen] 适配给 MCP 的 phone_* 工具用。
 *
 * 两件事：
 *
 * 1. **坐标换算**。MCP 协议里 phone_tap 的 x/y 是 0–999 归一化坐标（见 McpPhoneTools 的工具说明），
 *    而 [ScreenPort.tapXY] 要的是屏幕像素。直接把 0–999 当像素传过去，点 500,500 会落在左上角附近
 *    而不是屏幕中心。这里按当前屏尺寸换算回像素。
 *
 * 2. **OCR 兜底**。微信等 App 在 Mika 的虚拟屏上画面正常、但完全不向无障碍服务注册窗口
 *    （windows 为空），元素表因此恒为 0，模型拿不到任何可点的东西。这类 App 上退一步用
 *    Mika 自带的 OCR 认屏上的文字，把每行字当成一个可点元素交出去。
 *    OCR 的 bounds 与 UiElement.bounds 同为 0–999 归一化坐标，centerX/centerY 仍是像素。
 */
class McpScreenPort(
    private val base: ScreenPort,
) : ScreenPort by base {

    /** OCR 兜底产出的 ref → 像素坐标。每次 snapshot 重建，与模型手里的 ref 一一对应。 */
    private val ocrTargets = HashMap<String, Pair<Int, Int>>()
    private val ocrLock = Mutex()
    private val ocrSeq = AtomicInteger()

    /** 上一次 snapshot 是否走的 OCR 兜底（此时无障碍树读不出任何元素）。 */
    @Volatile
    private var lastWasOcr = false

    override suspend fun snapshot(): ScreenSnapshot {
        val real = base.snapshot()
        // 无障碍树读得出东西就用它，这是最准的
        if (real.elements.isNotEmpty()) {
            ocrLock.withLock { ocrTargets.clear() }
            lastWasOcr = false
            return real
        }

        // 读不出来：先看是不是压根没开 App / 屏是黑的，那就没什么可兜的
        val lines = runCatching { base.readVisibleText() }.getOrNull()
        if (lines.isNullOrEmpty()) {
            ocrLock.withLock { ocrTargets.clear() }
            lastWasOcr = false
            return real
        }

        val gen = ocrSeq.incrementAndGet()
        val targets = HashMap<String, Pair<Int, Int>>()
        val elements = lines.mapIndexed { i, line ->
            val ref = "@o${i + 1}"
            val b = line.bounds
            val cx = ((b[0] + b[2]) / 2.0 * (real.width - 1) / 999.0).toInt()
            val cy = ((b[1] + b[3]) / 2.0 * (real.height - 1) / 999.0).toInt()
            targets[ref] = cx to cy
            UiElement(
                ref = ref,
                role = "Text",
                name = line.text,
                centerX = cx,
                centerY = cy,
                bounds = b,
                resourceId = null,
                clickable = true,
                editable = false,
                scrollable = false,
            )
        }
        ocrLock.withLock {
            if (gen == ocrSeq.get()) {
                ocrTargets.clear()
                ocrTargets.putAll(targets)
            }
        }
        lastWasOcr = true
        return real.copy(elements = elements, snapshotId = "ocr_${real.snapshotId}")
    }

    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
        // OCR 兜底产出的 ref 无障碍树里没有，按记下的像素点直接点
        val at = ocrLock.withLock { ocrTargets[ref] }
        if (at != null) return base.tapXY(at.first, at.second)
        return base.tapRef(ref, snapshotId)
    }

    /** MCP 传进来的是 0–999 归一化坐标，换成像素再交给端口。 */
    override suspend fun tapXY(x: Int, y: Int): ActionResult {
        val snap = base.snapshot()
        if (snap.width <= 1 || snap.height <= 1) return base.tapXY(x, y)
        return base.tapXY(x * (snap.width - 1) / 999, y * (snap.height - 1) / 999)
    }

    /** 同 [tapXY]：0–999 归一化换像素。 */
    override suspend fun typeAt(x: Int, y: Int, text: String): ActionResult {
        val snap = base.snapshot()
        if (snap.width <= 1 || snap.height <= 1) return base.typeAt(x, y, text)
        return base.typeAt(x * (snap.width - 1) / 999, y * (snap.height - 1) / 999, text)
    }

    override suspend fun clearAt(x: Int, y: Int): ActionResult {
        val snap = base.snapshot()
        if (snap.width <= 1 || snap.height <= 1) return base.clearAt(x, y)
        return base.clearAt(x * (snap.width - 1) / 999, y * (snap.height - 1) / 999)
    }
}
