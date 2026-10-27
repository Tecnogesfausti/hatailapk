@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.homeassistant.companion.android.hatail

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.homeassistant.companion.android.BuildConfig
import io.homeassistant.companion.android.common.R
import io.homeassistant.companion.android.common.compose.composable.HAAccentButton
import io.homeassistant.companion.android.common.compose.composable.HAFilledButton
import io.homeassistant.companion.android.common.compose.composable.HAModalBottomSheet
import io.homeassistant.companion.android.common.compose.composable.HAPlainButton
import io.homeassistant.companion.android.common.compose.composable.rememberHAModalBottomSheetState
import io.homeassistant.companion.android.common.compose.theme.HADimens
import io.homeassistant.companion.android.common.compose.theme.HATextStyle
import io.homeassistant.companion.android.common.compose.theme.LocalHAColorScheme
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun HatailHistorySheet(
    state: HatailHistoryState,
    onFilter: (HistoryFilter) -> Unit,
    loadImage: suspend (String) -> ByteArray?,
    onSettings: () -> Unit,
    onSample: () -> Unit,
    onDismiss: () -> Unit,
) {
    HAModalBottomSheet(rememberHAModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(HADimens.SPACE4),
            verticalArrangement = Arrangement.spacedBy(HADimens.SPACE2),
        ) {
            Text(stringResource(R.string.hatail_history_title), style = HATextStyle.HeadlineMedium)
            if (!state.accessGranted) {
                Text(stringResource(R.string.hatail_history_permission_help), style = HATextStyle.BodyMedium)
                HAAccentButton(stringResource(R.string.hatail_history_enable), onSettings)
            } else {
                Text(stringResource(R.string.hatail_history_retention), style = HATextStyle.BodyMedium)
                Row {
                    HAPlainButton(stringResource(R.string.hatail_history_settings), onSettings)
                    HAPlainButton(stringResource(R.string.hatail_history_sample), onSample)
                }
            }
            HistoryFilters(state.filter, onFilter)
            if (state.sampleFailed) {
                Text(
                    stringResource(R.string.hatail_history_sample_failed),
                    style = HATextStyle.BodyMedium,
                )
            }
            if (state.entries.isEmpty()) {
                Text(stringResource(R.string.hatail_history_empty), style = HATextStyle.Body)
                Box(Modifier.weight(1f))
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(HADimens.SPACE3)) {
                    items(state.entries, key = { it.id }) { HistoryCard(it, loadImage) }
                }
            }
            HAPlainButton(stringResource(R.string.hatail_versions_close), onDismiss)
        }
    }
}

@Composable
private fun HistoryFilters(selected: HistoryFilter, onFilter: (HistoryFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(HADimens.SPACE1)) {
        HistoryFilterButton(R.string.hatail_history_all, selected == HistoryFilter.All) { onFilter(HistoryFilter.All) }
        HistoryFilterButton(R.string.hatail_history_hatail, selected == HistoryFilter.Hatail) {
            onFilter(HistoryFilter.Hatail)
        }
        HistoryFilterButton(R.string.hatail_history_official, selected == HistoryFilter.Official) {
            onFilter(HistoryFilter.Official)
        }
    }
}

@Composable
private fun HistoryFilterButton(label: Int, selected: Boolean, onClick: () -> Unit) {
    if (selected) HAFilledButton(stringResource(label), onClick) else HAPlainButton(stringResource(label), onClick)
}

@Composable
private fun HistoryCard(item: HatailHistoryItem, loadImage: suspend (String) -> ByteArray?) {
    Surface(color = LocalHAColorScheme.current.colorSurfaceLow) {
        Column(
            Modifier.fillMaxWidth().padding(HADimens.SPACE3),
            verticalArrangement = Arrangement.spacedBy(HADimens.SPACE2),
        ) {
            val app = stringResource(
                if (item.sourcePackage ==
                    BuildConfig.APPLICATION_ID
                ) {
                    R.string.hatail_history_hatail
                } else {
                    R.string.hatail_history_official
                },
            )
            Text(app, style = HATextStyle.Body.copy(fontWeight = FontWeight.SemiBold))
            Text(item.origin ?: stringResource(R.string.hatail_history_unknown_home), style = HATextStyle.BodyMedium)
            Text(
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.postedAtEpochMs)),
                style = HATextStyle.BodyMedium,
            )
            if (item.title.isNotBlank()) {
                Text(
                    item.title,
                    style = HATextStyle.Body.copy(fontWeight = FontWeight.SemiBold),
                )
            }
            if (item.message.isNotBlank()) Text(item.message, style = HATextStyle.Body)
            if (item.imageExpected || item.hasImage) HistoryImage(item, loadImage)
        }
    }
}

@Composable
private fun HistoryImage(item: HatailHistoryItem, loadImage: suspend (String) -> ByteArray?) {
    val image by produceState<ImageBitmap?>(null, item.id, item.hasImage) {
        if (item.hasImage) {
            val bytes = loadImage(item.id)
            value =
                withContext(Dispatchers.Default) {
                    bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                }
        }
    }
    var expanded by remember(item.id) { mutableStateOf(false) }
    val bitmap = image
    if (bitmap == null) {
        Text(stringResource(R.string.hatail_history_image_missing), style = HATextStyle.BodyMedium)
    } else {
        Image(
            bitmap,
            stringResource(R.string.hatail_history_image),
            Modifier.fillMaxWidth().heightIn(max = 220.dp).clickable {
                expanded =
                    true
            },
            contentScale = ContentScale.Fit,
        )
        if (expanded) {
            Dialog(onDismissRequest = { expanded = false }) {
                Surface(color = LocalHAColorScheme.current.colorSurfaceDefault) {
                    Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(HADimens.SPACE3)) {
                        Image(
                            bitmap,
                            stringResource(R.string.hatail_history_image),
                            Modifier.fillMaxWidth().weight(1f),
                            contentScale = ContentScale.Fit,
                        )
                        HAPlainButton(stringResource(R.string.hatail_versions_close), { expanded = false })
                    }
                }
            }
        }
    }
}
