package app.bizdrivelog.drive

import android.car.VehicleGear

/**
 * 앱이 먼저 음성으로 묻는 것은 기어 P에서만. D·R·N이거나 기어를 아직 모르면(서비스 시작 직후 등) 묻지 않는다.
 * 정차 후 자동 질문(P 전환)과 확인 카드의 "음성으로 답하기"가 모두 이 판단을 거친다.
 */
object VoiceGate {
    fun canAsk(gear: Int?): Boolean = gear == VehicleGear.GEAR_PARK
}
