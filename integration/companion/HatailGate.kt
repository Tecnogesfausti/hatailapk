package io.homeassistant.companion.android.hatail

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import io.homeassistant.companion.android.frontend.navigation.FrontendTarget
import io.homeassistant.companion.android.launch.LaunchActivity

private const val RENDERED_HOME_EXTRA = "hatail.rendered_home"
private const val RENDERED_SERVER_EXTRA = "hatail.rendered_server"
private const val ONBOARDING_URL_EXTRA = "hatail.onboarding_url"

/** Explicit exit action shared with Companion's frontend Back handler. */
val LocalHatailExit = staticCompositionLocalOf<() -> Unit> { {} }

/** Gate the genuine Companion frontend on a verified embedded Tailscale connection. */
@Composable
fun HatailGate(content: @Composable () -> Unit) {
    val model: HatailGateViewModel = hiltViewModel()
    val versions: HatailVersionsViewModel = hiltViewModel()
    val history: HatailHistoryViewModel = hiltViewModel()
    val versionState by versions.state.collectAsStateWithLifecycle()
    var showVersions by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val owner = LocalLifecycleOwner.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.permissionResult(it.resultCode == Activity.RESULT_OK)
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                model.refresh()
                versions.refresh()
                history.refreshAccess()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state) {
        if (state == GateState.Permission) {
            val intent = VpnService.prepare(context)
            if (intent == null) model.permissionResult(true) else permission.launch(intent)
        }
        val connected = state as? GateState.Connected
        if (connected != null &&
            (
                activity?.intent?.getStringExtra(RENDERED_HOME_EXTRA) != connected.home.name ||
                    activity?.intent?.getIntExtra(RENDERED_SERVER_EXTRA, -1) != connected.serverId
                )
        ) {
            // A fresh navigation graph is required after activating a different Companion server.
            activity?.startActivity(
                LaunchActivity.newInstance(
                    context,
                    LaunchActivity.DeepLink.NavigateTo(FrontendTarget.Default, connected.serverId),
                ).apply {
                    putExtra(RENDERED_HOME_EXTRA, connected.home.name)
                    putExtra(RENDERED_SERVER_EXTRA, connected.serverId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                },
            )
            activity?.finish()
        }
        val onboarding = state as? GateState.Onboarding
        if (onboarding?.home == Home.SOL &&
            activity?.intent?.getStringExtra(ONBOARDING_URL_EXTRA) != onboarding.url
        ) {
            val url = requireNotNull(onboarding.url)
            activity?.startActivity(solOnboardingIntent(context, url))
            activity?.finish()
        }
    }
    val currentState = state
    val contentReady = when (currentState) {
        is GateState.Connected ->
            activity?.intent?.getStringExtra(RENDERED_HOME_EXTRA) == currentState.home.name &&
                activity?.intent?.getIntExtra(RENDERED_SERVER_EXTRA, -1) == currentState.serverId
        is GateState.Onboarding ->
            currentState.home == Home.CASA ||
                activity?.intent?.getStringExtra(ONBOARDING_URL_EXTRA) == currentState.url
        else -> false
    }
    Column(Modifier.fillMaxSize()) {
        HATheme {
            HatailVersionsHeader(
                versionState,
                onHistory = {
                    showVersions = false
                    showHistory = true
                },
                onOpen = {
                    showHistory = false
                    showVersions = true
                },
            )
        }
        Box(Modifier.weight(1f)) {
            if (contentReady) {
                CompositionLocalProvider(LocalHatailExit provides model::exitHome) { content() }
            } else {
                HATheme {
                    GateContent(
                        state,
                        model,
                        onAccount = { context.startActivity(Intent(context, MainActivity::class.java)) },
                        onSolRegistration = { url ->
                            activity?.startActivity(solOnboardingIntent(context, url))
                            activity?.finish()
                        },
                    )
                }
            }
        }
    }
    // Registered after the frontend: an explicit Back leaves the home and its VPN.
    BackHandler(enabled = state != GateState.Choose && !showVersions && !showHistory) { model.exitHome() }
    if (showVersions) {
        HATheme { HatailVersionsSheet(versionState, { versions.refresh(manual = true) }, { showVersions = false }) }
    }
    if (showHistory) {
        val historyState by history.state.collectAsStateWithLifecycle()
        HATheme {
            HatailHistorySheet(
                historyState,
                history::select,
                history::image,
                onSettings = { context.startActivity(historyAccessIntent(context)) },
                onSample = history::sample,
                onDismiss = { showHistory = false },
            )
        }
    }
}

private fun solOnboardingIntent(context: android.content.Context, url: String): Intent = LaunchActivity.newInstance(
    context,
    LaunchActivity.DeepLink.OpenOnboarding(url, hideExistingServers = true, skipWelcome = true),
).putExtra(ONBOARDING_URL_EXTRA, url)

@Composable
private fun GateContent(
    state: GateState,
    model: HatailGateViewModel,
    onAccount: () -> Unit,
    onSolRegistration: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(HADimens.SPACE6),
        verticalArrangement = Arrangement.spacedBy(HADimens.SPACE4),
    ) {
        Text(stringResource(R.string.hatail_title))
        when (state) {
            GateState.Choose -> {
                HAAccentButton(stringResource(R.string.hatail_casa), { model.selectHome(Home.CASA) })
                HAAccentButton(stringResource(R.string.hatail_sol), { model.selectHome(Home.SOL) })
            }
            GateState.Account -> {
                Text(stringResource(R.string.hatail_account_help))
                HAAccentButton(stringResource(R.string.hatail_account), onAccount)
                HAAccentButton(stringResource(R.string.hatail_bind), model::bindCurrentAccount)
            }
            is GateState.SetupHomeAssistant -> {
                if (state.home == Home.CASA) {
                    Text(stringResource(R.string.hatail_private_url_help))
                    HAAccentButton(stringResource(R.string.hatail_setup_ha), model::onboard)
                } else {
                    var url by rememberSaveable(state.savedUrl) { mutableStateOf(state.savedUrl.orEmpty()) }
                    Text(stringResource(R.string.hatail_sol_url_help))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(R.string.hatail_sol_url_label)) },
                        singleLine = true,
                    )
                    HAAccentButton(
                        stringResource(R.string.hatail_setup_sol),
                        { model.onboardSol(url, onSolRegistration) },
                        enabled = url.isNotBlank(),
                    )
                }
            }
            GateState.Error -> {
                Text(stringResource(R.string.hatail_error))
                HAAccentButton(stringResource(R.string.hatail_retry), model::refresh)
            }
            else -> Text(stringResource(R.string.hatail_working))
        }
    }
}
