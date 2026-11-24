package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cached_feeds")
data class CachedFeedEntity(
    @PrimaryKey val feedKey: String,
    val rawHtml: String,
    val isDebe: Boolean,
    val cachedAtMillis: Long
)

@Entity(tableName = "cached_topics")
data class CachedTopicEntity(
    @PrimaryKey val cacheKey: String,
    val title: String,
    val canonicalUrl: String,
    val rawHtml: String,
    val filterName: String,
    val page: Int,
    val cachedAtMillis: Long
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val id: String, // "topic:<slug>" or "entry:<id>"
    val isEntry: Boolean,
    val title: String,
    val slugUrl: String,
    val entryId: Long? = null,
    val author: String? = null,
    val excerpt: String? = null,
    val savedAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val searchedAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "reading_history")
data class ReadingHistoryEntity(
    @PrimaryKey val slugUrl: String,
    val title: String,
    val lastPage: Int = 1,
    val visitedAtMillis: Long = System.currentTimeMillis()
)

@Dao
interface EksiDao {
    // Feed Cache
    @Query("SELECT * FROM cached_feeds WHERE feedKey = :feedKey LIMIT 1")
    suspend fun getCachedFeed(feedKey: String): CachedFeedEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCachedFeed(entity: CachedFeedEntity)

    // Topic Cache
    @Query("SELECT * FROM cached_topics WHERE cacheKey = :cacheKey LIMIT 1")
    suspend fun getCachedTopic(cacheKey: String): CachedTopicEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCachedTopic(entity: CachedTopicEntity)

    @Query("DELETE FROM cached_feeds")
    suspend fun clearFeedCache()

    @Query("DELETE FROM cached_topics")
    suspend fun clearTopicCache()

    // Bookmarks
    @Query("SELECT * FROM bookmarks ORDER BY savedAtMillis DESC")
    fun observeBookmarks(): Flow<List<BookmarkEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE id = :id)")
    fun observeIsBookmarked(id: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: String)

    // Search History
    @Query("SELECT * FROM search_history ORDER BY searchedAtMillis DESC LIMIT 20")
    fun observeSearchHistory(): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchHistory(entry: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE `query` = :query")
    suspend fun deleteSearchQuery(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clearSearchHistory()

    // Reading History
    @Query("SELECT * FROM reading_history ORDER BY visitedAtMillis DESC LIMIT 50")
    fun observeReadingHistory(): Flow<List<ReadingHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReadingHistory(entry: ReadingHistoryEntity)

    @Query("DELETE FROM reading_history")
    suspend fun clearReadingHistory()
}

@Database(
    entities = [
        CachedFeedEntity::class,
        CachedTopicEntity::class,
        BookmarkEntity::class,
        SearchHistoryEntity::class,
        ReadingHistoryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class EksiDatabase : RoomDatabase() {
    abstract fun eksiDao(): EksiDao

    companion object {
        @Volatile
        private var INSTANCE: EksiDatabase? = null

        fun getInstance(context: Context): EksiDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    EksiDatabase::class.java,
                    "eksi_okur_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
