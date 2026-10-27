@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.homeassistant.companion.android.hatail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import io.homeassistant.companion.android.common.R
import io.homeassistant.companion.android.common.compose.composable.HAAccentButton
import io.homeassistant.companion.android.common.compose.composable.HAModalBottomSheet
import io.homeassistant.companion.android.common.compose.composable.HAPlainButton
import io.homeassistant.companion.android.common.compose.composable.consumeSheetScrollFling
import io.homeassistant.companion.android.common.compose.composable.rememberHAModalBottomSheetState
import io.homeassistant.companion.android.common.compose.theme.HADimens
import io.homeassistant.companion.android.common.compose.theme.HATextStyle
import io.homeassistant.companion.android.common.compose.theme.HAThemeForPreview
import io.homeassistant.companion.android.common.compose.theme.LocalHAColorScheme
import java.text.DateFormat
import java.util.Date

@Composable
internal fun HatailVersionsHeader(state: HatailVersionsState, onOpen: () -> Unit) {
    Surface(color = LocalHAColorScheme.current.colorSurfaceLow) {
        Column(
            Modifier.fillMaxWidth().statusBarsPadding().clickable(role = Role.Button, onClick = onOpen)
                .padding(horizontal = HADimens.SPACE4, vertical = HADimens.SPACE2),
        ) {
            Text(
                stringResource(
                    if (state.hasNewerRelease) R.string.hatail_versions_available else R.string.hatail_versions_title,
                ),
                style = HATextStyle.BodyMedium,
                textAlign = TextAlign.Start,
            )
            val companion = state.components.firstOrNull {
                it.installed.repository == "home-assistant/android"
            }?.installed?.version.orEmpty()
            val tailscale = state.components.firstOrNull {
                it.installed.repository == "tailscale/tailscale"
            }?.installed?.version.orEmpty()
            Text(stringResource(R.string.hatail_versions_summary, companion, tailscale), style = HATextStyle.BodyMedium)
        }
    }
}

@Composable
internal fun HatailVersionsSheet(state: HatailVersionsState, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    HAModalBottomSheet(
        bottomSheetState = rememberHAModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onDismiss,
    ) {
        Column(
            Modifier.fillMaxWidth().consumeSheetScrollFling().verticalScroll(
                rememberScrollState(),
            ).padding(HADimens.SPACE4),
            verticalArrangement = Arrangement.spacedBy(HADimens.SPACE4),
        ) {
            Text(stringResource(R.string.hatail_versions_title), style = HATextStyle.HeadlineMedium)
            Text(stringResource(R.string.hatail_versions_build, state.revision), style = HATextStyle.BodyMedium)
            Text(stringResource(R.string.hatail_versions_explanation), style = HATextStyle.Body)
            Text(stringResource(R.string.hatail_versions_schedule), style = HATextStyle.BodyMedium)
            if (state.attemptedAtEpochMs > 0) {
                Text(
                    stringResource(R.string.hatail_versions_attempt, formatVersionCheckTime(state.attemptedAtEpochMs)),
                    style = HATextStyle.BodyMedium,
                )
            }
            if (state.throttled) {
                Text(
                    stringResource(R.string.hatail_versions_throttled),
                    style = HATextStyle.BodyMedium,
                )
            }
            HAAccentButton(
                stringResource(
                    if (state.refreshing) R.string.hatail_versions_checking else R.string.hatail_versions_refresh,
                ),
                onRefresh,
                enabled = !state.refreshing,
            )
            state.components.forEach { ComponentVersionCard(it) }
            HAPlainButton(stringResource(R.string.hatail_versions_close), onDismiss)
        }
    }
}

@Composable
private fun ComponentVersionCard(component: ComponentVersionState) {
    val uriHandler = LocalUriHandler.current
    val title = when (component.installed.repository) {
        "home-assistant/android" -> R.string.hatail_versions_companion
        "tailscale/tailscale-android" -> R.string.hatail_versions_tail_android
        else -> R.string.hatail_versions_tail_engine
    }
    Column(verticalArrangement = Arrangement.spacedBy(HADimens.SPACE2)) {
        Text(stringResource(title), style = HATextStyle.HeadlineMedium)
        Text(stringResource(R.string.hatail_versions_installed, component.installed.version), style = HATextStyle.Body)
        HAPlainButton(
            stringResource(R.string.hatail_versions_source, component.installed.commit.take(12)),
            { uriHandler.openUri(component.installed.sourceUrl) },
        )
        if (component.failed) Text(stringResource(R.string.hatail_versions_error), style = HATextStyle.Body)
        val release = component.release
        if (release == null) {
            Text(stringResource(R.string.hatail_versions_pending), style = HATextStyle.BodyMedium)
        } else {
            Text(stringResource(R.string.hatail_versions_latest, release.tag), style = HATextStyle.Body)
            if (release.publishedAt.isNotBlank()) {
                Text(
                    stringResource(R.string.hatail_versions_published, release.publishedAt.substringBefore('T')),
                    style = HATextStyle.BodyMedium,
                )
            }
            Text(stringResource(relationMessage(release.relation)), style = HATextStyle.Body)
            Text(
                stringResource(R.string.hatail_versions_checked, formatVersionCheckTime(component.checkedAtEpochMs)),
                style = HATextStyle.BodyMedium,
            )
            if (release.notes.isNotBlank()) {
                Text(
                    release.notes,
                    style = HATextStyle.BodyMedium,
                    textAlign = TextAlign.Start,
                )
            }
            HAAccentButton(
                stringResource(R.string.hatail_versions_notes),
                { uriHandler.openUri(release.releaseUrl(component.installed.repository)) },
            )
        }
    }
}

private fun relationMessage(relation: ReleaseRelation): Int = when (relation) {
    ReleaseRelation.Current -> R.string.hatail_versions_current
    ReleaseRelation.Newer -> R.string.hatail_versions_newer
    ReleaseRelation.Older -> R.string.hatail_versions_older
    ReleaseRelation.Diverged -> R.string.hatail_versions_diverged
    ReleaseRelation.Unknown -> R.string.hatail_versions_unknown
}

private fun formatVersionCheckTime(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))

@Preview
@Composable
private fun HatailVersionsHeaderPreview() {
    HAThemeForPreview { HatailVersionsHeader(HatailVersionsState(), {}) }
}
