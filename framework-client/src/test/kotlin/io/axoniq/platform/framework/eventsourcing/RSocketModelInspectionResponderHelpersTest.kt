/*
 * Copyright (c) 2022-2026. AxonIQ B.V.
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

package io.axoniq.platform.framework.eventsourcing

import io.axoniq.platform.framework.AxoniqPlatformConfiguration
import io.axoniq.platform.framework.api.DomainEventAccessMode
import io.axoniq.platform.framework.api.ModelTimelineEntry
import io.axoniq.platform.framework.client.RSocketHandlerRegistrar
import io.mockk.every
import io.mockk.mockk
import org.axonframework.common.configuration.Configuration
import org.axonframework.eventsourcing.eventstore.EventStorageEngine
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID

/**
 * Unit tests for the pure-logic helpers on [RSocketModelInspectionResponder] that don't
 * require a live AF5 configuration / event store. These helpers govern the FE id-type form,
 * payload/state visibility, and per-page state trimming — regressing any of them silently
 * breaks either the inspection UI or the wire-level optimization.
 *
 * Tests are grouped per helper / behaviour via [Nested] so the surefire report mirrors the
 * code structure: one nested class per public surface of the responder.
 */
class RSocketModelInspectionResponderHelpersTest {

    private lateinit var responder: RSocketModelInspectionResponder

    @BeforeEach
    fun setUp() {
        // The id-type / normalization / trim helpers under test never reach into these
        // dependencies, so simple unrecorded mocks are enough. The access-mode nested group
        // builds its own responder per test against a tailored Configuration mock.
        responder = RSocketModelInspectionResponder(
                eventStorageEngine = mockk<EventStorageEngine>(),
                registrar = mockk<RSocketHandlerRegistrar>(),
                configuration = mockk<Configuration>(),
        )
    }

    /**
     * Drives whether the FE renders a single text input or a multi-field form. False
     * negatives produce a wrong UI for compound ids.
     */
    @Nested
    inner class IsSimpleIdType {

        @Test
        fun `is true for String UUID and primitives`() {
            assertTrue(responder.isSimpleIdType(String::class.java))
            assertTrue(responder.isSimpleIdType(UUID::class.java))
            assertTrue(responder.isSimpleIdType(java.lang.Long::class.java))
            assertTrue(responder.isSimpleIdType(java.lang.Integer::class.java))
            assertTrue(responder.isSimpleIdType(java.lang.Long.TYPE))      // primitive long
            assertTrue(responder.isSimpleIdType(java.lang.Integer.TYPE))   // primitive int
            assertTrue(responder.isSimpleIdType(BigDecimal::class.java))
            assertTrue(responder.isSimpleIdType(BigInteger::class.java))
        }

        @Test
        fun `is true for enums`() {
            assertTrue(responder.isSimpleIdType(SampleEnumId::class.java))
        }

        @Test
        fun `is false for record-style compound ids`() {
            assertFalse(responder.isSimpleIdType(SampleCompoundId::class.java))
        }

        @Test
        fun `unwraps Kotlin value classes and tests their underlying type`() {
            // SampleValueId wraps a String, so it should be classified as simple.
            assertTrue(responder.isSimpleIdType(SampleValueId::class.java))
        }
    }

    /**
     * Maps Java types to FE-friendly strings consumed by EntityIdForm.vue field renderer.
     */
    @Nested
    inner class NormalizedType {

        @Test
        fun `maps common Java types to FE strings`() {
            assertEquals("string", responder.normalizedType(String::class.java))
            assertEquals("uuid", responder.normalizedType(UUID::class.java))
            assertEquals("number", responder.normalizedType(java.lang.Long::class.java))
            assertEquals("number", responder.normalizedType(java.lang.Integer.TYPE)) // primitive
            assertEquals("number", responder.normalizedType(BigDecimal::class.java))
            assertEquals("boolean", responder.normalizedType(java.lang.Boolean::class.java))
            assertEquals("boolean", responder.normalizedType(java.lang.Boolean.TYPE))
            assertEquals("string", responder.normalizedType(java.lang.Character::class.java))
            // Anything we don't have a special case for falls through to "object".
            assertEquals("object", responder.normalizedType(SampleCompoundId::class.java))
        }
    }

