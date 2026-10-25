package io.homeassistant.companion.android.hatail

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tailscale.ipn.MainActivity
import io.homeassistant.companion.android.common.R
import io.homeassistant.companion.android.common.compose.composable.HAAccentButton
import io.homeassistant.companion.android.common.compose.theme.HADimens
import io.homeassistant.companion.android.common.compose.theme.HATheme

/** Explicit exit action shared with Companion's frontend Back handler. */
val LocalHatailExit = staticCompositionLocalOf<() -> Unit> { {} }

/** Gate the genuine Companion frontend on a verified embedded Tailscale connection. */
@Composable
fun HatailGate(content: @Composable () -> Unit) {
    val model: HatailGateViewModel = hiltViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.permissionResult(it.resultCode == Activity.RESULT_OK)
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.refresh()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state) {
        if (state == GateState.Permission) {
            val intent = VpnService.prepare(context)
            if (intent == null) model.permissionResult(true) else permission.launch(intent)
        }
    }
    if (state == GateState.Connected || state == GateState.Onboarding) {
        CompositionLocalProvider(LocalHatailExit provides model::exitHome) { content() }
    } else {
        HATheme {
            GateContent(state, model, onAccount = { context.startActivity(Intent(context, MainActivity::class.java)) })
        }
    }
    // Registered after the frontend: an explicit Back leaves the home and its VPN.
    BackHandler(enabled = state != GateState.Choose) { model.exitHome() }
}

@Composable
private fun GateContent(state: GateState, model: HatailGateViewModel, onAccount: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(HADimens.SPACE6),
        verticalArrangement = Arrangement.spacedBy(HADimens.SPACE4),
    ) {
        Text(stringResource(R.string.hatail_title))
        when (state) {
            GateState.Choose -> {
                HAAccentButton(stringResource(R.string.hatail_casa), model::selectCasa)
                HAAccentButton(stringResource(R.string.hatail_sol_pending), {}, enabled = false)
            }
            GateState.Account -> {
                Text(stringResource(R.string.hatail_account_help))
                HAAccentButton(stringResource(R.string.hatail_account), onAccount)
                HAAccentButton(stringResource(R.string.hatail_bind), model::bindCurrentAccount)
            }
            GateState.SetupHomeAssistant -> {
                Text(stringResource(R.string.hatail_private_url_help))
                HAAccentButton(stringResource(R.string.hatail_setup_ha), model::onboard)
            }
            GateState.Error -> {
                Text(stringResource(R.string.hatail_error))
                HAAccentButton(stringResource(R.string.hatail_retry), model::refresh)
            }
            else -> Text(stringResource(R.string.hatail_working))
        }
    }
}
