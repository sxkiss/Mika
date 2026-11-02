package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingTest {
    private fun inputs(phoneNeeded: Boolean = true, phoneSeen: Boolean = false) =
        Onboarding.Inputs(phoneNeeded, phoneSeen)

    @Test fun freshInstallGoesStraightToPhoneNoModelStep() {
        assertEquals(OnboardingStep.Phone, Onboarding.start(inputs()))
        assertNull(Onboarding.next(OnboardingStep.Phone, inputs()))
    }

    @Test fun phoneStepSkippedWhenReadyOrSeen() {
        assertNull(Onboarding.start(inputs(phoneSeen = true)))
        assertNull(Onboarding.start(inputs(phoneNeeded = false)))
    }

    @Test fun dotsFollowSteps() {
        assertEquals(listOf(0, 1, 2), OnboardingStep.entries.map(Onboarding::dot))
        assertEquals(3, Onboarding.DOTS)
    }
}
