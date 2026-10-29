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
}
