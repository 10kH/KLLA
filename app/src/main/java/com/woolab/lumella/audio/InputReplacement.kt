package com.woolab.lumella.audio

/**
 * Debug input (DEBUG_HEAR): recorded PCM that stands in for the microphone's, read for read, then
 * [trailingSilenceMs] of silence so the server's VAD hears the turn end. [AudioCapture] keeps
 * reading the microphone and sends this instead, so the recording arrives at the microphone's own
 * pace and down the same path as the wearer's voice (the take tap included); the room is muted
 * meanwhile. Same format as the microphone: 16-bit little-endian mono at [sampleRateHz].
 */
class InputReplacement(
    private val pcm: ByteArray,
    trailingSilenceMs: Int,
    sampleRateHz: Int,
    private val onDone: () -> Unit = {},
) {
    private val total = pcm.size +
        (sampleRateHz.toLong() * 2 * trailingSilenceMs.coerceAtLeast(0) / 1000).toInt().let { it - it % 2 }
    private var pos = 0
    private var done = false

    val durationMs: Long = pcm.size * 1000L / (sampleRateHz * 2L)

    /** The next [n] bytes in place of a microphone read, or null once finished ([onDone] runs once, then). */
    @Synchronized
    fun next(n: Int): ByteArray? {
        if (pos >= total) {
            if (!done) {
                done = true
                onDone()
            }
            return null
        }
        val out = ByteArray(n)
        if (pos < pcm.size) System.arraycopy(pcm, pos, out, 0, minOf(n, pcm.size - pos))
        pos += n
        return out
    }

    companion object {
        /**
         * The samples of a 16-bit PCM mono WAV at [sampleRateHz] (what take.sh records and `afconvert
         * -f WAVE -d LEI16@24000 -c 1` writes), or null with any other format. Skips chunks other than
         * fmt/data (afconvert adds FLLR padding).
         */
        fun wavPcm(bytes: ByteArray, sampleRateHz: Int): ByteArray? {
            fun u16(at: Int) = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)
            fun u32(at: Int) = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
            fun tag(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
            if (bytes.size < 12 || tag(0) != "RIFF" || tag(8) != "WAVE") return null
            var at = 12
            var formatOk = false
            while (at + 8 <= bytes.size) {
                val size = u32(at + 4)
                val body = at + 8
                when (tag(at)) {
                    "fmt " -> {
                        if (body + 16 > bytes.size) return null
                        formatOk = u16(body) == 1 && u16(body + 2) == 1 && u32(body + 4) == sampleRateHz.toLong() &&
                            u16(body + 14) == 16
                    }
                    "data" -> {
                        if (!formatOk) return null
                        val end = minOf(bytes.size.toLong(), body + size).toInt()
                        return bytes.copyOfRange(body, end - (end - body) % 2)
                    }
                }
                val next = body + size + size % 2
                if (next > bytes.size) return null // a chunk running past the file: corrupt
                at = next.toInt()
            }
            return null
        }
    }
}
