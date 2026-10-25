package es.tecnogesfausti.hatail.ui

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import es.tecnogesfausti.hatail.core.ConnectionState
import es.tecnogesfausti.hatail.core.Failure
import es.tecnogesfausti.hatail.core.HomeId
import es.tecnogesfausti.hatail.core.Phase

/** Presentation only. The HA host provides configured profiles and executes connection effects. */
class HomeSelectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    var onSelect: (HomeId) -> Unit = {}
    /** Used only to retry a failed exit; the host maps system Back to Event.Disconnect. */
    var onRetryExit: () -> Unit = {}
    private var current = ConnectionState()
    private val status = TextView(context)
    private val retry = Button(context)
    private val choices = LinearLayout(context).apply { orientation = VERTICAL }

    init {
        orientation = VERTICAL
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        addView(TextView(context).apply {
            text = context.getString(R.string.hatail_title)
            textSize = 28f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isAccessibilityHeading = true
        })
        choices.addView(TextView(context).apply { setText(R.string.hatail_choose_home) })
        listOf(HomeId.CASA to R.string.hatail_connect_casa, HomeId.SOL to R.string.hatail_connect_sol)
            .forEach { (home, label) ->
                choices.addView(Button(context).apply {
                    setText(label)
                    setOnClickListener { onSelect(home) }
                })
            }
        addView(choices)
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(status)
        retry.setText(R.string.hatail_retry)
        retry.setOnClickListener {
            val profile = current.profile
            if (profile == null) onRetryExit() else onSelect(profile.home)
        }
        addView(retry)
        render(current)
    }

    /** Call on the Android main thread for every state transition, including tunnel loss. */
    fun render(state: ConnectionState) {
        current = state
        val label = when (state.phase) {
            Phase.IDLE -> R.string.hatail_idle
            Phase.INSPECTING -> R.string.hatail_inspecting
            Phase.PERMISSION -> R.string.hatail_permission
            Phase.STOPPING -> R.string.hatail_stopping
            Phase.ACTIVATING -> R.string.hatail_activating
            Phase.CHECKING_HA -> R.string.hatail_checking
            Phase.CONNECTED -> R.string.hatail_connected
            Phase.ERROR -> errorLabel(state.failure)
        }
        status.text = context.getString(label)
        if (state.phase == Phase.CONNECTED) {
            val home = context.getString(if (state.profile?.home == HomeId.CASA) R.string.hatail_casa else R.string.hatail_sol)
            status.text = context.getString(R.string.hatail_connected_home, home)
        }
        retry.visibility = if (state.phase == Phase.ERROR) VISIBLE else GONE
        choices.visibility = if (state.phase == Phase.IDLE) VISIBLE else GONE
    }

    private fun errorLabel(failure: Failure?): Int = when (failure) {
        Failure.VPN_PERMISSION_DENIED -> R.string.hatail_permission_denied
        Failure.AUTHENTICATION_REQUIRED -> R.string.hatail_auth_required
        Failure.WRONG_TAILNET -> R.string.hatail_wrong_tailnet
        Failure.HA_UNREACHABLE -> R.string.hatail_ha_unreachable
        Failure.WRONG_HA_SERVER -> R.string.hatail_wrong_ha
        Failure.TIMEOUT -> R.string.hatail_timeout
        Failure.TUNNEL_LOST -> R.string.hatail_tunnel_lost
        else -> R.string.hatail_backend_unavailable
    }
}
