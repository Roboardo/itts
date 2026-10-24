package net.biavati.itts

import android.app.Activity
import android.app.AlertDialog
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
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

open class MainActivity : Activity() {

    /** Compact dialog layout, used when opened from WhatsApp's share sheet. */
    protected open val popup = false

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var output: TextView
    private lateinit var copyBtn: Button
    private lateinit var shareBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (popup) setFinishOnTouchOutside(false)
        buildUi()
        output.text = lastText
        updateButtons()
        if (savedInstanceState == null) handleIntent(intent)
        if (!busy && lastText.isEmpty()) {
            status.text = if (ModelManager.isInstalled(this))
                "Condividi un vocale da WhatsApp con «Trascrivi», oppure scegli un file."
            else
                "Al primo uso verrà scaricato il modello di trascrizione (~${ModelManager.TOTAL_MB} MB, " +
                    "meglio in Wi‑Fi), poi tutto funziona offline.\n\n" +
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
        if (ModelManager.isInstalled(this)) {
            run(file)
        } else {
            AlertDialog.Builder(this)
                .setTitle("Scaricare il modello?")
                .setMessage("Serve una volta sola: ~${ModelManager.TOTAL_MB} MB. " +
                    "Consigliato il Wi‑Fi. Se si interrompe, riprende da dove era rimasto.")
                .setPositiveButton("Scarica") { _, _ -> run(file) }
                .setNegativeButton("Annulla") { _, _ -> file.delete() }
                .setOnCancelListener { file.delete() }
                .show()
        }
    }

    private fun run(file: File) {
        busy = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lastText = ""
        output.text = ""
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        updateButtons()
        status.text = "In coda…"

        worker.execute {
            try {
                val recognizer = ModelManager.get(applicationContext) { msg -> ui { status.text = msg } }
                ui {
                    status.text = "Trascrizione in corso…"
                    progress.isIndeterminate = false
                }
                val text = Transcriber(recognizer).transcribe(file) { pct, partial ->
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
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        if (!popup) root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(px(16) + bars.left, px(16) + bars.top, px(16) + bars.right, px(16) + bars.bottom)
            insets
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = if (popup) 20f else 24f
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
        if (popup) {
            // Grow with the text, but keep the buttons on screen.
            val maxH = resources.displayMetrics.heightPixels * 55 / 100
            val scroll = object : ScrollView(this) {
                override fun onMeasure(widthSpec: Int, heightSpec: Int) =
                    super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
            }
            scroll.addView(output)
            root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        } else {
            root.addView(ScrollView(this).apply { addView(output) },
                LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
            row.addView(this, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        if (!popup) button("Scegli file") { pick() }
        copyBtn = button("Copia") { copy() }
        shareBtn = button("Condividi") { share() }
        if (popup) button("Chiudi") { finish() }
        root.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(root)
        if (popup) {
            window.setLayout(resources.displayMetrics.widthPixels * 92 / 100, WRAP_CONTENT)
        }
    }

    companion object {
        private const val PICK = 1
        private val worker = Executors.newSingleThreadExecutor()

        // Survive rotation / re-creation without a ViewModel.
        @Volatile private var lastText = ""
        @Volatile private var busy = false
    }
}
