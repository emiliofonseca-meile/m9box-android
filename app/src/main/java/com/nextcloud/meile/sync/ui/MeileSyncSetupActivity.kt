/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * "Sincronizar Agenda e Contatos" setup screen. Reuses the already-signed-in
 * M9 Box file account (senha Box = senha NC) to create the CalDAV/CardDAV sync
 * account, requests the calendar/contacts runtime permissions, then triggers an
 * immediate sync so events/contacts land in the phone's native apps.
 */
package com.nextcloud.meile.sync.ui

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.owncloud.android.R
import com.owncloud.android.lib.common.accounts.AccountUtils
import java.net.URI

class MeileSyncSetupActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var button: Button

    private val requiredPermissions = arrayOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = getString(R.string.meile_sync_setup_title)
        setContentView(buildLayout())
    }

    private fun buildLayout(): View {
        val pad = (resources.displayMetrics.density * 20).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(TextView(this).apply {
            text = getString(R.string.meile_sync_setup_title)
            textSize = 20f
            setTextColor(Color.parseColor("#116DF0"))
        })
        column.addView(TextView(this).apply {
            text = getString(R.string.meile_sync_setup_description)
            textSize = 15f
            setPadding(0, pad / 2, 0, pad)
        })
        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        button = Button(this).apply {
            text = getString(R.string.meile_sync_setup_button)
            setOnClickListener { onActivate() }
        }
        column.addView(button)
        column.addView(status)
        return ScrollView(this).apply { addView(column) }
    }

    private fun onActivate() {
        if (currentFileAccount() == null) {
            status.text = getString(R.string.meile_sync_setup_no_account)
            return
        }
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            status.text = getString(R.string.meile_sync_setup_permissions)
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMISSIONS)
            return
        }
        activateSync()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                activateSync()
            } else {
                status.text = getString(R.string.meile_sync_setup_permissions)
            }
        }
    }

    private fun activateSync() {
        button.isEnabled = false
        status.text = getString(R.string.meile_sync_setup_progress)
        try {
            val fileAccount = currentFileAccount() ?: throw IllegalStateException("no account")
            val am = AccountManager.get(this)
            val baseUrl = am.getUserData(fileAccount, AccountUtils.Constants.KEY_OC_BASE_URL)
                ?: throw IllegalStateException("missing server url")
            val userId = am.getUserData(fileAccount, AccountUtils.Constants.KEY_USER_ID)
                ?: fileAccount.name.substringBeforeLast('@')
            val password = am.getPassword(fileAccount) ?: throw IllegalStateException("missing password")

            val displayName = displayNameFor(fileAccount, baseUrl)
            val syncAccount: Account = com.nextcloud.meile.sync.MeileSync.createOrUpdateAccount(
                context = this,
                displayName = displayName,
                baseUrl = baseUrl,
                userId = userId,
                password = password
            )
            com.nextcloud.meile.sync.MeileSync.requestImmediateSync(syncAccount)
            status.text = getString(R.string.meile_sync_setup_success)
        } catch (e: Exception) {
            status.text = getString(R.string.meile_sync_setup_error, e.message ?: "erro")
            button.isEnabled = true
        }
    }

    /** First M9 Box file account (type network.m9.box). */
    private fun currentFileAccount(): Account? =
        AccountManager.get(this).getAccountsByType(getString(R.string.account_type)).firstOrNull()

    /** "<user>@<box-host>" so the sync account is recognizable under Settings. */
    private fun displayNameFor(fileAccount: Account, baseUrl: String): String {
        val host = runCatching { URI(baseUrl).host }.getOrNull() ?: return fileAccount.name
        val user = fileAccount.name.substringBeforeLast('@', fileAccount.name)
        return "$user@$host"
    }

    companion object {
        private const val REQ_PERMISSIONS = 4201
    }
}
