package org.driftlock.app.trips

import androidx.room.*

/** Low-frequency index only. Raw IMU samples stay in buffered session files. */
@Entity(tableName="trip_packs")
data class TripRecord(@PrimaryKey val id:String,val name:String,val bytes:Long,val routeCount:Int,val updatedMs:Long)
@Dao interface TripDao {
    @Query("SELECT * FROM trip_packs ORDER BY updatedMs DESC") fun all():List<TripRecord>
    @Insert(onConflict=OnConflictStrategy.REPLACE) fun put(record:TripRecord)
    @Query("DELETE FROM trip_packs WHERE id=:id") fun remove(id:String)
}
@Database(entities=[TripRecord::class],version=1,exportSchema=true)
abstract class TripDatabase:RoomDatabase() { abstract fun trips():TripDao }
