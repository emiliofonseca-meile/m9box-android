/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * "Sincronizar Agenda e Contatos" screen. Three clear states, so there is never a
 * stuck/dark disabled button:
 *   - OFF      : description + "Ativar sincronização" button.
 *   - SYNCING  : spinner + "Sincronizando…".
 *   - ACTIVE   : a green success card ("✓ Sincronização ativada", auto on app open
 *                + every ~1h) with a subtle "Sincronizar agora" link for a manual refresh.
 * A SyncStatusObserver flips SYNCING → ACTIVE when the background sync-adapter
 * finishes, so the manual sync gives real feedback.
 */
package com.nextcloud.meile.sync.ui

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.nextcloud.meile.sync.MeileSync
import com.owncloud.android.R
import com.owncloud.android.lib.common.accounts.AccountUtils
import java.net.URI

class MeileSyncSetupActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var actionButton: MaterialButton
    private lateinit var successCard: MaterialCardView
    private lateinit var progress: ProgressBar

    private var observerHandle: Any? = null
    private var awaitingSync = false
    private var sawSyncActive = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val syncTimeout = Runnable { if (awaitingSync) finishSyncing() }

    private val requiredPermissions = arrayOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Clean up sync accounts orphaned by a previous logout so a stale one is
        // never picked and never fails auth in the background.
        MeileSync.removeStaleAccounts(this)
        // Bring accounts created before the interval changed up to the current cadence.
        MeileSync.reconcilePeriodicSync(this)
        setContentView(R.layout.activity_meile_sync)

        val toolbar = findViewById<MaterialToolbar>(R.id.meile_sync_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        status = findViewById(R.id.meile_sync_status)
        actionButton = findViewById(R.id.meile_sync_action)
        successCard = findViewById(R.id.meile_sync_success)
        progress = findViewById(R.id.meile_sync_progress)

        actionButton.setOnClickListener { onAction() }
        findViewById<MaterialButton>(R.id.meile_sync_resync).setOnClickListener { triggerSync() }
        renderIdle()
    }

    override fun onResume() {
        super.onResume()
        observerHandle = ContentResolver.addStatusChangeListener(
            ContentResolver.SYNC_OBSERVER_TYPE_ACTIVE or ContentResolver.SYNC_OBSERVER_TYPE_PENDING
        ) { runOnUiThread { onSyncStatusChanged() } }
        if (!awaitingSync) renderIdle()
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(syncTimeout)
        observerHandle?.let { ContentResolver.removeStatusChangeListener(it) }
        observerHandle = null
    }

    /** OFF (button) or ACTIVE (success card), depending on whether sync is set up. */
    private fun renderIdle(doneMessage: String? = null) {
        awaitingSync = false
        progress.visibility = View.GONE
        val active = syncAccount() != null
        actionButton.visibility = if (active) View.GONE else View.VISIBLE
        actionButton.isEnabled = true
        successCard.visibility = if (active) View.VISIBLE else View.GONE
        status.text = doneMessage ?: if (active) "" else getString(R.string.meile_sync_state_off)
    }

    /** SYNCING: hide both action affordances, show the spinner. */
    private fun renderSyncing() {
        awaitingSync = true
        sawSyncActive = false
        actionButton.visibility = View.GONE
        successCard.visibility = View.GONE
        progress.visibility = View.VISIBLE
        status.text = getString(R.string.meile_sync_syncing)
        // Safety net: never stay stuck on the spinner even if the sync gets
        // throttled/coalesced and no active->idle transition ever fires.
        uiHandler.removeCallbacks(syncTimeout)
        uiHandler.postDelayed(syncTimeout, 20_000)
    }

    private fun finishSyncing() {
        uiHandler.removeCallbacks(syncTimeout)
        renderIdle(doneMessage = getString(R.string.meile_sync_done))
    }

    private fun onAction() {
        if (syncAccount() == null) {
            if (currentFileAccount() == null) {
                status.text = getString(R.string.meile_sync_setup_no_account)
                return
            }
            val missing = requiredPermissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMISSIONS)
                return
            }
        }
        triggerSync()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                triggerSync()
            } else {
                status.text = getString(R.string.meile_sync_setup_permissions)
            }
        }
    }

    /** Create the account if needed, then request an immediate sync + show progress. */
    private fun triggerSync() {
        try {
            val account = syncAccount() ?: createSyncAccount()
            renderSyncing()
            MeileSync.requestImmediateSync(account)
        } catch (e: Exception) {
            renderIdle()
            status.text = getString(R.string.meile_sync_setup_error, e.message ?: "erro")
        }
    }

    /** When the background sync stops running, flip to ACTIVE + "✓ Sincronizado". */
    private fun onSyncStatusChanged() {
        if (!awaitingSync) return
        val account = syncAccount() ?: return
        val running = ContentResolver.isSyncActive(account, MeileSync.CALENDAR_AUTHORITY) ||
            ContentResolver.isSyncActive(account, MeileSync.CONTACTS_AUTHORITY) ||
            ContentResolver.isSyncPending(account, MeileSync.CALENDAR_AUTHORITY) ||
            ContentResolver.isSyncPending(account, MeileSync.CONTACTS_AUTHORITY)
        if (running) {
            sawSyncActive = true
            return
        }
        // Only finish once we've actually seen the sync run (avoids finishing in
        // the brief gap before requestSync makes it active); the timeout covers
        // the throttled case where it never runs.
        if (sawSyncActive) finishSyncing()
    }

    private fun createSyncAccount(): Account {
        val fileAccount = currentFileAccount() ?: throw IllegalStateException("no account")
        val am = AccountManager.get(this)
        val baseUrl = am.getUserData(fileAccount, AccountUtils.Constants.KEY_OC_BASE_URL)
            ?: throw IllegalStateException("missing server url")
        val userId = am.getUserData(fileAccount, AccountUtils.Constants.KEY_USER_ID)
            ?: fileAccount.name.substringBeforeLast('@')
        val password = am.getPassword(fileAccount) ?: ""
        return MeileSync.createOrUpdateAccount(
            context = this,
            displayName = displayNameFor(fileAccount, baseUrl),
            baseUrl = baseUrl,
            userId = userId,
            password = password
        )
    }

    private fun syncAccount(): Account? = MeileSync.currentSyncAccount(this)

    private fun currentFileAccount(): Account? =
        AccountManager.get(this).getAccountsByType(getString(R.string.account_type)).firstOrNull()

    private fun displayNameFor(fileAccount: Account, baseUrl: String): String {
        val host = runCatching { URI(baseUrl).host }.getOrNull() ?: return fileAccount.name
        val user = fileAccount.name.substringBeforeLast('@', fileAccount.name)
        return "$user@$host"
    }

    companion object {
        private const val REQ_PERMISSIONS = 4201
    }
}
