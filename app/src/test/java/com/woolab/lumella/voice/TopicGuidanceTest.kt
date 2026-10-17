package com.woolab.lumella.voice

import com.woolab.lumella.voice.TopicGuidance.Opening
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TopicGuidanceTest {
    @get:Rule val tmp = TemporaryFolder()

    // --- how a conversation opens ---

    @Test
    fun withoutATopicTheTutorAsksWhatToTalkAboutWithTheLearnersOwnTopics() {
        val o = TopicGuidance.openingInstruction(null, returning = false, lastTopic = null,
            favorites = listOf("travel", "K-컬처", "음식"))
        assertTrue(o.contains("오늘은 어떤 얘기할까요?"))
        assertTrue(o.contains("examples: \"travel\", \"K-컬처\" —"))
        assertFalse("two examples at most", o.contains("음식"))
        assertTrue("English profile topics are said in Korean", o.contains("never an English word"))
        assertTrue("no tool on the opening turn", o.contains("Do not call any tool"))
    }

    @Test
    fun theLastChosenTopicIsOfferedFirst() {
        val o = TopicGuidance.openingInstruction(null, returning = false, lastTopic = "어제 친구랑 한 일",
            favorites = listOf("travel", "daily life"))
        assertTrue(o.contains("examples: \"어제 친구랑 한 일\", \"travel\" —"))
        assertTrue(o.contains("last time"))
    }

    @Test
    fun withoutAProfileOrHistoryItOffersEverydayTopics() {
        val o = TopicGuidance.openingInstruction(null, returning = false, lastTopic = null, favorites = emptyList())
        assertTrue(o.contains("어제 한 일"))
        assertFalse(o.contains("last time"))
    }

    @Test
    fun aPresetTopicOpensWithAQuestionOnIt() {
        assertEquals(Opening.ON_TOPIC, TopicGuidance.openingKind("여행", returning = false))
        val o = TopicGuidance.openingInstruction("여행", returning = false, lastTopic = "음식", favorites = listOf("x"))
        assertTrue(o.contains("Today's topic is \"여행\""))
        assertFalse("no menu of other topics", o.contains("음식"))
    }

    @Test
    fun aLearnerBackAfterAPauseIsAskedWhetherToKeepTheTopic() {
        assertEquals(Opening.WELCOME_BACK, TopicGuidance.openingKind("여행", returning = true))
        assertEquals(Opening.CHOOSE, TopicGuidance.openingKind(null, returning = true))
        val o = TopicGuidance.openingInstruction("여행", returning = true, lastTopic = null, favorites = emptyList())
        assertTrue(o.contains("back after a pause") && o.contains("\"여행\"") && o.contains("something else"))
    }

    @Test
    fun theOpeningNoteSaysWhatHappened() {
        assertTrue(TopicGuidance.openingNote(Opening.CHOOSE).contains("put the glasses on"))
        assertTrue(TopicGuidance.openingNote(Opening.WELCOME_BACK).contains("back after a pause"))
    }

    // --- topics ---

    @Test
    fun spokenTopicsAreTidiedAndBlankMeansNone() {
        assertEquals("여행", TopicGuidance.normalizeTopic("  \"여행\"  "))
        assertEquals("어제 친구와 한 일", TopicGuidance.normalizeTopic("어제 친구와 한 일\n두 번째 줄"))
        assertNull(TopicGuidance.normalizeTopic("   "))
        assertNull(TopicGuidance.normalizeTopic(null))
        assertEquals(TopicGuidance.MAX_TOPIC_CHARS, TopicGuidance.normalizeTopic("가".repeat(80))!!.length)
    }

    @Test
    fun topicMemoryKeepsTheLastChoiceMovesARepeatToTheEndAndForgetsOldOnes() {
        var clock = 1_000L
        val mem = TopicMemory(tmp.newFile("recent-topics.txt"), now = { clock })
        assertNull(mem.last())
        mem.remember("여행"); clock += 10
        mem.remember("음식"); clock += 10
        mem.remember("여행")
        assertEquals("여행", mem.last())
        val lines = tmp.root.resolve("recent-topics.txt").readLines()
        assertEquals(listOf("음식", "여행"), lines.map { it.substringAfter('\t') })
        clock += TopicMemory.DEFAULT_MAX_AGE_MS + 1
        assertNull("a week-old topic is not offered as last time's", mem.last())
    }

    @Test
    fun topicMemoryKeepsAtMostFiveAndSurvivesAGarbledFile() {
        val file = tmp.newFile("recent-topics.txt")
        file.writeText("garbage\n\t\nabc\tnot-a-time\n")
        var clock = 0L
        val mem = TopicMemory(file, now = { clock })
        assertNull(mem.last())
        for (i in 1..7) { clock += 1; mem.remember("주제$i") }
        assertEquals(TopicMemory.MAX_ENTRIES, file.readLines().size)
        assertEquals("주제7", mem.last())
    }

    @Test
    fun theSessionDeclaresSetTopicAndThePersonaSaysWhenToCallIt() {
        val p = OpenAiRealtimeTransport.DEFAULT_SESSION_INSTRUCTIONS
        assertTrue(p.contains("set_topic"))
        assertTrue("only when the learner chooses", p.contains("Never call it for a passing mention"))
        assertTrue("changing the subject without naming one: ask", p.contains("다른 얘기 하고 싶어요"))
    }

    // --- how a conversation ends ---

    @Test
    fun aGoodbyeOrThanksForAFunTalkEndsTheConversationButThanksForAnAnswerDoesNot() {
        listOf(
            "오늘 이야기 재미있었어요. 고마워요!", // script v6 line 30
            "그렇죠. 오늘 이야기 재미있었어요. 고마워요.", // the learner, lt8
            "오늘 대화 즐거웠어요",
            "안녕히 계세요",
            "이제 그만할게요",
            "고마워요. 다음에 또 봐요!",
            "그럼 우리 또 만나요",
            "클라랑 얘기해서 즐거웠어요",
            "오늘 수고하셨습니다",
        ).forEach { assertTrue(it, TopicGuidance.learnerIsClosing(it)) }
        listOf(
            "아, 고마워요!", // after the answer to a question (script v5)
            "고마워요! 제주도 또 가고 싶어요",
            "영화가 재미있었어요",
            "네, 마약 김밥이에요. 이름이 재밌어요.",
            // A goodbye word inside a sentence, or 'thanks' inside a word (review of b4d40a8).
            "주말에 친구를 또 만나요",
            "그 카페에 자주 가요. 시간이 잘 가요",
            "가족들은 다 잘 있어요",
            "영화는 다음에 봐요",
            "숙제를 빨리 끝낼게요",
            "추수감사절 파티가 재미있었어요",
            "오늘 친구랑 이야기를 많이 해서 즐거웠어요",
            "안녕하세요! 지난 주말 얘기할게요",
        ).forEach { assertFalse(it, TopicGuidance.learnerIsClosing(it)) }
    }

    @Test
    fun theTopicInstructionSaysHowToSayGoodbye() {
        val t = TopicGuidance.topicInstruction("지난 주말")
        assertTrue(t.contains("ending the conversation"))
        assertTrue(t.contains("ask nothing more"))
    }
}
