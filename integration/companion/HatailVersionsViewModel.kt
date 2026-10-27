package io.homeassistant.companion.android.hatail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.launch

private const val VERSION_CHECK_WORK = "hatail-version-check"
private const val VERSION_CHECK_INTERVAL_HOURS = 24L

@HiltViewModel
internal class HatailVersionsViewModel @Inject constructor(
    private val updates: HatailUpdateRepository,
    private val workManager: WorkManager,
) : ViewModel() {
    val state = updates.state

    init {
        val work = PeriodicWorkRequestBuilder<HatailVersionWorker>(VERSION_CHECK_INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniquePeriodicWork(VERSION_CHECK_WORK, ExistingPeriodicWorkPolicy.KEEP, work)
        viewModelScope.launch { updates.load() }
        refresh()
    }

    fun refresh(manual: Boolean = false) {
        // Work owns the request: a Casa/Sol activity transition must not cancel a version check.
        val work = OneTimeWorkRequestBuilder<HatailVersionWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("manual" to manual))
            .build()
        workManager.enqueueUniqueWork("$VERSION_CHECK_WORK-now", ExistingWorkPolicy.KEEP, work)
    }
}

/** Checks public releases without selecting a home or starting/stopping its VPN. */
class HatailVersionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface Dependencies {
        fun updates(): HatailUpdateRepository
    }

    override suspend fun doWork(): Result {
        EntryPoints.get(
            applicationContext,
            Dependencies::class.java,
        ).updates().refresh(inputData.getBoolean("manual", false))
        return Result.success()
    }
}
