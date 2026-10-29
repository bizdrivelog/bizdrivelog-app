package app.bizdrivelog.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Insert
    suspend fun insert(trip: Trip): Long

    @Update
    suspend fun update(trip: Trip)

    @Query("SELECT * FROM trips WHERE endedAt IS NULL ORDER BY id DESC LIMIT 1")
    suspend fun findOpen(): Trip?

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun find(id: Long): Trip?

    @Query("SELECT * FROM trips ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<Trip>>

    @Query("SELECT * FROM corrections ORDER BY id")
    fun observeCorrections(): Flow<List<Correction>>

    @Query("UPDATE trips SET mode = :mode, modeBy = :modeBy, purpose = :purpose, visitPlace = :visitPlace WHERE id = :id AND confirmedAt IS NULL")
    suspend fun updateClassification(id: Long, mode: TripMode, modeBy: String, purpose: String, visitPlace: String)

    /** 개인 운행: 좌표·장소 삭제, 거리만 남김 */
    @Query("UPDATE trips SET startLat = NULL, startLng = NULL, endLat = NULL, endLng = NULL, fromPlace = NULL, toPlace = NULL WHERE id = :id AND confirmedAt IS NULL")
    suspend fun clearLocation(id: Long)

    @Query("UPDATE trips SET confirmedAt = :at WHERE id = :id AND confirmedAt IS NULL AND endedAt IS NOT NULL AND mode IS NOT NULL")
    suspend fun confirm(id: Long, at: Long)

    @Insert
    suspend fun insertCorrections(corrections: List<Correction>)

    @Transaction
    suspend fun classify(id: Long, mode: TripMode, modeBy: String, purpose: String, visitPlace: String) {
        updateClassification(id, mode, modeBy, purpose, visitPlace)
        if (mode == TripMode.PERSONAL) clearLocation(id)
    }
}

@Dao
interface PlaceDao {
    @Upsert
    suspend fun upsert(place: Place)

    @Query("SELECT * FROM places")
    suspend fun getAll(): List<Place>

    @Query("SELECT * FROM places")
    fun observeAll(): Flow<List<Place>>
}
