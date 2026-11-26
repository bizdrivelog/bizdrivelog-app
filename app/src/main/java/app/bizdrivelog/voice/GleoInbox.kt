package app.bizdrivelog.voice

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicReference

/**
 * GleoActionActivity(권한으로 Gleo만 부를 수 있는 입구)가 받은 요청을 같은 프로세스 안에서 MainActivity로 넘긴다.
 * MainActivity는 런처 때문에 외부에 열려 있으므로 인텐트의 action은 보지 않고 여기서만 요청을 꺼낸다.
 * 한 번 꺼내면 비고, 넣은 지 [maxAgeMs]가 지난 요청은 버린다 (Gleo는 7초 뒤 취소).
 */
class GleoInbox<T : Any>(
    private val maxAgeMs: Long = MAX_AGE_MS,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val slot = AtomicReference<Pair<T, Long>?>(null)

    fun put(value: T) = slot.set(value to clock())

    fun take(): T? {
        val (value, at) = slot.getAndSet(null) ?: return null
        return value.takeIf { clock() - at in 0..maxAgeMs }
    }

    companion object {
        const val MAX_AGE_MS = 7_000L
    }
}
