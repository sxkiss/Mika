package com.wangzan101.mika.mcp

import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.VisibleTextLine
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

    override suspend fun snapshot(): ScreenSnapshot {
        val real = base.snapshot()
        // 无障碍树读得出东西就用它，这是最准的
        if (real.elements.isNotEmpty()) {
            ocrLock.withLock { ocrTargets.clear() }
            return real
        }

        // 读不出来：先看是不是压根没开 App / 屏是黑的，那就没什么可兜的
        val lines = runCatching { base.readVisibleText() }.getOrNull()
        if (lines.isNullOrEmpty()) {
            ocrLock.withLock { ocrTargets.clear() }
            return real
        }

        val gen = ocrSeq.incrementAndGet()
        val targets = HashMap<String, Pair<Int, Int>>()
        val kept = ArrayList<VisibleTextLine>(lines.size)
        val elements = ArrayList<UiElement>(lines.size)
        // 中英两套模型各认一遍，同一行字会回来两份（有时文字还略有出入），按框重叠去重，
        // 否则元素表里每行都出现两次，模型点哪一份都行、却白白翻倍
        for (line in lines) {
            if (kept.any { overlaps(it.bounds, line.bounds) }) continue
            kept += line
            val ref = "@o${kept.size}"
            val b = line.bounds
            val cx = ((b[0] + b[2]) / 2.0 * (real.width - 1) / 999.0).toInt()
            val cy = ((b[1] + b[3]) / 2.0 * (real.height - 1) / 999.0).toInt()
            targets[ref] = cx to cy
            elements += UiElement(
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
        return real.copy(elements = elements, snapshotId = "ocr_${real.snapshotId}")
    }

    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
        // OCR 兜底产出的 ref 无障碍树里没有，按记下的像素点直接点
        val at = ocrLock.withLock { ocrTargets[ref] ?: ocrTargets[normalizeOcrRef(ref)] }
        if (at != null) return base.tapXY(at.first, at.second)
        return base.tapRef(ref, snapshotId)
    }

    /**
     * 微信这类 App 上元素表来自 OCR，编号不在无障碍树的编号表里，`typeText` 按 ref 找框必然 ref_expired。
     * 这里退一步：先点那行字把输入框叫出来，再按当前焦点输入。
     */
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult {
        if (ref != null) {
            val at = ocrLock.withLock { ocrTargets[ref] ?: ocrTargets[normalizeOcrRef(ref)] }
            if (at != null) {
                val tapped = base.tapXY(at.first, at.second)
                if (tapped !is ActionResult.Ok) return tapped
                return base.typeText(null, null, text)
            }
        }
        return base.typeText(ref, snapshotId, text)
    }

    /** OCR 编号在快照里带 `@o` 前缀；外部回传时可能省掉 `@`，两种都认。 */
    private fun normalizeOcrRef(ref: String): String = if (ref.startsWith("@")) ref else "@$ref"

    /** MCP 传进来的是 0–999 归一化坐标，换成像素再交给端口。 */
    override suspend fun tapXY(x: Int, y: Int): ActionResult {
        // 用 peek 不用 snapshot：只想知道屏多大，不能顺手往编号表里塞一份新快照，
        // 否则每点一下都把模型手里的那份挤旧一层（编号表只留最近 4 份）
        val snap = base.peek()
        if (snap.width <= 1 || snap.height <= 1) return base.tapXY(x, y)
        return base.tapXY(x * (snap.width - 1) / 999, y * (snap.height - 1) / 999)
    }

    /** 同 [tapXY]：0–999 归一化换像素。 */
    override suspend fun typeAt(x: Int, y: Int, text: String): ActionResult {
        val snap = base.peek()
        if (snap.width <= 1 || snap.height <= 1) return base.typeAt(x, y, text)
        return base.typeAt(x * (snap.width - 1) / 999, y * (snap.height - 1) / 999, text)
    }

    override suspend fun clearAt(x: Int, y: Int): ActionResult {
        val snap = base.peek()
        if (snap.width <= 1 || snap.height <= 1) return base.clearAt(x, y)
        return base.clearAt(x * (snap.width - 1) / 999, y * (snap.height - 1) / 999)
    }

    /** 两个 0–999 的框是不是同一行字：纵向大部分重叠、横向也压得上。 */
    private fun overlaps(a: List<Int>, b: List<Int>): Boolean {
        if (a.size < 4 || b.size < 4) return false
        val top = maxOf(a[1], b[1])
        val bottom = minOf(a[3], b[3])
        val v = bottom - top
        if (v <= 0) return false
        val minH = minOf(a[3] - a[1], b[3] - b[1]).coerceAtLeast(1)
        if (v * 2 < minH) return false
        val left = maxOf(a[0], b[0])
        val right = minOf(a[2], b[2])
        val h = right - left
        if (h <= 0) return false
        val minW = minOf(a[2] - a[0], b[2] - b[0]).coerceAtLeast(1)
        return h * 2 >= minW
    }
}
