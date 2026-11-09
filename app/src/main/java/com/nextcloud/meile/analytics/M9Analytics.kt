/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * Firebase Analytics do M9 Box: quem usa o app e o quê. Só o flavor meile tem a biblioteca
 * (compileOnly nos outros), por isso tudo passa por aqui e checa o flavor antes.
 *
 * A conta vai como código, nunca como e-mail: os termos do Google Analytics proíbem dado que
 * identifique a pessoa, mesmo com hash. O código é um HMAC do login com uma chave que fica fora do
 * repositório (Keychain, injetada no build); só quem tem a chave liga código e pessoa.
 */
package com.nextcloud.meile.analytics

import android.content.Context
import androidx.core.os.bundleOf
import com.google.firebase.analytics.FirebaseAnalytics
import com.nextcloud.client.account.UserAccountManagerImpl
import com.owncloud.android.BuildConfig
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object M9Analytics {
    private val enabled = BuildConfig.FLAVOR == "meile"
    private const val NO_ACCOUNT = ""

    // Conta já informada ao Firebase; evita refazer o HMAC a cada tela.
    private var lastAccount: String? = null

    /** Código da conta: 16 hex do HMAC-SHA256 do login em minúsculas. Vazio sem chave no build. */
    @JvmStatic
    fun accountCode(login: String, key: String = BuildConfig.M9_ANALYTICS_KEY): String {
        if (key.isEmpty()) return NO_ACCOUNT
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")) }
        return mac.doFinal(login.lowercase().toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }

    /** Liga os eventos à conta atual (login, troca e saída de conta). Chamado a cada tela. */
    @JvmStatic
    fun identify(context: Context) {
        if (!enabled) return
        val user = UserAccountManagerImpl.fromContext(context).user
        val account = if (user.isAnonymous) NO_ACCOUNT else user.accountName
        if (account == lastAccount) return
        lastAccount = account

        val analytics = FirebaseAnalytics.getInstance(context)
        if (account == NO_ACCOUNT) {
            analytics.setUserId(null)
            analytics.setUserProperty("conta", null)
            analytics.setUserProperty("servidor", null)
            return
        }
        // accountName = "<login>@<host do servidor>"; o login pode ser um e-mail.
        val code = accountCode(account.substringBeforeLast('@')).ifEmpty { null }
        analytics.setUserId(code)
        analytics.setUserProperty("conta", code)
        analytics.setUserProperty("servidor", user.server.uri.host?.take(36))
    }

    /** Item do menu lateral ou da barra de baixo (ex.: "meile_talk", "favorites"). */
    @JvmStatic
    fun menu(context: Context, itemId: Int) {
        if (!enabled) return
        val item = runCatching { context.resources.getResourceEntryName(itemId) }.getOrNull() ?: return
        FirebaseAnalytics.getInstance(context).logEvent("menu_item", bundleOf("item" to item.removePrefix("nav_")))
    }

    /** Envio concluído; auto = envio automático de fotos/vídeos, não um envio da pessoa. */
    @JvmStatic
    fun upload(context: Context, auto: Boolean) {
        if (!enabled) return
        FirebaseAnalytics.getInstance(context).logEvent("file_upload", bundleOf("auto" to auto.toString()))
    }
}
