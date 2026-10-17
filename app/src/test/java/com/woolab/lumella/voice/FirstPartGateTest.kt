package com.woolab.lumella.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstPartGateTest {

    private val played = mutableListOf<String>()
    private val redone = mutableListOf<Pair<Int, List<LearnerSlip>>>()
    private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
    private val gate = FirstPartGate(
        maxHoldMs = 2_500L,
        schedule = { ms, action -> scheduled.add(ms to action) },
        redo = { turn, slips -> redone.add(turn to slips) },
    )
    private val place = LearnerSlips.find("시장에 떡볶이를 먹었어요")
    private val spicy = LearnerSlips.find("떡볶이가 너무 맵었어요")

    /** Routes one delta as MainActivity does: through the gate, else straight out. */
    private fun audio(name: String) {
        if (!gate.keepAudio { played.add(name) }) played.add(name)
    }

    private fun text(delta: String) {
        if (!gate.onText(delta) { played.add("text:$delta") }) played.add("text:$delta")
    }

    private fun done() {
        if (!gate.keepDone({ played.add("done") }, responseDone = true)) played.add("done")
    }

    @Test
    fun withoutSlipsNothingIsHeld() {
        gate.onResponseStarted(4)
        gate.onTranscript(4, emptyList())
        audio("a1")
        text("시장에 떡볶이를 먹었군요!")
        done()

        assertEquals(listOf("a1", "text:시장에 떡볶이를 먹었군요!", "done"), played)
        assertTrue(redone.isEmpty())
    }

    @Test
    fun aHeldFirstPartThatSaysTheRightFormPlaysEverythingInOrderAndThenFlowsFreely() {
        gate.onResponseStarted(4)
        text("시장")
        gate.onTranscript(4, place)
        audio("a1")
        assertEquals("held: only the text before the hold is out", listOf("text:시장"), played)

        text("에서 떡볶이")
        audio("a2")

        assertEquals(listOf("text:시장", "a1", "text:에서 떡볶이", "a2"), played)
        assertTrue(redone.isEmpty())
        assertEquals("one hold, one timer", 1, scheduled.size)
    }

    @Test
    fun aFirstPartThatSaysThePlaceSlipBackIsDroppedAndRedoneWithTheSlips() {
        gate.onResponseStarted(4)
        gate.onTranscript(4, place)
        audio("a1")
        text("시장에 매콤한 떡볶이를 먹었군요!")

        assertEquals(listOf(4 to place), redone)
        assertTrue("nothing of the draft was heard", played.isEmpty())
        audio("a2")
        done()
        assertTrue("the rest of the draft is dropped too", played.isEmpty())

        gate.onResponseStarted(4)
        audio("b1")
        done()
        assertEquals("the redo's answer is not held", listOf("b1", "done"), played)
    }

    @Test
    fun theIrregularSlipItselfRedoesAtOnceWithoutWaitingForTheSentenceToEnd() {
        gate.onResponseStarted(7)
        gate.onTranscript(7, spicy)
        text("떡볶이가 엄청 맵었")

        assertEquals(listOf(7 to spicy), redone)
    }

    @Test
    fun aFirstPartThatEndsWithoutTheRightFormIsRedone() {
        gate.onResponseStarted(7)
        gate.onTranscript(7, spicy)
        text("떡볶이가 엄청 맵고 자극적이었군요!")
        assertTrue("a sentence without the slip or the fix waits for the end", redone.isEmpty())

        done()

        assertEquals(listOf(7 to spicy), redone)
        assertTrue(played.isEmpty())
    }

    @Test
    fun aFirstPartWithNoWordsAtAllIsReleasedNotRedone() {
        gate.onResponseStarted(7)
        gate.onTranscript(7, spicy)
        done()

        assertEquals(listOf("done"), played)
        assertTrue(redone.isEmpty())
    }

    @Test
    fun aFirstPartAlreadyPlayingIsNotHeld() {
        gate.onResponseStarted(4)
        audio("a1")
        gate.onTranscript(4, place)
        audio("a2")
        text("시장에 떡볶이를 먹었군요!")

        assertEquals(listOf("a1", "a2", "text:시장에 떡볶이를 먹었군요!"), played)
        assertTrue(redone.isEmpty())
    }

    @Test
    fun slipsHeardBeforeTheFirstPartStartsHoldItWhenItDoes() {
        gate.onResponseStarted(null)
        gate.onTranscript(5, place)
        gate.onResponseStarted(5)
        audio("a1")

        assertTrue(played.isEmpty())
    }

    @Test
    fun anArmedHoldIsForgottenWhenSomethingElseStarts() {
        gate.onTranscript(5, place)
        gate.onResponseStarted(null)
        audio("x1")
        gate.onResponseStarted(6)
        audio("y1")

        assertEquals(listOf("x1", "y1"), played)
    }

    @Test
    fun theHoldEndsAtItsLimitWhateverTheTextSays() {
        gate.onResponseStarted(4)
        gate.onTranscript(4, place)
        audio("a1")
        text("음")

        assertEquals(2_500L, scheduled.single().first)
        scheduled.single().second()

        assertEquals(listOf("a1", "text:음"), played)
        audio("a2")
        assertEquals(listOf("a1", "text:음", "a2"), played)
    }

    @Test
    fun aTimerFromAnEarlierHoldDoesNotReleaseALaterOne() {
        gate.onResponseStarted(4)
        gate.onTranscript(4, place)
        text("시장에서")
        gate.onResponseStarted(5)
        gate.onTranscript(5, place)
        audio("b1")

        scheduled.first().second()

        assertEquals(listOf("text:시장에서"), played)
    }

    @Test
    fun theLearnerTalkingOrAToolCallReleasesWhatIsHeld() {
        gate.onResponseStarted(4)
        gate.onTranscript(4, place)
        audio("a1")
        gate.onLearnerSpeech { error("nothing to drop while holding") }
        assertEquals(listOf("a1"), played)

        gate.onResponseStarted(5)
        gate.onTranscript(5, place)
        audio("b1")
        assertFalse("a held first part's call runs", gate.onToolCall())
        assertEquals(listOf("a1", "b1"), played)
    }

    /** Turn 4's first part held, then dropped for saying the slip back. */
    private fun redoAsked() {
        gate.onResponseStarted(4)
        gate.onTranscript(4, place)
        audio("a1")
        text("시장에 떡볶이를 먹었군요!")
        assertEquals(listOf(4 to place), redone)
    }

    @Test
    fun theLearnerTalkingBeforeTheRedoWentOutTakesItBackAndTheDraftStaysUnheard() {
        redoAsked()
        var asked = 0

        gate.onLearnerSpeech { asked++; false }
        audio("a2")
        done()

        assertEquals(1, asked)
        assertTrue("the cut-off draft's last events are still dropped", played.isEmpty())
        gate.onResponseStarted(5)
        audio("b1")
        assertEquals("the next turn plays", listOf("b1"), played)
    }

    @Test
    fun theLearnerTalkingAfterTheRedoWentOutDropsTheRedoAsItArrives() {
        redoAsked()
        done()

        gate.onLearnerSpeech { true }
        gate.onResponseStarted(null)
        audio("r1")
        text("시장에서 떡볶이를 먹었군요!")
        done()

        assertTrue(played.isEmpty())
        gate.onResponseStarted(5)
        audio("b1")
        assertEquals(listOf("b1"), played)
    }

    @Test
    fun aRedoThatCouldNotBeAskedForLetsTheDraftPlayAsItIs() {
        redoAsked()
        audio("a2")

        gate.onRedoFailed { turn -> played.add("first part is turn $turn's draft") }

        assertEquals(listOf("first part is turn 4's draft", "a1", "text:시장에 떡볶이를 먹었군요!", "a2"), played)
        played.removeAt(0)
        audio("a3")
        done()
        assertEquals(listOf("a1", "text:시장에 떡볶이를 먹었군요!", "a2", "a3", "done"), played)
    }

    @Test
    fun aDroppedDraftsToolCallDoesNotRun() {
        redoAsked()

        assertTrue(gate.onToolCall())
    }

    @Test
    fun anotherRuleHitInTheTutorsSentenceIsNotTheLearnersSlip() {
        gate.onResponseStarted(7)
        gate.onTranscript(7, spicy)
        text("집에 숙제를 했군요! ")

        assertTrue("waits: not the slip the learner made", redone.isEmpty())
        text("떡볶이가 매웠군요!")
        assertTrue(redone.isEmpty())
        assertEquals(listOf("text:집에 숙제를 했군요! ", "text:떡볶이가 매웠군요!"), played)
    }

    @Test
    fun aSentenceEndingInATildeIsChecked() {
        gate.onResponseStarted(4)
        gate.onTranscript(4, place)
        text("시장에 떡볶이를 먹었구나~")

        assertEquals(listOf(4 to place), redone)
    }

    @Test
    fun theLearnerTalkingWhileTheRedoIsAskedForWaitsForItAndThenDropsIt() {
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        var talker: Thread? = null
        lateinit var gate: FirstPartGate
        gate = FirstPartGate(
            maxHoldMs = 2_500L,
            schedule = { _, _ -> },
            redo = { _, _ ->
                // speech_started on the reader thread while a transcript injected on another asks.
                talker = Thread { gate.onLearnerSpeech { order.add("drop the redo"); true } }.also { it.start() }
                talker!!.join(300)
                order.add(if (talker!!.isAlive) "speech waits for the redo" else "speech got in first")
                order.add("redo sent")
            },
        )
        gate.onResponseStarted(4)
        gate.onTranscript(4, place)
        gate.onText("시장에 떡볶이를 먹었군요!") { }
        talker!!.join(2_000)

        assertEquals(listOf("speech waits for the redo", "redo sent", "drop the redo"), order)
    }

    @Test
    fun aRedoRefusedAfterTheLearnerTalkedOverItDoesNotTakeTheNextReplyForIt() {
        redoAsked()
        done()
        gate.onLearnerSpeech { true }

        gate.onRedoFailed { error("nothing to replay: the learner is talking") }
        gate.onResponseStarted(5)
        audio("b1")

        assertEquals(listOf("b1"), played)
    }

    @Test
    fun aLaterTurnsSlipsWhileADraftIsStillBeingDroppedHoldItsFirstPart() {
        redoAsked()
        gate.onLearnerSpeech { false }
        gate.onTranscript(5, spicy)
        audio("a2")
        gate.onResponseStarted(5)
        audio("b1")
        assertTrue("turn 5's first part is held", played.isEmpty())

        text("떡볶이가 너무 매웠군요!")

        assertEquals(listOf("b1", "text:떡볶이가 너무 매웠군요!"), played)
    }

    @Test
    fun aLaterTurnsSlipsWaitPastAMutedRedoToo() {
        redoAsked()
        gate.onLearnerSpeech { true }
        gate.onTranscript(5, spicy)
        gate.onResponseStarted(null)
        audio("r1")
        gate.onResponseStarted(5)
        audio("b1")
        assertTrue(played.isEmpty())

        text("떡볶이가 엄청 맵었")

        assertEquals(listOf(4 to place, 5 to spicy), redone)
    }

    @Test
    fun aLostConnectionForgetsWhatWasHeldOrDropped() {
        gate.onResponseStarted(4)
        gate.onTranscript(4, place)
        audio("a1")
        gate.onConnectionLost()
        gate.onResponseStarted(5)
        audio("b1")
        assertEquals("the held draft is not played after the loss", listOf("b1"), played)

        played.clear()
        redone.clear()
        redoAsked()
        gate.onLearnerSpeech { true }
        gate.onConnectionLost()
        gate.onResponseStarted(6)
        audio("c1")
        assertEquals("the muted redo is forgotten, the next reply plays", listOf("c1"), played)
    }

    @Test
    fun aLaterTurnsSlipsAwaitedPastARefusedRedoAreForgottenWithTheConnection() {
        redoAsked()
        gate.onLearnerSpeech { true }
        gate.onTranscript(5, spicy)
        gate.onRedoFailed { error("nothing to replay") }

        gate.onConnectionLost()
        gate.onResponseStarted(5)
        audio("b1")

        assertEquals("a reply after the reconnect is not held for the old turn's slips", listOf("b1"), played)
    }

    @Test
    fun anotherResponseStartingDropsAHeldFirstPart() {
        gate.onResponseStarted(4)
        gate.onTranscript(4, place)
        audio("a1")

        gate.onResponseStarted(5)
        audio("b1")

        assertEquals(listOf("b1"), played)
    }

    @Test
    fun aSecondTranscriptForAFirstPartAlreadyCheckedHoldsNothing() {
        gate.onResponseStarted(4)
        gate.onTranscript(4, place)
        text("시장에서 떡볶이를 먹었군요!")
        gate.onTranscript(4, place)
        audio("a1")

        assertEquals(listOf("text:시장에서 떡볶이를 먹었군요!", "a1"), played)
        assertFalse(gate.keepAudio { })
    }
}
