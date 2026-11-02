package com.wangzan101.mika.ui

import android.content.Context

/** 首次上手的三步。 */
enum class OnboardingStep { Welcome, Model, Phone }

/**
 * 首次打开时走哪几步(纯函数,便于测试)。纯 MCP 模式:跳过模型配置,只保留手机操作引导。
 */
object Onboarding {
    data class Inputs(
        /** 系统够新且手机操作还没就绪。 */
        val phoneNeeded: Boolean,
        val phoneGuideSeen: Boolean,
    )

    fun start(i: Inputs): OnboardingStep? = when {
        i.phoneNeeded && !i.phoneGuideSeen -> OnboardingStep.Phone
        else -> null
    }

    /** [step] 做完后的下一步;没有了为 null(进对话)。 */
    fun next(step: OnboardingStep, i: Inputs): OnboardingStep? = null

    /** 底部分页点:三步里的第几步。 */
    fun dot(step: OnboardingStep): Int = step.ordinal
    const val DOTS = 3
}

/** 上手流程里「配置模型」做完或跳过了没有;存在本机。 */
class OnboardingState(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun modelSetupDone(): Boolean = prefs.getBoolean(MODEL_DONE, false)

    fun markModelSetupDone() {
        prefs.edit().putBoolean(MODEL_DONE, true).apply()
    }

    /** 欢迎页点了「稍后配置」:记下来,下次打开不再强制走配置模型。 */
    fun modelSetupSkipped(): Boolean = prefs.getBoolean(MODEL_SKIPPED, false)

    fun markModelSetupSkipped() {
        prefs.edit().putBoolean(MODEL_SKIPPED, true).apply()
    }

    private companion object {
        const val PREFS = "onboarding"
        const val MODEL_DONE = "model_setup_done"
        const val MODEL_SKIPPED = "model_setup_skipped"
    }
}
