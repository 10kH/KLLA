package com.woolab.lumella.voice

import java.io.File

/**
 * lumella is organised around a conversation topic (주제 유도형 자유대화 — the booth panel's Chat
 * skill). Every conversation starts on one:
 *
 * - No topic yet: the tutor speaks first and asks what to talk about ("오늘은 어떤 얘기할까요?"),
 *   offering the topic of last time and the learner's favourites (luma profile) as examples. The
 *   learner's answer sets it by voice (the set_topic tool); "주제 없이" means free talk.
 * - A topic already set (an operator's `--topic`): the tutor opens with a question on it.
 * - Back after a break (the session idled out and was woken): the tutor asks whether to keep the
 *   topic or talk about something else.
 *
 * During the conversation luma routes on-topic turns to its topic tutor, ETRI Tango, prompted with
 * the topic and the running conversation; Tango's reply is its next line for the topic. The voice
 * the learner hears stays the realtime model (it answers in ~0.7 s; a coach turn takes seconds).
 * Two ways to put Tango's line into the conversation:
 *
 * - Lagged (default): Tango leads one turn behind; its line for turn N sets the direction of the
 *   voice's reply at turn N+1. Judged no better than no guide at all (aaai27 tango_use_probe.py,
 *   2026-10-16: lagged vs none 33 vs 32 of 75 blind).
 * - Split ([SplitReply], the `split-reply` launch flag): the reply comes in two parts. The voice
 *   says the recast (or the answer to a question) at once, and while it speaks Tango's line for
 *   THIS sentence comes back; the second part is one question in its direction. Judged better than
 *   lagged 51 of 75 (same probe). Possible since the coach turn stopped running the quality gate
 *   (KLLA-server #28, 2026-10-17: coach turn median 5.7 s -> 2.6 s, while the voice's reply to a
 *   sentence ends ~5.1 s after it on the glasses, lt8 median of 21).
 *
 * Off-topic turns go to GPT and carry no guide; the voice answers them and comes back.
 *
 * Recast stays first: the persona's recast rule is not replaced, the topic only decides where the
 * reply goes after it.
 */
object TopicGuidance {
    /** A guide older than this many turns describes a conversation that has moved on. */
    const val MAX_GUIDE_AGE_TURNS = 2

    const val MAX_TOPIC_CHARS = 30

    fun topicInstruction(topic: String): String =
        "TOPIC-GUIDED CONVERSATION. Today's topic: \"$topic\". Keep the conversation on this topic " +
            "without announcing it as a lesson: when there is room, ask one short question about it. If the " +
            "learner asks about something else, ANSWER IT first in one short, useful sentence — never refuse, " +
            "postpone or wave it away — and then bring the talk back to the topic with one question. Your recast " +
            "or expansion of the learner's sentence still comes first — the topic question never replaces it — " +
            "and the whole reply stays 1-2 short sentences. Every question asks something new: never ask again what you " +
            "already asked or what the learner already told you. When the learner is ending the conversation (they thank you, " +
            "say it was fun, say goodbye), ask nothing more: say goodbye warmly in one or two sentences in 해요체, naming " +
            "one thing they told you today (\"저도 즐거웠어요! … 다음에 또 이야기해요.\", never \"나도\" or \"안녕!\")."

    fun guideInstruction(guide: String): String =
        "The topic tutor (ETRI Tango) proposed this next line for the topic: \"$guide\". After your recast or " +
            "expansion, " +
            "take the conversation in that direction — use its question, in your own short words and in the " +
            "learner's politeness level. Do not read it out word for word. If the learner has just asked about " +
            "something else, answer that first; use this direction only to come back to the topic. If its question " +
            "asks what the learner already told you, ask something new in that direction instead."

