package com.forwardsweep.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.forwardsweep.classifier.Category
import com.forwardsweep.classifier.MediaType
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "scanned_images",
    indices = [Index("effectiveCategory"), Index("duplicateGroup")],
)
data class ScannedImageEntity(
    @PrimaryKey val uri: String,
    val mediaId: Long,
    val displayName: String,
    val relativePath: String?,
    val source: MediaSource,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val dateTaken: Long,
    val dateModified: Long,
    val pHash: Long,
    /** What the classifier decided. */
    val category: Category,
    val topCategory: Category,
    val confidence: Double,
    /** "real,greeting,meme,screenshot" probabilities. */
    val probabilities: String,
    val reasons: String,
    val protectedImage: Boolean,
    val preselected: Boolean,
    val faces: Int,
    /** Set when the user moved the image to another category. */
    val userCategory: Category? = null,
    /** userCategory ?: category, kept as a column so it can be indexed and grouped. */
    val effectiveCategory: Category,
    /** Shared id for near-duplicates; null when the image is unique. */
    val duplicateGroup: Long? = null,
    val scannedAt: Long,
    val classifierVersion: Int,
    // ── Stored scores, so the library can be re-sorted after a correction without decoding ──
    /** Default scorer logits "real,greeting,meme,screenshot"; null for rows from version 1. */
    val baseLogits: String? = null,
    val baseReasons: String = "",
    val exifEvidence: Double = 0.0,
    val ocrRan: Boolean = false,
    val words: Int = 0,
    val greetingKeyword: Boolean = false,
    /** CLIP embedding, little-endian float32. */
    val embedding: ByteArray? = null,
    val mediaType: MediaType = MediaType.IMAGE,
    /** Video length; 0 for images. */
    val durationMs: Long = 0,
)

enum class MediaSource { WHATSAPP, WHATSAPP_BUSINESS, GALLERY }

/**
 * A training example for the personal model. weight 1.0 = the user moved the image; 0.5 = an
 * implicit confirmation (the user deleted a greeting/meme). Kept after the image is deleted, so
 * it stores its own copy of the embedding and scores.
 */
@Entity(tableName = "corrections")
data class CorrectionEntity(
    @PrimaryKey val uri: String,
    val pHash: Long,
    val fromCategory: Category,
    val toCategory: Category,
    val correctedAt: Long,
    val weight: Double = 1.0,
    val baseLogits: String? = null,
    val embedding: ByteArray? = null,
)

data class CategorySummary(
    @ColumnInfo(name = "effectiveCategory") val category: Category,
    val count: Int,
    val totalBytes: Long,
)

/** Minimal projection for incremental scans. */
data class ScanStamp(val uri: String, val dateModified: Long, val classifierVersion: Int)

data class HashRow(val uri: String, val pHash: Long, val sizeBytes: Long)

/** Lightweight projection for selection state and the Home totals. */
data class SelectRow(
    val uri: String,
    val sizeBytes: Long,
    val preselected: Boolean,
    val protectedImage: Boolean,
    val effectiveCategory: Category,
)

@Dao
interface ImageDao {
    @Upsert
    suspend fun upsert(items: List<ScannedImageEntity>)

    @Query("SELECT uri, dateModified, classifierVersion FROM scanned_images")
    suspend fun stamps(): List<ScanStamp>

    @Query("SELECT uri, pHash, sizeBytes FROM scanned_images")
    suspend fun hashes(): List<HashRow>

    @Query("SELECT effectiveCategory, COUNT(*) AS count, COALESCE(SUM(sizeBytes), 0) AS totalBytes FROM scanned_images GROUP BY effectiveCategory")
    fun summary(): Flow<List<CategorySummary>>

    @Query("SELECT * FROM scanned_images WHERE effectiveCategory = :category ORDER BY preselected DESC, confidence DESC, dateTaken DESC")
    fun byCategory(category: Category): Flow<List<ScannedImageEntity>>

    /** The default clean-up set: pre-selected junk that is not face-protected. */
    @Query(
        """SELECT uri FROM scanned_images WHERE preselected = 1 AND protectedImage = 0
           AND effectiveCategory IN ('GREETING', 'MEME') ORDER BY sizeBytes DESC""",
    )
    suspend fun preselectedUris(): List<String>

    @Query("SELECT uri, sizeBytes, preselected, protectedImage, effectiveCategory FROM scanned_images")
    fun selectRows(): Flow<List<SelectRow>>

    /** Forces the next scan to reclassify everything (user corrections are kept by the scan). */
    @Query("UPDATE scanned_images SET classifierVersion = 0")
    suspend fun markAllForRescan()

