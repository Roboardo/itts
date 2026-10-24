package net.biavati.itts

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads (once) and loads NVIDIA Parakeet TDT 0.6B v3 (int8), a multilingual
 * model with strong Italian accuracy and built-in punctuation/capitalisation.
 */
object ModelManager {
    private const val BASE =
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/"

    // name to size in bytes (used for progress and to verify completeness).
    private val FILES = listOf(
        "tokens.txt" to 93_939L,
        "decoder.int8.onnx" to 11_845_275L,
        "joiner.int8.onnx" to 6_355_277L,
        "encoder.int8.onnx" to 652_184_281L,
    )
    val TOTAL_MB = FILES.sumOf { it.second } / 1_000_000

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    private fun modelDir(ctx: Context) = File(ctx.filesDir, "parakeet-v3")

    fun isInstalled(ctx: Context) =
        FILES.all { (name, size) -> File(modelDir(ctx), name).length() == size }

    /** Returns the loaded recognizer, downloading the model first if needed. Blocking. */
    @Synchronized
    fun get(ctx: Context, cancel: AtomicBoolean, onProgress: (String) -> Unit): OfflineRecognizer {
        recognizer?.let { return it }
        // Free the space used by the old Vosk model.
        File(ctx.filesDir, "model-it").deleteRecursively()
        if (!isInstalled(ctx)) download(ctx, cancel, onProgress)
        onProgress("Caricamento modello…")
        val dir = modelDir(ctx)
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(dir, "encoder.int8.onnx").path,
                    decoder = File(dir, "decoder.int8.onnx").path,
                    joiner = File(dir, "joiner.int8.onnx").path,
                ),
                tokens = File(dir, "tokens.txt").path,
                modelType = "nemo_transducer",
                numThreads = 4,
            ),
            decodingMethod = "greedy_search",
        )
        return OfflineRecognizer(null, config).also { recognizer = it }
    }

    private fun download(ctx: Context, cancel: AtomicBoolean, onProgress: (String) -> Unit) {
        val dir = modelDir(ctx).apply { mkdirs() }
        val total = FILES.sumOf { it.second }
        var before = 0L
        for ((name, size) in FILES) {
            val dest = File(dir, name)
            if (dest.length() != size) {
                // Resume a partial download if there is one.
                val part = File(dir, "$name.part")
                if (part.length() > size) part.delete()
                var done = part.length()
                val conn = URL(BASE + name).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                if (done > 0) conn.setRequestProperty("Range", "bytes=$done-")
                try {
                    val code = conn.responseCode
                    if (code == 200) done = 0
                    else if (code != 206) error("Download fallito: HTTP $code")
                    conn.inputStream.use { input ->
                        FileOutputStream(part, done > 0).use { out ->
                            val buf = ByteArray(256 * 1024)
                            var lastMb = -1L
                            while (true) {
                                cancel.throwIfSet()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val mb = (before + done) / 1_000_000
                                if (mb != lastMb) {
                                    lastMb = mb
                                    onProgress("Download modello (una volta sola): " +
                                        "$mb / ${total / 1_000_000} MB")
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                if (part.length() != size) error("Download incompleto, riprova")
                part.renameTo(dest)
            }
            before += size
        }
    }
}