    /**
     * The first part of a split reply ([SplitReply]): the recast or the answer, no question. A learner
     * question still gets its answer here; the way back to the topic is the second part's job.
     *
     * Check first, expand only a right sentence: measured 2026-10-17 with aaai27
     * split_reply_probe.py, script v6, six runs per version, on the 9 recast lines. Without these rules
     * 40/54; an expansion rule alone ("how it looked, tasted or felt") fixed the plain line (0/6 ->
     * 6/6) but the model then expanded slips too and kept them (맵었 3/6 -> 0/6); checking first gave
     * 45/54. Still missed: "시장에 떡볶이를 먹었어요" is taken as right and expanded (1/6), and a
     * stricter 에/에서 rule did not change that (1/6, 44/54 in all).
     */
    const val SPLIT_FIRST =
        "RIGHT NOW say ONLY the first part of your reply, one short sentence and no question: your recast or " +
            "expansion of the learner's last sentence or, if the learner asked you something, a short answer to it. " +
            "First check their sentence for a mistake: a particle (이/가 where 을/를 belongs, or 에 where the place " +
            "of an action needs 에서: \"도서관에 공부했어요\" -> \"도서관에서 공부했군요!\") or a verb form " +
            "(\"덥었어요\" -> \"더웠군요!\"). If there is one, your recast says their WHOLE sentence back with it fixed, " +
            "never repeated, and adds nothing. Only if there is none, say it back with one richer word about the " +
            "thing itself — how it looked, tasted or felt, not just 정말, 꽤 or 너무 (\"사과 샀어요\" -> " +
            "\"빨갛고 큰 사과를 샀군요!\"). If they asked for a better word, give one or two. If they are ending the " +
            "conversation, say your goodbye here. A second part follows separately."

    /** The second part of a split reply: one question, in the topic tutor's direction when its line came back. */
    const val SPLIT_SECOND =
        "You have just said the first part of your reply (your recast, or your answer to the learner's question). " +
            "Now say ONE short question that continues the conversation, spoken words only: no tool call, no " +
            "function name, no JSON, no topic label. Do not repeat or rephrase what you just said."

    /** How a conversation opens; each has its own first words (see [openingInstruction]). */
    enum class Opening { CHOOSE, ON_TOPIC, WELCOME_BACK }

    fun openingKind(topic: String?, returning: Boolean): Opening = when {
        topic == null -> Opening.CHOOSE
        returning -> Opening.WELCOME_BACK
        else -> Opening.ON_TOPIC
    }

    // Templates, one per opening. {examples} and {topic} are filled in; aaai27 opener_probe.py reads
    // these literals to measure exactly what the app sends.
    const val OPENING_CHOOSE =
        "OPENING (the learner has not spoken yet). In one or two short sentences in 해요체, greet the learner " +
            "with \"안녕하세요\" and ask what they would like to talk about today — \"오늘은 어떤 얘기할까요?\" — offering these as " +
            "examples: {examples} — every one said in natural Korean, never an English word (\"travel\" is 여행, " +
            "\"daily life\" is 일상). Do not call any tool now."
    const val OPENING_CHOOSE_WITH_LAST =
        " The first example is what you talked about last time: offer to continue it " +
            "(\"지난번에 이야기한 … 이어서 해도 좋고요\")."
    const val OPENING_EVERYDAY_EXAMPLES = "two everyday topics such as 어제 한 일, 좋아하는 음식"
    const val OPENING_ON_TOPIC =
        "OPENING (the learner has not spoken yet). Today's topic is \"{topic}\". In one or two short sentences " +
            "in 해요체, greet the learner with \"안녕하세요\", name today's topic in natural Korean, and ask one short, easy question " +
            "about it. Do not call any tool now."
    const val OPENING_WELCOME_BACK =
        "OPENING (the learner is back after a pause; they have not spoken yet). In one short sentence in 해요체, " +
            "greet them and ask whether to keep talking about \"{topic}\" or to talk about something else. " +
            "Do not call any tool now."

    /** What happened, as a system note put into the conversation before the opening response. */
    fun openingNote(kind: Opening): String = when (kind) {
        Opening.WELCOME_BACK -> "The learner is back after a pause. Open the conversation."
        else -> "The learner has just put the glasses on. Open the conversation."
    }

