package com.woolab.lumella.voice

/**
 * Holds a split reply's first part while it is checked against the learner's slips, so the learner
 * does not hear a recast that says their slip back to them. The voice drafts the first part at once
 * from the learner's audio; their transcript usually comes before the first part's first audio
 * (glasses, real microphone, script v6: 41 of 48 turns, a median 0.18 s after the first part's
 * response.created, its audio ~0.37 s after), and [LearnerSlips] reads it:
 *
 *   slips found, first part not heard yet     --> hold its audio, subtitle and done
 *   its text says every slip right            --> release: the held events play, in order
 *   its text says a slip ([LearnerSlip.wrong],
 *   or a finished sentence with the learner's
 *   slipped word in it), or it ends without
 *   the right form                            --> redo: drop them; [redo] asks for the first part
 *                                                 again with the slips named, and that one is not held
 *
 * No slip found (nearly every turn) or a first part already playing: nothing is held. A hold ends
 * at [maxHoldMs] whatever the text says; the learner talking or a tool call releases it. The
 * learner talking before the redo is heard drops the redo too ([onLearnerSpeech]): without the gate
 * the draft would have been cut off the same way.
 *
 * Measured before building it (aaai27 2026-10-17, gpt-realtime, script v6 lines 1-8 x4): the first
 * part kept "시장에" in 3 of 4 and "맵었" in 1 of 4; every redo said "시장에서"/"매웠", its first audio
 * 1.85-2.32 s after the sentence instead of ~0.7 s. The cancelled draft has to leave the conversation
 * first (OpenAiRealtimeTransport.redoResponse): left in, the redo carried on from the cut-off words.
 *
 * State only; the caller routes the current response's events through [keepAudio], [onText] and
 * [keepDone] and does the redo. Thread-safe. A release replays under the lock, so an event arriving
 * meanwhile waits and then plays after the held ones; a release by [maxHoldMs] replays on the
 * [schedule] thread, still under the lock, so the audio path never runs on two threads at once.
 * [redo] runs under the lock too: the learner starting to talk ([onLearnerSpeech]) comes either
 * before it (no redo) or after it (the transport has the redo to drop), whichever thread the
 * transcript that decided it came on. Lock order: this, then SplitReply, then the transport.
 */
