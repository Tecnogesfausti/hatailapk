package io.homeassistant.companion.android.hatail

import android.content.Context
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import com.tailscale.ipn.App
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import es.tecnogesfausti.hatail.core.TailnetAddressPolicy
import java.io.File
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class TraccarConfig(
    val url: String = "",
    val deviceId: String = "",
    val intervalSeconds: Long = 60,
    val distanceMeters: Float = 25f,
    val accuracyMeters: Float = 100f,
    val enabled: Boolean = false,
)

internal fun validateTraccarConfig(config: TraccarConfig, tailnet: String): TraccarConfig {
    val url = URI(config.url.trim()).normalize()
    require(TailnetAddressPolicy.accepts(url, tailnet))
    require(config.deviceId.trim().matches(Regex("[A-Za-z0-9_.:-]{1,128}")))
    require(config.intervalSeconds in 30..900)
    require(config.distanceMeters.isFinite() && config.distanceMeters in 0f..1000f)
    require(config.accuracyMeters.isFinite() && config.accuracyMeters in 10f..1000f)
    return config.copy(url = url.toString(), deviceId = config.deviceId.trim())
}

/** One latest pending fix: replaying an old route could trigger obsolete arrival automations. */
@Entity(tableName = "pending_position")
internal data class TraccarPosition(
    @PrimaryKey val slot: Int = 1,
    val time: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val speedMetersPerSecond: Float? = null,
    val bearing: Float? = null,
    val altitude: Double? = null,
    val battery: Int? = null,
) {
    fun acceptable(now: Long, maxAccuracy: Float): Boolean = time >= now - 120_000 &&
        time <= now + 30_000 &&
        latitude.isFinite() &&
        longitude.isFinite() &&
        latitude in -90.0..90.0 &&
        longitude in -180.0..180.0 &&
        accuracy.isFinite() &&
        accuracy in 0f..maxAccuracy
}

@Dao
internal interface TraccarPositionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(position: TraccarPosition)

    @Query("SELECT * FROM pending_position WHERE slot = 1")
    suspend fun pending(): TraccarPosition?

    @Query("DELETE FROM pending_position")
    suspend fun clear()
}

@Database(entities = [TraccarPosition::class], version = 1, exportSchema = false)
internal abstract class TraccarDatabase : RoomDatabase() {
    abstract fun positions(): TraccarPositionDao
}

@Module
@InstallIn(SingletonComponent::class)
internal object TraccarDatabaseModule {
    @Provides
    fun clock(): java.time.Clock = java.time.Clock.systemUTC()

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): TraccarDatabase = Room.databaseBuilder(
        context,
        TraccarDatabase::class.java,
        File(context.noBackupFilesDir, "hatail_traccar.db").absolutePath,
    ).build()

    @Provides
    fun positions(database: TraccarDatabase): TraccarPositionDao = database.positions()
}

/** Configuration stays in the embedded client's encrypted preferences, never in source or logs. */
internal class TraccarPreferences @Inject constructor() {
    private val prefs get() = App.get().getEncryptedPrefs()
    suspend fun read(): TraccarConfig = withContext(Dispatchers.IO) {
        TraccarConfig(
            prefs.getString("hatail.traccar.url", "").orEmpty(),
            prefs.getString("hatail.traccar.device", "").orEmpty(),
            prefs.getLong("hatail.traccar.interval", 60),
            prefs.getFloat("hatail.traccar.distance", 25f),
            prefs.getFloat("hatail.traccar.accuracy", 100f),
            prefs.getBoolean("hatail.traccar.enabled", false),
        )
    }
    suspend fun write(config: TraccarConfig) = withContext(Dispatchers.IO) {
        check(
            prefs.edit().putString("hatail.traccar.url", config.url).putString("hatail.traccar.device", config.deviceId)
                .putLong("hatail.traccar.interval", config.intervalSeconds)
                .putFloat("hatail.traccar.distance", config.distanceMeters)
                .putFloat("hatail.traccar.accuracy", config.accuracyMeters)
                .putBoolean("hatail.traccar.enabled", config.enabled).commit(),
        )
    }
    suspend fun tailnet(): String = withContext(Dispatchers.IO) { prefs.getString("hatail.casa.tailnet", "").orEmpty() }
    suspend fun profile(): String = withContext(Dispatchers.IO) { prefs.getString("hatail.casa.profile", "").orEmpty() }
    suspend fun suggestedUrl(): String = withContext(Dispatchers.IO) {
        runCatching {
            val home = URI(prefs.getString("hatail.casa.server_url", "").orEmpty())
            URI("http", null, home.host, 5055, "/", null, null).toString()
        }.getOrDefault("")
    }
    suspend fun lastSent(): Long = withContext(Dispatchers.IO) { prefs.getLong("hatail.traccar.last_sent", 0) }
    suspend fun sent(time: Long) = withContext(Dispatchers.IO) {
        check(prefs.edit().putLong("hatail.traccar.last_sent", time).commit())
    }
}
