package io.homeassistant.companion.android.hatail

import androidx.annotation.VisibleForTesting
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

private const val NETWORK_TIMEOUT_SECONDS = 15L
private const val NOTES_PREVIEW_LENGTH = 700

/** Uses an independent public client so HA credentials can never reach GitHub. */
internal class HatailReleaseClient @VisibleForTesting constructor(private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    @Inject constructor() : this(
        OkHttpClient.Builder()
            .callTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .build(),
    )

    suspend fun latest(installed: InstalledComponent): PublishedRelease {
        require(installed.repository in UPDATE_REPOSITORIES)
        require(installed.commit.matches(Regex("[0-9a-f]{40}")))
        val release = json.decodeFromString<GitHubRelease>(get("${installed.repository}/releases/latest"))
        require(!release.draft && !release.prerelease && release.tag.isNotBlank())
        val tag = URLEncoder.encode(release.tag, "UTF-8")
        val publishedCommit = json.decodeFromString<GitHubCommit>(get("${installed.repository}/commits/$tag")).sha
        require(publishedCommit.matches(Regex("[0-9a-f]{40}")))
        // GitHub omits the potentially large file patches after the first comparison page.
        val comparison = json.decodeFromString<GitHubComparison>(
            get("${installed.repository}/compare/${installed.commit}...$publishedCommit?per_page=1&page=2"),
        )
        return PublishedRelease(
            tag = release.tag,
            name = release.name.orEmpty().ifBlank { release.tag },
            notes = release.body.orEmpty().trim().take(NOTES_PREVIEW_LENGTH),
            publishedAt = release.publishedAt.orEmpty(),
            commit = publishedCommit,
            relation = releaseRelation(comparison.status),
        )
    }

    private suspend fun get(path: String): String = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url("https://api.github.com/repos/$path")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "HATailAPK-version-check")
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use {
                        if (!it.isSuccessful) throw IOException("GitHub HTTP ${it.code}")
                        it.body.string()
                    }
                    if (continuation.isActive) continuation.resume(text)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
)

@Serializable
private data class GitHubComparison(val status: String)

@Serializable
private data class GitHubCommit(val sha: String)
