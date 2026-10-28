package io.homeassistant.companion.android.hatail

import android.net.Network
import dagger.hilt.android.testing.HiltTestApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class HatailTraccarTransportTest {
    private val vpn = mockk<TraccarVpn>()
    private val preferences = mockk<TraccarPreferences>()
    private val config = TraccarConfig("http://100.64.0.10:5055/", "test-device")
    private val point = TraccarPosition(time = 1000, latitude = 40.0, longitude = -3.0, accuracy = 10f)

    @Test fun `VPN verification failure prevents sending any coordinates`() = runTest {
        coEvery { preferences.tailnet() } returns "shared.ts.net"
        coEvery { vpn.network() } throws TraccarFailure(TraccarIssue.VPN)
        try {
            TraccarTransport(vpn, preferences).send(config, point)
            fail("Must require the bound VPN")
        } catch (error: TraccarFailure) {
            assertEquals(TraccarIssue.VPN, error.issue)
        }
        coVerify(exactly = 1) { vpn.network() }
    }

    @Test fun `network failure uses only the VPN socket factory without physical network fallback`() = runTest {
        coEvery { preferences.tailnet() } returns "shared.ts.net"
        val network = mockk<Network>()
        var sockets = 0
        val factory = object : SocketFactory() {
            override fun createSocket(): Socket {
                sockets++
                throw IOException("Simulated tunnel outage")
            }
            override fun createSocket(host: String?, port: Int): Socket = createSocket()
            override fun createSocket(host: String?, port: Int, local: InetAddress?, localPort: Int): Socket = createSocket()
            override fun createSocket(host: InetAddress?, port: Int): Socket = createSocket()
            override fun createSocket(host: InetAddress?, port: Int, local: InetAddress?, localPort: Int): Socket = createSocket()
        }
        every { network.socketFactory } returns factory
        coEvery { vpn.network() } returns network
        try {
            TraccarTransport(vpn, preferences).send(config, point)
            fail("An unavailable tunnel must not succeed")
        } catch (_: IOException) {
            assertTrue(sockets > 0)
        }
    }
}
