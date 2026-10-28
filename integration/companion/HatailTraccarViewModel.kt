package io.homeassistant.companion.android.hatail

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class TraccarPermissions(
    val precise: Boolean = false,
    val background: Boolean = false,
    val notifications: Boolean = false,
    val gps: Boolean = false,
) {
    val ready get() = precise && background && notifications && gps
}

@HiltViewModel
internal class HatailTraccarViewModel @Inject constructor(
    private val repository: TraccarRepository,
    private val preferences: TraccarPreferences,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val state = repository.state
    private val mutablePermissions = MutableStateFlow(TraccarPermissions())
    val permissions = mutablePermissions.asStateFlow()
    private val mutableSuggestion = MutableStateFlow("")
    val suggestion = mutableSuggestion.asStateFlow()

    /** Called only by a foreground activity, never from a boot receiver or periodic worker. */
    fun refresh() = viewModelScope.launch {
        try {
            repository.load()
            mutableSuggestion.value = preferences.suggestedUrl()
            mutablePermissions.value = TraccarPermissions(
                granted(Manifest.permission.ACCESS_FINE_LOCATION),
                Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                NotificationManagerCompat.from(context).areNotificationsEnabled(),
                LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java)),
            )
            if (state.value.config.enabled && !state.value.running) startService()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            repository.issue(TraccarIssue.STORAGE)
        }
    }

    fun save(url: String, deviceId: String, interval: String, distance: String, accuracy: String) =
        viewModelScope.launch {
            try {
                repository.save(
                    TraccarConfig(
                        url,
                        deviceId,
                        interval.toLongOrNull() ?: -1,
                        distance.replace(',', '.').toFloatOrNull() ?: -1f,
                        accuracy.replace(',', '.').toFloatOrNull() ?: -1f,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                repository.issue(TraccarIssue.CONFIG)
            }
        }

    fun start() = viewModelScope.launch {
        if (!mutablePermissions.value.ready) {
            repository.issue(TraccarIssue.PERMISSION)
            return@launch
        }
        try {
            repository.enabled(true)
            startService()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            repository.issue(TraccarIssue.CONFIG)
        }
    }

    fun stop() = viewModelScope.launch {
        context.stopService(Intent(context, HatailTraccarService::class.java))
        try {
            repository.enabled(false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            repository.issue(TraccarIssue.STORAGE)
        }
    }

    private fun startService() {
        if (!traccarPermissionsGranted(context)) {
            repository.issue(TraccarIssue.PERMISSION)
            return
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, HatailTraccarService::class.java))
        } catch (_: Exception) {
            repository.issue(TraccarIssue.START)
        }
    }
    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
