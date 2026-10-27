@file:OptIn(kotlin.time.ExperimentalTime::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.homeassistant.companion.android.hatail

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.homeassistant.companion.android.BuildConfig
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface HistoryFilter {
    data object All : HistoryFilter
    data object Hatail : HistoryFilter
    data object Official : HistoryFilter
}

internal data class HatailHistoryState(
    val entries: List<HatailHistoryItem> = emptyList(),
    val filter: HistoryFilter = HistoryFilter.All,
    val accessGranted: Boolean = false,
    val sampleFailed: Boolean = false,
)

internal class HatailHistoryAccess @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun granted(): Boolean = withContext(Dispatchers.IO) {
        val component = ComponentName(context, HatailNotificationListener::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
        } else {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
                .split(':').any { ComponentName.unflattenFromString(it) == component }
        }
    }
}

internal fun historyAccessIntent(context: Context): Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
        ComponentName(context, HatailNotificationListener::class.java).flattenToString(),
    )
} else {
    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
}

@HiltViewModel
internal class HatailHistoryViewModel @Inject constructor(
    private val history: HatailHistoryRepository,
    private val access: HatailHistoryAccess,
    private val samples: HatailHistorySample,
    clock: Clock,
    workManager: WorkManager,
) : ViewModel() {
    private val filter = MutableStateFlow<HistoryFilter>(HistoryFilter.All)
    private val granted = MutableStateFlow(false)
    private val sampleFailed = MutableStateFlow(false)
    private val cutoff = flow {
        while (true) {
            emit(clock.now().toEpochMilliseconds() - HISTORY_RETENTION.inWholeMilliseconds)
            delay(1.minutes)
        }
    }
    private val entries = cutoff.flatMapLatest(history::observe)
    val state = combine(entries, filter, granted, sampleFailed) { alerts, selected, allowed, sampleError ->
        HatailHistoryState(
            entries = alerts.filter {
                when (selected) {
                    HistoryFilter.All -> true
                    HistoryFilter.Hatail -> it.sourcePackage == BuildConfig.APPLICATION_ID
                    HistoryFilter.Official -> it.sourcePackage in OFFICIAL_COMPANION_PACKAGES
                }
            },
            filter = selected,
            accessGranted = allowed,
            sampleFailed = sampleError,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HatailHistoryState())

    init {
        workManager.enqueueUniquePeriodicWork(
            "hatail-history-cleanup",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HatailHistoryCleanupWorker>(1, TimeUnit.HOURS).build(),
        )
        viewModelScope.launch { history.prune() }
        refreshAccess()
    }

    fun select(value: HistoryFilter) {
        filter.value = value
    }
    fun refreshAccess() {
        viewModelScope.launch { granted.value = access.granted() }
    }
    fun sample() {
        sampleFailed.value = !samples.post()
    }
    suspend fun image(id: String): ByteArray? = history.image(id)
}

/** Removes expired archive rows and their private image copies without requiring a VPN. */
class HatailHistoryCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface Dependencies {
        fun history(): HatailHistoryRepository
    }

    override suspend fun doWork(): Result {
        EntryPoints.get(applicationContext, Dependencies::class.java).history().prune()
        return Result.success()
    }
}
