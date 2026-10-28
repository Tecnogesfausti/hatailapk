package io.homeassistant.companion.android.hatail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.homeassistant.companion.android.common.R
import io.homeassistant.companion.android.common.compose.theme.HADimens
import io.homeassistant.companion.android.common.compose.theme.LocalHAColorScheme

@Composable
internal fun HatailHomeSwitcher(selected: Home, enabled: Boolean, onSelect: (Home) -> Unit) {
    Surface(color = LocalHAColorScheme.current.colorSurfaceLow) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = HADimens.SPACE4),
            horizontalArrangement = Arrangement.spacedBy(HADimens.SPACE3),
        ) {
            Home.entries.forEach { home ->
                FilterChip(
                    selected = selected == home,
                    onClick = { onSelect(home) },
                    enabled = enabled,
                    label = {
                        Text(
                            stringResource(
                                if (home ==
                                    Home.CASA
                                ) {
                                    R.string.hatail_casa
                                } else {
                                    R.string.hatail_sol
                                },
                            ),
                        )
                    },
                    leadingIcon = {
                        Icon(
                            painterResource(if (home == Home.CASA) R.drawable.hatail_home else R.drawable.hatail_sun),
                            contentDescription = null,
                        )
                    },
                )
            }
        }
    }
}
