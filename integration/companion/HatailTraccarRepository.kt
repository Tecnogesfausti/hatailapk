package io.homeassistant.companion.android.hatail

import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class TraccarState(
    val config: TraccarConfig = TraccarConfig(),
    val running: Boolean = false,
    val pending: Boolean = false,
    val lastFix: Long = 0,
    val lastSent: Long = 0,
    val issue: TraccarIssue = TraccarIssue.NONE,
)

@Singleton
internal class TraccarRepository @Inject constructor(
    private val preferences: TraccarPreferences,
    private val positions: TraccarPositionDao,
    private val transport: TraccarTransport,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private var loaded = false
    private val mutableState = MutableStateFlow(TraccarState())
    val state = mutableState.asStateFlow()

    suspend fun load() = mutex.withLock { loadLocked() }
    private suspend fun loadLocked() {
        if (loaded) return
        val config = preferences.read()
        val point = positions.pending()
        val fresh = point?.acceptable(clock.millis(), config.accuracyMeters) == true
        if (!fresh) positions.clear()
        mutableState.value =
            TraccarState(
                config,
                pending = fresh,
                lastFix = if (fresh) point!!.time else 0,
                lastSent = preferences.lastSent(),
            )
        loaded = true
    }

    suspend fun save(config: TraccarConfig) = mutex.withLock {
        loadLocked()
        check(!state.value.config.enabled)
        val checked = validateTraccarConfig(config.copy(enabled = false), preferences.tailnet())
        // Never send pending coordinates to a newly configured destination or device identifier.
        positions.clear()
        preferences.write(checked)
        preferences.sent(0)
        mutableState.update {
            it.copy(config = checked, pending = false, lastFix = 0, lastSent = 0, issue = TraccarIssue.NONE)
        }
    }

    suspend fun enabled(value: Boolean) = mutex.withLock {
        loadLocked()
        val config = state.value.config.copy(enabled = value)
        if (value) validateTraccarConfig(config, preferences.tailnet())
        if (value && !state.value.config.enabled) positions.clear()
        preferences.write(config)
        mutableState.update {
            it.copy(
                config = config,
                pending = if (value) it.pending else false,
                issue = if (value) it.issue else TraccarIssue.NONE,
            )
        }
        if (!value) positions.clear()
    }

    suspend fun record(point: TraccarPosition) = mutex.withLock {
        loadLocked()
        if (!state.value.config.enabled || !point.acceptable(clock.millis(), state.value.config.accuracyMeters)) return
        if (point.time <= state.value.lastFix) return
        positions.save(point)
        mutableState.update { it.copy(pending = true, lastFix = point.time) }
    }

    suspend fun flush() = mutex.withLock {
        loadLocked()
        if (!state.value.config.enabled) return
        val point = positions.pending() ?: return
        if (!point.acceptable(clock.millis(), state.value.config.accuracyMeters)) {
            positions.clear()
            mutableState.update { it.copy(pending = false) }
            return
        }
        try {
            transport.send(state.value.config, point)
            positions.clear()
            preferences.sent(clock.millis())
            mutableState.update { it.copy(pending = false, lastSent = clock.millis(), issue = TraccarIssue.NONE) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: TraccarFailure) {
            issue(failure.issue)
        } catch (_: Exception) {
            issue(TraccarIssue.NETWORK)
        }
    }

    fun running(value: Boolean) {
        mutableState.update { it.copy(running = value) }
    }
    fun issue(value: TraccarIssue) {
        mutableState.update { it.copy(issue = value) }
    }
}