    /**
     * This shape directly drives the FE multi-field form. Records expose recordComponents,
     * POJOs expose declared fields, and simple types collapse to an empty list (single text
     * input on the FE).
     */
    @Nested
    inner class DescribeIdFields {

        @Test
        fun `returns empty for simple id types`() {
            assertTrue(responder.describeIdFields(String::class.java).isEmpty())
            assertTrue(responder.describeIdFields(UUID::class.java).isEmpty())
            assertTrue(responder.describeIdFields(java.lang.Long::class.java).isEmpty())
        }

        @Test
        fun `exposes record components in declaration order with normalized types`() {
            val descriptors = responder.describeIdFields(SampleCompoundId::class.java)
            assertEquals(2, descriptors.size)

            assertEquals("showId", descriptors[0].name)
            assertEquals("string", descriptors[0].type)
            assertEquals(String::class.java.name, descriptors[0].javaType)

            assertEquals("seatNumber", descriptors[1].name)
            assertEquals("number", descriptors[1].type)
            assertEquals(java.lang.Integer.TYPE.name, descriptors[1].javaType)
        }

        @Test
        fun `exposes plain POJO declared fields and skips static synthetic`() {
            val descriptors = responder.describeIdFields(SamplePojoId::class.java)
            // STATIC_FIELD must not appear; only `tenant` and `code`.
            assertEquals(listOf("tenant", "code"), descriptors.map { it.name })
            assertEquals(listOf("string", "number"), descriptors.map { it.type })
        }
    }

    /**
     * Trims stateBefore from every entry past the first in a page. The FE rehydrates these
     * positions from the previous entry's stateAfter, so transmitting both is wasted bytes.
     * Regressing this silently doubles a page-of-100 timeline response (~1.9 MB pre-gzip).
     */
    @Nested
    inner class TrimRedundantStateBefore {

        @Test
        fun `keeps stateBefore on the first entry and nulls the rest`() {
            val entries = mutableListOf(
                    entry(seq = 0, before = "{\"v\":\"initial\"}", after = "{\"v\":\"a\"}"),
                    entry(seq = 1, before = "{\"v\":\"a\"}", after = "{\"v\":\"b\"}"),
                    entry(seq = 2, before = "{\"v\":\"b\"}", after = "{\"v\":\"c\"}"),
            )

            responder.trimRedundantStateBefore(entries)

            assertEquals("{\"v\":\"initial\"}", entries[0].stateBefore)
            assertNull(entries[1].stateBefore)
            assertNull(entries[2].stateBefore)
            // stateAfter and the other fields must survive untouched — the FE relies on the
            // after chain for its lookback rehydration.
            assertEquals("{\"v\":\"a\"}", entries[0].stateAfter)
            assertEquals("{\"v\":\"b\"}", entries[1].stateAfter)
            assertEquals("{\"v\":\"c\"}", entries[2].stateAfter)
        }

        @Test
        fun `is a no-op on an empty page`() {
            val entries = mutableListOf<ModelTimelineEntry>()
            responder.trimRedundantStateBefore(entries)
            assertTrue(entries.isEmpty())
        }

        @Test
        fun `is a no-op on a single-entry page (no later entries to trim)`() {
            val entries = mutableListOf(entry(seq = 0, before = "{\"v\":\"only\"}", after = "{\"v\":\"a\"}"))
            responder.trimRedundantStateBefore(entries)
            assertEquals("{\"v\":\"only\"}", entries[0].stateBefore)
        }

        @Test
        fun `leaves an already-null stateBefore alone`() {
            // The very first event of an entity has no prior state, so the upstream collector
            // may already emit stateBefore = null. The trim must not throw on that path.
            val entries = mutableListOf(
                    entry(seq = 0, before = null, after = "{\"v\":\"a\"}"),
                    entry(seq = 1, before = "{\"v\":\"a\"}", after = "{\"v\":\"b\"}"),
            )

            responder.trimRedundantStateBefore(entries)

            assertNull(entries[0].stateBefore)
            assertNull(entries[1].stateBefore)
        }
    }

