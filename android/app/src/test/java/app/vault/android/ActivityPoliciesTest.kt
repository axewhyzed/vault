package app.vault.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityPoliciesTest {
    @Test fun launchIntentIsConsumedOnlyForTheFirstActivityCreation() {
        assertTrue(shouldHandleLaunchIntent(hasSavedInstanceState = false))
        assertFalse(shouldHandleLaunchIntent(hasSavedInstanceState = true))
    }

    @Test fun demoModeIsUnavailableInReleaseBuilds() {
        assertTrue(shouldApplyDemoMode(isDebugBuild = true, demoRequested = true))
        assertFalse(shouldApplyDemoMode(isDebugBuild = false, demoRequested = true))
        assertFalse(shouldApplyDemoMode(isDebugBuild = true, demoRequested = false))
    }
}
