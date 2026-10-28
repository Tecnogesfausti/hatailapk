@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.homeassistant.companion.android.hatail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import java.text.DateFormat
import java.util.Date

@Composable
internal fun HatailVersionsHeader(state: HatailVersionsState, onHistory: () -> Unit = {}, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onOpen) {
            BadgedBox(badge = { if (state.hasNewerRelease) Badge() }) {
                Icon(
                    painterResource(R.drawable.hatail_versions),
                    contentDescription = stringResource(
                        if (state.hasNewerRelease) {
                        R.string.hatail_versions_available
                    } else {
                        R.string.hatail_versions_title
                    },
                    ),
                )
            }
        }
        IconButton(onClick = onHistory) {
            Icon(
                painterResource(R.drawable.hatail_history),
                contentDescription = stringResource(R.string.hatail_history_button),
            )
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
            Text(stringResource(R.string.hatail_versions_heading), style = HATextStyle.HeadlineMedium)
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
            Text(stringResource(R.string.hatail_versions_core_note), style = HATextStyle.BodyMedium)
            Text(stringResource(R.string.hatail_versions_build, state.revision), style = HATextStyle.BodyMedium)
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
        Text(
            stringResource(title),
            style = HATextStyle.Body.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Start,
        )
        Text(stringResource(R.string.hatail_versions_installed, component.installed.version), style = HATextStyle.Body)
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
            Text(
                stringResource(relationMessage(release.relation)),
                style = HATextStyle.Body,
                textAlign = TextAlign.Start,
            )
            Text(
                stringResource(R.string.hatail_versions_checked, formatVersionCheckTime(component.checkedAtEpochMs)),
                style = HATextStyle.BodyMedium,
            )
            HAAccentButton(
                stringResource(R.string.hatail_versions_notes),
                { uriHandler.openUri(release.releaseUrl(component.installed.repository)) },
            )
            if (release.notes.isNotBlank()) {
                Text(
                    release.notes,
                    style = HATextStyle.BodyMedium,
                    textAlign = TextAlign.Start,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HAPlainButton(
            stringResource(R.string.hatail_versions_source, component.installed.commit.take(12)),
            { uriHandler.openUri(component.installed.sourceUrl) },
        )
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
    HAThemeForPreview { HatailVersionsHeader(HatailVersionsState()) {} }
}
