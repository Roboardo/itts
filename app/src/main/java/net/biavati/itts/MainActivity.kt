package net.biavati.itts

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var output: TextView
    private lateinit var copyBtn: Button
    private lateinit var shareBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        output.text = lastText
        updateButtons()
        if (savedInstanceState == null) handleIntent(intent)
        if (!busy && lastText.isEmpty()) {
            status.text = if (ModelManager.isInstalled(this))
                "Condividi un vocale da WhatsApp con «Trascrivi», oppure scegli un file."
            else
                "Al primo uso verrà scaricato il modello italiano (~50 MB), poi tutto funziona offline.\n\n" +
                    "Condividi un vocale da WhatsApp con «Trascrivi», oppure scegli un file."
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) start(uri)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK && resultCode == RESULT_OK) data?.data?.let { start(it) }
    }

    private fun start(uri: Uri) {
        // Copy now, while we hold the read grant from WhatsApp.
        val file = File(cacheDir, "input-${System.nanoTime()}")
        try {
            contentResolver.openInputStream(uri)!!.use { i -> file.outputStream().use { i.copyTo(it) } }
        } catch (e: Exception) {
            status.text = "Impossibile leggere il file: ${e.message}"
            return
        }
        busy = true
        lastText = ""
        output.text = ""
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        updateButtons()
        status.text = "In coda…"

        worker.execute {
            try {
                val model = ModelManager.get(applicationContext) { msg -> ui { status.text = msg } }
                ui {
                    status.text = "Trascrizione in corso…"
                    progress.isIndeterminate = false
                }
                val text = Transcriber(model).transcribe(file) { pct, partial ->
                    ui {
                        progress.progress = pct
                        status.text = "Trascrizione in corso… $pct%"
                        output.text = partial
                    }
                }
                lastText = text
                ui {
                    output.text = text
                    status.text = if (text.isBlank()) "Nessun parlato riconosciuto." else "Fatto."
                }
            } catch (e: Throwable) {
                ui { status.text = "Errore: ${e.message ?: e.javaClass.simpleName}" }
            } finally {
                file.delete()
                busy = false
                ui {
                    progress.visibility = View.GONE
                    updateButtons()
                }
            }
        }
    }

    private fun ui(block: () -> Unit) = runOnUiThread {
        if (!isDestroyed) block()
    }

    private fun updateButtons() {
        val has = lastText.isNotBlank() && !busy
        copyBtn.isEnabled = has
        shareBtn.isEnabled = has
    }

    private fun copy() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Trascrizione", lastText))
        Toast.makeText(this, "Copiato", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, lastText)
        startActivity(Intent.createChooser(send, null))
    }

    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("audio/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "application/ogg"))
        @Suppress("DEPRECATION")
        startActivityForResult(i, PICK)
    }

    private fun buildUi() {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(16), px(16), px(16))
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(px(16) + bars.left, px(16) + bars.top, px(16) + bars.right, px(16) + bars.bottom)
            insets
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })

        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, px(8), 0, px(8))
        }
        root.addView(status)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        output = TextView(this).apply {
            textSize = 18f
            setTextIsSelectable(true)
            setPadding(0, px(12), 0, px(12))
            setLineSpacing(0f, 1.2f)
        }
        root.addView(ScrollView(this).apply { addView(output) },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
            row.addView(this, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        button("Scegli file") { pick() }
        copyBtn = button("Copia") { copy() }
        shareBtn = button("Condividi") { share() }
        root.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(root)
    }

    companion object {
        private const val PICK = 1
        private val worker = Executors.newSingleThreadExecutor()

        // Survive rotation / re-creation without a ViewModel.
        @Volatile private var lastText = ""
        @Volatile private var busy = false
    }
}
