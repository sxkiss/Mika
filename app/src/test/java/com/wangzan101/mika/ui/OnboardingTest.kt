package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingTest {
    private fun inputs(
        hasMain: Boolean = false, modelDone: Boolean = false, phoneNeeded: Boolean = true, phoneSeen: Boolean = false,
        modelSkipped: Boolean = false,
    ) = Onboarding.Inputs(hasMain, modelDone, phoneNeeded, phoneSeen, modelSkipped)

    @Test fun freshInstallStartsWithWelcomeThenModelThenPhone() {
        val i = inputs()
        assertEquals(OnboardingStep.Welcome, Onboarding.start(i))
        assertEquals(OnboardingStep.Model, Onboarding.next(OnboardingStep.Welcome, i))
        assertEquals(OnboardingStep.Phone, Onboarding.next(OnboardingStep.Model, i))
        assertNull(Onboarding.next(OnboardingStep.Phone, i))
    }

    @Test fun configuredModelSkipsFirstTwoSteps() {
        assertEquals(OnboardingStep.Phone, Onboarding.start(inputs(hasMain = true)))
        assertNull(Onboarding.start(inputs(hasMain = true, phoneSeen = true)))
        assertNull(Onboarding.start(inputs(hasMain = true, phoneNeeded = false)))
    }

    @Test fun modelSetupDoneOnceIsNotRepeatedEvenIfModelsWereRemoved() {
        assertEquals(OnboardingStep.Phone, Onboarding.start(inputs(modelDone = true)))
        assertNull(Onboarding.start(inputs(modelDone = true, phoneSeen = true)))
    }

    @Test fun phoneStepSkippedWhenReadyOrSeen() {
        assertNull(Onboarding.next(OnboardingStep.Model, inputs(phoneNeeded = false)))
        assertNull(Onboarding.next(OnboardingStep.Model, inputs(phoneSeen = true)))
    }

    @Test fun skippedModelSetupGoesStraightToPhone() {
        assertEquals(OnboardingStep.Phone, Onboarding.start(inputs(modelSkipped = true)))
        assertNull(Onboarding.start(inputs(modelSkipped = true, phoneSeen = true)))
        assertNull(Onboarding.start(inputs(modelSkipped = true, phoneNeeded = false)))
    }

    @Test fun skipStaysSkippedAfterRestartAndOnceModelConfigured() {
        // 跳过以后再打开 App 不该再弹欢迎/配置模型
        assertNull(Onboarding.start(inputs(modelSkipped = true, phoneSeen = true)))
        // 跳过之后又去设置里配了模型,同样不该再弹
        assertNull(Onboarding.start(inputs(hasMain = true, modelSkipped = true, phoneSeen = true)))
        assertEquals(OnboardingStep.Phone, Onboarding.start(inputs(modelDone = true, modelSkipped = true)))
    }

    @Test fun dotsFollowSteps() {
        assertEquals(listOf(0, 1, 2), OnboardingStep.entries.map(Onboarding::dot))
        assertEquals(3, Onboarding.DOTS)
    }
}
