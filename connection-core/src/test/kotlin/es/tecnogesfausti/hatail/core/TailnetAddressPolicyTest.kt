package es.tecnogesfausti.hatail.core

import java.net.URI
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetAddressPolicyTest {
    @Test fun `private CGNAT boundaries and the selected MagicDNS domain are accepted`() {
        listOf("http://100.64.0.1:8123", "https://100.127.255.255", "https://ha.casa.ts.net",
            "https://HA.CASA.TS.NET.").forEach {
            assertTrue(it, TailnetAddressPolicy.accepts(URI(it), "casa.ts.net"))
        }
    }

    @Test fun `Cloudflare public URLs different tailnets and lookalike domains are rejected`() {
        listOf("https://ha.example.com", "https://ha.sol.ts.net", "https://ha.casa.ts.net.evil.test",
            "http://100.63.255.255:8123", "http://100.128.0.1", "http://192.168.1.1",
            "http://100.064.0.1", "http://100.64.0.999").forEach {
            assertFalse(it, TailnetAddressPolicy.accepts(URI(it), "casa.ts.net"))
        }
    }

    @Test fun `credentials tokens fragments ambiguous ports and non HTTP schemes are rejected`() {
        listOf("https://user:secret@ha.casa.ts.net", "https://ha.casa.ts.net?token=x",
            "https://ha.casa.ts.net#token", "ftp://ha.casa.ts.net", "/relative",
            "https://ha.casa.ts.net:65536", "https://ha.casa.ts.net:0").forEach {
            assertFalse(it, TailnetAddressPolicy.accepts(URI(it), "casa.ts.net"))
        }
    }

    @Test fun `a missing or malformed tailnet binding never authorizes a URL`() {
        listOf("", "ts.net", "evil.test", ".ts.net").forEach {
            assertFalse(TailnetAddressPolicy.accepts(URI("https://ha.casa.ts.net"), it))
        }
    }
}
