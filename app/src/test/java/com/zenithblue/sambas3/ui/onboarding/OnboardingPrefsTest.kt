package com.zenithblue.sambas3.ui.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zenithblue.sambas3.utils.GeneralSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnboardingPrefsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        GeneralSettings.init(context)
        GeneralSettings.setValue(OnboardingPrefs.KEY_COMPLETED, null)
        GeneralSettings.setValue(OnboardingPrefs.KEY_PRIVACY_POLICY_ACCEPTED, null)
    }

    @Test
    fun freshInstallRequiresPrivacyPolicy() {
        assertFalse(OnboardingPrefs.isCompleted())
        assertFalse(OnboardingPrefs.isPrivacyPolicyAccepted())
    }

    @Test
    fun markPrivacyPolicyAcceptedUpdatesState() {
        OnboardingPrefs.markPrivacyPolicyAccepted()
        assertTrue(OnboardingPrefs.isPrivacyPolicyAccepted())
        assertFalse(OnboardingPrefs.isCompleted())
    }

    @Test
    fun legacyCompletedInstallTreatsPrivacyPolicyAsAccepted() {
        OnboardingPrefs.markCompleted()
        assertTrue(OnboardingPrefs.isCompleted())
        assertTrue(OnboardingPrefs.isPrivacyPolicyAccepted())
    }
}
