package com.wangzan101.mika.ui

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.i18n.ResFileTexts
import com.wangzan101.mika.i18n.UiText
import com.wangzan101.mika.about.CheckStatus
import com.wangzan101.mika.about.ReleaseInfo
import com.wangzan101.mika.about.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutCopyTest {
    @get:Rule val texts = ChineseTexts()

    private val r020 = ReleaseInfo("0.2.0", "Mika 0.2.0", "- 新", "2026-11-05", "https://github.com/sxkiss/Mika/releases/tag/v0.2.0", null)
    private val r010 = r020.copy(version = "0.1.0")

    @Test
    fun updateShowsDotsEverywhere() {
        val s = UpdateState(latest = r020, update = r020)
        assertTrue(AboutCopy.drawerDot(s))
        assertEquals(AboutCopy.CheckValue("0.2.0 可更新", dot = true, highlight = true), AboutCopy.checkValue(s))
        assertEquals(AboutCopy.CheckValue("0.2.0 可更新", dot = true, highlight = true), AboutCopy.settingsValue(s.update?.version, "0.1.0"))
    }

    @Test
    fun noUpdateNoDots() {
        val s = UpdateState(latest = r010)
        assertFalse(AboutCopy.drawerDot(s))
        assertEquals(AboutCopy.CheckValue(AboutCopy.UP_TO_DATE), AboutCopy.checkValue(s))
        assertEquals(AboutCopy.CheckValue("0.1.0"), AboutCopy.settingsValue(null, "0.1.0"))
        assertFalse(AboutCopy.drawerDot(UpdateState()))
    }

    @Test
    fun checkRowStates() {
        assertEquals(AboutCopy.CheckValue(null), AboutCopy.checkValue(UpdateState()))
        assertTrue(AboutCopy.checkValue(UpdateState(status = CheckStatus.Checking)).spinner)
        assertEquals(AboutCopy.CheckValue(AboutCopy.OFFLINE), AboutCopy.checkValue(UpdateState(status = CheckStatus.Failed)))
        // 连不上但早先查到过新版:照样提示有新版
        assertTrue(AboutCopy.checkValue(UpdateState(latest = r020, update = r020, status = CheckStatus.Failed)).dot)
    }

    @Test
    fun updateSheetSubtitle() {
        assertEquals("当前 0.1.0 Beta · 2026-11-05 发布", AboutCopy.updateSub("0.1.0", beta = true, date = "2026-11-05"))
        assertEquals("当前 0.1.0", AboutCopy.updateSub("0.1.0", beta = false, date = null))
    }

    /** 联系作者面板:中文界面微信码在前;英文界面 X 群聊在前。 */
    @Test
    fun xGroupFirstOnlyInEnglish() {
        assertFalse(AboutCopy.X_GROUP_FIRST)
        UiText.source = ResFileTexts.traditional
        assertFalse(AboutCopy.X_GROUP_FIRST)
        UiText.source = ResFileTexts.english
        assertTrue(AboutCopy.X_GROUP_FIRST)
    }
}
