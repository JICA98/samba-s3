package com.zenithblue.sambas3.ui.onboarding

import com.zenithblue.sambas3.utils.GeneralSettings

const val ONBOARDING_ROUTE = "onboarding"
// The explicit permissions review is a separate page before device setup.
const val ONBOARDING_PAGE_COUNT = 7

enum class OnboardingEntry {
    FirstRun,
    Replay,
}

object OnboardingPrefs {
    const val KEY_COMPLETED = "has_completed_onboarding"
    const val KEY_PRIVACY_POLICY_ACCEPTED = "has_accepted_privacy_policy"

    fun isCompleted(): Boolean = GeneralSettings[KEY_COMPLETED] as? Boolean ?: false

    fun markCompleted() {
        GeneralSettings.setValue(KEY_COMPLETED, true)
    }

    fun isPrivacyPolicyAccepted(): Boolean {
        val accepted = GeneralSettings[KEY_PRIVACY_POLICY_ACCEPTED] as? Boolean
        if (accepted != null) return accepted
        return isCompleted()
    }

    fun markPrivacyPolicyAccepted() {
        GeneralSettings.setValue(KEY_PRIVACY_POLICY_ACCEPTED, true)
    }
}
