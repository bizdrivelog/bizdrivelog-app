package app.bizdrivelog.export

/**
 * 일회용 다운로드 링크의 요청 판단 (HTTP 서버와 분리한 순수 로직).
 * - 전송 중이면 같은 IP의 요청은 허용한다 (브라우저가 확인 후 다시 요청하는 경우 등, 이전 전송을 대체).
 *   다른 IP는 409로 거절한다.
 * - 중간에 끊긴 전송은 사용 처리하지 않고 점유를 풀어 다시 받을 수 있게 한다.
 * - 끝까지 보낸 전송이 하나라도 있으면 사용 처리하고 이후 요청은 모두 410.
 */
class DownloadGate {

    sealed interface Decision {
        /** 보내도 됨. 전송이 끝나면 [finished]에 이 id를 넘긴다 */
        data class Serve(val id: Long) : Decision
        /** 다른 기기가 받는 중 (409) */
        data object Busy : Decision
        /** 이미 다 받은 링크 (410) */
        data object Gone : Decision
    }

    private var used = false
    private var owner: String? = null
    /** 가장 최근에 허용한 전송. 끊긴 이전 전송이 점유를 풀지 않도록 구분한다 */
    private var current = 0L

    @Synchronized
    fun request(ip: String): Decision {
        if (used) return Decision.Gone
        if (owner != null && owner != ip) return Decision.Busy
        owner = ip
        current++
        return Decision.Serve(current)
    }

    /** 전송이 끝났을 때(정상·끊김 모두). 이번에 처음 사용 처리됐으면 true */
    @Synchronized
    fun finished(id: Long, complete: Boolean): Boolean {
        if (complete) {
            if (used) return false
            used = true
            owner = null
            return true
        }
        // 같은 IP가 새로 요청해 대체된 이전 전송이 끊긴 것이면 지금 전송의 점유를 풀지 않는다
        if (id == current) owner = null
        return false
    }
}
