package app.bizdrivelog.voice

import app.bizdrivelog.data.TripMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceAnswerTest {

    private fun parse(text: String, suggested: TripMode? = TripMode.BUSINESS) = VoiceAnswer.parse(text, suggested)

    @Test fun clientMeeting() = assertEquals(VoiceAnswer(TripMode.BUSINESS, "거래처 미팅"), parse("거래처 미팅"))
    @Test fun companyVisit() = assertEquals(VoiceAnswer(TripMode.BUSINESS, "거래처 방문", "한빛상사"), parse("한빛상사 방문했어요"))
    @Test fun meetingWithCompany() = assertEquals(VoiceAnswer(TripMode.BUSINESS, "거래처 미팅", "대한전자"), parse("대한전자랑 미팅"))
    @Test fun delivery() = assertEquals(VoiceAnswer(TripMode.BUSINESS, "납품·배송", "삼성물산"), parse("삼성물산에 납품하고 왔어"))
    @Test fun placeWithParticle() = assertEquals(VoiceAnswer(TripMode.BUSINESS, "거래처 방문", "판교"), parse("판교에 다녀왔어요"))
    @Test fun personal() = assertEquals(VoiceAnswer(TripMode.PERSONAL), parse("개인 용무"))
    @Test fun notBusiness() = assertEquals(VoiceAnswer(TripMode.PERSONAL), parse("아니요 업무 아니에요"))
    @Test fun commuteHome() = assertEquals(VoiceAnswer(TripMode.COMMUTE, "퇴근"), parse("퇴근이에요"))
    @Test fun yesKeepsSuggestion() = assertEquals(VoiceAnswer(TripMode.COMMUTE), parse("네 맞아요", TripMode.COMMUTE))
    @Test fun freeBusiness() = assertEquals(VoiceAnswer(TripMode.BUSINESS, "견적서 전달"), parse("업무, 견적서 전달"))
    @Test fun noOnly() = assertNull(parse("아니요"))
    @Test fun noise() = assertNull(parse("음"))
    @Test fun empty() = assertNull(parse(" "))

    // 짧은 긍정은 단어 전체가 같을 때만
    @Test fun wordStartingWithEoIsNotYes() = assertNull(parse("어디였더라"))
    @Test fun eoAloneKeepsSuggestion() = assertEquals(VoiceAnswer(TripMode.COMMUTE), parse("어", TripMode.COMMUTE))
    @Test fun conjugatedYesKeepsSuggestion() = assertEquals(VoiceAnswer(TripMode.PERSONAL), parse("그래요", TripMode.PERSONAL))

    // 출퇴근 도중 업무 근거가 있으면 업무, 없으면 출퇴근
    @Test fun clientOnTheWayToWork() = assertEquals(VoiceAnswer(TripMode.BUSINESS, "거래처 방문"), parse("출근길에 거래처 들렀어"))
    @Test fun commuteAfterMeeting() = assertEquals(VoiceAnswer(TripMode.COMMUTE, "퇴근"), parse("회의 끝나고 퇴근해요"))
    @Test fun deliveryOnTheWayHome() = assertEquals(VoiceAnswer(TripMode.BUSINESS, "납품·배송", "한빛상사"), parse("퇴근하면서 한빛상사 납품"))
    @Test fun onTheWayWithoutBusinessIsCommute() = assertEquals(VoiceAnswer(TripMode.COMMUTE, "퇴근"), parse("퇴근길이에요"))
    // 출퇴근 도중 은행·관공서는 개인 볼일일 수 있어 출퇴근, 도중이 아니면 업무
    @Test fun bankOnTheWayToWork() = assertEquals(VoiceAnswer(TripMode.COMMUTE, "출근"), parse("출근하는 길에 은행 들렀어"))
    @Test fun bankStopOnCommuteIsCommute() = assertEquals(VoiceAnswer(TripMode.COMMUTE, "출근"), parse("출근길에 은행 들렀어"))
    @Test fun bankVisitIsBusiness() = assertEquals(VoiceAnswer(TripMode.BUSINESS, "관공서·은행 업무"), parse("은행 다녀왔어"))

    // 출퇴근 도중 들른 곳만 있으면(일반 동사·장소 이름) 출퇴근
    @Test fun shopOnTheWayHomeIsCommute() = assertEquals(VoiceAnswer(TripMode.COMMUTE, "퇴근"), parse("퇴근길에 마트 들렀어"))
    @Test fun stopOnTheWayHomeIsCommute() = assertEquals(VoiceAnswer(TripMode.COMMUTE, "퇴근"), parse("퇴근하면서 들렀어"))
    @Test fun companyStopOnTheWayHomeIsCommute() = assertEquals(VoiceAnswer(TripMode.COMMUTE, "퇴근"), parse("퇴근길에 한빛상사 들렀어"))

    // 같은 짧은 긍정어를 반복해도 긍정
    @Test fun repeatedYesKeepsSuggestion() {
        assertEquals(VoiceAnswer(TripMode.COMMUTE), parse("네네", TripMode.COMMUTE))
        assertEquals(VoiceAnswer(TripMode.COMMUTE), parse("응응", TripMode.COMMUTE))
        assertEquals(VoiceAnswer(TripMode.COMMUTE), parse("예예", TripMode.COMMUTE))
    }
}
