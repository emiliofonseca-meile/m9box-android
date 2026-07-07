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
import android.util.Log
import com.owncloud.android.R
import com.owncloud.android.lib.common.accounts.AccountUtils
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object MeileSync {

    private const val TAG = "MeileSync"

    /** AccountManager userdata keys on the sync account. */
    const val KEY_BASE_URL = "meile_base_url"
    const val KEY_USER_ID = "meile_user_id"

    /** Provider authorities we sync into (see CalendarContract / ContactsContract). */
    const val CALENDAR_AUTHORITY = "com.android.calendar"
    const val CONTACTS_AUTHORITY = "com.android.contacts"

    /**
     * Periodic sync interval (seconds). Android's Doze batches periodic syncs into
     * maintenance windows, so anything under ~1h is unreliable and drains battery
     * for little gain; 1h is the sweet spot. Active users get near-instant freshness
     * from the foreground sync below (opening the app), and event changes that name
     * an attendee are also delivered out-of-band via the server's iMIP e-mail. True
     * real-time would need DAV Push (WebDAV-Push over UnifiedPush/FCM) — a v2 item.
     */
    private const val SYNC_INTERVAL_SECONDS = 60 * 60L // 1h

    /** Don't fire a foreground sync more often than this (throttle, seconds). */
    private const val FOREGROUND_SYNC_MIN_INTERVAL_MS = 10 * 60 * 1000L // 10min
    private var lastForegroundSyncMs = 0L

    fun accountType(context: Context): String = context.getString(R.string.meile_sync_account_type)

    private fun fileAccounts(context: Context): List<Account> =
        AccountManager.get(context).getAccountsByType(context.getString(R.string.account_type)).toList()

    /**
     * The sync account tied to a currently signed-in file account (they share the
     * same name). Picking the first sync account blindly can hit a STALE one left
     * over from a previous login (no file account -> auth fails -> the real account
     * never syncs), so always match against the current file account(s).
     */
    fun currentSyncAccount(context: Context): Account? {
        val fileNames = fileAccounts(context).map { it.name }.toSet()
        return AccountManager.get(context).getAccountsByType(accountType(context))
            .firstOrNull { it.name in fileNames }
    }

    /** Drop sync accounts whose file account is gone (e.g. after a logout). */
    fun removeStaleAccounts(context: Context) {
        val am = AccountManager.get(context)
        val fileNames = fileAccounts(context).map { it.name }.toSet()
        am.getAccountsByType(accountType(context))
            .filter { it.name !in fileNames }
            .forEach { runCatching { am.removeAccountExplicitly(it) } }
    }

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
        // removePeriodicSync first: re-adding with the same extras does NOT reliably
        // update the period of an existing registration (the old JobScheduler job
        // survives), so an interval change wouldn't take on already-provisioned
        // accounts. Clearing it forces the new cadence.
        ContentResolver.removePeriodicSync(account, authority, Bundle())
        ContentResolver.addPeriodicSync(account, authority, Bundle(), SYNC_INTERVAL_SECONDS)
    }

    /**
     * Re-apply the periodic interval to an already-provisioned account. Accounts
     * created before the interval changed keep their old registration until enableSync
     * runs again; call this when the sync screen opens so existing users pick up the
     * current cadence without re-adding the account.
     */
    fun reconcilePeriodicSync(context: Context) {
        val account = currentSyncAccount(context) ?: return
        enableSync(account, CALENDAR_AUTHORITY)
        enableSync(account, CONTACTS_AUTHORITY)
    }

    /**
     * Kick off an immediate manual sync of both providers. IGNORE_BACKOFF +
     * IGNORE_SETTINGS make it run unconditionally — without them the framework
     * throttles/coalesces a manual sync that follows a recent one, so nothing
     * actually runs and the UI would wait forever.
     */
    /**
     * Throttled sync when the app comes to the foreground, so an active user sees
     * server-side changes almost immediately (instead of waiting up to 1h). Uses
     * plain extras — NOT ignore-backoff — so the framework can still coalesce it and
     * it stays battery-cheap. No-op if there is no current sync account. Safe to call
     * from any Activity.onResume.
     */
    fun requestForegroundSync(context: Context) {
        val account = currentSyncAccount(context) ?: return
        val now = System.currentTimeMillis()
        if (now - lastForegroundSyncMs < FOREGROUND_SYNC_MIN_INTERVAL_MS) return
        lastForegroundSyncMs = now
        val extras = Bundle().apply { putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true) }
        ContentResolver.requestSync(account, CALENDAR_AUTHORITY, extras)
        ContentResolver.requestSync(account, CONTACTS_AUTHORITY, extras)
    }

    fun requestImmediateSync(account: Account) {
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, true)
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
        if (baseUrl.isEmpty() || userId.isEmpty()) return null
        val header = authHeaderFromFileAccount(context, account.name) ?: return null
        return Credentials(baseUrl, userId, header)
    }

    /**
     * Reuse the M9 Box FILE account's real DAV credentials (the sync account shares
     * its name). The app authenticates with an app-password whose Basic username is
     * the login name — NOT the user id (oc_id). Hand-building `Basic base64(userId:pw)`
     * was wrong and got 401; `OwnCloudClient.credentials.toOkHttpCredentials()` yields
     * the exact Authorization header the app uses (Basic or Bearer).
     */
    private fun authHeaderFromFileAccount(context: Context, accountName: String): String? {
        val fileType = context.getString(R.string.account_type)
        val fileAccount = AccountManager.get(context)
            .getAccountsByType(fileType)
            .firstOrNull { it.name == accountName } ?: return null
        return try {
            // Load the real credentials from AccountManager. Building an
            // OwnCloudAccount and getClientFor() without loadCredentials() yields an
            // ANONYMOUS client ("Basic :") → 401; getCredentialsForAccount loads them.
            val creds = AccountUtils.getCredentialsForAccount(context, fileAccount)
            creds?.toOkHttpCredentials()
        } catch (e: Exception) {
            Log.w(TAG, "credential load failed", e)
            null
        }
    }

    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
}
