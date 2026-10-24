package net.biavati.itts

import android.content.Context
import org.vosk.Model
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Downloads (once) and loads the Italian Vosk model. */
object ModelManager {
    private const val MODEL_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip"

    @Volatile
    private var model: Model? = null

    private fun modelDir(ctx: Context) = File(ctx.filesDir, "model-it")

    fun isInstalled(ctx: Context) = File(modelDir(ctx), ".done").exists()

    /** Returns the loaded model, downloading it first if needed. Blocking. */
    @Synchronized
    fun get(ctx: Context, onProgress: (String) -> Unit): Model {
        model?.let { return it }
        if (!isInstalled(ctx)) download(ctx, onProgress)
        onProgress("Caricamento modello…")
        return Model(modelDir(ctx).absolutePath).also { model = it }
    }

    private fun download(ctx: Context, onProgress: (String) -> Unit) {
        val zip = File(ctx.cacheDir, "model.zip")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        try {
            if (conn.responseCode != 200) error("Download fallito: HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(zip).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress("Download modello italiano (una volta sola): " +
                                if (pct >= 0) "$pct%" else "${done / 1_000_000} MB")
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        onProgress("Estrazione modello…")
        val dest = modelDir(ctx)
        dest.deleteRecursively()
        dest.mkdirs()
        val root = dest.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                // Strip the archive's top-level folder (vosk-model-small-it-0.22/).
                val rel = entry.name.substringAfter('/', "")
                if (rel.isEmpty()) continue
                val f = File(dest, rel)
                if (!f.canonicalPath.startsWith(root)) continue
                if (entry.isDirectory) {
                    f.mkdirs()
                } else {
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { zin.copyTo(it) }
                }
            }
        }
        zip.delete()
        File(dest, ".done").createNewFile()
    }
}
