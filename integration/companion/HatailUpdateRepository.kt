@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.homeassistant.companion.android.hatail

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.homeassistant.companion.android.BuildConfig
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private val AUTOMATIC_CHECK_INTERVAL = 24.hours
private val FAILED_CHECK_INTERVAL = 1.hours
private val MANUAL_CHECK_INTERVAL = 15.minutes

/** Public version metadata only; this store never handles HA credentials or VPN keys. */
internal class HatailUpdateStorage @Inject constructor(@ApplicationContext private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun buildInformation(): HatailBuildInformation = withContext(Dispatchers.IO) {
        val text = context.assets.open("hatail-build.json").bufferedReader().use { it.readText() }
        val information = json.decodeFromString<HatailBuildInformation>(text)
        require(information.components.map { it.repository }.toSet() == UPDATE_REPOSITORIES)
        information.copy(
            components = information.components.map {
                if (it.repository == "home-assistant/android") it.copy(version = BuildConfig.VERSION_NAME) else it
            },
        )
    }

    suspend fun load(): HatailUpdateCache? = withContext(Dispatchers.IO) {
        val value = context.getSharedPreferences("hatail_versions", Context.MODE_PRIVATE).getString("cache", null)
        value?.let { runCatching { json.decodeFromString<HatailUpdateCache>(it) }.getOrNull() }
    }

    suspend fun save(cache: HatailUpdateCache) = withContext(Dispatchers.IO) {
        val saved = context.getSharedPreferences("hatail_versions", Context.MODE_PRIVATE).edit()
            .putString("cache", json.encodeToString(cache)).commit()
        if (!saved) throw IOException("Could not persist version metadata")
    }
}

/** Serializes manual/background checks and invalidates comparisons after changing embedded commits. */
@Singleton
internal class HatailUpdateRepository @Inject constructor(
    private val storage: HatailUpdateStorage,
    private val releases: HatailReleaseClient,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(HatailVersionsState())
    val state = mutableState.asStateFlow()
    private var loaded = false
    private var buildKey = ""

    suspend fun load() = mutex.withLock { loadLocked() }

    suspend fun refresh(manual: Boolean = false) = mutex.withLock {
        loadLocked()
        val before = mutableState.value
        val now = clock.now().toEpochMilliseconds()
        val interval = when {
            manual -> MANUAL_CHECK_INTERVAL
            before.components.any { it.failed || it.release == null } -> FAILED_CHECK_INTERVAL
            else -> AUTOMATIC_CHECK_INTERVAL
        }
        if (before.attemptedAtEpochMs > 0 && now - before.attemptedAtEpochMs in 0 until interval.inWholeMilliseconds) {
            if (manual) mutableState.value = before.copy(throttled = true)
            return@withLock
        }
        mutableState.value = before.copy(refreshing = true, throttled = false, attemptedAtEpochMs = now)
        var completed = false
        try {
            val checked = checkComponents(before.components, now)
            storage.save(HatailUpdateCache(buildKey, now, checked))
            mutableState.value = mutableState.value.copy(components = checked)
            completed = true
        } finally {
            mutableState.value = mutableState.value.copy(
                refreshing = false,
                attemptedAtEpochMs = if (completed) now else before.attemptedAtEpochMs,
            )
        }
    }

    private suspend fun loadLocked() {
        if (loaded) return
        val build = storage.buildInformation()
        buildKey = build.components.joinToString("|") { "${it.repository}:${it.commit}" }
        val cache = storage.load()?.takeIf { it.buildKey == buildKey }
        val components = build.components.map { installed ->
            cache?.components?.singleOrNull {
                it.installed.commit == installed.commit &&
                    it.installed.repository == installed.repository
            }
                ?.copy(installed = installed) ?: ComponentVersionState(installed)
        }
        mutableState.value = HatailVersionsState(
            revision = build.revision,
            components = components,
            attemptedAtEpochMs = cache?.attemptedAtEpochMs ?: 0,
        )
        loaded = true
    }

    private suspend fun checkComponents(
        components: List<ComponentVersionState>,
        now: Long,
    ): List<ComponentVersionState> = coroutineScope {
        components.map { previous ->
            async(Dispatchers.IO) {
                try {
                    previous.copy(release = releases.latest(previous.installed), checkedAtEpochMs = now, failed = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    previous.copy(failed = true)
                }
            }
        }.awaitAll()
    }
}
