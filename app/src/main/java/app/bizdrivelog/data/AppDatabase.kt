package app.bizdrivelog.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Trip::class, Place::class, Correction::class], version = 3)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun placeDao(): PlaceDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "bizdrivelog.db")
                // 출시 전이라 스키마가 바뀌면 개발용으로 초기화한다. 출시 후에는 Migration으로 바꿀 것
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .addCallback(LockTriggers)
                .build()
    }
}

/** v3: ④ 사용자 스냅샷 열 추가 (기존 2단계 기록 유지) */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE trips ADD COLUMN userDept TEXT")
        db.execSQL("ALTER TABLE trips ADD COLUMN userName TEXT")
    }
}

/** 기록 잠금을 앱 코드가 아니라 DB에서 강제한다. 테이블을 다시 만들면 트리거도 지워지므로 열 때마다 확인한다. */
private object LockTriggers : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        listOf(
            """CREATE TRIGGER IF NOT EXISTS trips_lock_confirmed BEFORE UPDATE ON trips
               WHEN OLD.confirmedAt IS NOT NULL
               BEGIN SELECT RAISE(ABORT, 'confirmed trip is locked; add a correction instead'); END""",
            """CREATE TRIGGER IF NOT EXISTS trips_keep_odometer BEFORE UPDATE ON trips
               WHEN OLD.endOdometerKm IS NOT NULL
                 AND (NEW.startOdometerKm IS NOT OLD.startOdometerKm OR NEW.endOdometerKm IS NOT OLD.endOdometerKm)
               BEGIN SELECT RAISE(ABORT, 'odometer readings are immutable'); END""",
            """CREATE TRIGGER IF NOT EXISTS trips_no_delete_confirmed BEFORE DELETE ON trips
               WHEN OLD.confirmedAt IS NOT NULL
               BEGIN SELECT RAISE(ABORT, 'confirmed trip cannot be deleted'); END""",
            """CREATE TRIGGER IF NOT EXISTS corrections_no_update BEFORE UPDATE ON corrections
               BEGIN SELECT RAISE(ABORT, 'corrections are append-only'); END""",
            """CREATE TRIGGER IF NOT EXISTS corrections_no_delete BEFORE DELETE ON corrections
               BEGIN SELECT RAISE(ABORT, 'corrections are append-only'); END""",
        ).forEach(db::execSQL)
    }
}
