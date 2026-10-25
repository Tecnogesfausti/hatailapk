package es.tecnogesfausti.hatail.core

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class ConnectionMachineTest {
    private val casa = HomeProfile(HomeId.CASA, "profile-casa", "casa-example.ts.net",
        URI("https://ha.casa-example.ts.net"), "ha-casa")
    private val sol = HomeProfile(HomeId.SOL, "profile-sol", "sol-example.ts.net",
        URI("https://ha.sol-example.ts.net"), "ha-sol")

    private fun ready(profile: HomeProfile) = TunnelIdentity(
        profile.tailscaleProfileId, profile.tailnetDnsSuffix, backendRunning = true, vpnEstablished = true,
    )

    private fun selecting(profile: HomeProfile = casa) =
        ConnectionMachine.reduce(ConnectionState(), Event.Select(profile)).state

    private fun activating(profile: HomeProfile = casa): ConnectionState {
        val selected = selecting(profile)
        val stopping = ConnectionMachine.reduce(selected, Event.PermissionResult(selected.attempt, true)).state
        return ConnectionMachine.reduce(stopping, Event.TunnelStopped(stopping.attempt)).state
    }

    private fun checking(profile: HomeProfile = casa): ConnectionState {
        val state = activating(profile)
        return ConnectionMachine.reduce(state, Event.TunnelReady(state.attempt, ready(profile))).state
    }

    @Test fun `startup has no selected home and requests no connection`() {
        assertEquals(Phase.IDLE, ConnectionState().phase)
        assertNull(ConnectionState().profile)
    }

    @Test fun `both homes require permission before stopping and activating`() {
        listOf(casa, sol).forEach { profile ->
            val selected = ConnectionMachine.reduce(ConnectionState(), Event.Select(profile))
            assertEquals(Phase.PERMISSION, selected.state.phase)
            assertEquals(listOf(Effect.CancelBefore(1), Effect.CloseHomeAssistant,
                Effect.StartDeadline(1), Effect.PrepareVpn(1)), selected.effects)
            val permitted = ConnectionMachine.reduce(selected.state, Event.PermissionResult(1, true))
            assertEquals(listOf(Effect.StopTunnel(1)), permitted.effects)
            val stopped = ConnectionMachine.reduce(permitted.state, Event.TunnelStopped(1))
            assertEquals(listOf(Effect.Activate(1, profile)), stopped.effects)
        }
    }

    @Test fun `permission denial never starts a profile`() {
        val state = selecting()
        val result = ConnectionMachine.reduce(state, Event.PermissionResult(state.attempt, false))
        assertEquals(Failure.VPN_PERMISSION_DENIED, result.state.failure)
        assertTrue(result.effects.none { it is Effect.Activate || it is Effect.OpenHomeAssistant })
    }

    @Test fun `wrong tailnet is rejected even when the VPN is active`() {
        val state = activating()
        val identity = ready(casa).copy(tailnetDnsSuffix = sol.tailnetDnsSuffix)
        val result = ConnectionMachine.reduce(state, Event.TunnelReady(state.attempt, identity))
        assertEquals(Failure.WRONG_TAILNET, result.state.failure)
        assertTrue(result.effects.none { it is Effect.VerifyServer })
    }

    @Test fun `wrong login profile is rejected within the same tailnet`() {
        val state = activating()
        val identity = ready(casa).copy(profileId = "different-profile")
        assertEquals(Failure.WRONG_TAILNET,
            ConnectionMachine.reduce(state, Event.TunnelReady(state.attempt, identity)).state.failure)
    }

    @Test fun `running backend without established VPN cannot open HA`() {
        val state = activating()
        val identity = ready(casa).copy(vpnEstablished = false)
        assertEquals(Phase.ERROR,
            ConnectionMachine.reduce(state, Event.TunnelReady(state.attempt, identity)).state.phase)
    }

    @Test fun `DNS suffix comparison ignores case and trailing dot`() {
        assertTrue(ready(casa).copy(tailnetDnsSuffix = "CASA-EXAMPLE.TS.NET.").matches(casa))
    }

    @Test fun `VPN verification alone never opens a dashboard`() {
        val state = activating()
        val result = ConnectionMachine.reduce(state, Event.TunnelReady(state.attempt, ready(casa)))
        assertEquals(Phase.CHECKING_HA, result.state.phase)
        assertEquals(listOf(Effect.VerifyServer(state.attempt, casa)), result.effects)
    }

    @Test fun `only the selected authenticated HA server can be opened`() {
        listOf(casa, sol).forEach { profile ->
            val state = checking(profile)
            val result = ConnectionMachine.reduce(state,
                Event.ServerVerified(state.attempt, profile.homeAssistantUrl, profile.homeAssistantServerId))
            assertEquals(Phase.CONNECTED, result.state.phase)
            assertEquals(listOf(Effect.CancelDeadline(state.attempt),
                Effect.OpenHomeAssistant(state.attempt, profile)), result.effects)
        }
    }

    @Test fun `reachable wrong HA endpoint or registration is rejected`() {
        val state = checking()
        listOf(Event.ServerVerified(state.attempt, sol.homeAssistantUrl, casa.homeAssistantServerId),
            Event.ServerVerified(state.attempt, casa.homeAssistantUrl, sol.homeAssistantServerId)).forEach {
            assertEquals(Failure.WRONG_HA_SERVER, ConnectionMachine.reduce(state, it).state.failure)
        }
    }

    @Test fun `switching homes invalidates old probe and tunnel callbacks`() {
        val old = checking()
        val current = ConnectionMachine.reduce(old, Event.Select(sol)).state
        val events = listOf(Event.ServerVerified(old.attempt, casa.homeAssistantUrl, casa.homeAssistantServerId),
            Event.TunnelReady(old.attempt, ready(casa)), Event.TunnelStopped(old.attempt),
            Event.Failed(old.attempt, Failure.TIMEOUT))
        events.forEach {
            assertEquals(Transition(current), ConnectionMachine.reduce(current, it))
        }
    }

    @Test fun `out of order and duplicate success cannot skip verification or reopen dashboard`() {
        val selected = selecting()
        assertEquals(Transition(selected), ConnectionMachine.reduce(selected,
            Event.ServerVerified(selected.attempt, casa.homeAssistantUrl, casa.homeAssistantServerId)))
        val state = checking()
        val success = Event.ServerVerified(state.attempt, casa.homeAssistantUrl, casa.homeAssistantServerId)
        val connected = ConnectionMachine.reduce(state, success).state
        assertEquals(Transition(connected), ConnectionMachine.reduce(connected, success))
    }

    @Test fun `disconnect fences old work before stopping the tunnel`() {
        val state = checking()
        val result = ConnectionMachine.reduce(state, Event.Disconnect)
        assertNull(result.state.profile)
        assertTrue(result.effects.first() is Effect.CancelBefore)
        val idle = ConnectionMachine.reduce(result.state, Event.TunnelStopped(result.state.attempt))
        assertEquals(Phase.IDLE, idle.state.phase)
    }

    @Test fun `failure closes HA stops tunnel and supports retry`() {
        val state = checking()
        listOf(Failure.TIMEOUT, Failure.AUTHENTICATION_REQUIRED, Failure.HA_UNREACHABLE,
            Failure.BACKEND_UNAVAILABLE).forEach { failure ->
            val result = ConnectionMachine.reduce(state, Event.Failed(state.attempt, failure))
            assertEquals(Phase.ERROR, result.state.phase)
            assertTrue(result.effects.contains(Effect.CloseHomeAssistant))
            assertTrue(result.effects.any { it is Effect.StopTunnel })
            assertEquals(Phase.PERMISSION, ConnectionMachine.reduce(result.state, Event.Select(casa)).state.phase)
        }
    }

    @Test fun `network loss after connection immediately closes HA`() {
        val state = checking()
        val connected = ConnectionMachine.reduce(state,
            Event.ServerVerified(state.attempt, casa.homeAssistantUrl, casa.homeAssistantServerId)).state
        val lost = ConnectionMachine.reduce(connected, Event.Failed(connected.attempt, Failure.TUNNEL_LOST))
        assertEquals(Failure.TUNNEL_LOST, lost.state.failure)
        assertTrue(lost.effects.contains(Effect.CloseHomeAssistant))
    }

    @Test fun `tailnet changes are monitored after connection`() {
        val state = checking()
        val connected = ConnectionMachine.reduce(state,
            Event.ServerVerified(state.attempt, casa.homeAssistantUrl, casa.homeAssistantServerId)).state
        assertEquals(Failure.WRONG_TAILNET, ConnectionMachine.reduce(connected,
            Event.TunnelReady(connected.attempt, ready(sol))).state.failure)
    }

    @Test fun `late canceled deadline cannot destroy a successful connection`() {
        val state = checking()
        val connected = ConnectionMachine.reduce(state,
            Event.ServerVerified(state.attempt, casa.homeAssistantUrl, casa.homeAssistantServerId)).state
        assertEquals(Transition(connected), ConnectionMachine.reduce(connected,
            Event.Failed(connected.attempt, Failure.TIMEOUT)))
    }

    @Test fun `profile forbids credentials and ambiguous URLs`() {
        listOf("ftp://ha.example", "https://user:secret@ha.example", "https://ha.example?token=x",
            "https://ha.example#token", "/relative").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { casa.copy(homeAssistantUrl = URI(value)) }
        }
    }
}
