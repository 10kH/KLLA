package com.woolab.lumella.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopicRouteHintTest {
    private val topic = "지난 주말"

    private fun hint(text: String, topic: String? = this.topic, imageId: String? = null) =
        TopicRouteHint.of(text, topic, imageId)

    /** The learner's own speech as the glasses transcribed it (~/shots/lt8-200609-app.log, 2026-10-01). */
    @Test
    fun theRecordedLearnersStatementsAreHintedAndHerQuestionIsNot() {
        val statements = listOf(
            "토요일에 친구가 만났어요.",
            "우리는 광장 시장에 갔어요.",
            "시장에 사람이 정말 많았고 우리는 시장에 떡볶이 먹었어요.",
            "떡볶이가 너무 맵었어요.",
            "그리고 빈대떡이 먹었어요.",
            "네, 저는 매우 좋아해요.",
            "인절떡은 종류는 근데 맛있었어요.",
            "네, 근데 친구는 매운 음식이 못 먹어요.",
            "그래서 친구가 김밥이 샀어요.",
            "네, 마약 김밥이에요. 이름이 재밌어요.",
            "그냥 평범한 것 같아요. 계속 먹고 싶었어요.",
            "그다음에는 옷 갔는데 친구가 가방을 사는데 가방이 비싸서 친구가 싸게 해주세요 말했어요.",
            "아주머니가 친절해서 조금 싸게 해줬어요.",
            "네, 그래서 저녁에는 이 카페에 왔어요.",
            "여기서 치즈케이크를 먹었어요.",
            "친구는 커피를 마셨다.",
            "네, 저는 밤에 커피를 마셔요. 잠을 못 자요.",
            "친구는 에스프레소, 저는 유자를 하셨어요.",
        )
        statements.forEach { assertEquals(it, TopicRouteHint.TOPIC_CHAT, hint(it)) }
        // A question; the topic choice; the goodbye: the router decides these.
        listOf(
            "글쎄요, 마약 김밥은 왜 이름이 마약이에요?",
            "지난 주말 이야기를 하고 싶어요.",
            "그렇죠. 오늘 이야기 재미있었어요. 고마워요.",
        ).forEach { assertNull(it, hint(it)) }
    }

    /** Every question in the demo scripts (v5, v6, held-out 10/14), with and without the question mark. */
    @Test
    fun questionsAreLeftToTheRouterEvenWithoutTheMark() {
        listOf(
            "근데 떡볶이는 왜 빨간색이에요?",
            "마약김밥은 왜 이름이 마약이에요?",
            "근데 제주도는 왜 귤이 유명해요?",
            "한국에서 비행기표는 어디서 제일 싸게 사요?",
            "근데 BTS 콘서트 티켓은 어떻게 사요?",
            "'많이 샀어요' 말고 다른 표현도 있어요?",
            "치즈케이크가 달고 부드러웠어요. 더 좋은 말 있어요?",
            "이거 한국어로 뭐라고 해요?",
        ).forEach { q ->
            assertNull(q, hint(q))
            val unmarked = q.removeSuffix("?")
            // The documented miss: no mark and no interrogative word reads as a statement.
            if (!unmarked.endsWith("있어요")) assertNull(unmarked, hint(unmarked))
        }
        assertEquals(TopicRouteHint.TOPIC_CHAT, hint("더 좋은 말 있어요"))
    }

    @Test
    fun askingEndingsRequestsAndTalkAboutTheConversationAreLeftToTheRouter() {
        listOf(
            "친구도 매운 음식 좋아하나요",
            "같이 갈까요",
            "내일 시장에 갈까",
            "다시 말해 주세요",
            "천천히 말해 주세요",
            "좀 더 쉽게 해 줘",
            "빈대떡 뜻을 모르겠어요",
            "다른 얘기 하고 싶어요",
            "주제 바꿔요",
            "이제 그만할래요",
            "감사합니다",
            "I ate tteokbokki",
        ).forEach { assertNull(it, hint(it)) }
    }

    /** Questions and requests in statement form, which the recognizer writes without a mark (review of b4d40a8). */
    @Test
    fun formalQuestionsAndRequestsWithoutAMarkAreLeftToTheRouter() {
        listOf(
            "선생님도 매운 음식을 좋아합니까",
            "이 문장이 맞습니까",
            "그게 맞는 건가요",
            "매운 거 좋아하죠",
            "혹시 광장시장 가 봤어요",
            "예문 하나 부탁해요",
            "잘 못 알아들었어요",
            "잘 안 들려요",
            "떡볶이 만드는 법을 알고 싶어요",
            "광장시장이 유명한지 궁금해요",
            "쉬운 말로 해 주면 좋겠어요",
            "한 번 더 말해 줄 수 있어요",
            "이 문장 고쳐 주세요. 어제 시장에 갔어요.",
            "잘 들어주세요. 오늘은 피곤해요",
            "다음에 또 봐요",
            "오늘 수고하셨습니다",
        ).forEach { assertNull(it, hint(it)) }
        // 가요 that goes somewhere, and the causal 니까, are still statements.
        listOf("내일 또 시장에 가요", "친구는 안 가요", "맛있으니까 또 먹었어요. 맛있었으니까")
            .forEach { assertEquals(it, TopicRouteHint.TOPIC_CHAT, hint(it)) }
    }

    @Test
    fun becauseIsNotAQuestion() {
        assertEquals(TopicRouteHint.TOPIC_CHAT, hint("왜냐하면 떡볶이가 너무 매웠어요"))
        assertEquals(TopicRouteHint.TOPIC_CHAT, hint("비싸서 안 샀어요. 너무 비싸니까"))
    }

    @Test
    fun recognitionSlipsInAStatementStillReadAsAStatement() {
        // 네 -> 내/뇌, 밖 -> 박: what the glasses' recognizer has written for this learner.
        listOf("내, 저는 매우 좋아해요", "뇌, 친구는 커피를 마셨어요", "박에서 친구를 기다렸어요")
            .forEach { assertEquals(it, TopicRouteHint.TOPIC_CHAT, hint(it)) }
    }

    @Test
    fun noTopicAPhotoOrAlmostNoKoreanMeansNoHint() {
        assertNull(hint("떡볶이를 먹었어요", topic = null))
        assertNull(hint("떡볶이를 먹었어요", topic = " "))
        assertNull(hint("떡볶이를 먹었어요", imageId = "img-1"))
        assertNull(hint("네"))
        assertNull(hint("  "))
    }
}
