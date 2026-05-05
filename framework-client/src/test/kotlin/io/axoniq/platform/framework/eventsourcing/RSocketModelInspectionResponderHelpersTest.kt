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

import io.axoniq.platform.framework.client.RSocketHandlerRegistrar
import io.mockk.mockk
import org.axonframework.common.configuration.Configuration
import org.axonframework.eventsourcing.eventstore.EventStorageEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID

/**
 * Unit tests for the pure-logic helpers on [RSocketModelInspectionResponder] that don't
 * require a live AF5 configuration / event store. These helpers govern the FE id-type form,
 * so regressing them silently breaks the inspection UI.
 */
class RSocketModelInspectionResponderHelpersTest {

    private lateinit var responder: RSocketModelInspectionResponder

    @BeforeEach
    fun setUp() {
        // The helpers under test never reach into these dependencies, so simple unrecorded
        // mocks are enough — we don't need MockK relaxed mocks elsewhere.
        responder = RSocketModelInspectionResponder(
                eventStorageEngine = mockk<EventStorageEngine>(),
                registrar = mockk<RSocketHandlerRegistrar>(),
                configuration = mockk<Configuration>(),
        )
    }

    // ---------------------------------------------------------------------------------------
    //  isSimpleIdType
    //
    //  Drives whether the FE renders a single text input or a multi-field form. False
    //  negatives produce a wrong UI for compound ids.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `isSimpleIdType is true for String UUID and primitives`() {
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
    fun `isSimpleIdType is true for enums`() {
        assertTrue(responder.isSimpleIdType(SampleEnumId::class.java))
    }

    @Test
    fun `isSimpleIdType is false for record-style compound ids`() {
        assertFalse(responder.isSimpleIdType(SampleCompoundId::class.java))
    }

    @Test
    fun `isSimpleIdType unwraps Kotlin value classes and tests their underlying type`() {
        // SampleValueId wraps a String, so it should be classified as simple.
        assertTrue(responder.isSimpleIdType(SampleValueId::class.java))
    }

    // ---------------------------------------------------------------------------------------
    //  normalizedType
    //
    //  Maps Java types to FE-friendly strings consumed by EntityIdForm.vue field renderer.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `normalizedType maps common Java types to FE strings`() {
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

    // ---------------------------------------------------------------------------------------
    //  describeIdFields
    //
    //  This shape directly drives the FE multi-field form. Records expose recordComponents,
    //  POJOs expose declared fields, and simple types collapse to an empty list (single
    //  text input on the FE).
    // ---------------------------------------------------------------------------------------

    @Test
    fun `describeIdFields returns empty for simple id types`() {
        assertTrue(responder.describeIdFields(String::class.java).isEmpty())
        assertTrue(responder.describeIdFields(UUID::class.java).isEmpty())
        assertTrue(responder.describeIdFields(java.lang.Long::class.java).isEmpty())
    }

    @Test
    fun `describeIdFields exposes record components in declaration order with normalized types`() {
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
    fun `describeIdFields exposes plain POJO declared fields and skips static synthetic`() {
        val descriptors = responder.describeIdFields(SamplePojoId::class.java)
        // STATIC_FIELD must not appear; only `tenant` and `code`.
        assertEquals(listOf("tenant", "code"), descriptors.map { it.name })
        assertEquals(listOf("string", "number"), descriptors.map { it.type })
    }

    // ---------------------------------------------------------------------------------------
    //  Test fixtures
    // ---------------------------------------------------------------------------------------

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
