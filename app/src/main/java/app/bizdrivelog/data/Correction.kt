package app.bizdrivelog.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 정정 대상. 계기판 거리(주행 전·후)는 원본 보존을 위해 정정 대상이 아니다. */
enum class CorrectionField(val label: String) {
    MODE("구분"),
    PURPOSE("목적"),
    VISIT_PLACE("방문처"),
}

/** 확정된 운행의 정정 이력. 추가만 가능 (DB 트리거로 수정·삭제 차단). MODE의 before/after는 TripMode 이름. */
@Entity(
    tableName = "corrections",
    foreignKeys = [ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"])],
    indices = [Index("tripId")],
)
data class Correction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val field: CorrectionField,
    val before: String,
    val after: String,
    val reason: String,
    val createdAt: Long,
)
