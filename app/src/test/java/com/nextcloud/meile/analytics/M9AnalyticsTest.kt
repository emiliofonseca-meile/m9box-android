/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.nextcloud.meile.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class M9AnalyticsTest {

    // Mesmo cálculo do Box/analytics/uso-app.sh (openssl dgst -sha256 -hmac), que liga código e pessoa.
    @Test
    fun `account code is the first 16 hex of HMAC-SHA256 of the lowercase login`() {
        val key = "chave-de-teste"
        assertEquals("8e75db620d3f026e", M9Analytics.accountCode("Fulano@Exemplo.com.br", key))
        assertEquals(M9Analytics.accountCode("fulano@exemplo.com.br", key), M9Analytics.accountCode("FULANO@exemplo.com.br", key))
    }

    @Test
    fun `no key in the build means no account code`() {
        assertEquals("", M9Analytics.accountCode("fulano@exemplo.com.br", ""))
    }
}
