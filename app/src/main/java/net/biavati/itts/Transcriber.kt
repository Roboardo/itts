package net.biavati.itts

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Decodes any audio file Android can play (WhatsApp voice notes are Ogg/Opus)
 * to 16 kHz mono PCM and runs it through Vosk.
 */
class Transcriber(private val model: Model) {

    /** [onUpdate] receives (progress 0..100, text so far). */
    fun transcribe(file: File, onUpdate: (Int, String) -> Unit): String {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("Nessuna traccia audio nel file")
        extractor.selectTrack(track)
        val inFormat = extractor.getTrackFormat(track)
        val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION))
            inFormat.getLong(MediaFormat.KEY_DURATION) else 0L

        val codec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(inFormat, null, null, 0)
        codec.start()

        val recognizer = Recognizer(model, TARGET_RATE.toFloat())
        val text = StringBuilder()
        var resampler: Resampler? = null
        var channels = 1
        var floatPcm = false
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var lastPct = -1

        fun addResult(json: String) {
            val t = JSONObject(json).optString("text")
            if (t.isNotBlank()) {
                if (text.isNotEmpty()) text.append(' ')
                text.append(t)
            }
        }

        fun applyFormat(f: MediaFormat) {
            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            resampler = Resampler(f.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        }

        try {
            while (true) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(idx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val out = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> applyFormat(codec.outputFormat)
                    out >= 0 -> {
                        if (resampler == null) applyFormat(codec.outputFormat)
                        val buf = codec.getOutputBuffer(out)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        // Downmix to mono floats.
                        val mono: FloatArray
                        if (floatPcm) {
                            val fb = buf.asFloatBuffer()
                            mono = FloatArray(fb.remaining() / channels) { i ->
                                var s = 0f
                                for (c in 0 until channels) s += fb.get(i * channels + c)
                                s / channels * 32767f
                            }
                        } else {
                            val sb = buf.asShortBuffer()
                            mono = FloatArray(sb.remaining() / channels) { i ->
                                var s = 0f
                                for (c in 0 until channels) s += sb.get(i * channels + c)
                                s / channels
                            }
                        }
                        codec.releaseOutputBuffer(out, false)

                        val pcm = resampler!!.process(mono)
                        if (pcm.isNotEmpty() && recognizer.acceptWaveForm(pcm, pcm.size)) {
                            addResult(recognizer.result)
                        }
                        if (durationUs > 0) {
                            val pct = (info.presentationTimeUs * 100 / durationUs).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onUpdate(pct, text.toString())
                            }
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            addResult(recognizer.finalResult)
        } finally {
            recognizer.close()
            codec.stop()
            codec.release()
            extractor.release()
        }
        val result = text.toString().let { s -> s.replaceFirstChar { it.uppercase() } }
        onUpdate(100, result)
        return result
    }

    /** Streaming low-pass + linear-interpolation resampler to 16 kHz. */
    private class Resampler(inRate: Int) {
        private val step = inRate.toDouble() / TARGET_RATE
        private var pos = 0.0
        private var last = 0f

        // RBJ biquad low-pass at 7 kHz, only used when downsampling.
        private val filter = inRate > TARGET_RATE
        private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0
        private var a1 = 0.0; private var a2 = 0.0
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

        init {
            if (filter) {
                val w = 2 * PI * 7000.0 / inRate
                val alpha = sin(w) / (2 * 0.7071)
                val a0 = 1 + alpha
                b0 = (1 - cos(w)) / 2 / a0
                b1 = (1 - cos(w)) / a0
                b2 = b0
                a1 = -2 * cos(w) / a0
                a2 = (1 - alpha) / a0
            }
        }

        fun process(input: FloatArray): ShortArray {
            val n = input.size
            if (n == 0) return ShortArray(0)
            val x = if (!filter) input else FloatArray(n) { i ->
                val xi = input[i].toDouble()
                val y = b0 * xi + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = xi; y2 = y1; y1 = y
                y.toFloat()
            }
            // Virtual sequence: index -1 is the last sample of the previous buffer.
            val out = ShortArray(((n - pos) / step).toInt() + 2)
            var count = 0
            while (pos <= n - 1) {
                val i = floor(pos).toInt()
                val frac = (pos - i).toFloat()
                val a = if (i < 0) last else x[i]
                val b = if (i + 1 < n) x[i + 1] else a
                val v = (a + (b - a) * frac).roundToInt().coerceIn(-32768, 32767)
                out[count++] = v.toShort()
                pos += step
            }
            pos -= n
            last = x[n - 1]
            return out.copyOf(count)
        }
    }

    companion object {
        const val TARGET_RATE = 16000
    }
}