    /**
     * accessMode gates (mayShowPayload / mayShowState) mirror the AF4 console-framework-client
     * contract:
     *   FULL                     -> payload + state both visible
     *   PREVIEW_PAYLOAD_ONLY     -> payload visible, state hidden
     *   LOAD_DOMAIN_STATE_ONLY   -> state visible, payload hidden
     *   NONE (default)           -> both hidden
     *
     * Each test builds its own responder against a tailored Configuration mock so the lazy
     * accessMode read picks up the per-test mode.
     */
    @Nested
    inner class AccessModeGates {

        @Test
        fun `defaults to NONE when no AxoniqPlatformConfiguration is registered`() {
            // Privacy-first: if the operator never wired up a configuration (or the wiring
            // forgot to bridge the mode property), the responder must lock both gates closed.
            val responderForMode = responderForAccessMode(null)
            assertFalse(responderForMode.mayShowPayload())
            assertFalse(responderForMode.mayShowState())
            assertEquals(DomainEventAccessMode.NONE, responderForMode.accessMode)
        }

        @Test
        fun `FULL opens both payload and state gates`() {
            val responderForMode = responderForAccessMode(DomainEventAccessMode.FULL)
            assertTrue(responderForMode.mayShowPayload())
            assertTrue(responderForMode.mayShowState())
        }

        @Test
        fun `PREVIEW_PAYLOAD_ONLY opens payload only`() {
            val responderForMode = responderForAccessMode(DomainEventAccessMode.PREVIEW_PAYLOAD_ONLY)
            assertTrue(responderForMode.mayShowPayload())
            assertFalse(responderForMode.mayShowState())
        }

        @Test
        fun `LOAD_DOMAIN_STATE_ONLY opens state only`() {
            val responderForMode = responderForAccessMode(DomainEventAccessMode.LOAD_DOMAIN_STATE_ONLY)
            assertFalse(responderForMode.mayShowPayload())
            assertTrue(responderForMode.mayShowState())
        }

        @Test
        fun `NONE explicitly registered keeps both gates closed`() {
            val responderForMode = responderForAccessMode(DomainEventAccessMode.NONE)
            assertFalse(responderForMode.mayShowPayload())
            assertFalse(responderForMode.mayShowState())
        }

        /**
         * Builds a fresh responder whose [Configuration] yields an
         * [AxoniqPlatformConfiguration] with the given [mode]. Passing `null` simulates "no
         * configuration registered" — the responder should fall back to
         * [DomainEventAccessMode.NONE].
         */
        private fun responderForAccessMode(mode: DomainEventAccessMode?): RSocketModelInspectionResponder {
            val config = mockk<Configuration>()
            val platformConfig = mode?.let {
                mockk<AxoniqPlatformConfiguration>().also {
                    every { it.domainEventAccessMode } returns mode
                }
            }
            every {
                config.getOptionalComponent(AxoniqPlatformConfiguration::class.java)
            } returns Optional.ofNullable(platformConfig)
            return RSocketModelInspectionResponder(
                    eventStorageEngine = mockk<EventStorageEngine>(),
                    registrar = mockk<RSocketHandlerRegistrar>(),
                    configuration = config,
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    //  Shared test fixtures
    // ---------------------------------------------------------------------------------------

    private fun entry(seq: Long, before: String?, after: String?): ModelTimelineEntry =
            ModelTimelineEntry(
                    sequenceNumber = seq,
                    timestamp = "2026-01-01T00:00:00Z",
                    eventType = "SampleEvent",
                    eventPayload = "{}",
                    stateBefore = before,
                    stateAfter = after,
            )

    enum class SampleEnumId { A, B }

    /** A typical AF5 compound entity id (record). Mirrors `ReservationId(showId, seatNumber)`. */
    @JvmRecord
    data class SampleCompoundId(val showId: String, val seatNumber: Int)

    /** A plain POJO id with a static field that must be ignored. */
    @Suppress("unused")
    class SamplePojoId(val tenant: String, val code: Int) {
        companion object {
            @JvmStatic
            val STATIC_FIELD: String = "ignore-me"
        }
    }

    /** A Kotlin inline value class wrapping a String — should classify as simple. */
    @JvmInline
    value class SampleValueId(val raw: String)
}
