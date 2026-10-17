package com.woolab.lumella.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class InputReplacementTest {

    private fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())
    private fun le32(v: Int) = le16(v and 0xffff) + le16((v ushr 16) and 0xffff)

    private fun wav(pcm: ByteArray, rate: Int = 24_000, channels: Int = 1, bits: Int = 16, padding: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        val fmt = le16(1) + le16(channels) + le32(rate) + le32(rate * channels * bits / 8) + le16(channels * bits / 8) + le16(bits)
        val chunks = ByteArrayOutputStream()
        chunks.write("fmt ".toByteArray()); chunks.write(le32(fmt.size)); chunks.write(fmt)
        // afconvert writes a FLLR chunk before the data; an odd size is padded to even.
        if (padding) { chunks.write("FLLR".toByteArray()); chunks.write(le32(3)); chunks.write(byteArrayOf(0, 0, 0, 0)) }
        chunks.write("data".toByteArray()); chunks.write(le32(pcm.size)); chunks.write(pcm)
        out.write("RIFF".toByteArray()); out.write(le32(4 + chunks.size())); out.write("WAVE".toByteArray())
        out.write(chunks.toByteArray())
        return out.toByteArray()
    }

    @Test
    fun aMonoTwentyFourKilohertzWavGivesItsSamplesPastAFillerChunk() {
        val pcm = ByteArray(480) { it.toByte() }
        assertArrayEquals(pcm, InputReplacement.wavPcm(wav(pcm), 24_000))
        assertArrayEquals(pcm, InputReplacement.wavPcm(wav(pcm, padding = true), 24_000))
    }

    @Test
    fun anyOtherFormatIsRefused() {
        val pcm = ByteArray(480)
        assertNull(InputReplacement.wavPcm(wav(pcm, rate = 16_000), 24_000))
        assertNull(InputReplacement.wavPcm(wav(pcm, channels = 2), 24_000))
        assertNull(InputReplacement.wavPcm(wav(pcm, bits = 8), 24_000))
        assertNull(InputReplacement.wavPcm("not a wav file at all".toByteArray(), 24_000))
    }

    @Test
    fun theRecordingGoesOutReadForReadThenSilenceThenTheMicrophoneAgain() {
        var done = 0
        // 100 ms of sound at 24 kHz (4,800 bytes), then 50 ms (2,400 bytes) of silence.
        val r = InputReplacement(ByteArray(4_800) { 7 }, trailingSilenceMs = 50, sampleRateHz = 24_000) { done++ }
        assertEquals(100L, r.durationMs)

        val reads = generateSequence { r.next(1_920) }.toList()

        assertEquals("4 reads of 40 ms cover 150 ms", 4, reads.size)
        assertEquals(7, reads[0][0].toInt())
        assertEquals("the partial read: sound, then zeros", 7, reads[2][959].toInt())
        assertEquals(0, reads[2][960].toInt())
        assertEquals(0, reads[3].sumOf { it.toInt() })
        assertEquals(1, done)
        assertNull("finished: the microphone again", r.next(1_920))
        assertEquals("onDone once", 1, done)
        assertNotNull(reads.last())
    }
}