class FirstPartGate(
    private val maxHoldMs: Long,
    private val schedule: (delayMs: Long, action: () -> Unit) -> Unit,
    /** Ask again for [turnId]'s first part with [slips] named. Called under the lock. */
    private val redo: (turnId: Int, slips: List<LearnerSlip>) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private enum class State { IDLE, ARMED, HOLDING, DROPPING, PASS }

    private var state = State.IDLE
    private var turnId = 0
    private var slips: List<LearnerSlip> = emptyList()
    /** The turn whose first part the current response is, or 0. */
    private var firstPartOf = 0
    private var heard = false
    private var done = false
    private val text = StringBuilder()
    private val held = ArrayList<() -> Unit>()
    /** The draft's events from the redo decision on, played by [onRedoFailed] if no redo went out. */
    private val dropped = ArrayList<() -> Unit>()
    /** DROPPING: the redo is still wanted (the learner has not started talking). */
    private var redoWanted = false
    /** DROPPING: the redo had gone out when the learner started talking; its response is dropped too. */
    private var redoMuted = false
    /**
     * DROPPING with no redo wanted: a later turn's slips, whose transcript came while the dropped
     * responses were still arriving. They arm that turn's first part when its response.created comes.
     */
    private var nextTurnId = 0
    private var nextSlips: List<LearnerSlip> = emptyList()
    private var holdStartedAtMs = 0L
    private var hold = 0

    /**
     * response.created. [firstPartOf] is the turn whose split first part this response is
     * ([SplitReply.speakingFirstPartOf]), or null for any other response.
     */
    @Synchronized
    fun onResponseStarted(firstPartOf: Int?) {
        val turn = firstPartOf ?: 0
        if (state == State.HOLDING) log("첫 부분 확인 turn $turnId: 다른 응답이 시작돼 붙잡은 첫 부분 버림")
        held.clear()
        dropped.clear()
        state = when {
            state == State.DROPPING && redoMuted -> {
                log("첫 부분 확인 turn $turnId: 학습자가 말해 취소한 다시 요청의 응답 버림")
                redoMuted = false
                redoWanted = false
                State.DROPPING
            }
            state == State.DROPPING && redoWanted && turn == turnId -> State.PASS
            state == State.ARMED && turn == turnId -> State.HOLDING.also { startHold() }
            nextTurnId != 0 && turn == nextTurnId -> {
                turnId = nextTurnId
                slips = nextSlips
                log("첫 부분 확인 turn $turnId: 실수 ${named(slips)} — 첫 부분 붙잡음")
                State.HOLDING.also { startHold() }
            }
            else -> State.IDLE
        }
        if (state != State.DROPPING) {
            nextTurnId = 0
            nextSlips = emptyList()
        }
        this.firstPartOf = turn
        heard = false
        done = false
        text.setLength(0)
    }

    /**
     * The learner's transcript for [turnId] had [slips]. Holds that turn's first part if it is the
     * current response and has not been heard, or arms the hold for its response.created.
     */
    @Synchronized
    fun onTranscript(turnId: Int, slips: List<LearnerSlip>) {
        if (slips.isEmpty()) return
        if (state == State.DROPPING && !redoWanted && turnId != this.turnId) {
            nextTurnId = turnId
            nextSlips = slips
            log("첫 부분 확인 turn $turnId: 실수 ${named(slips)} — 버리는 응답이 끝나고 첫 부분이 시작되면 붙잡음")
            return
        }
        // Already being checked, or this turn's first part was checked (a redo's answer included).
        if (state == State.HOLDING || state == State.DROPPING || (state == State.PASS && firstPartOf == turnId)) return
        this.turnId = turnId
        this.slips = slips
        when {
            firstPartOf != turnId -> { state = State.ARMED; log("첫 부분 확인 turn $turnId: 실수 ${named(slips)} — 첫 부분이 시작되면 붙잡음") }
            heard -> { state = State.IDLE; log("첫 부분 확인 turn $turnId: 실수 ${named(slips)} — 첫 부분이 이미 들림, 확인 못 함") }
            else -> { state = State.HOLDING; startHold(); log("첫 부분 확인 turn $turnId: 실수 ${named(slips)} — 첫 부분 붙잡음"); judgeLocked() }
        }
    }

    /** One audio delta of the current response; true when the gate keeps (or drops) it. */
    @Synchronized
    fun keepAudio(play: () -> Unit): Boolean = when (state) {
        State.HOLDING -> { held.add(play); true }
        State.DROPPING -> { dropped.add(play); true }
        else -> { heard = true; false }
    }

    /** One transcript delta of the current response; true when the gate keeps (or drops) [show]. */
    @Synchronized
    fun onText(delta: String, show: () -> Unit): Boolean = when (state) {
        State.HOLDING -> { text.append(delta); held.add(show); judgeLocked(); true }
        State.DROPPING -> { dropped.add(show); true }
        else -> { text.append(delta); false }
    }

    /** The current response's output_audio.done or response.done; true when the gate keeps it. */
    @Synchronized
    fun keepDone(run: () -> Unit, responseDone: Boolean): Boolean = when (state) {
        State.HOLDING -> { held.add(run); if (responseDone) done = true; judgeLocked(); true }
        State.DROPPING -> { dropped.add(run); true }
        else -> false
    }

    /**
     * The learner started talking. What is held plays, as it would have without the gate. A redo
     * not heard yet is dropped: [dropRedo] takes it back from the transport and says whether its
     * response.create had already gone out (then that response is dropped here too).
     */
    @Synchronized
    fun onLearnerSpeech(dropRedo: () -> Boolean) {
        when (state) {
            State.HOLDING -> release("학습자가 말하기 시작")
            State.DROPPING -> if (redoWanted) {
                redoWanted = false
                redoMuted = dropRedo()
                log("첫 부분 확인 turn $turnId: 학습자가 말하기 시작해 다시 요청 취소")
            }
            else -> Unit
        }
    }

    /**
     * The current response called a tool. A held first part is released (the tool's continuation
     * carries the turn); true when it is a draft being dropped, whose call must not run: its item
     * leaves the conversation with it.
     */
    @Synchronized
    fun onToolCall(): Boolean = when (state) {
        State.HOLDING -> { release("도구 호출"); false }
        State.DROPPING -> true
        else -> false
    }

    /**
     * [redo] could not ask again, at once or by the server refusing it: the draft plays as it is
     * rather than leave the turn silent, after [beforeReplay] (the split reply takes the draft back
     * as the turn's first part). A redo the learner had already talked over is just forgotten, so
     * the next response is not taken for it and dropped. The draft replayed after a refusal has left
     * the conversation already (and may have been cancelled mid-sentence): the learner hears it, the
     * second part is made without it. A silent turn would be worse, and refusals are rare.
     */
    @Synchronized
    fun onRedoFailed(beforeReplay: (turnId: Int) -> Unit) {
        if (state != State.DROPPING) return
        if (redoMuted) {
            log("첫 부분 확인 turn $turnId: 취소한 다시 요청이 거절됨 — 버릴 응답 없음")
            redoMuted = false
            state = State.IDLE
            dropped.clear()
            return
        }
        if (!redoWanted) return
        log("첫 부분 확인 turn $turnId: 다시 요청 실패 — 첫 부분을 그대로 내보냄")
        state = State.PASS
        redoWanted = false
        heard = true
        beforeReplay(turnId)
        val events = dropped.toList()
        dropped.clear()
        events.forEach { it() }
    }

    /**
     * The connection went down: whatever was held, dropped or awaited belongs to a response that
     * will not finish. Nothing is replayed (a reply cut off by the loss is not played after it).
     */
    @Synchronized
    fun onConnectionLost() {
        nextTurnId = 0
        nextSlips = emptyList()
        if (state == State.IDLE || state == State.PASS) return
        log("첫 부분 확인 turn $turnId: 연결이 끊겨 확인 중인 첫 부분 버림")
        state = State.IDLE
        held.clear()
        dropped.clear()
        redoWanted = false
        redoMuted = false
    }

    private fun startHold() {
        holdStartedAtMs = now()
        val mine = ++hold
        schedule(maxHoldMs) { onHoldTimeout(mine) }
    }

    @Synchronized
    private fun onHoldTimeout(which: Int) {
        if (which == hold && state == State.HOLDING) release("${maxHoldMs}ms 시간 초과")
    }

    /** Releases, or starts a redo. */
    private fun judgeLocked() {
        val said = text.toString()
        if (slips.all { s -> s.right.any { it in said } }) {
            release("맞게 말함")
            return
        }
        val kept = slips.firstOrNull { s -> s.wrong != null && s.wrong in said }?.let { "\"${it.wrong}\"을 그대로 말함" }
            // A finished sentence of the tutor's with the learner's slipped word in it as they said it
            // ("시장에 매콤한 떡볶이를 먹었군요!"): the recast has gone by without the fix.
            ?: COMPLETE_SENTENCE.findAll(said).map { m -> WORD.findAll(m.value).map { it.value }.toSet() }
                .firstNotNullOfOrNull { words -> slips.firstOrNull { it.wrong == null && it.said in words } }
                ?.let { "\"${it.said}\"라고 말함" }
            ?: if (done && said.isNotBlank()) "고쳐 말하지 않음" else null
        if (kept == null) {
            if (done) release("말이 없음")
            return
        }
        state = State.DROPPING
        redoWanted = true
        redoMuted = false
        dropped.addAll(held)
        held.clear()
        log("첫 부분 확인 turn $turnId: 다시 요청 ($kept, 붙잡은 지 ${now() - holdStartedAtMs}ms): $said")
        redo(turnId, slips)
    }

    private fun named(slips: List<LearnerSlip>) = slips.joinToString { "${it.said}->${it.fixed}" }

    private fun release(why: String) {
        log("첫 부분 확인 turn $turnId: 내보냄 ($why, 붙잡은 지 ${now() - holdStartedAtMs}ms)")
        state = State.PASS
        heard = true
        val events = held.toList()
        held.clear()
        events.forEach { it() }
    }

    companion object {
        /** A sentence of the tutor's that has ended, for [LearnerSlips] to read. */
        private val COMPLETE_SENTENCE = Regex("[^.!?？…~]+[.!?？…~]+")
        private val WORD = Regex("[가-힣]+")
    }
}
