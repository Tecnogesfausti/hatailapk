package io.homeassistant.companion.android.hatail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
class HatailHistoryScreenTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltComponentActivity>()

    @Test fun `Given no listener permission when opening history then offer explicit Android settings`() {
        var requested = 0
        compose.setContent {
            HAThemeForPreview {
                HatailHistorySheet(HatailHistoryState(), {}, { null }, { requested++ }, {}, {})
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.hatail_history_enable)).performClick()
        assertEquals(1, requested)
        compose.onNodeWithText(compose.activity.getString(R.string.hatail_history_empty)).assertIsDisplayed()
    }

    @Test fun `Given listener permission when filtering and closing then preserve the expected callbacks`() {
        var selected: HistoryFilter = HistoryFilter.All
        var closed = 0
        compose.setContent {
            HAThemeForPreview {
                HatailHistorySheet(HatailHistoryState(accessGranted = true), { selected = it }, { null }, {}, {}, { closed++ })
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.hatail_history_official)).performClick()
        assertEquals(HistoryFilter.Official, selected)
        compose.onNodeWithText(compose.activity.getString(R.string.hatail_versions_close)).performClick()
        assertEquals(1, closed)
    }
}
