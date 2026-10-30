package app.bizdrivelog.data

/** 원본 운행 + 정정 이력. 화면의 원본 값은 그대로 두고, 집계에는 정정 반영값(effective*)을 쓴다. */
data class TripRecord(val trip: Trip, val corrections: List<Correction>) {
    val effectiveMode: TripMode? =
        corrections.lastOrNull { it.field == CorrectionField.MODE }?.let { TripMode.valueOf(it.after) } ?: trip.mode
    val effectivePurpose: String =
        corrections.lastOrNull { it.field == CorrectionField.PURPOSE }?.after ?: trip.purpose
    val effectiveVisitPlace: String =
        corrections.lastOrNull { it.field == CorrectionField.VISIT_PLACE }?.after ?: trip.visitPlace

    /** 해당 항목에 정정 이력이 있는지 (상세 화면의 "정정됨"은 항목별로 붙인다) */
    fun isCorrected(field: CorrectionField): Boolean = corrections.any { it.field == field }

    /** 상세 화면의 구분·목적·방문처: 현재 값(정정 반영)과, 그 항목이 정정된 경우에만 원본 값. MODE 값은 TripMode 이름 */
    val fieldViews: List<FieldView>
        get() = listOf(
            Triple(CorrectionField.MODE, effectiveMode?.name.orEmpty(), trip.mode?.name.orEmpty()),
            Triple(CorrectionField.PURPOSE, effectivePurpose, trip.purpose),
            Triple(CorrectionField.VISIT_PLACE, effectiveVisitPlace, trip.visitPlace),
        ).map { (field, current, original) -> FieldView(field, current, original.takeIf { isCorrected(field) }) }

    /** 업무용 사용거리 = 출퇴근 + 일반업무 (법인세법 시행규칙 제27조의2 ⑦) */
    val businessUseKm: Double
        get() = if (effectiveMode == TripMode.BUSINESS || effectiveMode == TripMode.COMMUTE) trip.distanceKm ?: 0.0 else 0.0
}

data class FieldView(val field: CorrectionField, val current: String, val original: String?) {
    val corrected: Boolean get() = original != null
}

fun Correction.displayValue(value: String): String = when {
    value.isEmpty() -> "(없음)"
    field == CorrectionField.MODE -> TripMode.valueOf(value).label
    else -> value
}
