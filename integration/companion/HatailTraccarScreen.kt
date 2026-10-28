@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.homeassistant.companion.android.hatail

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import io.homeassistant.companion.android.common.R
import io.homeassistant.companion.android.common.compose.composable.HAAccentButton
import io.homeassistant.companion.android.common.compose.composable.HAModalBottomSheet
import io.homeassistant.companion.android.common.compose.composable.HAPlainButton
import io.homeassistant.companion.android.common.compose.composable.consumeSheetScrollFling
import io.homeassistant.companion.android.common.compose.composable.rememberHAModalBottomSheetState
import io.homeassistant.companion.android.common.compose.theme.HADimens
import io.homeassistant.companion.android.common.compose.theme.HATextStyle
import java.text.DateFormat
import java.util.Date

@Composable
internal fun HatailTraccarSheet(
    state: TraccarState,
    permissions: TraccarPermissions,
    suggestion: String,
    model: HatailTraccarViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val request =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.refresh() }
    val config = state.config
    var url by rememberSaveable(config.url, suggestion) { mutableStateOf(config.url.ifBlank { suggestion }) }
    var identifier by rememberSaveable(config.deviceId) { mutableStateOf(config.deviceId) }
    var interval by rememberSaveable(config.intervalSeconds) { mutableStateOf(config.intervalSeconds.toString()) }
    var distance by rememberSaveable(config.distanceMeters) { mutableStateOf(config.distanceMeters.toInt().toString()) }
    var accuracy by rememberSaveable(config.accuracyMeters) { mutableStateOf(config.accuracyMeters.toInt().toString()) }
    val saved = url.trim() == config.url &&
        identifier.trim() == config.deviceId &&
        interval.toLongOrNull() == config.intervalSeconds &&
        distance.replace(',', '.').toFloatOrNull() == config.distanceMeters &&
        accuracy.replace(',', '.').toFloatOrNull() == config.accuracyMeters
    HAModalBottomSheet(
        bottomSheetState = rememberHAModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onDismiss,
    ) {
        Column(
            Modifier.fillMaxWidth().consumeSheetScrollFling().verticalScroll(
                rememberScrollState(),
            ).padding(HADimens.SPACE4),
            verticalArrangement = Arrangement.spacedBy(HADimens.SPACE3),
        ) {
            Text(stringResource(R.string.hatail_traccar_title), style = HATextStyle.HeadlineMedium)
            Text(stringResource(R.string.hatail_traccar_help), style = HATextStyle.BodyMedium)
            Text(
                stringResource(
                    when {
                        state.running -> R.string.hatail_traccar_active
                        config.enabled -> R.string.hatail_traccar_resume
                        else -> R.string.hatail_traccar_inactive
                    },
                ),
                style = HATextStyle.Body,
            )
            if (state.lastFix > 0) Text(stringResource(R.string.hatail_traccar_fix, trackingTime(state.lastFix)))
            if (state.lastSent > 0) Text(stringResource(R.string.hatail_traccar_sent, trackingTime(state.lastSent)))
            if (state.pending) Text(stringResource(R.string.hatail_traccar_pending))
            if (state.issue != TraccarIssue.NONE) {
                Text(stringResource(traccarIssueText(state.issue)))
            }
            OutlinedTextField(url, {
                url = it
            }, label = {
                Text(stringResource(R.string.hatail_traccar_url))
            }, enabled = !config.enabled, singleLine = true)
            OutlinedTextField(identifier, {
                identifier = it
            }, label = {
                Text(stringResource(R.string.hatail_traccar_id))
            }, enabled = !config.enabled, singleLine = true)
            TrackingNumber(interval, { interval = it }, R.string.hatail_traccar_interval, !config.enabled)
            TrackingNumber(distance, { distance = it }, R.string.hatail_traccar_distance, !config.enabled)
            TrackingNumber(accuracy, { accuracy = it }, R.string.hatail_traccar_accuracy, !config.enabled)
            if (!config.enabled) {
                HAPlainButton(stringResource(R.string.hatail_traccar_save), {
                    model.save(url, identifier, interval, distance, accuracy)
                })
            }
            if (!permissions.precise) {
                HAPlainButton(stringResource(R.string.hatail_traccar_permission), {
                    request.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    )
                })
            }
            if (!permissions.notifications && Build.VERSION.SDK_INT >= 33) {
                HAPlainButton(stringResource(R.string.hatail_traccar_notifications), {
                    request.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                })
            }
            if (!permissions.background || !permissions.precise || !permissions.notifications) {
                Text(stringResource(R.string.hatail_traccar_permissions_help))
            }
            HAPlainButton(stringResource(R.string.hatail_traccar_settings), {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                )
            })
            if (!permissions.gps) {
                HAPlainButton(stringResource(R.string.hatail_traccar_gps), {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                })
            }
            if (config.enabled) {
                HAAccentButton(stringResource(R.string.hatail_traccar_stop), model::stop)
                if (!state.running) {
                    HAPlainButton(
                        stringResource(R.string.hatail_traccar_restart),
                        model::start,
                        enabled = permissions.ready,
                    )
                }
            } else {
                HAAccentButton(
                    stringResource(R.string.hatail_traccar_start),
                    model::start,
                    enabled = permissions.ready && saved && config.url.isNotBlank() && config.deviceId.isNotBlank(),
                )
            }
            Text(stringResource(R.string.hatail_traccar_caution), style = HATextStyle.BodyMedium)
            HAPlainButton(stringResource(R.string.hatail_versions_close), onDismiss)
        }
    }
}

@Composable
private fun TrackingNumber(value: String, change: (String) -> Unit, label: Int, enabled: Boolean) {
    OutlinedTextField(
        value,
        change,
        label = { Text(stringResource(label)) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

private fun trackingTime(value: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(value))
private fun traccarIssueText(issue: TraccarIssue): Int = when (issue) {
    TraccarIssue.CONFIG -> R.string.hatail_traccar_error_config
    TraccarIssue.PERMISSION -> R.string.hatail_traccar_error_permission
    TraccarIssue.GPS -> R.string.hatail_traccar_error_gps
    TraccarIssue.VPN -> R.string.hatail_traccar_error_vpn
    TraccarIssue.SERVER -> R.string.hatail_traccar_error_server
    TraccarIssue.START -> R.string.hatail_traccar_error_start
    TraccarIssue.STORAGE -> R.string.hatail_traccar_error_storage
    else -> R.string.hatail_traccar_error_network
}
