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
 */
package com.nextcloud.meile.talk

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
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
            if (!path.contains("/apps/spreed") && !path.contains("/call/")) return false
            val account = fileAccount(activity, accountName) ?: return false
            val base = AccountManager.get(activity).getUserData(account, AccountUtils.Constants.KEY_OC_BASE_URL)
                ?: return false
            if (Uri.parse(base).host != uri.host) return false
            activity.startActivity(intent(activity, account.name, link))
            return true
        }

        private fun fileAccount(context: Context, name: String?): Account? {
            val accounts = AccountManager.get(context).getAccountsByType(context.getString(R.string.account_type))
            return accounts.firstOrNull { it.name == name } ?: accounts.firstOrNull()
        }
    }

    private lateinit var web: WebView
    private var host: String? = null
    private var auth: String = ""
    private val http by lazy { MeileSync.okHttp() }
    private var pendingRequest: PermissionRequest? = null

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
                start(savedInstanceState, base, credential)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun start(savedInstanceState: Bundle?, base: String, credential: String) {
        val baseUri = Uri.parse(base)
        host = baseUri.host
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
            // dentro do Box fica no app; link para fora vai pro navegador
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == host) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }

            // WebView antiga sem script de início de documento: injeta assim que a página começa
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!startScript && Uri.parse(url ?: "").host == host) view.evaluateJavascript(script, null)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                withCredential(request)
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                if (request.origin.host != host) {
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            // A credencial vai só no primeiro pedido: o Nextcloud abre a sessão (senha de app) e o
            // resto da navegação segue no cookie, como num navegador logado.
            val url = intent.getStringExtra(EXTRA_URL) ?: (base.trimEnd('/') + TALK_PATH)
            web.loadUrl(url, mapOf("Authorization" to auth))
        }
    }

    /** GET do Box sem credencial (navegação, prévia, avatar): busca com ela. O resto segue normal. */
    private fun withCredential(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.host != host || request.method != "GET" || STATIC.containsMatchIn(url.path ?: "")) return null
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
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
