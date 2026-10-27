package io.homeassistant.companion.android.hatail

import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HatailReleaseClientTest {
    @Test fun `Given real GitHub response shape when comparing then resolve the tag and interpret older stable correctly`() = runTest {
        val paths = mutableListOf<String>()
        val published = "b".repeat(40)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            paths += request.url.encodedPath
            val body = when {
                request.url.encodedPath.endsWith("releases/latest") -> """{"tag_name":"2026.10.1","name":null,"body":"Release notes"}"""
                request.url.encodedPath.contains("/commits/") -> """{"sha":"$published"}"""
                else -> """{"status":"behind","base_commit":{"sha":"${"a".repeat(40)}"},"commits":[]}"""
            }
            if (request.url.encodedPath.contains("/compare/")) assertEquals("2", request.url.queryParameter("page"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        val result = HatailReleaseClient(http).latest(InstalledComponent("home-assistant/android", "dev", "a".repeat(40)))
        assertEquals(ReleaseRelation.Older, result.relation)
        assertEquals(published, result.commit)
        assertEquals("2026.10.1", result.name)
        assertEquals("https://github.com/home-assistant/android/releases/tag/2026.10.1", result.releaseUrl("home-assistant/android"))
        assertEquals(3, paths.size)
        assertTrue(paths.last().endsWith(published))
    }

    @Test fun `Given GitHub rate limit when checking then report failure instead of claiming current`() = runTest {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("Rate limit")
                .body("{}".toResponseBody()).build()
        }.build()
        var rejected = false
        try {
            HatailReleaseClient(http).latest(InstalledComponent("tailscale/tailscale", "dev", "a".repeat(40)))
        } catch (_: IOException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test fun `Given an unexpected repository when checking then reject before sending a request`() = runTest {
        var called = false
        val http = OkHttpClient.Builder().addInterceptor { error("Request must not run".also { called = true }) }.build()
        var rejected = false
        try {
            HatailReleaseClient(http).latest(InstalledComponent("example/unknown", "dev", "a".repeat(40)))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals(false, called)
    }
}
