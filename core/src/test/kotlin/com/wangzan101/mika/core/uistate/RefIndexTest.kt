package com.wangzan101.mika.core.uistate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefIndexTest {
    // 快照里发出去的编号带 @e 前缀；MCP 工具说明写的是 e3，模型常照说明传。两种都必须认，
    // 否则「刚 observe 过就 ref_expired」——曾导致按 ref 点击整条路在 MCP 上不可用。
    @Test fun acceptsPrefixedAndBareForms() {
        assertEquals(0, refIndex("@e1"))
        assertEquals(0, refIndex("e1"))
        assertEquals(0, refIndex("1"))
        assertEquals(23, refIndex("@e24"))
        assertEquals(23, refIndex("e24"))
        assertEquals(23, refIndex("24"))
    }

    @Test fun rejectsGarbage() {
        assertNull(refIndex(""))
        assertNull(refIndex("@"))
        assertNull(refIndex("e"))
        assertNull(refIndex("abc"))
        assertNull(refIndex("@e0"))  // 编号从 1 起
        assertNull(refIndex("0"))
        assertNull(refIndex("@o3"))  // OCR 编号不走这条路
    }
}
