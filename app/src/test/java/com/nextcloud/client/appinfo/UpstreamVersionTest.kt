/*
 * M9 Box
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.nextcloud.client.appinfo

import com.owncloud.android.BuildConfig
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The User-Agent carries BuildConfig.UPSTREAM_VERSION, not the versionName a flavor shows to users.
 * Servers gate features on the client version they read there (the Nextcloud Office direct editor
 * needs a major >= 34), so it has to stay a real upstream release.
 */
class UpstreamVersionTest {
    @Test
    fun `upstream version is a real upstream release`() {
        val version = BuildConfig.UPSTREAM_VERSION

        assertTrue(version, Regex("""\d+\.\d+\.\d+( RC\d+| Alpha\d+)?""").matches(version))
        assertTrue(version, version.substringBefore('.').toInt() >= 34)
    }
}
