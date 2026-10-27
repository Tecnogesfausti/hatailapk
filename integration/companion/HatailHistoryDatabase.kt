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
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "received_alerts")
internal data class HatailHistoryItem(
    @PrimaryKey val id: String,
    val sourcePackage: String,
    val postedAtEpochMs: Long,
    val title: String,
    val message: String,
    val origin: String?,
    val imageExpected: Boolean,
    val hasImage: Boolean = false,
)

@Dao
internal interface HatailHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(item: HatailHistoryItem)

    @Query("SELECT * FROM received_alerts WHERE postedAtEpochMs >= :cutoff ORDER BY postedAtEpochMs DESC, id DESC")
    fun observeSince(cutoff: Long): Flow<List<HatailHistoryItem>>

    @Query("SELECT * FROM received_alerts WHERE id = :id")
    suspend fun get(id: String): HatailHistoryItem?

    @Query("DELETE FROM received_alerts WHERE postedAtEpochMs < :cutoff")
    suspend fun deleteBefore(cutoff: Long)

    @Query("SELECT id FROM received_alerts")
    suspend fun ids(): List<String>
}

/** Separate schema so upstream Companion migrations cannot collide with the archive. */
@Database(entities = [HatailHistoryItem::class], version = 1, exportSchema = false)
internal abstract class HatailHistoryDatabase : RoomDatabase() {
    abstract fun alerts(): HatailHistoryDao
}

@Module
@InstallIn(SingletonComponent::class)
internal object HatailHistoryDatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): HatailHistoryDatabase =
        Room.databaseBuilder(context, HatailHistoryDatabase::class.java, "hatail_notification_history").build()

    @Provides
    fun alerts(database: HatailHistoryDatabase): HatailHistoryDao = database.alerts()
}
