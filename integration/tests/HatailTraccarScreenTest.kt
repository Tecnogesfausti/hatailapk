package io.homeassistant.companion.android.hatail

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.homeassistant.companion.android.HiltComponentActivity
import io.homeassistant.companion.android.common.R
import io.homeassistant.companion.android.common.compose.theme.HAThemeForPreview
import io.mockk.mockk
import io.mockk.verify
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
@HiltAndroidTest
class HatailTraccarScreenTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltComponentActivity>()
    private val model = mockk<HatailTraccarViewModel>(relaxed = true)
    private val config = TraccarConfig("http://100.64.0.10:5055/", "test-device")

    @Test fun `device identifier is editable but unsaved changes cannot start tracking`() {
        compose.setContent {
            HAThemeForPreview {
                HatailTraccarSheet(TraccarState(config), TraccarPermissions(true, true, true, true), "", model) {}
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.hatail_traccar_id)).performTextReplacement("new-device")
        compose.onNodeWithText(compose.activity.getString(R.string.hatail_traccar_start)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.hatail_traccar_save)).performScrollTo().performClick()
        verify { model.save(config.url, "new-device", "60", "25", "100") }
    }

    @Test fun `active tracking must be stopped before editing its destination`() {
        compose.setContent {
            HAThemeForPreview {
                HatailTraccarSheet(TraccarState(config.copy(enabled = true), running = true), TraccarPermissions(true, true, true, true), "", model) {}
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.hatail_traccar_id)).assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.hatail_traccar_stop)).performScrollTo().performClick()
        verify { model.stop() }
    }
}
