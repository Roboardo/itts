package net.biavati.itts

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The selectable speech models, all int8. [files] maps name to size in bytes
 * (used for progress and to verify completeness).
 */
enum class SpeechModel(
    val label: String,
    val dirName: String,
    val baseUrl: String,
    val files: List<Pair<String, Long>>,
    val config: (File) -> OfflineModelConfig,
) {
    FAST(
        "Veloce (Whisper Base)",
        "whisper-base",
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/",
        listOf(
            "base-tokens.txt" to 816_730L,
            "base-decoder.int8.onnx" to 130_672_026L,
            "base-encoder.int8.onnx" to 29_120_534L,
        ),
        { dir -> whisperConfig(dir, "base") },
    ),

    /** NVIDIA Parakeet TDT 0.6B v3, strong Italian with built-in punctuation. */
    STANDARD(
        "Standard (Parakeet v3)",
        "parakeet-v3",
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/",
        listOf(
            "tokens.txt" to 93_939L,
            "decoder.int8.onnx" to 11_845_275L,
            "joiner.int8.onnx" to 6_355_277L,
            "encoder.int8.onnx" to 652_184_281L,
        ),
        { dir ->
            OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(dir, "encoder.int8.onnx").path,
                    decoder = File(dir, "decoder.int8.onnx").path,
                    joiner = File(dir, "joiner.int8.onnx").path,
                ),
                tokens = File(dir, "tokens.txt").path,
                modelType = "nemo_transducer",
                numThreads = 4,
            )
        },
    ),
    ACCURATE(
        "Preciso (Whisper Turbo)",
        "whisper-turbo",
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo/resolve/main/",
        listOf(
            "turbo-tokens.txt" to 816_730L,
            "turbo-decoder.int8.onnx" to 361_080_764L,
            "turbo-encoder.int8.onnx" to 674_716_297L,
        ),
        { dir -> whisperConfig(dir, "turbo") },
    );

    val totalMb get() = files.sumOf { it.second } / 1_000_000
}

private fun whisperConfig(dir: File, name: String) = OfflineModelConfig(
    whisper = OfflineWhisperModelConfig(
        encoder = File(dir, "$name-encoder.int8.onnx").path,
        decoder = File(dir, "$name-decoder.int8.onnx").path,
        language = "it",
        task = "transcribe",
    ),
    tokens = File(dir, "$name-tokens.txt").path,
    modelType = "whisper",
    numThreads = 4,
)

/** Downloads (once) and loads the selected speech model. */
object ModelManager {
    private const val PREFS = "settings"
    private const val KEY_MODEL = "model"

    @Volatile
    private var recognizer: OfflineRecognizer? = null
    private var loaded: SpeechModel? = null

    private fun modelDir(ctx: Context, model: SpeechModel) = File(ctx.filesDir, model.dirName)

    fun selected(ctx: Context): SpeechModel {
        val name = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODEL, null)
        return SpeechModel.entries.firstOrNull { it.name == name } ?: SpeechModel.STANDARD
    }

    fun select(ctx: Context, model: SpeechModel) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODEL, model.name).apply()
    }

    fun isInstalled(ctx: Context, model: SpeechModel) =
        model.files.all { (name, size) -> File(modelDir(ctx, model), name).length() == size }

    /** Returns the loaded recognizer, downloading the model first if needed. Blocking. */
    @Synchronized
    fun get(ctx: Context, model: SpeechModel, cancel: AtomicBoolean, onProgress: (String) -> Unit): OfflineRecognizer {
        if (loaded == model) recognizer?.let { return it }
        // Free the space used by the old Vosk model.
        File(ctx.filesDir, "model-it").deleteRecursively()
        if (!isInstalled(ctx, model)) download(ctx, model, cancel, onProgress)
        // Only one model in memory at a time; jobs run one by one, so nothing is using it.
        recognizer?.release()
        recognizer = null
        loaded = null
        onProgress("Caricamento modello…")
        val config = OfflineRecognizerConfig(
            modelConfig = model.config(modelDir(ctx, model)),
            decodingMethod = "greedy_search",
        )
        return OfflineRecognizer(null, config).also {
            recognizer = it
            loaded = model
        }
    }

    private fun download(ctx: Context, model: SpeechModel, cancel: AtomicBoolean, onProgress: (String) -> Unit) {
        val dir = modelDir(ctx, model).apply { mkdirs() }
        val total = model.files.sumOf { it.second }
        var before = 0L
        for ((name, size) in model.files) {
            val dest = File(dir, name)
            if (dest.length() != size) {
                // Resume a partial download if there is one.
                val part = File(dir, "$name.part")
                if (part.length() > size) part.delete()
                var done = part.length()
                val conn = URL(model.baseUrl + name).openConnection() as HttpURLConnection
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
