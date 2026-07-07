/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * Embedded CalDAV/CardDAV device sync — the "one app" alternative to a separate
 * DAVx5 fork. Instead of a calendar screen that lives only inside this app, we
 * push the server's Agenda/Contatos into the phone's NATIVE Calendar/Contacts
 * providers via an AccountManager account + SyncAdapters. The account reuses the
 * credentials of the M9 Box file account already signed in (senha Box = senha NC).
 *
 * This object is the shared glue: account type, credential storage/retrieval,
 * discovery, and the OkHttp client the sync adapters use.
 */
package com.nextcloud.meile.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.util.Base64
import com.owncloud.android.R
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object MeileSync {

    /** AccountManager userdata keys on the sync account. */
    const val KEY_BASE_URL = "meile_base_url"
    const val KEY_USER_ID = "meile_user_id"

    /** Provider authorities we sync into (see CalendarContract / ContactsContract). */
    const val CALENDAR_AUTHORITY = "com.android.calendar"
    const val CONTACTS_AUTHORITY = "com.android.contacts"

    /** Periodic sync interval (seconds). */
    private const val SYNC_INTERVAL_SECONDS = 4 * 60 * 60L // 4h

    fun accountType(context: Context): String = context.getString(R.string.meile_sync_account_type)

    /**
     * Derive the Box server URL from an e-mail using the frota convention
     * (`box.<domain>` CNAME). Kept for a login-first flow; the in-app path reuses
     * the already-signed-in account's server instead (no guessing needed).
     */
    fun deriveBoxUrl(email: String): String? {
        val domain = email.substringAfter('@', "").trim().lowercase()
        if (domain.isEmpty() || !domain.contains('.')) return null
        return "https://box.$domain"
    }

    /**
     * Create (or update) the sync account and enable calendar + contacts sync.
     * @param displayName human account name shown under Settings > Accounts.
     */
    fun createOrUpdateAccount(
        context: Context,
        displayName: String,
        baseUrl: String,
        userId: String,
        password: String
    ): Account {
        val am = AccountManager.get(context)
        val account = Account(displayName, accountType(context))
        val userData = Bundle().apply {
            putString(KEY_BASE_URL, baseUrl.trimEnd('/'))
            putString(KEY_USER_ID, userId)
        }

        val existing = am.getAccountsByType(accountType(context)).firstOrNull { it.name == displayName }
        if (existing == null) {
            am.addAccountExplicitly(account, password, userData)
        } else {
            am.setPassword(existing, password)
            userData.keySet().forEach { am.setUserData(existing, it, userData.getString(it)) }
        }

        enableSync(account, CALENDAR_AUTHORITY)
        enableSync(account, CONTACTS_AUTHORITY)
        return account
    }

    private fun enableSync(account: Account, authority: String) {
        ContentResolver.setIsSyncable(account, authority, 1)
        ContentResolver.setSyncAutomatically(account, authority, true)
        ContentResolver.addPeriodicSync(account, authority, Bundle(), SYNC_INTERVAL_SECONDS)
    }

    /** Kick off an immediate manual sync of both providers. */
    fun requestImmediateSync(account: Account) {
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        ContentResolver.requestSync(account, CALENDAR_AUTHORITY, extras)
        ContentResolver.requestSync(account, CONTACTS_AUTHORITY, extras)
    }

    data class Credentials(val baseUrl: String, val userId: String, val authHeader: String)

    /** Read credentials for a sync account; null if incomplete. */
    fun credentials(context: Context, account: Account): Credentials? {
        val am = AccountManager.get(context)
        val baseUrl = am.getUserData(account, KEY_BASE_URL) ?: return null
        val userId = am.getUserData(account, KEY_USER_ID) ?: return null
        val password = am.getPassword(account) ?: return null
        if (baseUrl.isEmpty() || userId.isEmpty() || password.isEmpty()) return null
        return Credentials(baseUrl, userId, authHeader(userId, password))
    }

    fun authHeader(userId: String, password: String): String {
        val token = Base64.encodeToString("$userId:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "Basic $token"
    }

    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
}
