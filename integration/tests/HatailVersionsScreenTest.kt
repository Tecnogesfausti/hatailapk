package io.homeassistant.companion.android.hatail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.homeassistant.companion.android.HiltComponentActivity
import io.homeassistant.companion.android.common.R
import io.homeassistant.companion.android.common.compose.theme.HAThemeForPreview
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
@HiltAndroidTest
class HatailVersionsScreenTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltComponentActivity>()

    @Test fun `Given embedded versions when tapping header then open details without leaving home`() {
        var opened = 0
        val state = HatailVersionsState(
            components = listOf(
                ComponentVersionState(InstalledComponent("home-assistant/android", "2026.10-test", "a".repeat(40))),
                ComponentVersionState(InstalledComponent("tailscale/tailscale", "1.105-test", "b".repeat(40))),
            ),
        )
        compose.setContent { HAThemeForPreview { HatailVersionsHeader(state) { opened++ } } }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.hatail_versions_title)).performClick()
        assertEquals(1, opened)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.hatail_history_button)).assertIsDisplayed()
    }

    @Test fun `Given version details when refreshing and closing then dispatch the expected actions`() {
        var refreshed = 0
        var closed = 0
        compose.setContent { HAThemeForPreview { HatailVersionsSheet(HatailVersionsState(), { refreshed++ }, { closed++ }) } }
        compose.onNodeWithText(compose.activity.getString(R.string.hatail_versions_refresh)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.hatail_versions_close)).performScrollTo().performClick()
        assertEquals(1, refreshed)
        assertEquals(1, closed)
    }
}
