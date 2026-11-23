package app.bizdrivelog.export

import app.bizdrivelog.export.DownloadGate.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 일회용 링크: 전송 중 같은 IP는 허용, 다른 IP는 409, 끊기면 다시 받을 수 있고, 다 받으면 410 */
class DownloadGateTest {

    private val phone = "192.168.43.20"
    private val other = "192.168.43.30"

    @Test fun `전송 중 같은 IP의 두 번째 요청은 허용`() {
        val gate = DownloadGate()
        assertEquals(Decision.Serve(1), gate.request(phone))
        assertEquals(Decision.Serve(2), gate.request(phone))
    }

    @Test fun `전송 중 다른 IP는 409`() {
        val gate = DownloadGate()
        gate.request(phone)
        assertEquals(Decision.Busy, gate.request(other))
    }

    @Test fun `중간에 끊기면 다시 받을 수 있다`() {
        val gate = DownloadGate()
        val first = gate.request(phone) as Decision.Serve
        assertFalse(gate.finished(first.id, complete = false))
        // 점유가 풀려 다른 기기도, 같은 기기도 다시 받을 수 있다
        assertTrue(gate.request(other) is Decision.Serve)
    }

    @Test fun `다 받은 뒤에는 누구든 410`() {
        val gate = DownloadGate()
        val first = gate.request(phone) as Decision.Serve
        assertTrue(gate.finished(first.id, complete = true))
        assertEquals(Decision.Gone, gate.request(phone))
        assertEquals(Decision.Gone, gate.request(other))
    }
}
