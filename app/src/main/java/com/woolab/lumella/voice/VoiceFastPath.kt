package com.woolab.lumella.voice

import com.woolab.lumella.contract.SteeringEvidence
import com.woolab.lumella.contract.SteeringResult
import com.woolab.lumella.contract.TurnEvidence
import com.woolab.lumella.contract.TutorBrain
import com.woolab.tutor.slowpath.ResponseInstructions
import com.woolab.tutor.slowpath.StateGraphOrchestrator
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Orchestrates the realtime voice turn loop (plan P3): on turn start it builds the
 * per-response instructions via [StateGraphOrchestrator]/SteeringComposer, layers in
 * the [TutorBrain]'s slow-path [SteeringResult] (Available -> composed into the
 * instructions; Unavailable/error -> voice-only degrade, DELIVER-only, loop
 * continues), and sends the result through [RealtimeTransport] — the only output
 * channel this class knows about.
 *
 * D-4 INVARIANT: brain/steering text has NO direct path to spoken output. It is
 * folded into [ResponseInstructions.text] and handed to [RealtimeTransport.sendInstructions]
 * only; [RealtimeTransport] has no "speak" method for this class (or anything else)
 * to call directly.
 *
 * [submitTurnEvidence] is fire-and-forget: brain failures/unavailability must never
 * break the voice loop (W-1 ABSENT -> COACH_UNSUPPORTED degrade).
 *
 * [fetchSteering] is bounded on the caller side by [fetchSteeringTimeoutMs]: a slow
 * or hung brain implementation must never stall the voice loop past that budget.
 * The wait runs on a dedicated daemon single-thread executor (reused across turns,
 * not spawned per call) so a timed-out call cannot leak threads; timing out is
 * treated exactly like [SteeringResult.Unavailable] (degrade, loop continues).
 */
class VoiceFastPath(
    private val orchestrator: StateGraphOrchestrator,
    private val brain: TutorBrain,
    private val transport: RealtimeTransport,
    private val sessionId: () -> String,
    private val personaSummary: String = "",
    private val fetchSteeringTimeoutMs: Long = 1_500L,
    /** The session's conversation topic, or null for an open conversation (see [TopicGuidance]). */
    private val topic: () -> String? = { null },
    /** Topic replies in two parts with Tango's line for the current sentence; null = the lagged guide. */
    private val splitReply: SplitReply? = null,
) {

    /** The current split turn and its instructions minus the part note, kept for [sendSecondPart]. */
    @Volatile
    private var splitTurn: Pair<Int, String>? = null

    /** The topic tutor's line applied at the last turn start: (turn, source turn, line); for the log. */
    @Volatile
    var lastAppliedTopicGuide: Triple<Int, Int, String>? = null
        private set

    /** True once the brain has reported/thrown Unavailable for the current turn's steering fetch. */
    @Volatile
    var degraded: Boolean = false
        private set

    /** Dedicated daemon executor reused across turns/calls; never one thread per call. */
    private val steeringExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "voice-fast-path-steering").apply { isDaemon = true }
    }

    /**
     * Turn-start hook: composes and sends per-response instructions, folding in brain
     * steering when available. Always sends instructions (voice loop never stalls on
     * the brain) and always returns the orchestrator's [ResponseInstructions] so the
     * caller can drive delivery bookkeeping ([StateGraphOrchestrator.commitDelivery]).
     */
    fun onTurnStart(currentTurnId: Int): ResponseInstructions {
        val instructions = orchestrator.buildResponseInstructions(currentTurnId, personaSummary)
        val evidence = fetchSteering(currentTurnId)
        val steeringText = evidence?.let { composeSteeringText(it) }
        val sessionTopic = topic()?.takeIf { it.isNotBlank() }
        val split = splitReply?.takeIf { sessionTopic != null }
        // A split turn gets Tango's line for THIS sentence in its second part; the line for the
        // previous sentence would only pull the reply backwards.
        val guide = if (split == null && sessionTopic != null && evidence != null) {
            TopicGuidance.freshGuide(evidence.topicGuide, evidence.sourceTurnId, currentTurnId)
        } else {
            null
        }
        lastAppliedTopicGuide = guide?.let { Triple(currentTurnId, evidence!!.sourceTurnId, it) }
        val base = listOfNotNull(
            instructions.text,
            steeringText,
            sessionTopic?.let { TopicGuidance.topicInstruction(it) },
        ).filter { it.isNotBlank() }.joinToString("\n")

        if (split != null) {
            splitTurn = currentTurnId to base
            split.onFirstPartRequested(currentTurnId)
            transport.sendInstructions(base + "\n" + TopicGuidance.SPLIT_FIRST)
        } else {
            splitTurn = null
            transport.sendInstructions(listOfNotNull(base, guide?.let { TopicGuidance.guideInstruction(it) })
                .filter { it.isNotBlank() }.joinToString("\n"))
        }
        orchestrator.commitDelivery(instructions)
        return instructions
    }

    /**
     * The second part of split turn [turnId] ([SplitReply]): the turn's own instructions, Tango's
     * [line] for this sentence when it came back, and [TopicGuidance.SPLIT_SECOND]. False, sending
     * nothing, when a later turn has started (its first part is what the learner should hear).
     */
    fun sendSecondPart(turnId: Int, line: String?): Boolean {
        val (turn, base) = splitTurn ?: return false
        if (turn != turnId) return false
        splitTurn = null
        lastAppliedTopicGuide = line?.let { Triple(turnId, turnId, it) }
        transport.sendInstructions(listOfNotNull(base, line?.let { TopicGuidance.guideInstruction(it) }, TopicGuidance.SPLIT_SECOND)
            .joinToString("\n"))
        return true
    }

    /** Fire-and-forget submission of this turn's evidence to the brain's slow path. */
    fun submitTurnEvidence(evidence: TurnEvidence) {
        try {
            brain.submitTurnEvidence(evidence)
        } catch (_: Exception) {
            // Slow-path evidence submission must never break the voice loop.
        }
    }

    /**
     * Fetches steering, bounded by [fetchSteeringTimeoutMs] on the caller side: the
     * blocking [TutorBrain.fetchSteering] call runs on [steeringExecutor] and this
     * method waits at most [fetchSteeringTimeoutMs] for it. A timeout is treated the
     * same as [SteeringResult.Unavailable]: degrade and let the voice loop continue.
     * The timed-out future is cancelled with interruption; a truly hung
     * (non-interruptible) brain call keeps the single worker occupied, so fetches
     * queued behind it also time out to the same degrade path — the loop never stalls.
     */
    private fun fetchSteering(currentTurnId: Int): SteeringEvidence? {
        val future = steeringExecutor.submit(Callable { brain.fetchSteering(sessionId()) })
        val result = try {
            future.get(fetchSteeringTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            degraded = true
            return null
        } catch (_: Exception) {
            future.cancel(true)
            degraded = true
            return null
        }

        return when (result) {
            is SteeringResult.Available -> {
                degraded = false
                result.evidence
            }
            is SteeringResult.Unavailable -> {
                degraded = true
                null
            }
        }
    }

    private fun composeSteeringText(evidence: SteeringEvidence): String {
        val sb = StringBuilder()
        evidence.focusHint?.let { sb.append(it).append('\n') }
        evidence.hints.forEach { sb.append(it).append('\n') }
        return sb.toString().trim()
    }
}
