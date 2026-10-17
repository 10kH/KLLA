package com.woolab.lumella.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitReplyTest {

    private val sent = mutableListOf<Pair<Int, String?>>()
    private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
    private var unplayed = 0L

    private val split = SplitReply(
        extraWaitMs = 1_000L,
        schedule = { ms, action -> scheduled.add(ms to action) },
        unplayedMs = { unplayed },
        sendSecond = { turn, line -> sent.add(turn to line) },
    )

    /** First part requested, started and generated. */
    private fun firstPartOut(turn: Int) {
        split.onFirstPartRequested(turn)
        assertFalse(split.onResponseStarted())
        split.onResponseDone()
    }

    @Test
    fun tangosLineThatCameBackWhileTheFirstPartWasGeneratingGoesOutWhenItIsDone() {
        split.onFirstPartRequested(4)
        split.onResponseStarted()
        split.onCoachAnswered(4, "어떤 커피 마셨나요?")
        assertTrue("nothing before the first part is generated", sent.isEmpty())

        split.onResponseDone()

        assertEquals(listOf(4 to "어떤 커피 마셨나요?"), sent)
        assertTrue("no timer when nothing is left to wait for", scheduled.isEmpty())
        assertTrue("the second part's response continues the first on screen", split.onResponseStarted())
    }

    @Test
    fun aLineArrivingAfterTheFirstPartGoesOutAtOnceAndTheTimerThenDoesNothing() {
        unplayed = 2_500L
        firstPartOut(4)
        assertEquals("waits out the first part's audio plus the extra wait", 3_500L, scheduled.single().first)

        split.onCoachAnswered(4, "어떤 커피 마셨나요?")
        scheduled.single().second()

        assertEquals(listOf(4 to "어떤 커피 마셨나요?"), sent)
    }

    @Test
    fun noLineInTimeMeansTheSecondPartGoesWithoutOneAndALateLineIsIgnored() {
        firstPartOut(4)
        scheduled.single().second()
        split.onCoachAnswered(4, "늦은 줄")

        assertEquals(listOf<Pair<Int, String?>>(4 to null), sent)
    }

    @Test
    fun aCoachAnswerWithNoLineSendsTheSecondPartWithoutWaiting() {
        // An off-topic question went to GPT: there will be no Tango line for this sentence.
        firstPartOut(4)
        split.onCoachAnswered(4, null)

        assertEquals(listOf<Pair<Int, String?>>(4 to null), sent)
    }

    @Test
    fun theLearnerTalkingOrAToolCallMeansNoSecondPart() {
        firstPartOut(4)
        split.onLearnerSpeech()
        split.onCoachAnswered(4, "어떤 커피 마셨나요?")
        scheduled.forEach { it.second() }

        split.onFirstPartRequested(5)
        split.onResponseStarted()
        split.onToolCall()
        split.onResponseDone()
        split.onCoachAnswered(5, "어떤 커피 마셨나요?")

        assertTrue(sent.isEmpty())
    }

    @Test
    fun anOlderResponsesDoneBeforeTheFirstPartStartsIsNotTheFirstPart() {
        // Publishing turn 5 cancels turn 4's reply; that reply's response.done arrives after the
        // first part was requested and must not count as the first part being out.
        split.onFirstPartRequested(5)
        split.onCoachAnswered(5, "어떤 커피 마셨나요?")
        split.onResponseDone()
        assertTrue(sent.isEmpty())

        split.onResponseStarted()
        split.onResponseDone()
        assertEquals(listOf(5 to "어떤 커피 마셨나요?"), sent)
    }

    @Test
    fun anotherTurnsLineOrTimerDoesNotFireThisTurnsSecondPart() {
        firstPartOut(4)
        val oldTimer = scheduled.single().second
        split.onFirstPartRequested(5)
        split.onCoachAnswered(4, "turn 4의 줄")
        oldTimer()
        assertTrue(sent.isEmpty())

        split.onResponseStarted()
        split.onCoachAnswered(5, "turn 5의 줄")
        split.onResponseDone()
        assertEquals(listOf(5 to "turn 5의 줄"), sent)
    }

    @Test
    fun anUnplayedReadingFromAFlushedTrackIsCapped() {
        unplayed = 60_000L
        firstPartOut(4)
        assertEquals(SplitReply.MAX_UNPLAYED_MS + 1_000L, scheduled.single().first)
    }
}
