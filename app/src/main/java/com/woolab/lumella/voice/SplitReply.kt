package com.woolab.lumella.voice

/**
 * A topic-session reply in two parts, so ETRI Tango's line for the learner's CURRENT sentence is
 * used in the same turn (see [TopicGuidance] for why the lagged guide is not enough).
 *
 *   turn published --> first part sent (recast or answer, [TopicGuidance.SPLIT_FIRST])
 *   first part generated (response.done) + Tango's line for this turn back --> second part sent
 *   (one question in Tango's direction, [TopicGuidance.SPLIT_SECOND])
 *
 * The second part never waits for the first part's audio: it is asked for as soon as both are in,
 * and its audio queues behind the first part's on the speaker. When the coach answers with no line
 * (an off-topic question went to GPT) or has not answered by the time the first part has been heard
 * plus [extraWaitMs], the second part goes without a line: the voice's own question back to the
 * topic. When the learner starts talking, or the first part turned into a tool call, there is no
 * second part.
 *
 * State only; the caller wires the events and does the sending. Thread-safe: the events arrive on
 * the websocket reader, the coach executor and the voice queue.
 */
class SplitReply(
    /** Silence the learner may hear after the first part while Tango is still out, in ms. */
    private val extraWaitMs: Long,
    /** Runs [action] after [delayMs] (a timer thread is fine; the action re-checks the state). */
    private val schedule: (delayMs: Long, action: () -> Unit) -> Unit,
    /** Audio of the first part still queued on the speaker when its generation ended, in ms. */
    private val unplayedMs: () -> Long,
    /** Sends the second part of [turnId] with Tango's [line], or null for the voice's own question. */
    private val sendSecond: (turnId: Int, line: String?) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private enum class Phase { IDLE, FIRST_REQUESTED, FIRST_SPEAKING, FIRST_DONE, SECOND_REQUESTED }

    private var phase = Phase.IDLE
    private var turnId = 0
    private var coachAnswered = false
    private var line: String? = null
    private var firstDoneAtMs = 0L

    /** Called before the first part's response.create goes out, so its response.created cannot outrun it. */
    @Synchronized
    fun onFirstPartRequested(turnId: Int) {
        if (phase != Phase.IDLE && phase != Phase.SECOND_REQUESTED) log("나눠 답하기 turn ${this.turnId}: 다음 턴이 시작돼 둘째 부분 없음")
        this.turnId = turnId
        phase = Phase.FIRST_REQUESTED
        coachAnswered = false
        line = null
    }

    /**
     * response.created. True when this response is the second part (the caller keeps the first
     * part's subtitle on screen instead of starting a new one).
     */
    @Synchronized
    fun onResponseStarted(): Boolean = when (phase) {
        Phase.FIRST_REQUESTED -> { phase = Phase.FIRST_SPEAKING; false }
        Phase.SECOND_REQUESTED -> { phase = Phase.IDLE; true }
        else -> false
    }

    /** response.done. Only the first part's own done counts; a cancelled predecessor's is ignored. */
    fun onResponseDone() {
        val wait: Long
        val turn: Int
        synchronized(this) {
            if (phase != Phase.FIRST_SPEAKING) return
            phase = Phase.FIRST_DONE
            firstDoneAtMs = System.currentTimeMillis()
            if (coachAnswered) {
                fireLocked("Tango 줄이 이미 와 있음")
                return
            }
            turn = turnId
            wait = unplayedMs().coerceIn(0L, MAX_UNPLAYED_MS) + extraWaitMs
        }
        schedule(wait) { onDeadline(turn) }
    }

    /** The coach turn for [turnId] came back; [line] is Tango's line, or null when it gave none. */
    @Synchronized
    fun onCoachAnswered(turnId: Int, line: String?) {
        if (turnId != this.turnId || coachAnswered) return
        coachAnswered = true
        this.line = line?.takeIf { it.isNotBlank() }
        if (phase == Phase.FIRST_DONE) {
            fireLocked("Tango 줄이 첫 부분 생성 ${System.currentTimeMillis() - firstDoneAtMs}ms 뒤 도착")
        }
    }

    /** The learner started talking: their next turn replaces the second part. */
    @Synchronized
    fun onLearnerSpeech() = drop("학습자가 말하기 시작")

    /** The first part became a tool call; the tool's own continuation carries the turn. */
    @Synchronized
    fun onToolCall() = drop("첫 부분이 도구 호출")

    @Synchronized
    private fun onDeadline(turn: Int) {
        if (turn != turnId || phase != Phase.FIRST_DONE) return
        fireLocked("Tango 줄을 기다리다 시간 초과")
    }

    private fun drop(why: String) {
        if (phase == Phase.FIRST_REQUESTED || phase == Phase.FIRST_SPEAKING || phase == Phase.FIRST_DONE) {
            log("나눠 답하기 turn $turnId: 둘째 부분 없음 ($why)")
            phase = Phase.IDLE
        }
    }

    private fun fireLocked(why: String) {
        phase = Phase.SECOND_REQUESTED
        log("나눠 답하기 turn $turnId: 둘째 부분 ${if (line != null) "Tango 줄로" else "Tango 줄 없이"} ($why)")
        sendSecond(turnId, line)
    }

    companion object {
        /** An unplayed figure beyond this is a wrong reading (a flushed track), not a long reply. */
        const val MAX_UNPLAYED_MS = 8_000L
    }
}
