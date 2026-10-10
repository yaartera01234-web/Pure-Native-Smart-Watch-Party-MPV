package app.party.wpnative

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Exact native rendering of party-final1.html's Web Audio Room tunes.
 *
 * The source uses independent sine oscillators, a 20 ms exponential attack from
 * 0.0001 to 0.4, an exponential release back to 0.0001 at `dur`, and stops each
 * oscillator 50 ms later. We synthesize that same signal at Android's usual Web
 * Audio output rate (48 kHz) and deliberately do not request audio focus, so a
 * Room notification never pauses or changes synchronized movie playback.
 */
internal class RoomEventTunes {
    private data class Note(val hz0: Double, val hz1: Double, val dur: Double, val delay: Double)

    private val handler = Handler(Looper.getMainLooper())
    private val active = LinkedHashSet<AudioTrack>()

    fun message() = play(MESSAGE_PCM)
    fun joined() = play(JOIN_PCM)
    fun left() = play(LEAVE_PCM)

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        active.toList().forEach(::release)
        active.clear()
    }

    private fun play(pcm: ShortArray) {
        var track: AudioTrack? = null
        try {
            val created = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track = created
            if (created.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) != pcm.size) {
                release(created)
                return
            }
            active.add(created)
            created.play()
            val lifeMs = ceil(pcm.size * 1000.0 / SAMPLE_RATE).toLong() + 150L
            handler.postDelayed({ active.remove(created); release(created) }, lifeMs)
        } catch (_: Throwable) {
            track?.let { active.remove(it); release(it) }
        }
    }

    private fun release(track: AudioTrack) {
        try { track.stop() } catch (_: Throwable) {}
        try { track.flush() } catch (_: Throwable) {}
        try { track.release() } catch (_: Throwable) {}
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val FLOOR = 0.0001
        const val PEAK = 0.4
        const val ATTACK = 0.02
        const val TAIL = 0.05

        // party-final1.html: tuneMsg / tuneJoin / tuneLeave — values intentionally literal.
        val MESSAGE = arrayOf(
            Note(880.0, 880.0, 0.13, 0.0),
            Note(1318.0, 1318.0, 0.20, 0.16)
        )
        val JOIN = arrayOf(
            Note(523.0, 523.0, 0.12, 0.0),
            Note(659.0, 659.0, 0.12, 0.12),
            Note(784.0, 784.0, 0.12, 0.24),
            Note(1047.0, 1047.0, 0.30, 0.36)
        )
        val LEAVE = arrayOf(
            Note(1047.0, 1047.0, 0.12, 0.0),
            Note(784.0, 784.0, 0.12, 0.12),
            Note(659.0, 659.0, 0.12, 0.24),
            Note(523.0, 523.0, 0.30, 0.36)
        )

        val MESSAGE_PCM: ShortArray by lazy { render(MESSAGE) }
        val JOIN_PCM: ShortArray by lazy { render(JOIN) }
        val LEAVE_PCM: ShortArray by lazy { render(LEAVE) }

        fun render(notes: Array<Note>): ShortArray {
            val total = notes.maxOf { it.delay + it.dur + TAIL }
            val mix = DoubleArray(ceil(total * SAMPLE_RATE).toInt().coerceAtLeast(1))
            notes.forEach { note ->
                val start = (note.delay * SAMPLE_RATE).roundToInt()
                val count = ceil((note.dur + TAIL) * SAMPLE_RATE).toInt()
                var phase = 0.0
                val frequencyRatio = note.hz1 / max(note.hz0, 1.0)
                for (i in 0 until count) {
                    val at = i.toDouble() / SAMPLE_RATE
                    val gain = when {
                        at <= ATTACK -> exponential(FLOOR, PEAK, at / ATTACK)
                        at <= note.dur -> exponential(PEAK, FLOOR,
                            (at - ATTACK) / (note.dur - ATTACK))
                        else -> FLOOR
                    }
                    val index = start + i
                    if (index >= mix.size) break
                    mix[index] += sin(phase) * gain
                    val rampAt = (at / note.dur).coerceIn(0.0, 1.0)
                    val hz = max(note.hz0, 1.0) * exp(ln(frequencyRatio) * rampAt)
                    phase += 2.0 * PI * hz / SAMPLE_RATE
                }
            }
            return ShortArray(mix.size) { i ->
                (mix[i].coerceIn(-1.0, 1.0) * Short.MAX_VALUE).roundToInt().toShort()
            }
        }

        fun exponential(from: Double, to: Double, fraction: Double): Double =
            from * exp(ln(to / from) * fraction.coerceIn(0.0, 1.0))
    }
}
