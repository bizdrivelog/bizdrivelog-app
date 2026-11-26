package app.bizdrivelog.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** GleoActionActivity → MainActivity 요청 전달: 한 번만 꺼내고, 7초가 지난 요청은 버린다 */
class GleoInboxTest {

    private var now = 1_000_000L
    private val inbox = GleoInbox<String>(clock = { now })

    @Test fun `비어 있으면 null`() {
        assertNull(inbox.take())
    }

    @Test fun `한 번만 꺼낸다`() {
        inbox.put("last_month")
        assertEquals("last_month", inbox.take())
        assertNull(inbox.take())
    }

    @Test fun `7초 안이면 꺼내고 7초가 지나면 null`() {
        inbox.put("a")
        now += GleoInbox.MAX_AGE_MS
        assertEquals("a", inbox.take())
        inbox.put("b")
        now += GleoInbox.MAX_AGE_MS + 1
        assertNull(inbox.take())
        assertNull(inbox.take())
    }

    @Test fun `새 요청이 앞 요청을 대신한다`() {
        inbox.put("this_month")
        inbox.put("last_month")
        assertEquals("last_month", inbox.take())
        assertNull(inbox.take())
    }
}