    /** The tutor's first words of a conversation (the learner has not spoken; politeness is 해요체). */
    fun openingInstruction(
        topic: String?,
        returning: Boolean,
        lastTopic: String?,
        favorites: List<String>,
    ): String = when (openingKind(topic, returning)) {
        Opening.ON_TOPIC -> OPENING_ON_TOPIC.replace("{topic}", topic!!)
        Opening.WELCOME_BACK -> OPENING_WELCOME_BACK.replace("{topic}", topic!!)
        Opening.CHOOSE -> {
            val last = lastTopic?.trim()?.takeIf { it.isNotEmpty() }
            val examples = (listOfNotNull(last) + favorites.map { it.trim() })
                .filter { it.isNotEmpty() }.distinct().take(2)
            val text = if (examples.isEmpty()) OPENING_EVERYDAY_EXAMPLES else examples.joinToString(", ") { "\"$it\"" }
            OPENING_CHOOSE.replace("{examples}", text) + if (last != null) OPENING_CHOOSE_WITH_LAST else ""
        }
    }

    /** A spoken or file topic, tidied: one line, at most [MAX_TOPIC_CHARS]; blank means none. */
    fun normalizeTopic(raw: String?): String? =
        raw?.lineSequence()?.firstOrNull()?.trim()?.trim('"', '\'', '“', '”')?.take(MAX_TOPIC_CHARS)
            ?.takeIf { it.isNotEmpty() }

    /** The guide to use at [currentTurnId], or null when there is none or it is stale. */
    fun freshGuide(guide: String?, sourceTurnId: Int, currentTurnId: Int): String? =
        guide?.takeIf { it.isNotBlank() && currentTurnId - sourceTurnId in 1..MAX_GUIDE_AGE_TURNS }

    private val GOODBYES = listOf(
        "안녕히", "잘 가요", "잘 있어요", "다음에 봐요", "다음에 또 봐요", "또 만나요", "다음에 또 얘기해요",
        "다음에 또 이야기해요", "그만할게요", "그만할래요", "그만 할게요", "그만 할래요", "끝낼게요", "끝낼래요",
        "이만 갈게요", "수고하셨",
    )
    private val HAD_FUN = listOf("재미있었", "재밌었", "즐거웠")
    private val THANKS = listOf("고마워", "고맙", "감사")

    /**
     * The learner is ending the conversation: a goodbye, or thanks together with "that was fun"
     * ("오늘 이야기 재미있었어요. 고마워요!"). Thanks alone is not an ending: after an answer it
     * thanks the answer ("아, 고마워요!"). The topic instruction tells the voice how to say goodbye;
     * this lets a split reply skip its second part, which would otherwise ask one more question.
     */
    fun learnerIsClosing(transcript: String): Boolean {
        val t = transcript.trim()
        if (GOODBYES.any { it in t }) return true
        val hadFun = HAD_FUN.any { it in t }
        return hadFun && (THANKS.any { it in t } || ("오늘" in t && listOf("이야기", "얘기", "대화").any { it in t }))
    }
}

/**
 * The topics the learner chose by voice, newest last, as "epochMillis<TAB>topic" lines — so the next
 * conversation can offer to continue the last one. Operator presets are not remembered (they are not
 * the learner's choice). ops/launch-lumella.sh --reset deletes the file with the learner record.
 */
class TopicMemory(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun remember(topic: String) {
        val kept = entries().filter { it.second != topic } + (now() to topic)
        runCatching {
            file.writeText(kept.takeLast(MAX_ENTRIES).joinToString("") { "${it.first}\t${it.second}\n" })
        }
    }

    /** The last chosen topic, if chosen within [maxAgeMs]. */
    fun last(maxAgeMs: Long = DEFAULT_MAX_AGE_MS): String? =
        entries().lastOrNull()?.takeIf { now() - it.first in 0..maxAgeMs }?.second

    private fun entries(): List<Pair<Long, String>> =
        runCatching { if (file.isFile) file.readLines() else emptyList() }.getOrDefault(emptyList())
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@mapNotNull null
                val at = line.substring(0, tab).toLongOrNull() ?: return@mapNotNull null
                TopicGuidance.normalizeTopic(line.substring(tab + 1))?.let { at to it }
            }

    companion object {
        const val MAX_ENTRIES = 5
        const val DEFAULT_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
