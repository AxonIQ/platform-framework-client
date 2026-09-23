/*
 * Copyright (c) 2026. AxonIQ B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.axoniq.platform.framework.client

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AxoniqConsoleRSocketClientLoggingTest {

    @Nested
    inner class RecognisingARefusal {

        @Test
        fun `recognises what the platform says when it rejects a token`() {
            assertTrue(isAuthFailure(RuntimeException("Access Denied")))
            assertTrue(isAuthFailure(RuntimeException("access denied")))
            assertTrue(isAuthFailure(RuntimeException("invalid authentication")))
        }

        @Test
        fun `looks through the wrapper RSocket puts around the server's error`() {
            val wrapped = IllegalStateException(
                    "Could not receive the settings from Axoniq Platform!",
                    RuntimeException("Access Denied")
            )

            assertTrue(isAuthFailure(wrapped))
        }

        @Test
        fun `does not mistake an unreachable platform for a rejected token`() {
            assertFalse(isAuthFailure(java.net.ConnectException("Connection refused")))
            assertFalse(isAuthFailure(RuntimeException("Connection reset by peer")))
            assertFalse(isAuthFailure(RuntimeException(null as String?)))
        }

        @Test
        fun `does not mistake a network failure that merely mentions authentication`() {
            // Telling an operator to check their access token because a proxy or TLS handshake said
            // "authentication" is the misattribution this all exists to remove.
            assertFalse(isAuthFailure(RuntimeException("Proxy Authentication Required")))
            assertFalse(isAuthFailure(RuntimeException("SSL handshake failed: client authentication")))
            assertFalse(isAuthFailure(RuntimeException("Unauthorized")))
        }

        @Test
        fun `terminates on a cause chain that refers back to itself`() {
            val looping = object : RuntimeException("Connection reset") {
                override val cause: Throwable get() = this
            }

            assertFalse(isAuthFailure(looping))
        }

        private fun isAuthFailure(error: Throwable) =
                AxoniqConsoleRSocketClient.isAuthenticationFailure(error)
    }

    @Nested
    inner class DecidingWhenToSpeak {

        @Test
        fun `says so at once when the very first connection an application makes is refused`() {
            // Nothing has ever worked, so this is far likelier to be a misconfigured token than a blip,
            // and someone is usually watching the application start.
            assertTrue(shouldReport(refusedCredentials = true, hasEverConnected = false, retryCount = 1))
        }

        @Test
        fun `stays quiet when a refusal interrupts a connection that had been working`() {
            // The case the customer hit: the platform refused a perfectly good token for two seconds while
            // its own gateway was restarting.
            assertFalse(shouldReport(refusedCredentials = true, hasEverConnected = true, retryCount = 1))
            (2..9).forEach {
                assertFalse(shouldReport(refusedCredentials = true, hasEverConnected = true, retryCount = it))
            }
        }

        @Test
        fun `repeats itself while the problem lasts`() {
            listOf(10, 20, 30).forEach {
                assertTrue(shouldReport(refusedCredentials = true, hasEverConnected = true, retryCount = it))
                assertTrue(shouldReport(refusedCredentials = false, hasEverConnected = true, retryCount = it))
            }
            listOf(11, 19, 21).forEach {
                assertFalse(shouldReport(refusedCredentials = true, hasEverConnected = true, retryCount = it))
            }
        }

        @Test
        fun `keeps reporting a token that never worked, after the immediate first word`() {
            assertTrue(shouldReport(refusedCredentials = true, hasEverConnected = false, retryCount = 1))
            (2..9).forEach {
                assertFalse(shouldReport(refusedCredentials = true, hasEverConnected = false, retryCount = it))
            }
            assertTrue(shouldReport(refusedCredentials = true, hasEverConnected = false, retryCount = 10))
        }

        @Test
        fun `does not treat an unreachable platform at startup as something to shout about`() {
            // A platform that cannot be reached on the first attempt usually can be on the second.
            assertFalse(shouldReport(refusedCredentials = false, hasEverConnected = false, retryCount = 1))
        }

        @Test
        fun `never reports before an attempt has been made`() {
            assertFalse(shouldReport(refusedCredentials = true, hasEverConnected = false, retryCount = 0))
            assertFalse(shouldReport(refusedCredentials = false, hasEverConnected = true, retryCount = 0))
        }

        private fun shouldReport(refusedCredentials: Boolean, hasEverConnected: Boolean, retryCount: Int) =
                AxoniqConsoleRSocketClient.shouldReport(refusedCredentials, hasEverConnected, retryCount)
    }
}
