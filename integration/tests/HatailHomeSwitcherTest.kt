package io.homeassistant.companion.android.hatail

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.homeassistant.companion.android.HiltComponentActivity
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
class HatailHomeSwitcherTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltComponentActivity>()

    @Test fun `switch homes directly from the header and identify the selected panel`() {
        var chosen: Home? = null
        compose.setContent { HAThemeForPreview { HatailHomeSwitcher(Home.SOL, true) { chosen = it } } }
        compose.onNodeWithText("Sol").assertIsSelected()
        compose.onNodeWithText("Casa").performClick()
        assertEquals(Home.CASA, chosen)
    }

    @Test fun `disable both selectors while validating a home`() {
        compose.setContent { HAThemeForPreview { HatailHomeSwitcher(Home.CASA, false) {} } }
        compose.onNodeWithText("Casa").assertIsNotEnabled()
        compose.onNodeWithText("Sol").assertIsNotEnabled()
    }
}
