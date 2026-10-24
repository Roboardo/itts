package net.biavati.itts

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Decodes any audio file Android can play (WhatsApp voice notes are Ogg/Opus)
 * to 16 kHz mono PCM and runs it through the offline recognizer.
 */
class Transcriber(private val recognizer: OfflineRecognizer) {

    /** [onUpdate] receives (progress 0..100, text so far). */
    fun transcribe(file: File, onUpdate: (Int, String) -> Unit): String {
        // Decoding is fast compared to recognition: count it as the first 10%.
        val audio = decode(file) { pct -> onUpdate(pct / 10, "") }
        val chunks = split(audio)
        val text = StringBuilder()
        for ((i, range) in chunks.withIndex()) {
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(audio.copyOfRange(range.first, range.last + 1), TARGET_RATE)
                recognizer.decode(stream)
                val t = recognizer.getResult(stream).text.trim()
                if (t.isNotEmpty()) {
                    if (text.isNotEmpty()) text.append(' ')
                    text.append(t)
                }
            } finally {
                stream.release()
            }
            onUpdate(10 + 90 * (i + 1) / chunks.size, text.toString())
        }
        return text.toString()
    }

    /**
     * Splits audio into pieces of at most [MAX_CHUNK_S] seconds, cutting at the
     * quietest 100 ms frame in the last third of each piece, so words aren't cut.
     */
    private fun split(audio: FloatArray): List<IntRange> {
        val max = MAX_CHUNK_S * TARGET_RATE
        val frame = TARGET_RATE / 10
        val out = ArrayList<IntRange>()
        var start = 0
        while (audio.size - start > max) {
            var bestCut = start + max
            var bestEnergy = Double.MAX_VALUE
            var f = start + max * 2 / 3
            while (f + frame <= start + max) {
                var e = 0.0
                for (k in f until f + frame) e += audio[k] * audio[k]
                if (e < bestEnergy) {
                    bestEnergy = e
                    bestCut = f + frame / 2
                }
                f += frame / 2
            }
            out.add(start until bestCut)
            start = bestCut
        }
        if (audio.size > start) out.add(start until audio.size)
        return out
    }

    /** Decodes the whole file to 16 kHz mono floats in [-1, 1]. */
    private fun decode(file: File, onProgress: (Int) -> Unit): FloatArray {
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

        var samples = FloatArray(if (durationUs > 0) (durationUs * TARGET_RATE / 1_000_000).toInt() + TARGET_RATE else TARGET_RATE * 60)
        var count = 0
        var resampler: Resampler? = null
        var channels = 1
        var floatPcm = false
        val info = MediaCodec.BufferInfo()
        var inputDone = false

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
                        // Downmix to mono floats in [-1, 1].
                        val mono: FloatArray
                        if (floatPcm) {
                            val fb = buf.asFloatBuffer()
                            mono = FloatArray(fb.remaining() / channels) { i ->
                                var s = 0f
                                for (c in 0 until channels) s += fb.get(i * channels + c)
                                s / channels
                            }
                        } else {
                            val sb = buf.asShortBuffer()
                            mono = FloatArray(sb.remaining() / channels) { i ->
                                var s = 0f
                                for (c in 0 until channels) s += sb.get(i * channels + c)
                                s / channels / 32768f
                            }
                        }
                        codec.releaseOutputBuffer(out, false)

                        val pcm = resampler!!.process(mono)
                        if (count + pcm.size > samples.size) {
                            samples = samples.copyOf(maxOf(samples.size * 2, count + pcm.size))
                        }
                        pcm.copyInto(samples, count)
                        count += pcm.size
                        if (durationUs > 0) {
                            onProgress((info.presentationTimeUs * 100 / durationUs).toInt().coerceIn(0, 100))
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        return samples.copyOf(count)
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

        fun process(input: FloatArray): FloatArray {
            val n = input.size
            if (n == 0) return FloatArray(0)
            val x = if (!filter) input else FloatArray(n) { i ->
                val xi = input[i].toDouble()
                val y = b0 * xi + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = xi; y2 = y1; y1 = y
                y.toFloat()
            }
            // Virtual sequence: index -1 is the last sample of the previous buffer.
            val out = FloatArray(((n - pos) / step).toInt() + 2)
            var count = 0
            while (pos <= n - 1) {
                val i = floor(pos).toInt()
                val frac = (pos - i).toFloat()
                val a = if (i < 0) last else x[i]
                val b = if (i + 1 < n) x[i + 1] else a
                out[count++] = a + (b - a) * frac
                pos += step
            }
            pos -= n
            last = x[n - 1]
            return out.copyOf(count)
        }
    }

    companion object {
        const val TARGET_RATE = 16000
        const val MAX_CHUNK_S = 30
    }
}
