package app.bizdrivelog.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class PlaceKind(val label: String) {
    HOME("자택"),
    OFFICE("회사"),
}

@Entity(tableName = "places")
data class Place(
    @PrimaryKey val kind: PlaceKind,
    val lat: Double,
    val lng: Double,
    val radiusMeters: Float = DEFAULT_RADIUS_METERS,
    val registeredAt: Long,
) {
    companion object {
        const val DEFAULT_RADIUS_METERS = 300f
    }
}
