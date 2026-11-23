package app.bizdrivelog.voice

import app.bizdrivelog.data.TripMode

/**
 * 운행 종료 질문("이번 운행은 업무로 기록할까요? 목적을 말씀해 주세요")에 대한 음성 응답을
 * 분류·목적·방문처로 바꾼다. 규칙 기반. 결과는 분류 확인 카드에 채우기만 하고 확정은 사용자가 한다.
 */
data class VoiceAnswer(
    val mode: TripMode?,
    val purpose: String = "",
    val visitPlace: String = "",
) {
    companion object {
        private val PERSONAL = listOf("개인", "사적", "사사로", "업무 아니", "업무가 아니", "업무는 아니")
        private val COMMUTE_OUT = listOf("퇴근")
        private val COMMUTE_IN = listOf("출근")
        /** 짧은 대답은 단어 전체가 같을 때만 ("어디", "예약"처럼 이 글자로 시작하는 말이 많아서) */
        private val YES_EXACT = listOf("네", "예", "응", "어")
        /** 활용형이 붙는 대답은 앞부분 일치 ("맞아요", "그래요", "기록해 줘") */
        private val YES_PREFIX = listOf("맞아", "맞습니다", "그래", "좋아", "기록해")
        private val NO = listOf("아니", "아뇨", "아니요")

        /** 목적 키워드 → 운행기록부에 남길 목적 (앞에 있는 것이 우선) */
        private val PURPOSES = listOf(
            listOf("미팅", "회의", "상담", "협의") to "미팅",
            listOf("납품", "배송", "배달", "운반") to "납품·배송",
            listOf("출장") to "출장",
            listOf("교육", "세미나", "행사", "박람회", "전시회") to "교육·행사",
            listOf("점검", "현장", "수리", "설치", "A/S", "AS") to "현장 업무",
            listOf("영업") to "영업",
            listOf("은행", "세무서", "관공서", "구청", "시청", "법원", "등기소") to "관공서·은행 업무",
            listOf("방문", "들렀", "다녀") to "거래처 방문",
        )
        private val BUSINESS = listOf("업무", "거래처", "고객", "회사 일", "외근") + PURPOSES.flatMap { it.first }

        /** 어디에 들렀다는 뜻만 있는 일반 동사. 출퇴근 도중이면 이것만으로는 업무로 보지 않는다 (마트·약국에 들른 것일 수 있음) */
        private val GENERIC_VISIT = listOf("방문", "들렀", "다녀")
        /** 출퇴근 도중 업무로 볼 구체적 근거: 일반 동사를 뺀 목적 키워드 + 거래처·고객 */
        private val SPECIFIC_PURPOSE = listOf("거래처", "고객") + PURPOSES.flatMap { it.first }.filter { it !in GENERIC_VISIT }

        /** 출퇴근하는 도중 ("출근길에", "퇴근하면서", "퇴근하는 길에"). 구체적 업무 근거가 함께 있으면 업무로 본다 */
        private val COMMUTE_EN_ROUTE = Regex("""(출근|퇴근)(길|\s*하면서|\s*하는\s*길)""")

        /** 회사·기관 이름 끝말 (○○상사, △△전자 …) */
        private val ORG_SUFFIX =
            "상사|물산|산업|전자|건설|기업|공업|화학|제약|통상|무역|유통|식품|은행|병원|의원|약국|공단|공사|재단|협회|조합|" +
                "본사|지사|지점|사무소|사무실|공장|센터|연구소|대학교|대학|학교|시청|구청|세무서|법원|등기소|㈜|주식회사"
        private val ORG = Regex("""([0-9A-Za-z가-힣·&]+(?:$ORG_SUFFIX))""")

        /** "○○ 방문", "○○에 다녀왔어", "○○랑 미팅" 처럼 장소·상대가 조사로 이어지는 경우 */
        private val PLACE_BEFORE_VERB = Regex(
            """([0-9A-Za-z가-힣·&]{2,15}?)(?:에서|에|로|으로|랑|이랑|과|와|하고|을|를)?\s*(?:방문|미팅|회의|상담|납품|배송|다녀|들렀|갔|가서|점검|설치)""",
        )
        private val NOT_A_PLACE = setOf(
            "거래처", "고객", "업무", "회사", "회의", "미팅", "출장", "외근", "그냥", "오늘", "방금", "아까", "잠깐", "개인", "업무상",
            "거래처에", "고객사", "현장",
        )

        fun parse(raw: String, suggested: TripMode?): VoiceAnswer? {
            val text = normalize(raw)
            if (text.isEmpty()) return null

            if (PERSONAL.any { it in text }) return VoiceAnswer(TripMode.PERSONAL)

            val visit = visitPlace(text)
            val purpose = PURPOSES.firstOrNull { (keys, _) -> keys.any { it in text } }?.second
            // "출근길에 거래처 들렀어"처럼 가는 도중 구체적인 업무를 봤으면 업무, 그 밖의 출근·퇴근은 출퇴근
            // ("퇴근길에 마트 들렀어", "퇴근길에 한빛상사 들렀어"처럼 들른 곳만 있으면 출퇴근)
            val businessOnTheWay = COMMUTE_EN_ROUTE.containsMatchIn(text) && SPECIFIC_PURPOSE.any { it in text }
            if (!businessOnTheWay) {
                COMMUTE_OUT.firstOrNull { it in text }?.let { return VoiceAnswer(TripMode.COMMUTE, purpose = "퇴근") }
                COMMUTE_IN.firstOrNull { it in text }?.let { return VoiceAnswer(TripMode.COMMUTE, purpose = "출근") }
            }

            if (purpose != null || visit.isNotEmpty() || BUSINESS.any { it in text }) {
                val resolved = when {
                    purpose == "미팅" -> if (visit.isNotEmpty() || "거래처" in text || "고객" in text) "거래처 미팅" else "회의"
                    purpose != null -> purpose
                    visit.isNotEmpty() -> "거래처 방문"
                    else -> freeText(text)
                }
                return VoiceAnswer(TripMode.BUSINESS, purpose = resolved, visitPlace = visit)
            }

            val words = text.split(' ')
            if (NO.any { no -> words.any { it.startsWith(no) } }) return null
            val yes = words.any { w -> isShortYes(w) || YES_PREFIX.any { w.startsWith(it) } }
            if (yes) return suggested?.let { VoiceAnswer(it) }
            return null
        }

        /** "네", "응" 또는 같은 말을 반복한 "네네", "응응", "예예" */
        private fun isShortYes(word: String): Boolean =
            YES_EXACT.any { yes -> word.isNotEmpty() && word.length % yes.length == 0 && word == yes.repeat(word.length / yes.length) }

        private fun visitPlace(text: String): String {
            ORG.find(text)?.let { return it.groupValues[1] }
            return PLACE_BEFORE_VERB.findAll(text)
                .map { it.groupValues[1] }
                .firstOrNull { it !in NOT_A_PLACE && "출근" !in it && "퇴근" !in it && PURPOSES.none { (keys, _) -> keys.any { k -> k in it } } }
                .orEmpty()
        }

        /** 목적 키워드가 없는 업무 응답("업무, 견적서 전달")은 말한 내용을 그대로 짧게 남긴다 */
        private fun freeText(text: String): String {
            val cleaned = text
                .replace(Regex("""^(네|예|응)\s*"""), "")
                .replace(Regex("""(업무로|업무|으로|로)?\s*(기록해\s*줘|기록해\s*주세요|해\s*줘|했어요|했습니다|했어|이에요|예요|입니다|이요|요)$"""), "")
                .replace(Regex("""^업무\s*"""), "")
                .trim()
            return cleaned.take(20).ifEmpty { "업무" }
        }

        private fun normalize(raw: String) = raw
            .replace(Regex("""[.,!?~"'“”‘’]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
