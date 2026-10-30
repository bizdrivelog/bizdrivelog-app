package app.bizdrivelog.data

/**
 * 분류 확인 카드·상세 화면이 쓰는 분류·확정·정정 처리. 화면과 디버그 시연 데이터 생성이 같은 경로를 타도록 한곳에 둔다.
 * 확정 이후의 잠금은 DB 트리거가 강제한다 (확정 운행 UPDATE/DELETE, 정정 이력 UPDATE/DELETE 차단).
 */
object TripActions {

    suspend fun classify(dao: TripDao, tripId: Long, mode: TripMode, modeBy: String, purpose: String, visitPlace: String) {
        dao.classify(tripId, mode, modeBy, purpose.trim(), visitPlace.trim())
    }

    /** 업무 운행은 목적이 있어야 확정할 수 있다 (분류 카드의 "운행 확정" 버튼 조건과 같음) */
    fun canConfirm(mode: TripMode?, purpose: String): Boolean =
        mode != null && (mode != TripMode.BUSINESS || purpose.isNotBlank())

    suspend fun confirm(dao: TripDao, tripId: Long, at: Long = System.currentTimeMillis()) {
        dao.confirm(tripId, at)
    }

    /** 정정 이력 추가. 바뀐 항목만 남기고, 사유가 없거나 확정 전 운행이면 아무것도 하지 않는다. 추가한 항목을 돌려준다. */
    suspend fun correct(
        dao: TripDao,
        record: TripRecord,
        mode: TripMode,
        purpose: String,
        visitPlace: String,
        reason: String,
        now: Long = System.currentTimeMillis(),
    ): List<CorrectionField> {
        val changes = listOf(
            Triple(CorrectionField.MODE, record.effectiveMode?.name.orEmpty(), mode.name),
            Triple(CorrectionField.PURPOSE, record.effectivePurpose, purpose.trim()),
            Triple(CorrectionField.VISIT_PLACE, record.effectiveVisitPlace, visitPlace.trim()),
        ).filter { (_, before, after) -> before != after }
        if (changes.isEmpty() || reason.isBlank() || !record.trip.isConfirmed) return emptyList()
        dao.insertCorrections(
            changes.map { (field, before, after) ->
                Correction(tripId = record.trip.id, field = field, before = before, after = after, reason = reason.trim(), createdAt = now)
            }
        )
        return changes.map { it.first }
    }
}
