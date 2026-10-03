package com.chamet.guesser

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "rounds")
data class RoundEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    // TIMING
    val timestamp: Long,
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val dayOfWeek: Int,
    val timeSinceLastRound: Long,
    val sessionId: Long,

    // ROADS (legacy banner slots — kept for Session 5 analytics)
    val r1: String,
    val r2: String? = null,
    val r3: String? = null,

    // Screen positions (left/mid/right as seen)
    val carPosition1: String? = null,
    val carPosition2: String? = null,
    val carPosition3: String? = null,

    // VEHICLES final rank order 1st/2nd/3rd
    val v1: String,
    val v2: String? = null,
    val v3: String? = null,

    val mathV1: String? = null,
    val mathV2: String? = null,
    val mathV3: String? = null,

    val confidence: Int = 0,
    val modeObserved: String? = null,
    val winner: String? = null,
    val winnerPosition: Int? = null,
    val won: Boolean? = null,

    val balanceBeforeBet: Long = 0,
    val balanceAfterBet: Long = 0,
    val balanceAfterPayout: Long = 0,
    val poolA: Long = 0,
    val poolB: Long = 0,
    val poolC: Long = 0,
    val oddsA: Double = 0.0,
    val oddsB: Double = 0.0,
    val oddsC: Double = 0.0,
    val evA: Double = 0.0,
    val evB: Double = 0.0,
    val evC: Double = 0.0,
    val suggestedBet1: Int = 0,
    val suggestedBet2: Int = 0,
    val suggestedCar1: String? = null,
    val suggestedCar2: String? = null,
    val totalSuggestedBet: Int = 0,
    val lastRefreshTimestamp: Long = 0,

    val ocrSource: String? = null,
    val aiProviderUsed: String? = null,
    val aiFallbackTriggered: Boolean = false,

    // ---- Session 1 (v5) columns — all default so v4 rows survive migration ----
    /** Stable id shared with race_track and future server sync. */
    val roundUuid: String = "",
    /** Which banner slot showed the visible road: 1=R1, 2=R2, 3=R3. */
    val revealedSlot: Int? = null,
    /** Canonical visible road name (e.g. "Desert"), or null if unknown. */
    val visibleRoad: String? = null,
    /** Full layout once the race strip is read (Session 4+). */
    val roadType1: String? = null,
    val roadType2: String? = null,
    val roadType3: String? = null,
    val roadPx1: Double? = null,
    val roadPx2: Double? = null,
    val roadPx3: Double? = null,
    val trackWidthPx: Double? = null,
    /** Sum of the three pools as shown on screen (post-close check). */
    val poolTotalShown: Long? = null,
    /** Top-view lane cars as "name1|name2|name3". */
    val laneCars: String? = null,
    /** Crossing order as "name1|name2|name3". */
    val finishOrder: String? = null,
    val modelWinner: String? = null,
    /** JSON map of vehicle → predicted finish time. */
    val modelTimes: String? = null,
    /** JSON of what the engine returned for this round. */
    val pickJson: String? = null,
    val paramsVersion: String? = null,
    /** track / screen / balance / manual */
    val winnerSource: String? = null,
    /** screen / ai / manual */
    val roadSource: String? = null,
    /** betting / closed / race / finish / saved */
    val captureStage: String? = null,
    val synced: Boolean = false
)

/**
 * Per-frame samples of the three lane positions during a race (Session 5+).
 * Up to ~100 rows per roundUuid. Linked by roundUuid (not a hard FK so empty
 * legacy rows and partial inserts stay valid).
 */
@Entity(
    tableName = "race_track",
    primaryKeys = ["roundUuid", "timeMs"],
    indices = [Index("roundUuid")]
)
data class RaceTrackEntity(
    val roundUuid: String,
    /** Milliseconds from race start. */
    val timeMs: Long,
    val x1: Float,
    val x2: Float,
    val x3: Float
)

@Dao
interface RoundDao {
    @Insert
    suspend fun insert(round: RoundEntity): Long

    @Query("SELECT * FROM rounds ORDER BY timestamp DESC")
    suspend fun getAll(): List<RoundEntity>

    @Query("SELECT * FROM rounds WHERE won = 1")
    suspend fun getWins(): List<RoundEntity>

    @Query("SELECT * FROM rounds WHERE won = 0")
    suspend fun getLosses(): List<RoundEntity>

    @Query("SELECT COUNT(*) FROM rounds")
    suspend fun totalRounds(): Int

    @Query("SELECT COUNT(*) FROM rounds WHERE won = 1")
    suspend fun totalWins(): Int

    @Query("SELECT * FROM rounds ORDER BY timestamp DESC LIMIT :n")
    suspend fun getRecent(n: Int): List<RoundEntity>

    @Query("DELETE FROM rounds")
    suspend fun clearAll()

    @Query("UPDATE rounds SET roundUuid = :uuid WHERE id = :id AND (roundUuid IS NULL OR roundUuid = '')")
    suspend fun setRoundUuid(id: Long, uuid: String)

    @Query("SELECT id FROM rounds WHERE roundUuid IS NULL OR roundUuid = ''")
    suspend fun idsMissingRoundUuid(): List<Long>

    @Query("UPDATE rounds SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: Long)

    @Query("SELECT * FROM rounds WHERE roundUuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): RoundEntity?
}

@Dao
interface RaceTrackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(samples: List<RaceTrackEntity>)

    @Query("SELECT * FROM race_track WHERE roundUuid = :uuid ORDER BY timeMs ASC")
    suspend fun getForRound(uuid: String): List<RaceTrackEntity>

    @Query("DELETE FROM race_track WHERE roundUuid = :uuid")
    suspend fun deleteForRound(uuid: String)

    @Query("DELETE FROM race_track")
    suspend fun clearAll()
}
