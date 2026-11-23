package app.bizdrivelog.drive

import android.car.VehicleGear
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 앱이 먼저 음성으로 묻는 것은 기어 P에서만 */
class VoiceGateTest {

    @Test fun `P에서는 묻는다`() = assertTrue(VoiceGate.canAsk(VehicleGear.GEAR_PARK))

    @Test fun `D에서는 묻지 않는다`() = assertFalse(VoiceGate.canAsk(VehicleGear.GEAR_DRIVE))

    @Test fun `R과 N에서도 묻지 않는다`() {
        assertFalse(VoiceGate.canAsk(VehicleGear.GEAR_REVERSE))
        assertFalse(VoiceGate.canAsk(VehicleGear.GEAR_NEUTRAL))
    }

    @Test fun `기어를 모르면 묻지 않는다`() {
        assertFalse(VoiceGate.canAsk(null))
        assertFalse(VoiceGate.canAsk(VehicleGear.GEAR_UNKNOWN))
    }
}
