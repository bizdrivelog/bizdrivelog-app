package app.bizdrivelog.data

/** 원본 운행 + 정정 이력. 화면의 원본 값은 그대로 두고, 집계에는 정정 반영값(effective*)을 쓴다. */
data class TripRecord(val trip: Trip, val corrections: List<Correction>) {
    val effectiveMode: TripMode? =
        corrections.lastOrNull { it.field == CorrectionField.MODE }?.let { TripMode.valueOf(it.after) } ?: trip.mode
    val effectivePurpose: String =
        corrections.lastOrNull { it.field == CorrectionField.PURPOSE }?.after ?: trip.purpose
    val effectiveVisitPlace: String =
        corrections.lastOrNull { it.field == CorrectionField.VISIT_PLACE }?.after ?: trip.visitPlace

    /** 업무용 사용거리 = 출퇴근 + 일반업무 (법인세법 시행규칙 제27조의2 ⑦) */
    val businessUseKm: Double
        get() = if (effectiveMode == TripMode.BUSINESS || effectiveMode == TripMode.COMMUTE) trip.distanceKm ?: 0.0 else 0.0
}

fun Correction.displayValue(value: String): String = when {
    value.isEmpty() -> "(없음)"
    field == CorrectionField.MODE -> TripMode.valueOf(value).label
    else -> value
}