    @Query("SELECT * FROM scanned_images WHERE uri = :uri")
    suspend fun get(uri: String): ScannedImageEntity?

    @Query("SELECT * FROM scanned_images WHERE uri IN (:uris)")
    suspend fun getAll(uris: List<String>): List<ScannedImageEntity>

    @Query("SELECT COUNT(*) FROM scanned_images WHERE effectiveCategory = :category AND scannedAt > :since")
    suspend fun countNewSince(category: Category, since: Long): Int

    @Query("DELETE FROM scanned_images WHERE uri IN (:uris)")
    suspend fun delete(uris: List<String>)

    @Query("UPDATE scanned_images SET duplicateGroup = NULL")
    suspend fun clearDuplicateGroups()

    @Query("UPDATE scanned_images SET duplicateGroup = :group WHERE uri IN (:uris)")
    suspend fun setDuplicateGroup(uris: List<String>, group: Long)

    @Query(
        """UPDATE scanned_images SET userCategory = :category, effectiveCategory = :category,
           preselected = :preselected WHERE uri IN (:uris)""",
    )
    suspend fun setUserCategory(uris: List<String>, category: Category, preselected: Boolean)

    /** Undo every user label: rows go back to the classifier's decision. */
    @Query(
        """UPDATE scanned_images SET userCategory = NULL, effectiveCategory = category,
           preselected = (category IN ('GREETING', 'MEME') AND protectedImage = 0)""",
    )
    suspend fun clearUserCategories()

    /** Rows the personal model may re-sort: scored by version 2+ and not labelled by the user. */
    @Query("SELECT * FROM scanned_images WHERE userCategory IS NULL AND baseLogits IS NOT NULL")
    suspend fun redecidable(): List<ScannedImageEntity>

    @Query("SELECT uri FROM scanned_images WHERE duplicateGroup = :group")
    suspend fun duplicateMembers(group: Long): List<String>

    @Transaction
    suspend fun replaceDuplicateGroups(groups: List<List<String>>) {
        clearDuplicateGroups()
        // Room limits bound parameters to 999 per statement.
        groups.forEach { g -> g.chunked(900).forEach { setDuplicateGroup(it, g.first().hashCode().toLong()) } }
    }
}

@Dao
interface CorrectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(c: CorrectionEntity)

    @Query("SELECT * FROM corrections")
    suspend fun all(): List<CorrectionEntity>

    @Query("SELECT COUNT(*) FROM corrections WHERE weight >= 1.0")
    fun countFlow(): Flow<Int>

    @Query("DELETE FROM corrections")
    suspend fun clear()
}

class Converters {
    @TypeConverter fun fromCategory(c: Category?): String? = c?.name
    @TypeConverter fun toCategory(s: String?): Category? = s?.let(Category::valueOf)
    @TypeConverter fun fromSource(s: MediaSource): String = s.name
    @TypeConverter fun toSource(s: String): MediaSource = MediaSource.valueOf(s)
    @TypeConverter fun fromMediaType(t: MediaType): String = t.name
    @TypeConverter fun toMediaType(s: String): MediaType = MediaType.valueOf(s)
}

@Database(entities = [ScannedImageEntity::class, CorrectionEntity::class], version = 4)
@TypeConverters(Converters::class)
abstract class ForwardSweepDatabase : RoomDatabase() {
    abstract fun images(): ImageDao
    abstract fun corrections(): CorrectionDao

    companion object {
        fun create(context: Context): ForwardSweepDatabase =
            // No destructive fallback: the cache could be rebuilt by rescanning, but the user's
            // corrections could not. Schema changes need a real Migration (schemas/ is exported).
            Room.databaseBuilder(context, ForwardSweepDatabase::class.java, "forwardsweep.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()

        /** v4: whether a greeting phrase was recognised (for the "too little text" rule). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scanned_images ADD COLUMN greetingKeyword INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3: videos. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scanned_images ADD COLUMN mediaType TEXT NOT NULL DEFAULT 'IMAGE'")
                db.execSQL("ALTER TABLE scanned_images ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v2: stored scores + CLIP embeddings for on-device learning. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scanned_images ADD COLUMN baseLogits TEXT")
                db.execSQL("ALTER TABLE scanned_images ADD COLUMN baseReasons TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE scanned_images ADD COLUMN exifEvidence REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE scanned_images ADD COLUMN ocrRan INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE scanned_images ADD COLUMN words INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE scanned_images ADD COLUMN embedding BLOB")
                db.execSQL("ALTER TABLE corrections ADD COLUMN weight REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE corrections ADD COLUMN baseLogits TEXT")
                db.execSQL("ALTER TABLE corrections ADD COLUMN embedding BLOB")
            }
        }
    }
}
