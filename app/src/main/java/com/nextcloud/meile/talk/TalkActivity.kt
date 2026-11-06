/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * Conversas: o Talk do próprio Box (chat + chamada de voz e vídeo) dentro do app.
 * Em vez de embutir o app Nextcloud Talk (outra base de código e outra marca), abre o Talk web
 * do servidor numa WebView já autenticada com a credencial da conta de arquivos e libera câmera
 * e microfone para o WebRTC. A chamada passa pelo mesmo HPB (meet) que o navegador usa.
 *
 * Autenticação: o Nextcloud não abre sessão web para senha de app (cada pedido com Basic vale só
 * para ele), então a credencial vai em TODO pedido ao Box:
 * - XHR/fetch do Talk: script no início do documento, só na origem do Box, que põe Authorization
 *   e OCS-APIRequest (este dispensa o token CSRF da sessão, que muda a cada pedido);
 * - GET sem credencial (navegação, prévia de arquivo, avatar): shouldInterceptRequest busca com ela.
 *
 * Sessão: o Talk guarda na sessão PHP em que sala o usuário está. Com senha de app, o Nextcloud
 * troca o id da sessão no segundo pedido autenticado; se esse segundo pedido for a rajada de XHR
 * do Talk, cada um troca para um id diferente e a sala "some". Por isso, antes de abrir o Talk,
 * dois pedidos em sequência fixam a sessão, e o cookie dela vai para a WebView.
 */
package com.nextcloud.meile.talk

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.DownloadManager
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.nextcloud.meile.sync.MeileSync
import com.owncloud.android.R
import com.owncloud.android.lib.common.accounts.AccountUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

class TalkActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MeileTalk"
        private const val EXTRA_ACCOUNT = "account"
        private const val EXTRA_URL = "url"
        private const val TALK_PATH = "/index.php/apps/spreed/"
        private val STATIC = Regex("""\.(js|mjs|css|map|woff2?|ttf|otf|wasm)$""")
        private val CALL_PATH = Regex("""/call/[A-Za-z0-9]+""")

        // Mesma origem = mesmo esquema, host e porta. Só o host não basta: um link http:// (ou de
        // outra porta) do mesmo host levaria a senha de app em texto claro ou para outro serviço.
        private fun port(u: Uri) = if (u.port != -1) u.port else if (u.scheme.equals("https", true)) 443 else 80
        fun sameOrigin(a: Uri, b: Uri): Boolean =
            a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && port(a) == port(b)

        // Credencial em todo XHR/fetch do Box. OCS-APIRequest faz o Nextcloud dispensar o token
        // CSRF (a sessão de um pedido com senha de app não sobrevive para o seguinte).
        private fun authScript(auth: String) = """
            (() => {
              if (window.__m9auth) return; window.__m9auth = true;
              const H = {"Authorization": ${JSONObject.quote(auth)}, "OCS-APIRequest": "true"};
              const same = u => { try { return new URL(u, location.href).origin === location.origin } catch (e) { return false } };
              const open = XMLHttpRequest.prototype.open, send = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.open = function (m, u) { this.__m9 = same(u); return open.apply(this, arguments) };
              XMLHttpRequest.prototype.send = function () {
                if (this.__m9) for (const k in H) { try { this.setRequestHeader(k, H[k]) } catch (e) {} }
                return send.apply(this, arguments);
              };
              const f = window.fetch;
              window.fetch = function (input, init) {
                const url = typeof input === "string" ? input : (input && input.url) || String(input);
                if (!same(url)) return f.apply(this, arguments);
                const req = new Request(input, init);
                for (const k in H) if (!req.headers.has(k)) req.headers.set(k, H[k]);
                return f.call(this, req);
              };
            })();
        """.trimIndent()

        fun intent(context: Context, accountName: String?, url: String? = null): Intent =
            Intent(context, TalkActivity::class.java)
                .putExtra(EXTRA_ACCOUNT, accountName)
                .putExtra(EXTRA_URL, url)

        /** Link do Talk no servidor da conta (notificação, perfil)? Abre aqui e devolve true. */
        fun openIfTalkLink(activity: Activity, link: String?, accountName: String? = null): Boolean {
            if (link.isNullOrEmpty()) return false
            val uri = runCatching { Uri.parse(link) }.getOrNull() ?: return false
            val path = uri.path ?: return false
            if (!path.contains("/apps/spreed") && !CALL_PATH.containsMatchIn(path)) return false
            val account = fileAccount(activity, accountName) ?: return false
            val base = AccountManager.get(activity).getUserData(account, AccountUtils.Constants.KEY_OC_BASE_URL)
                ?: return false
            if (!sameOrigin(Uri.parse(base), uri)) return false
            activity.startActivity(intent(activity, account.name, link))
            return true
        }

        // conta pedida; sem nome, a primeira (com nome que não existe, nenhuma: nunca abre como outro usuário)
        private fun fileAccount(context: Context, name: String?): Account? {
            val accounts = AccountManager.get(context).getAccountsByType(context.getString(R.string.account_type))
            return if (name == null) accounts.firstOrNull() else accounts.firstOrNull { it.name == name }
        }
    }

    private lateinit var web: WebView
    private lateinit var baseUri: Uri
    private var auth: String = ""
    private val http by lazy { MeileSync.okHttp() }
    private var pendingRequest: PermissionRequest? = null

    private var fileCallback: ValueCallback<Array<Uri>>? = null

    // "Enviar do dispositivo" do Talk: seletor do Android; cancelar devolve null (senão o Talk trava)
    private val pickFiles = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        fileCallback?.onReceiveValue(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
        fileCallback = null
    }

    // Câmera/microfone: o Talk pede pelo WebRTC; o Android precisa da permissão do app antes.
    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val request = pendingRequest ?: return@registerForActivityResult
        pendingRequest = null
        grantAllowed(request)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val account = fileAccount(this, intent.getStringExtra(EXTRA_ACCOUNT))
        lifecycleScope.launch {
            // o AccountManager bloqueia ao entregar a credencial: fora da thread principal
            val base = account?.let { AccountManager.get(this@TalkActivity).getUserData(it, AccountUtils.Constants.KEY_OC_BASE_URL) }
            val credential = account?.let {
                withContext(Dispatchers.IO) {
                    runCatching { AccountUtils.getCredentialsForAccount(this@TalkActivity, it)?.toOkHttpCredentials() }
                        .onFailure { e -> Log.w(TAG, "credential load failed", e) }
                        .getOrNull()
                }
            }
            if (base.isNullOrEmpty() || credential.isNullOrEmpty()) {
                Toast.makeText(this@TalkActivity, R.string.meile_talk_no_account, Toast.LENGTH_LONG).show()
                finish()
            } else {
                if (savedInstanceState == null) withContext(Dispatchers.IO) { warmSession(base, credential) }
                start(savedInstanceState, base, credential)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun start(savedInstanceState: Bundle?, base: String, credential: String) {
        baseUri = Uri.parse(base)
        auth = credential
        val origin = baseUri.scheme + "://" + baseUri.host + (if (baseUri.port > 0) ":" + baseUri.port else "")

        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        web = WebView(this)
        // edge-to-edge (alvo 35): o conteúdo do Talk não pode ficar sob a barra de status/navegação.
        // O recuo vai num container: a WebView não respeita padding no próprio conteúdo.
        val root = FrameLayout(this).apply { addView(web) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        // chamada não pode apagar a tela no meio
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            setSupportMultipleWindows(false)
            // O Talk marca a WebView ("; wv", "Version/4.0") como navegador sem suporte e avisa
            // na tela; o motor é o Chrome do aparelho, então ela se apresenta como Chrome.
            userAgentString = WebSettings.getDefaultUserAgent(this@TalkActivity)
                .replace("; wv", "").replace(" Version/4.0", "")
        }
        CookieManager.getInstance().setAcceptCookie(true)

        val script = authScript(auth)
        val startScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (startScript) {
            WebViewCompat.addDocumentStartJavaScript(web, script, setOf(origin))
        }

        web.webViewClient = object : WebViewClient() {
            // dentro do Box fica no app; link http(s) para fora vai pro navegador; outros esquemas, nada
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (sameOrigin(baseUri, request.url)) return false
                if (request.url.scheme == "http" || request.url.scheme == "https") {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                }
                return true
            }

            // WebView antiga sem script de início de documento: injeta assim que a página começa
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!startScript && sameOrigin(baseUri, Uri.parse(url ?: ""))) view.evaluateJavascript(script, null)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                withCredential(request)
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                pickFiles.launch(params.acceptTypes.firstOrNull { it.isNotBlank() } ?: "*/*")
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                if (!sameOrigin(baseUri, request.origin)) {
                    request.deny()
                    return
                }
                val missing = androidPermissionsFor(request).filter {
                    ContextCompat.checkSelfPermission(this@TalkActivity, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) {
                    grantAllowed(request)
                } else {
                    pendingRequest?.deny()
                    pendingRequest = request
                    askPermissions.launch(missing.toTypedArray())
                }
            }
        }

        // Baixar anexo: o DownloadManager leva a credencial (só para o Box) e avisa quando terminar.
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val uri = Uri.parse(url)
            if (!sameOrigin(baseUri, uri)) {
                if (uri.scheme == "http" || uri.scheme == "https") runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                return@setDownloadListener
            }
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val download = DownloadManager.Request(uri)
                .addRequestHeader("Authorization", auth)
                .addRequestHeader("User-Agent", userAgent)
                .setMimeType(mimeType)
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            getSystemService(DownloadManager::class.java).enqueue(download)
            Toast.makeText(this, getString(R.string.meile_talk_downloading, name), Toast.LENGTH_SHORT).show()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            // link de fora do Box nunca recebe a credencial: cai na tela inicial das Conversas
            val url = intent.getStringExtra(EXTRA_URL)?.takeIf { sameOrigin(baseUri, Uri.parse(it)) }
                ?: (base.trimEnd('/') + TALK_PATH)
            web.loadUrl(url, mapOf("Authorization" to auth))
        }
    }

    /** Dois pedidos autenticados em sequência fixam a sessão PHP; o cookie final vai para a WebView. */
    private fun warmSession(base: String, credential: String) {
        val jar = mutableMapOf<String, okhttp3.Cookie>()
        val client = http.newBuilder().cookieJar(object : okhttp3.CookieJar {
            override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                cookies.forEach { jar[it.name] = it }
            }
            override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> = jar.values.toList()
        }).build()
        val url = base.trimEnd('/') + "/ocs/v2.php/cloud/user?format=json"
        try {
            repeat(2) {
                client.newCall(
                    Request.Builder().url(url).header("Authorization", credential).header("OCS-APIRequest", "true").build()
                ).execute().close()
            }
            val cookies = CookieManager.getInstance()
            jar.values.forEach { c ->
                cookies.setCookie(
                    base,
                    "${c.name}=${c.value}; Path=${c.path}" + (if (c.secure) "; Secure" else "") + (if (c.httpOnly) "; HttpOnly" else "")
                )
            }
            cookies.flush()
        } catch (e: Exception) {
            Log.w(TAG, "aquecimento da sessão falhou; segue sem", e)
        }
    }

    /** GET do Box sem credencial (navegação, prévia, avatar): busca com ela. O resto segue normal. */
    private fun withCredential(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (!sameOrigin(baseUri, url) || request.method != "GET" || STATIC.containsMatchIn(url.path ?: "")) return null
        if (request.requestHeaders.keys.any { it.equals("Authorization", ignoreCase = true) }) return null
        return try {
            val builder = Request.Builder().url(url.toString())
            request.requestHeaders.forEach { (k, v) -> if (!k.equals("Cookie", true)) builder.header(k, v) }
            builder.header("Authorization", auth)
            val resp = http.newCall(builder.build()).execute()
            val type = resp.header("Content-Type") ?: "application/octet-stream"
            val mime = type.substringBefore(';').trim()
            val charset = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)?.trim()
            val headers = resp.headers.names().associateWith { resp.header(it) ?: "" }
                .filterKeys { !it.equals("Content-Type", true) && !it.equals("Set-Cookie", true) }
            WebResourceResponse(mime, charset, resp.code, resp.message.ifEmpty { "OK" }, headers, resp.body.byteStream())
        } catch (e: Exception) {
            Log.w(TAG, "falha ao buscar ${url.path}", e)
            null
        }
    }

    private fun androidPermissionsFor(request: PermissionRequest): List<String> = request.resources.mapNotNull {
        when (it) {
            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
            else -> null
        }
    }

    // concede ao Talk só o que o Android liberou (sem câmera, a chamada segue só com áudio)
    private fun grantAllowed(request: PermissionRequest) {
        val allowed = request.resources.filter { res ->
            val perm = when (res) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                else -> return@filter false
            }
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
        if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::web.isInitialized) web.saveState(outState)
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
