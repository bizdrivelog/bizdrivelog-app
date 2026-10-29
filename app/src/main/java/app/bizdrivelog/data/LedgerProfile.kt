package app.bizdrivelog.data

import android.content.Context

/** 운행기록부 머리 정보. ④ 사용자는 운전자가 아니라 차량 이용자(부서·성명). 차종은 CarInfo로 안 나와서 직접 입력. */
data class LedgerProfile(
    val company: String = "",
    val bizNo: String = "",
    val carModel: String = "",
    val plate: String = "",
    val dept: String = "",
    val userName: String = "",
) {
    val missing: List<String>
        get() = listOf(
            "상호" to company, "사업자등록번호" to bizNo, "차종" to carModel,
            "자동차등록번호" to plate, "부서" to dept, "성명" to userName,
        ).filter { it.second.isBlank() }.map { it.first }
}

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("ledger_profile", Context.MODE_PRIVATE)

    fun load() = LedgerProfile(
        company = prefs.getString("company", "").orEmpty(),
        bizNo = prefs.getString("bizNo", "").orEmpty(),
        carModel = prefs.getString("carModel", "").orEmpty(),
        plate = prefs.getString("plate", "").orEmpty(),
        dept = prefs.getString("dept", "").orEmpty(),
        userName = prefs.getString("userName", "").orEmpty(),
    )

    fun save(profile: LedgerProfile) {
        prefs.edit()
            .putString("company", profile.company.trim())
            .putString("bizNo", profile.bizNo.trim())
            .putString("carModel", profile.carModel.trim())
            .putString("plate", profile.plate.trim())
            .putString("dept", profile.dept.trim())
            .putString("userName", profile.userName.trim())
            .apply()
    }
}
