package com.woolab.lumella.voice

/**
 * Decides on the glasses, for the plain case, what luma's router would decide with a model call:
 * in a topic session, a learner sentence that is clearly a statement goes to the topic tutor
 * (`routeHint = "topic_chat"`, ETRI Tango). luma takes an explicit routeHint before any model call
 * (luma-api transition_policy.pre_engine_guardrail, "FR5 fast path", 0 round trips), so such a
 * turn skips the router's gpt-5.4 selection call (~1.5 s of the coach turn, measured 2026-10-17)
 * and the learner's sentence is not sent to OpenAI for routing.
 *
 * Everything else gets no hint and the router decides as before: a question (a question mark, or
 * an interrogative word such as 왜/뭐/어떻게, because speech recognition does not always write the
 * mark), a request or a word question (알려 주세요, 무슨 뜻, 영어로), a topic change or the end of
 * the conversation, English words, a photo turn, a session without a topic. The rule only ever
 * says "this is plain on-topic talk"; it never picks gpt_qa or any other route, so a sentence it
 * cannot read costs nothing but the old latency.
 *
 * Known miss: a question with neither a mark nor an interrogative word ("더 좋은 말 있어요") is read
 * as a statement. The voice answers it all the same; only that turn's topic line comes from Tango.
 */
object TopicRouteHint {
    const val TOPIC_CHAT = "topic_chat"

    /** Shown on the corner hint for a hinted turn instead of the router's reason (it gave none). */
    const val REASON = "질문이 아닌 문장이라 앱이 바로 주제 대화로 보냈어요"

    /** Interrogative stems; any occurrence leaves the turn to the router. 왜냐하면 is removed first. */
    private val QUESTION_WORDS = listOf(
        "뭐", "뭔", "뭘", "무엇", "무슨", "왜", "어떻게", "어떡", "어때", "어떤", "어디", "언제", "누구", "누가",
        "몇", "얼마", "어느", "어땠",
    )

    /** Sentence endings that ask, when the question mark is missing; a final 까 asks unless it is 니까 (because). */
    private val QUESTION_ENDINGS = listOf("나요", "까요", "는지", "니", "냐", "죠")

    /** Requests, questions about words, and talk about the conversation itself. */
    private val META_WORDS = listOf(
        "알려", "가르쳐", "설명", "뜻", "의미", "영어", "한국어로", "다시", "천천히", "모르", "주제", "그만",
        "바꾸", "바꿔", "다른 얘기", "다른 이야기", "질문", "고마워", "고맙", "감사", "안녕",
    )

    /** Choosing what to talk about: "지난 주말 이야기를 하고 싶어요", "여행 얘기해요". */
    private val TOPIC_CHOICE = Regex("(얘기|이야기)\\S*\\s*(하고|할래|해요|하자|해 주)")

    /** Request endings ("…해 주세요"), checked on the last sentence only: inside a sentence they are quoted. */
    private val REQUEST_ENDINGS = listOf("주세요", "줘요", "줘", "줄래요", "주실래요", "주시겠어요", "볼래요")

    private val HANGUL = Regex("[가-힣]")
    private val LATIN_WORD = Regex("[A-Za-z]{2,}")
    private val SENTENCE_END = Regex("[.!…~\\s]+$")

    /** [TOPIC_CHAT] when [transcript] is plain on-topic talk in a topic session without a photo, else null. */
    fun of(transcript: String, topic: String?, imageId: String?): String? {
        if (topic.isNullOrBlank() || !imageId.isNullOrBlank()) return null
        val text = transcript.trim()
        if (HANGUL.findAll(text).count() < 2) return null
        if (LATIN_WORD.containsMatchIn(text)) return null
        if ('?' in text || '？' in text) return null
        val words = text.replace("왜냐하면", "").replace("왜냐면", "")
        if (QUESTION_WORDS.any { it in words }) return null
        if (META_WORDS.any { it in text } || TOPIC_CHOICE.containsMatchIn(text)) return null
        val sentences = text.split('.', '!', '…').map { it.replace(SENTENCE_END, "").trim() }.filter { it.isNotEmpty() }
        if (sentences.any { s -> QUESTION_ENDINGS.any { s.endsWith(it) } || (s.endsWith("까") && !s.endsWith("니까")) }) return null
        val last = sentences.lastOrNull() ?: return null
        if (REQUEST_ENDINGS.any { last.endsWith(it) }) return null
        return TOPIC_CHAT
    }
}
