package br.com.jfpwrite

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.print.PrintManager
import android.provider.OpenableColumns
import android.util.Base64
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var dirty = false

    // Respostas pendentes que o JavaScript está esperando (id -> ação)
    private var pendingOpen: String? = null
    private var pendingSave: Triple<String, String, String>? = null // id, conteúdo, nome sugerido
    private var pendingExport: Pair<String, ByteArray>? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private lateinit var openLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var saveLauncher: ActivityResultLauncher<String>
    private lateinit var exportLauncher: ActivityResultLauncher<String>
    private lateinit var imageLauncher: ActivityResultLauncher<Array<String>>

    private var exportMime = "application/octet-stream"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE

        registerLaunchers()

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.WHITE)
        web = WebView(this)
        root.addView(
            web,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val merged = Insets.max(bars, ime)
            v.setPadding(merged.left, merged.top, merged.right, merged.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val loader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            textZoom = 100
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == HOST) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (_: Exception) {
                }
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = callback
                try {
                    imageLauncher.launch(arrayOf("image/*"))
                } catch (_: Exception) {
                    fileChooserCallback = null
                    callback.onReceiveValue(null)
                    return false
                }
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "AndroidJFP")
        web.loadUrl("https://$HOST/assets/www/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Primeiro deixa o editor fechar janelas e o painel de revisão.
                web.evaluateJavascript("window.JFPMobile ? JFPMobile.back() : false") { result ->
                    if (result == "true") return@evaluateJavascript
                    if (dirty) askBeforeLeaving() else finish()
                }
            }
        })
    }

    private fun askBeforeLeaving() {
        AlertDialog.Builder(this)
            .setTitle("Fechar o JFPWrite?")
            .setMessage("O documento tem alterações não salvas.")
            .setPositiveButton("Salvar e sair") { _, _ ->
                web.evaluateJavascript("window.JFPMobile && JFPMobile.saveAndClose()", null)
            }
            .setNegativeButton("Sair sem salvar") { _, _ -> finish() }
            .setNeutralButton("Cancelar", null)
            .show()
    }

    private fun registerLaunchers() {
        openLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val id = pendingOpen ?: return@registerForActivityResult
            pendingOpen = null
            if (uri == null) {
                resolve(id, null)
                return@registerForActivityResult
            }
            try {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                val text = contentResolver.openInputStream(uri)!!.use { String(it.readBytes(), Charsets.UTF_8) }
                val json = JSONObject()
                json.put("path", uri.toString() + "#/" + displayName(uri))
                json.put("content", text)
                resolve(id, json.toString())
            } catch (e: Exception) {
                toast("Não consegui abrir o arquivo.")
                resolve(id, null)
            }
        }

        saveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val pending = pendingSave ?: return@registerForActivityResult
            pendingSave = null
            if (uri == null) {
                resolve(pending.first, null)
                return@registerForActivityResult
            }
            resolve(pending.first, writeText(uri, pending.second))
        }

        exportLauncher = registerForActivityResult(
            object : ActivityResultContracts.CreateDocument("*/*") {
                override fun createIntent(context: Context, input: String): Intent =
                    super.createIntent(context, input).setType(exportMime)
            }
        ) { uri ->
            val pending = pendingExport ?: return@registerForActivityResult
            pendingExport = null
            if (uri == null) {
                resolve(pending.first, null)
                return@registerForActivityResult
            }
            try {
                contentResolver.openOutputStream(uri, "wt")!!.use { it.write(pending.second) }
                resolve(pending.first, JSONObject.quote(displayName(uri)))
            } catch (e: Exception) {
                toast("Não consegui gravar o arquivo.")
                resolve(pending.first, null)
            }
        }

        imageLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val cb = fileChooserCallback
            fileChooserCallback = null
            cb?.onReceiveValue(if (uri == null) null else arrayOf(uri))
        }
    }

    // Grava texto e devolve o JSON com o "caminho" (uri#/nome) ou null.
    private fun writeText(uri: Uri, text: String): String? =
        try {
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            JSONObject.quote(uri.toString() + "#/" + displayName(uri))
        } catch (e: Exception) {
            toast("Não consegui salvar o arquivo.")
            null
        }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (!name.isNullOrBlank()) return name.replace("/", "_")
                }
            }
        } catch (_: Exception) {
        }
        return "Documento.jfpw"
    }

    private fun toast(text: String) = runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }

    // Entrega a resposta ao JavaScript. `json` já é texto JSON válido (ou null).
    private fun resolve(id: String, json: String?) {
        val arg = if (json == null) "null" else JSONObject.quote(json)
        runOnUiThread {
            web.evaluateJavascript("JFPMobile.resolve(${JSONObject.quote(id)}, $arg)", null)
        }
    }

    override fun onDestroy() {
        fileChooserCallback?.onReceiveValue(null)
        web.removeJavascriptInterface("AndroidJFP")
        super.onDestroy()
    }

    /** Ponte chamada pelo mobile.js. Roda em outra thread: tudo que mexe na tela vai por runOnUiThread. */
    inner class Bridge {
        @JavascriptInterface
        fun open(id: String) {
            runOnUiThread {
                pendingOpen = id
                try {
                    openLauncher.launch(arrayOf("*/*"))
                } catch (_: Exception) {
                    pendingOpen = null
                    resolve(id, null)
                }
            }
        }

        @JavascriptInterface
        fun save(id: String, path: String, content: String, suggestedName: String) {
            runOnUiThread {
                val target = path.substringBefore("#/")
                if (target.startsWith("content:")) {
                    val ok = try {
                        writeText(Uri.parse(target), content)
                    } catch (_: Exception) {
                        null
                    }
                    if (ok != null) {
                        resolve(id, ok)
                        return@runOnUiThread
                    }
                }
                pendingSave = Triple(id, content, suggestedName)
                try {
                    saveLauncher.launch("$suggestedName.jfpw")
                } catch (_: Exception) {
                    pendingSave = null
                    resolve(id, null)
                }
            }
        }

        @JavascriptInterface
        fun exportFile(id: String, name: String, mime: String, base64: String) {
            runOnUiThread {
                try {
                    pendingExport = Pair(id, Base64.decode(base64, Base64.DEFAULT))
                    exportMime = mime
                    exportLauncher.launch(name)
                } catch (_: Exception) {
                    pendingExport = null
                    resolve(id, null)
                }
            }
        }

        @JavascriptInterface
        fun print(title: String) {
            runOnUiThread {
                val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
                val job = title.ifBlank { "Documento" }
                pm.print(job, web.createPrintDocumentAdapter(job), null)
            }
        }

        @JavascriptInterface
        fun setDirty(flag: Boolean) {
            dirty = flag
        }

        @JavascriptInterface
        fun closeNow() {
            runOnUiThread { finish() }
        }
    }

    companion object {
        private const val HOST = "appassets.androidplatform.net"
    }
}
