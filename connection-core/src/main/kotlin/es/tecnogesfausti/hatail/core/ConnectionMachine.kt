package es.tecnogesfausti.hatail.core

import java.net.URI

enum class Phase { IDLE, INSPECTING, PERMISSION, STOPPING, ACTIVATING, CHECKING_HA, CONNECTED, ERROR }

enum class Failure {
    VPN_PERMISSION_DENIED, AUTHENTICATION_REQUIRED, WRONG_TAILNET, HA_UNREACHABLE,
    WRONG_HA_SERVER, TIMEOUT, TUNNEL_LOST, BACKEND_UNAVAILABLE,
}

data class ConnectionState(
    val attempt: Long = 0,
    val profile: HomeProfile? = null,
    val phase: Phase = Phase.IDLE,
    val failure: Failure? = null,
)

sealed interface Event {
    /** Cold start or return to the foreground. Persisted selection is not a connection proof. */
    data class Resume(val rememberedProfile: HomeProfile?) : Event
    data class Select(val profile: HomeProfile) : Event
    /** User pressed system Back to leave the home, not merely backgrounded the app. */
    data object Disconnect : Event
    data class PermissionResult(val attempt: Long, val granted: Boolean) : Event
    data class TunnelStopped(val attempt: Long) : Event
    data class TunnelReady(val attempt: Long, val identity: TunnelIdentity) : Event
    /** Must originate from an authenticated HA probe routed through the selected VPN. */
    data class ServerVerified(val attempt: Long, val url: URI, val serverId: String) : Event
    data class Failed(val attempt: Long, val failure: Failure) : Event
}

sealed interface Effect {
    /** Cancel and fence all older work, including queued backend mutations and navigation. */
    data class CancelBefore(val attempt: Long) : Effect
    data object CloseHomeAssistant : Effect
    /** Temporarily gate the existing dashboard while preserving its navigation/session. */
    data object GuardHomeAssistant : Effect
    data class RememberHome(val attempt: Long, val profile: HomeProfile) : Effect
    data object ForgetHome : Effect
    data class InspectTunnel(val attempt: Long) : Effect
    data class PrepareVpn(val attempt: Long) : Effect
    data class StopTunnel(val attempt: Long) : Effect
    data class Activate(val attempt: Long, val profile: HomeProfile) : Effect
    data class VerifyServer(val attempt: Long, val profile: HomeProfile) : Effect
    data class OpenHomeAssistant(val attempt: Long, val profile: HomeProfile) : Effect
    /** The host starts a bounded deadline for the complete connection attempt. */
    data class StartDeadline(val attempt: Long) : Effect
    data class CancelDeadline(val attempt: Long) : Effect
}

data class Transition(val state: ConnectionState, val effects: List<Effect> = emptyList())

/** Pure reducer. The host must serialize events and effects; see docs/architecture.md. */
object ConnectionMachine {
    fun reduce(state: ConnectionState, event: Event): Transition {
        when (event) {
            is Event.Resume -> {
                if (state.phase !in setOf(Phase.IDLE, Phase.CONNECTED, Phase.ERROR)) return Transition(state)
                val profile = state.profile ?: event.rememberedProfile
                if (profile == null) return reduce(state, Event.Disconnect)
                val next = state.attempt + 1
                return Transition(
                    ConnectionState(next, profile, Phase.INSPECTING),
                    listOf(Effect.CancelBefore(next), Effect.GuardHomeAssistant,
                        Effect.StartDeadline(next), Effect.InspectTunnel(next)),
                )
            }
            is Event.Select -> {
                val retry = state.phase == Phase.ERROR && state.profile == event.profile
                if (state.phase != Phase.IDLE && !retry) return Transition(state)
                val next = state.attempt + 1
                return Transition(
                    ConnectionState(next, event.profile, Phase.PERMISSION),
                    listOf(Effect.CancelBefore(next), Effect.CloseHomeAssistant,
                        Effect.RememberHome(next, event.profile), Effect.StartDeadline(next), Effect.PrepareVpn(next)),
                )
            }
            Event.Disconnect -> {
                val next = state.attempt + 1
                return Transition(
                    ConnectionState(next, phase = Phase.STOPPING),
                    listOf(Effect.CancelBefore(next), Effect.CloseHomeAssistant, Effect.ForgetHome,
                        Effect.StartDeadline(next), Effect.StopTunnel(next)),
                )
            }
            else -> Unit
        }

        val attempt = when (event) {
            is Event.PermissionResult -> event.attempt
            is Event.TunnelStopped -> event.attempt
            is Event.TunnelReady -> event.attempt
            is Event.ServerVerified -> event.attempt
            is Event.Failed -> event.attempt
            else -> error("Selection events were handled above")
        }
        if (attempt != state.attempt) return Transition(state)

        return when (event) {
            is Event.PermissionResult -> {
                if (state.phase != Phase.PERMISSION) return Transition(state)
                if (!event.granted) return fail(state, Failure.VPN_PERMISSION_DENIED)
                Transition(state.copy(phase = Phase.STOPPING), listOf(Effect.StopTunnel(attempt)))
            }
            is Event.TunnelStopped -> {
                if (state.phase != Phase.STOPPING) return Transition(state)
                val profile = state.profile
                if (profile == null) {
                    Transition(ConnectionState(attempt), listOf(Effect.CancelDeadline(attempt)))
                } else {
                    Transition(state.copy(phase = Phase.ACTIVATING), listOf(Effect.Activate(attempt, profile)))
                }
            }
            is Event.TunnelReady -> {
                if (state.phase !in setOf(Phase.INSPECTING, Phase.ACTIVATING, Phase.CHECKING_HA, Phase.CONNECTED)) {
                    return Transition(state)
                }
                val profile = requireNotNull(state.profile)
                if (state.phase == Phase.INSPECTING && !event.identity.matches(profile)) {
                    return Transition(state.copy(phase = Phase.PERMISSION), listOf(Effect.PrepareVpn(attempt)))
                }
                if (!event.identity.matches(profile)) return fail(state, Failure.WRONG_TAILNET)
                if (state.phase !in setOf(Phase.INSPECTING, Phase.ACTIVATING)) return Transition(state)
                Transition(state.copy(phase = Phase.CHECKING_HA), listOf(Effect.VerifyServer(attempt, profile)))
            }
            is Event.ServerVerified -> {
                if (state.phase != Phase.CHECKING_HA) return Transition(state)
                val profile = requireNotNull(state.profile)
                if (event.url != profile.homeAssistantUrl || event.serverId != profile.homeAssistantServerId) {
                    return fail(state, Failure.WRONG_HA_SERVER)
                }
                Transition(state.copy(phase = Phase.CONNECTED),
                    listOf(Effect.CancelDeadline(attempt), Effect.OpenHomeAssistant(attempt, profile)))
            }
            is Event.Failed -> {
                if (state.phase in setOf(Phase.IDLE, Phase.ERROR)) return Transition(state)
                // A canceled deadline can already be queued after successful verification.
                if (state.phase == Phase.CONNECTED && event.failure == Failure.TIMEOUT) return Transition(state)
                fail(state, event.failure)
            }
            else -> Transition(state)
        }
    }

    private fun fail(state: ConnectionState, failure: Failure): Transition {
        val cleanup = state.attempt + 1
        return Transition(
            state.copy(attempt = cleanup, phase = Phase.ERROR, failure = failure),
            listOf(Effect.CancelBefore(cleanup), Effect.CancelDeadline(state.attempt),
                Effect.CloseHomeAssistant, Effect.StopTunnel(cleanup)),
        )
    }
}
