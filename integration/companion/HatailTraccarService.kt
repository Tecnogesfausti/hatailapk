package io.homeassistant.companion.android.hatail

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.common.R
import io.homeassistant.companion.android.launch.LaunchActivity
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val TRACCAR_SCREEN_EXTRA = "hatail.open_traccar"
private const val TRACKING_CHANNEL = "hatail_traccar"
private const val TRACKING_NOTIFICATION = 48110
private const val STOP_TRACKING = "hatail.traccar.STOP"

internal fun traccarPermissionsGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED &&
        (
            Build.VERSION.SDK_INT < 29 ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
            ) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()

/** Independent from activities and the selected HA dashboard. Location never leaves via a physical network. */
@AndroidEntryPoint
class HatailTraccarService :
    Service(),
    LocationListener {
    @Inject internal lateinit var repository: TraccarRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val locations get() = getSystemService(LocationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP_TRACKING) {
            runCatching { locations.removeUpdates(this) }
            job?.cancel()
            scope.launch {
                try {
                    repository.enabled(false)
                } catch (_: Exception) {
                    repository.issue(TraccarIssue.STORAGE)
                } finally {
                    stopSelf()
                }
            }
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_STICKY
        if (!traccarPermissionsGranted(this)) {
            repository.issue(TraccarIssue.PERMISSION)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    TRACKING_CHANNEL,
                    getString(R.string.hatail_traccar_title),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            val open = LaunchActivity.newInstance(this).putExtra(TRACCAR_SCREEN_EXTRA, true)
            val stop = Intent(this, HatailTraccarService::class.java).setAction(STOP_TRACKING)
            val notification = NotificationCompat.Builder(this, TRACKING_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_ic_notification)
                .setContentTitle(getString(R.string.hatail_traccar_active))
                .setContentText(getString(R.string.hatail_traccar_notification))
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        TRACKING_NOTIFICATION,
                        open,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
                .addAction(
                    0,
                    getString(R.string.hatail_traccar_stop),
                    PendingIntent.getService(this, TRACKING_NOTIFICATION, stop, PendingIntent.FLAG_IMMUTABLE),
                )
                .setOngoing(true).setOnlyAlertOnce(true).build()
            ServiceCompat.startForeground(
                this,
                TRACKING_NOTIFICATION,
                notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (_: Exception) {
            repository.issue(TraccarIssue.START)
            stopSelf()
            return START_NOT_STICKY
        }
        job = scope.launch {
            try {
                repository.load()
                val config = repository.state.value.config
                if (!config.enabled) {
                    stopSelf()
                    return@launch
                }
                var providers = 0
                for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                    if (provider in locations.allProviders) {
                        locations.requestLocationUpdates(
                            provider,
                            config.intervalSeconds * 1000,
                            config.distanceMeters,
                            this@HatailTraccarService,
                            Looper.getMainLooper(),
                        )
                        if (locations.isProviderEnabled(provider)) providers++
                    }
                }
                repository.running(true)
                if (providers == 0) repository.issue(TraccarIssue.GPS)
                while (isActive) {
                    repository.flush()
                    delay(30_000)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                repository.issue(TraccarIssue.PERMISSION)
                stopSelf()
            } catch (_: Exception) {
                repository.issue(TraccarIssue.START)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onLocationChanged(location: Location) {
        if (!location.hasAccuracy() || (Build.VERSION.SDK_INT >= 31 && location.isMock)) return
        val point = TraccarPosition(
            time = location.time,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() },
            bearing = location.bearing.takeIf { location.hasBearing() },
            altitude = location.altitude.takeIf { location.hasAltitude() },
            battery = getSystemService(
                BatteryManager::class.java,
            ).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
        )
        scope.launch {
            try {
                repository.record(point)
                repository.flush()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                repository.issue(TraccarIssue.STORAGE)
            }
        }
    }

    override fun onProviderDisabled(provider: String) {
        repository.issue(TraccarIssue.GPS)
    }
    override fun onProviderEnabled(provider: String) {
        repository.issue(TraccarIssue.NONE)
    }

    @Deprecated("Legacy location callback")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

    override fun onDestroy() {
        runCatching { locations.removeUpdates(this) }
        scope.cancel()
        repository.running(false)
        super.onDestroy()
    }
}
