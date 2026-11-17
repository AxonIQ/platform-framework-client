/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.eventprocessor

import io.axoniq.framework.messaging.deadletter.Cause
import io.axoniq.framework.messaging.deadletter.DeadLetter
import io.axoniq.framework.messaging.deadletter.SequencedDeadLetterProcessor
import io.axoniq.framework.messaging.deadletter.SequencedDeadLetterQueue
import io.axoniq.platform.framework.api.AutomaticRetryBackoff
import io.axoniq.platform.framework.api.AxoniqConsoleDlqMode
import io.axoniq.platform.framework.api.DeadLetterResponse
import io.axoniq.platform.framework.api.DeadLettersForAutomaticRetryRequest
import io.axoniq.platform.framework.tenancy.PlatformTenancy
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.apache.commons.codec.digest.DigestUtils
import org.axonframework.common.configuration.Configuration
import org.axonframework.messaging.core.Context
import org.axonframework.messaging.core.EmptyApplicationContext
import org.axonframework.messaging.core.Metadata
import org.axonframework.messaging.core.MessageType
import org.axonframework.messaging.core.unitofwork.ProcessingContext
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory
import org.axonframework.messaging.core.unitofwork.TransactionalUnitOfWorkFactory
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory
import org.axonframework.messaging.core.unitofwork.transaction.Transaction
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken
import org.axonframework.messaging.eventhandling.EventHandlingComponent
import org.axonframework.messaging.eventhandling.EventMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import java.util.function.UnaryOperator

/**
 * Unit tests for [DeadLetterManager]. Behaviour is grouped under [Nested] inner classes so each
 * area (discovery, sequence-identifier resolution, pagination, mutations, payload handling, DLQ
 * modes, error paths) is easy to scan and runs in isolation.
 *
 * The realistic end-to-end flow lives in [RSocketDlqResponderIntegrationTest], which boots a real
 * AF5 configuration with a Pooled Streaming processor and a deliberately-failing event handler.
 * These unit tests intentionally cover mockable edge cases the integration test cannot easily
 * exercise (negative pagination arguments, ByteArray payload-type fallback, every DlqMode).
 */
class DeadLetterManagerTest {

    @Nested
    inner class Discovery {

        @Test
        fun `exposes processor name as processing group when the processor has a single DLQ`() {
            val dlq = fakeDlq()
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
            )

            val infos = manager.infoFor("orders")

            assertEquals(listOf("orders"), infos.map { it.processingGroup })
        }

        @Test
        fun `exposes processor__component identifier when a processor has multiple DLQs`() {
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(),
                    "DeadLetterQueue[EventHandlingComponent[orders][AuditProjector]]" to fakeDlq(),
            )

            val infos = manager.infoFor("orders")

            assertEquals(
                    setOf("orders::OrderProjector", "orders::AuditProjector"),
                    infos.map { it.processingGroup }.toSet(),
            )
        }

        @Test
        fun `ignores components whose names do not match the DLQ pattern`() {
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(),
                    "SomeOtherComponent" to fakeDlq(),
                    "DeadLetterQueue[Other][format]" to fakeDlq(),
            )

            assertEquals(listOf("orders"), manager.infoFor("orders").map { it.processingGroup })
        }

        @Test
        fun `infoFor returns only DLQs belonging to the requested processor`() {
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequenceCount = 3),
                    "DeadLetterQueue[EventHandlingComponent[shipping][ShipmentProjector]]" to fakeDlq(sequenceCount = 7),
            )

            val ordersInfo = manager.infoFor("orders")
            val shippingInfo = manager.infoFor("shipping")

            assertEquals(listOf("orders" to 3L), ordersInfo.map { it.processingGroup to it.dlqSize })
            assertEquals(listOf("shipping" to 7L), shippingInfo.map { it.processingGroup to it.dlqSize })
        }
    }

    @Nested
    inner class SequenceIdentifiers {

        @Test
        fun `String result from policy is used verbatim as the sequence identifier`() {
            val ehc = ehcWithPolicy { _ -> "saga-42" }
            val sequence = listOf(fakeLetter("m1"), fakeLetter("m2"))
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequences = listOf(sequence)),
                    ehc = ehc,
            )

            val response = manager.deadLetters("orders")

            assertEquals(listOf("saga-42", "saga-42"), response.sequences[0].map { it.sequenceIdentifier })
        }

        @Test
        fun `non-String result from policy is reduced to hashCode toString`() {
            val payloadObject = 12345
            val ehc = ehcWithPolicy { _ -> payloadObject }
            val sequence = listOf(fakeLetter("m1"))
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequences = listOf(sequence)),
                    ehc = ehc,
            )

            val response = manager.deadLetters("orders")

            assertEquals(payloadObject.hashCode().toString(), response.sequences[0][0].sequenceIdentifier)
        }

        @Test
        fun `null result from policy falls back to message identifier`() {
            val ehc = ehcWithPolicy { _ -> null }
            val sequence = listOf(fakeLetter("m1"))
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequences = listOf(sequence)),
                    ehc = ehc,
            )

            val response = manager.deadLetters("orders")

            assertEquals("m1", response.sequences[0][0].sequenceIdentifier)
        }

        @Test
        fun `policy is invoked once per sequence (using the first letter) and the id is stamped across the sequence`() {
            val ehc = ehcWithPolicy { event -> event.identifier() }
            val sequence = listOf(fakeLetter("m1"), fakeLetter("m2"), fakeLetter("m3"))
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequences = listOf(sequence)),
                    ehc = ehc,
            )

            val response = manager.deadLetters("orders")

            assertEquals(listOf("m1", "m1", "m1"), response.sequences[0].map { it.sequenceIdentifier })
        }

        @Test
        fun `when no EventHandlingComponent is registered the manager falls back to the letter's message id`() {
            val sequence = listOf(fakeLetter("only-letter"))
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequences = listOf(sequence)),
                    ehc = null,
            )

            val response = manager.deadLetters("orders")

            assertEquals("only-letter", response.sequences[0][0].sequenceIdentifier)
        }

        @Test
        fun `empty sequence yields an empty letter list without crashing`() {
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequences = listOf(emptyList())),
            )

            val response = manager.deadLetters("orders")

            assertEquals(1, response.sequences.size)
            assertTrue(response.sequences[0].isEmpty())
        }
    }

    @Nested
    inner class Pagination {

        @Test
        fun `lettersForSequence returns the requested slice in order with the correct total`() {
            val sequence = (1..5).map { fakeLetter(messageId = "m$it", payload = "p$it") }
            val ehc = ehcWithPolicy { event -> event.identifier() }
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequences = listOf(sequence)),
                    ehc = ehc,
            )

            val response = manager.lettersForSequence("orders", "m1", offset = 1, size = 2)

            assertEquals(5L, response.totalCount)
            assertEquals(listOf("m2", "m3"), response.letters.map { it.messageIdentifier })
        }

        @Test
        fun `lettersForSequence coerces a negative offset to zero`() {
            val sequence = (1..3).map { fakeLetter("m$it") }
            val ehc = ehcWithPolicy { event -> event.identifier() }
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequences = listOf(sequence)),
                    ehc = ehc,
            )

            val response = manager.lettersForSequence("orders", "m1", offset = -5, size = 2)

            assertEquals(listOf("m1", "m2"), response.letters.map { it.messageIdentifier })
        }

        @Test
        fun `lettersForSequence coerces a non-positive size to one`() {
            val sequence = (1..3).map { fakeLetter("m$it") }
            val ehc = ehcWithPolicy { event -> event.identifier() }
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequences = listOf(sequence)),
                    ehc = ehc,
            )

            val response = manager.lettersForSequence("orders", "m1", offset = 0, size = 0)

            assertEquals(1, response.letters.size)
            assertEquals("m1", response.letters[0].messageIdentifier)
        }

        @Test
        fun `lettersForSequence returns an empty response when no sequence matches the id`() {
            val sequence = listOf(fakeLetter("real"))
            val ehc = ehcWithPolicy { event -> event.identifier() }
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequences = listOf(sequence)),
                    ehc = ehc,
            )

            val response = manager.lettersForSequence("orders", "stale-id", 0, 10)

            assertTrue(response.letters.isEmpty())
            assertEquals(0L, response.totalCount)
        }

        @Test
        fun `lettersForSequence caps the slice at the size argument even when the sequence is larger`() {
            val sequence = (1..10).map { fakeLetter("m$it") }
            val ehc = ehcWithPolicy { event -> event.identifier() }
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequences = listOf(sequence)),
                    ehc = ehc,
            )

            val response = manager.lettersForSequence("orders", "m1", offset = 0, size = 3)

            assertEquals(3, response.letters.size)
            assertEquals(10L, response.totalCount)
        }
    }

    @Nested
    inner class Mutations {

        @Test
        fun `delete by sequence evicts every letter in that sequence`() {
            val letters = listOf(fakeLetter("m1"), fakeLetter("m2"), fakeLetter("m3"))
            val dlq = fakeDlq(sequences = listOf(letters))
            val ehc = ehcWithPolicy { event -> event.identifier() }
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
                    ehc = ehc,
            )

            val evicted = manager.delete("orders", "m1")

            assertEquals(3, evicted)
            letters.forEach { verify(exactly = 1) { dlq.evict(it, null) } }
        }

        @Test
        fun `delete by sequence is a no-op when the sequence does not exist`() {
            val dlq = fakeDlq(sequences = listOf(listOf(fakeLetter("real"))))
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
            )

            val evicted = manager.delete("orders", "ghost")

            assertEquals(0, evicted)
            verify(exactly = 0) { dlq.evict(any<DeadLetter<EventMessage>>(), any()) }
        }

        @Test
        fun `delete by message evicts only the matching letter`() {
            val letter1 = fakeLetter("m1")
            val letter2 = fakeLetter("m2")
            val dlq = fakeDlq(sequences = listOf(listOf(letter1, letter2)))
            val ehc = ehcWithPolicy { event -> event.identifier() }
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
                    ehc = ehc,
            )

            val evicted = manager.delete("orders", "m1", "m2")

            assertTrue(evicted)
            verify(exactly = 1) { dlq.evict(letter2, null) }
            verify(exactly = 0) { dlq.evict(letter1, null) }
        }

        @Test
        fun `delete by message is a no-op when the message id is unknown in the sequence`() {
            val letter1 = fakeLetter("m1")
            val dlq = fakeDlq(sequences = listOf(listOf(letter1)))
            val ehc = ehcWithPolicy { event -> event.identifier() }
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
                    ehc = ehc,
            )

            val evicted = manager.delete("orders", "m1", "missing")

            assertFalse(evicted)
            verify(exactly = 0) { dlq.evict(any<DeadLetter<EventMessage>>(), any()) }
        }

        @Test
        fun `delete by message is a no-op when the sequence does not resolve`() {
            val dlq = fakeDlq(sequences = listOf(listOf(fakeLetter("real"))))
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
            )

            val evicted = manager.delete("orders", "ghost", "anything")

            assertFalse(evicted)
            verify(exactly = 0) { dlq.evict(any<DeadLetter<EventMessage>>(), any()) }
        }

        @Test
        fun `deleteAll returns the queue size and clears the queue`() {
            val dlq = fakeDlq(totalSize = 42L)
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
            )

            val deleted = manager.deleteAll("orders")

            assertEquals(42, deleted)
            verify(exactly = 1) { dlq.clear(null) }
        }

        @Test
        fun `sequenceSize returns the count of letters for the matching id`() {
            val sequence = (1..4).map { fakeLetter("m$it") }
            val ehc = ehcWithPolicy { event -> event.identifier() }
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequences = listOf(sequence)),
                    ehc = ehc,
            )

            assertEquals(4L, manager.sequenceSize("orders", "m1"))
        }

        @Test
        fun `sequenceSize returns zero when the id does not resolve`() {
            val sequence = listOf(fakeLetter("real"))
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequences = listOf(sequence)),
            )

            assertEquals(0L, manager.sequenceSize("orders", "ghost"))
        }
    }

    @Nested
    inner class PayloadHandling {

        @Test
        fun `payload at or below 1024 UTF-8 bytes is returned untouched`() {
            val payload = "x".repeat(1024)
            val sequence = listOf(fakeLetter("m1", payload = payload))
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequences = listOf(sequence)),
            )

            val response = manager.deadLetters("orders")

            assertEquals(payload, response.sequences[0][0].message)
        }

        @Test
        fun `payload over 1024 UTF-8 bytes is truncated without splitting a multi-byte codepoint`() {
            val char = "č"
            val payload = char.repeat(600) // 600 * 2 = 1200 bytes
            val sequence = listOf(fakeLetter("m1", payload = payload))
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequences = listOf(sequence)),
            )

            val result = manager.deadLetters("orders").sequences[0][0].message

            assertTrue(result.toByteArray(Charsets.UTF_8).size <= 1024)
            assertFalse(result.contains('�'))
            assertTrue(result.all { it == 'č' })
        }

        @Test
        fun `messageType uses the qualified name carried on the message type (primary in AF5)`() {
            val message = fakeEventMessage(
                    id = "m1",
                    payload = "still serialised".toByteArray(),
                    payloadType = ByteArray::class.java,
                    typeName = "com.example.OrderPlaced",
            )
            val letter = fakeLetterFromMessage(message)
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequences = listOf(listOf(letter))),
            )

            val apiLetter = manager.deadLetters("orders").sequences[0][0]

            assertEquals("com.example.OrderPlaced", apiLetter.messageType)
        }

        @Test
        fun `messageType falls back to payload class fqn when the message type lookup throws`() {
            val message = mockk<EventMessage>(relaxed = true)
            every { message.identifier() } returns "m1"
            every { message.payload() } returns "hello"
            every { message.payloadType() } returns String::class.java
            every { message.type() } throws IllegalStateException("no type")
            val letter = fakeLetterFromMessage(message)
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequences = listOf(listOf(letter))),
            )

            val apiLetter = manager.deadLetters("orders").sequences[0][0]

            assertEquals("java.lang.String", apiLetter.messageType)
        }
    }

    @Nested
    inner class DlqModes {

        @Test
        fun `NONE returns an empty list of sequences but still reports the total`() {
            val sequence = listOf(fakeLetter("m1"), fakeLetter("m2"))
            val ehc = ehcWithPolicy { event -> event.identifier() }
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequences = listOf(sequence)),
                    ehc = ehc,
                    dlqMode = AxoniqConsoleDlqMode.NONE,
            )

            val response = manager.deadLetters("orders")

            assertTrue(response.sequences.isEmpty())
            assertEquals(1L, response.totalCount)
        }

        @Test
        fun `NONE returns an empty SequenceLettersResponse`() {
            val sequence = listOf(fakeLetter("m1"))
            val ehc = ehcWithPolicy { event -> event.identifier() }
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequences = listOf(sequence)),
                    ehc = ehc,
                    dlqMode = AxoniqConsoleDlqMode.NONE,
            )

            val response = manager.lettersForSequence("orders", "m1", 0, 10)

            assertTrue(response.letters.isEmpty())
            assertEquals(0L, response.totalCount)
        }

        @Test
        fun `LIMITED strips payload and cause message, keeps sequence id unhashed`() {
            val sequence = listOf(fakeLetter("m1", payload = "secret-payload"))
            val ehc = ehcWithPolicy { event -> event.identifier() }
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequences = listOf(sequence)),
                    ehc = ehc,
                    dlqMode = AxoniqConsoleDlqMode.LIMITED,
            )

            val letter = manager.deadLetters("orders").sequences[0][0]

            assertEquals("<LIMITED>", letter.message)
            assertEquals("<LIMITED>", letter.causeMessage)
            assertEquals("m1", letter.sequenceIdentifier)
        }

        @Test
        fun `LIMITED filters diagnostics down to the configured whitelist`() {
            val whitelisted = mapOf("attempts" to "3", "cause" to "boom", "internal" to "shh")
            val sequence = listOf(fakeLetter("m1", diagnostics = whitelisted))
            val ehc = ehcWithPolicy { event -> event.identifier() }
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequences = listOf(sequence)),
                    ehc = ehc,
                    dlqMode = AxoniqConsoleDlqMode.LIMITED,
                    whitelist = listOf("attempts", "cause"),
            )

            val diagnostics = manager.deadLetters("orders").sequences[0][0].diagnostics

            assertEquals(setOf("attempts", "cause"), diagnostics.keys)
            assertFalse(diagnostics.containsKey("internal"))
        }

        @Test
        fun `LIMITED with empty whitelist drops every diagnostic`() {
            val sequence = listOf(fakeLetter("m1", diagnostics = mapOf("a" to "1", "b" to "2")))
            val ehc = ehcWithPolicy { event -> event.identifier() }
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequences = listOf(sequence)),
                    ehc = ehc,
                    dlqMode = AxoniqConsoleDlqMode.LIMITED,
            )

            val diagnostics = manager.deadLetters("orders").sequences[0][0].diagnostics

            assertTrue(diagnostics.isEmpty())
        }

        @Test
        fun `MASKED returns MASKED markers and SHA-256 hashes the sequence id`() {
            val sequence = listOf(fakeLetter("m1", payload = "secret"))
            val ehc = ehcWithPolicy { _ -> "saga-123" }
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequences = listOf(sequence)),
                    ehc = ehc,
                    dlqMode = AxoniqConsoleDlqMode.MASKED,
            )

            val letter = manager.deadLetters("orders").sequences[0][0]

            assertEquals("<MASKED>", letter.message)
            assertEquals("<MASKED>", letter.causeMessage)
            assertEquals(DigestUtils.sha256Hex("saga-123"), letter.sequenceIdentifier)
            assertNotEquals("saga-123", letter.sequenceIdentifier)
            assertTrue(letter.diagnostics.isEmpty())
        }

        @Test
        fun `MASKED still allows delete-by-sequence using the hashed id`() {
            val letters = listOf(fakeLetter("m1"), fakeLetter("m2"))
            val dlq = fakeDlq(sequences = listOf(letters))
            val ehc = ehcWithPolicy { _ -> "saga-xyz" }
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
                    ehc = ehc,
                    dlqMode = AxoniqConsoleDlqMode.MASKED,
            )

            val hashedId = DigestUtils.sha256Hex("saga-xyz")
            val evicted = manager.delete("orders", hashedId)

            assertEquals(2, evicted)
        }

        @Test
        fun `MASKED lettersForSequence returns the paginated slice when looked up by the hashed id`() {
            val letters = (1..5).map { fakeLetter("m$it", payload = "payload-$it") }
            val dlq = fakeDlq(sequences = listOf(letters))
            val ehc = ehcWithPolicy { _ -> "saga-abc" }
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
                    ehc = ehc,
                    dlqMode = AxoniqConsoleDlqMode.MASKED,
            )
            val hashedId = DigestUtils.sha256Hex("saga-abc")

            val response = manager.lettersForSequence("orders", hashedId, offset = 0, size = 3)

            assertEquals(5L, response.totalCount)
            assertEquals(3, response.letters.size)
            response.letters.forEach { assertEquals(hashedId, it.sequenceIdentifier) }
        }

        @Test
        fun `FULL preserves payload, cause message and raw sequence id`() {
            val sequence = listOf(fakeLetter("m1", payload = "fully-visible"))
            val ehc = ehcWithPolicy { _ -> "saga-99" }
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequences = listOf(sequence)),
                    ehc = ehc,
                    dlqMode = AxoniqConsoleDlqMode.FULL,
            )

            val letter = manager.deadLetters("orders").sequences[0][0]

            assertEquals("fully-visible", letter.message)
            assertEquals("boom", letter.causeMessage)
            assertEquals("saga-99", letter.sequenceIdentifier)
        }
    }

    @Nested
    inner class AutomaticRetry {

        @Test
        fun `returns only sequences that are due for retry`() {
            val due = fakeLetter("due")
            val exhausted = fakeLetter("exhausted", diagnostics = mapOf(PLATFORM_RETRIES_DIAGNOSTIC to "10"))
            val backingOff = fakeLetter("backing-off", lastTouched = Instant.now())
            val dlq = fakeDlq(sequences = listOf(listOf(due), listOf(exhausted), listOf(backingOff)))
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
                    ehc = ehcWithPolicy { event -> event.identifier() },
            )

            val response = manager.deadLettersForAutomaticRetry(retryRequest(maxRetries = 10))

            assertEquals(listOf("due"), response.candidates.map { it.messageIdentifier })
            assertEquals(listOf("due"), response.candidates.map { it.sequenceIdentifier })
            assertEquals(listOf(0), response.candidates.map { it.retries })
        }

        @Test
        fun `applies exponential backoff based on recorded retries`() {
            val twentyFiveMinutesAgo = Instant.now().minus(Duration.ofMinutes(25))
            val dueAfterTwoRetries = fakeLetter(
                    "two-retries",
                    diagnostics = mapOf(PLATFORM_RETRIES_DIAGNOSTIC to "2"),
                    lastTouched = twentyFiveMinutesAgo,
            )
            val waitingAfterThreeRetries = fakeLetter(
                    "three-retries",
                    diagnostics = mapOf(PLATFORM_RETRIES_DIAGNOSTIC to "3"),
                    lastTouched = twentyFiveMinutesAgo,
            )
            val dlq = fakeDlq(sequences = listOf(listOf(dueAfterTwoRetries), listOf(waitingAfterThreeRetries)))
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
                    ehc = ehcWithPolicy { event -> event.identifier() },
            )

            val response = manager.deadLettersForAutomaticRetry(
                    retryRequest(backoff = AutomaticRetryBackoff.EXPONENTIAL, backoffMinutes = 5),
            )

            assertEquals(listOf("two-retries"), response.candidates.map { it.messageIdentifier })
        }

        @Test
        fun `caps the number of candidates to the requested limit`() {
            val sequences = (1..3).map { listOf(fakeLetter("m$it")) }
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequences = sequences),
                    ehc = ehcWithPolicy { event -> event.identifier() },
            )

            val response = manager.deadLettersForAutomaticRetry(retryRequest(limit = 2))

            assertEquals(2, response.candidates.size)
        }

        @Test
        fun `fails closed in NONE mode`() {
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to
                            fakeDlq(sequences = listOf(listOf(fakeLetter("due")))),
                    ehc = ehcWithPolicy { event -> event.identifier() },
                    dlqMode = AxoniqConsoleDlqMode.NONE,
            )

            val response = manager.deadLettersForAutomaticRetry(retryRequest())

            assertTrue(response.candidates.isEmpty())
        }

        @Test
        fun `hashes sequence identifiers in MASKED mode`() {
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to
                            fakeDlq(sequences = listOf(listOf(fakeLetter("due")))),
                    ehc = ehcWithPolicy { event -> event.identifier() },
                    dlqMode = AxoniqConsoleDlqMode.MASKED,
            )

            val response = manager.deadLettersForAutomaticRetry(retryRequest())

            assertEquals(listOf(DigestUtils.sha256Hex("due")), response.candidates.map { it.sequenceIdentifier })
            assertEquals(listOf("due"), response.candidates.map { it.messageIdentifier })
        }

        @Test
        fun `successful automated retry leaves the retry counter untouched`() {
            val head = fakeLetter("m1")
            val dlq = fakeDlq(sequences = listOf(listOf(head)))
            val ehc = ehcWithPolicy { event -> event.identifier() }
            stubProcessResult(ehc, true)
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
                    ehc = ehc,
            )

            assertTrue(manager.automatedRetry("orders", "m1"))
            verify(exactly = 0) {
                dlq.requeue(any<DeadLetter<EventMessage>>(), any(), any())
            }
        }

        @Test
        fun `failed automated retry increments the platform retry counter on the head letter`() {
            val head = fakeLetter("m1", diagnostics = mapOf(PLATFORM_RETRIES_DIAGNOSTIC to "2"))
            val metadataOperator = slot<UnaryOperator<Metadata>>()
            every { head.withDiagnostics(capture(metadataOperator)) } returns head
            val dlq = fakeDlq(sequences = listOf(listOf(head)))
            val letterUpdater = slot<UnaryOperator<DeadLetter<out EventMessage>>>()
            every { dlq.requeue(head, capture(letterUpdater), null) } returns
                    CompletableFuture.completedFuture(null)
            val ehc = ehcWithPolicy { event -> event.identifier() }
            stubProcessResult(ehc, false)
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
                    ehc = ehc,
            )

            assertFalse(manager.automatedRetry("orders", "m1"))

            verify(exactly = 1) { dlq.requeue(head, any(), null) }
            letterUpdater.captured.apply(head)
            val updated = metadataOperator.captured.apply(head.diagnostics())
            assertEquals("3", updated[PLATFORM_RETRIES_DIAGNOSTIC])
        }

        @Test
        fun `timed-out automated retry counts as a failed attempt`() {
            val head = fakeLetter("m1", diagnostics = mapOf(PLATFORM_RETRIES_DIAGNOSTIC to "2"))
            val metadataOperator = slot<UnaryOperator<Metadata>>()
            every { head.withDiagnostics(capture(metadataOperator)) } returns head
            val dlq = fakeDlq(sequences = listOf(listOf(head)))
            val letterUpdater = slot<UnaryOperator<DeadLetter<out EventMessage>>>()
            every { dlq.requeue(head, capture(letterUpdater), null) } returns
                    CompletableFuture.completedFuture(null)
            val ehc = ehcWithPolicy { event -> event.identifier() }
            stubProcessTimeout(ehc)
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
                    ehc = ehc,
            )

            assertFalse(manager.automatedRetry("orders", "m1"))

            verify(exactly = 1) { dlq.requeue(head, any(), null) }
            letterUpdater.captured.apply(head)
            val updated = metadataOperator.captured.apply(head.diagnostics())
            assertEquals("3", updated[PLATFORM_RETRIES_DIAGNOSTIC])
        }

        @Test
        fun `failed automated retry skips the counter when the letter is no longer a sequence head`() {
            val dlq = fakeDlq(sequences = emptyList())
            val ehc = ehcWithPolicy { event -> event.identifier() }
            stubProcessResult(ehc, false)
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
                    ehc = ehc,
            )

            assertFalse(manager.automatedRetry("orders", "gone"))
            verify(exactly = 0) {
                dlq.requeue(any<DeadLetter<EventMessage>>(), any(), any())
            }
        }

        private fun retryRequest(
                processingGroup: String = "orders",
                backoff: AutomaticRetryBackoff = AutomaticRetryBackoff.CONSISTENT,
                backoffMinutes: Int = 5,
                maxRetries: Int = 10,
                limit: Int = 50,
        ) = DeadLettersForAutomaticRetryRequest(processingGroup, backoff, backoffMinutes, maxRetries, limit)

        @Suppress("UNCHECKED_CAST")
        private fun stubProcessResult(ehc: EventHandlingComponent, result: Boolean) {
            every {
                (ehc as SequencedDeadLetterProcessor<EventMessage>).process(any())
            } returns CompletableFuture.completedFuture(result)
        }

        @Suppress("UNCHECKED_CAST")
        private fun stubProcessTimeout(ehc: EventHandlingComponent) {
            val slowProcessing = mockk<CompletableFuture<Boolean>> {
                every { get(any<Long>(), any()) } throws TimeoutException()
            }
            every {
                (ehc as SequencedDeadLetterProcessor<EventMessage>).process(any())
            } returns slowProcessing
        }
    }

    @Nested
    inner class Errors {

        @Test
        fun `unknown processing group throws IllegalArgumentException`() {
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(),
            )

            assertThrows(IllegalArgumentException::class.java) {
                manager.sequenceSize("unknown-group", "whatever")
            }
        }

        @Test
        fun `NONE mode never reaches toApiLetter — bypassing the short-circuit throws IllegalStateException`() {
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(),
                    dlqMode = AxoniqConsoleDlqMode.NONE,
            )
            val toApiLetter = DeadLetterManager::class.java.declaredMethods
                    .single { it.name == "toApiLetter" }
                    .apply { isAccessible = true }
            val letter = fakeLetter("m1")

            val thrown = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                toApiLetter.invoke(manager, letter, "any-id", null)
            }
            assertTrue(thrown.cause is IllegalStateException)
        }
    }

    @Nested
    inner class MultiTenant {

        private val tenantKey = Context.ResourceKey.withLabel<String>("tenant")
        private val tenancy = object : PlatformTenancy {
            override fun tenants() = listOf("tenant-a", "tenant-b")
            override fun bind(context: ProcessingContext, tenantId: String) {
                context.putResource(tenantKey, tenantId)
            }
            override fun positionsIn(token: TrackingToken): Map<String, Long>? = null
        }
        private val evicted = mutableListOf<Pair<String, String>>()
        private val cleared = mutableListOf<String>()
        private val processedIn = mutableListOf<String>()

        private fun tenantOf(context: ProcessingContext?): String =
                context?.getResource(tenantKey) ?: error("A tenant-routed queue needs a tenant on the context")

        /** Behaves like the framework's tenant-routing queue: every call is answered by the context's tenant. */
        @Suppress("UNCHECKED_CAST")
        private fun tenantRoutedDlq(
                perTenant: Map<String, List<List<DeadLetter<EventMessage>>>>,
        ): SequencedDeadLetterQueue<EventMessage> {
            val dlq = mockk<SequencedDeadLetterQueue<EventMessage>>(relaxed = true)
            every { dlq.deadLetters(any()) } answers {
                CompletableFuture.completedFuture(
                        perTenant[tenantOf(firstArg())].orEmpty() as Iterable<Iterable<DeadLetter<out EventMessage>>>)
            }
            every { dlq.amountOfSequences(any()) } answers {
                CompletableFuture.completedFuture(perTenant[tenantOf(firstArg())].orEmpty().size.toLong())
            }
            every { dlq.size(any()) } answers {
                CompletableFuture.completedFuture(perTenant[tenantOf(firstArg())].orEmpty().sumOf { it.size }.toLong())
            }
            every { dlq.evict(any<DeadLetter<EventMessage>>(), any()) } answers {
                evicted += tenantOf(secondArg()) to firstArg<DeadLetter<EventMessage>>().message().identifier()
                CompletableFuture.completedFuture(null)
            }
            every { dlq.clear(any()) } answers {
                cleared += tenantOf(firstArg())
                CompletableFuture.completedFuture(null)
            }
            return dlq
        }

        /** Sequences by payload, so the same account id can be dead-lettered in two tenants. */
        @Suppress("UNCHECKED_CAST")
        private fun accountPolicy(): EventHandlingComponent {
            val ehc = ehcWithPolicy { it.payload() }
            every {
                (ehc as SequencedDeadLetterProcessor<EventMessage>).process(any(), any<ProcessingContext>())
            } answers {
                processedIn += tenantOf(secondArg())
                CompletableFuture.completedFuture(true)
            }
            return ehc
        }

        private val queue = "DeadLetterQueue[EventHandlingComponent[notifier][Notifier]]"

        @Test
        fun `lists the sequences of every tenant and tags each letter with its tenant`() {
            val manager = managerWith(
                    queue to tenantRoutedDlq(mapOf(
                            "tenant-a" to listOf(listOf(fakeLetter("a1", payload = "acc-1"))),
                            "tenant-b" to listOf(listOf(fakeLetter("b1", payload = "acc-2"))),
                    )),
                    ehc = accountPolicy(),
                    tenancy = tenancy,
            )

            val response = manager.deadLetters("notifier")

            assertEquals(2, response.totalCount)
            assertEquals(
                    listOf("tenant-a" to "acc-1", "tenant-b" to "acc-2"),
                    response.sequences.map { it.single().tenant to it.single().sequenceIdentifier },
            )
        }

        @Test
        fun `lists only the tenant asked for`() {
            val manager = managerWith(
                    queue to tenantRoutedDlq(mapOf(
                            "tenant-a" to listOf(listOf(fakeLetter("a1", payload = "acc-1"))),
                            "tenant-b" to listOf(listOf(fakeLetter("b1", payload = "acc-2"))),
                    )),
                    ehc = accountPolicy(),
                    tenancy = tenancy,
            )

            val response = manager.deadLetters("notifier", tenant = "tenant-b")

            assertEquals(1, response.totalCount)
            assertEquals("b1", response.sequences.single().single().messageIdentifier)
        }

        @Test
        fun `reports the queue size per tenant and in total`() {
            val manager = managerWith(
                    queue to tenantRoutedDlq(mapOf(
                            "tenant-a" to listOf(listOf(fakeLetter("a1", payload = "acc-1"))),
                            "tenant-b" to listOf(
                                    listOf(fakeLetter("b1", payload = "acc-2")),
                                    listOf(fakeLetter("b2", payload = "acc-3")),
                            ),
                    )),
                    ehc = accountPolicy(),
                    tenancy = tenancy,
            )

            val info = manager.infoFor("notifier").single()

            assertEquals(3, info.dlqSize)
            assertEquals(mapOf("tenant-a" to 1L, "tenant-b" to 2L), info.dlqSizePerTenant)
        }

        @Test
        fun `deletes a sequence in the tenant that holds it`() {
            val manager = managerWith(
                    queue to tenantRoutedDlq(mapOf(
                            "tenant-a" to listOf(listOf(fakeLetter("a1", payload = "acc-1"))),
                            "tenant-b" to listOf(listOf(fakeLetter("b1", payload = "acc-2"))),
                    )),
                    ehc = accountPolicy(),
                    tenancy = tenancy,
            )

            assertEquals(1, manager.delete("notifier", "acc-2"))
            assertEquals(listOf("tenant-b" to "b1"), evicted)
        }

        @Test
        fun `refuses to guess when a sequence id exists in two tenants, and acts on the tenant named`() {
            val manager = managerWith(
                    queue to tenantRoutedDlq(mapOf(
                            "tenant-a" to listOf(listOf(fakeLetter("a1", payload = "acc-1"))),
                            "tenant-b" to listOf(listOf(fakeLetter("b1", payload = "acc-1"))),
                    )),
                    ehc = accountPolicy(),
                    tenancy = tenancy,
            )

            assertThrows(IllegalArgumentException::class.java) { manager.delete("notifier", "acc-1") }
            assertTrue(evicted.isEmpty())

            manager.delete("notifier", "acc-1", tenant = "tenant-a")
            assertEquals(listOf("tenant-a" to "a1"), evicted)
        }

        @Test
        fun `processes a letter in the tenant that holds it`() {
            val manager = managerWith(
                    queue to tenantRoutedDlq(mapOf(
                            "tenant-a" to listOf(listOf(fakeLetter("a1", payload = "acc-1"))),
                            "tenant-b" to listOf(listOf(fakeLetter("b1", payload = "acc-2"))),
                    )),
                    ehc = accountPolicy(),
                    tenancy = tenancy,
            )

            assertTrue(manager.process("notifier", "b1"))
            assertEquals(listOf("tenant-b"), processedIn)
        }

        @Test
        fun `does not process a letter that no tenant holds`() {
            val manager = managerWith(
                    queue to tenantRoutedDlq(mapOf("tenant-a" to listOf(listOf(fakeLetter("a1", payload = "acc-1"))))),
                    ehc = accountPolicy(),
                    tenancy = tenancy,
            )

            assertFalse(manager.process("notifier", "unknown"))
            assertTrue(processedIn.isEmpty())
        }

        @Test
        fun `clears every tenant`() {
            val manager = managerWith(
                    queue to tenantRoutedDlq(mapOf(
                            "tenant-a" to listOf(listOf(fakeLetter("a1", payload = "acc-1"))),
                            "tenant-b" to listOf(listOf(fakeLetter("b1", payload = "acc-2"), fakeLetter("b2", payload = "acc-2"))),
                    )),
                    ehc = accountPolicy(),
                    tenancy = tenancy,
            )

            assertEquals(3, manager.deleteAll("notifier"))
            assertEquals(listOf("tenant-a", "tenant-b"), cleared)
        }

        @Test
        fun `runs tenant-scoped calls in a unit of work from the configured factory`() {
            var open = 0
            var committed = 0
            val transactionManager = TransactionManager {
                open++
                object : Transaction {
                    override fun commit() {
                        open--
                        committed++
                    }

                    override fun rollback() {
                        open--
                    }
                }
            }
            val openDuringCall = mutableListOf<Int>()
            val dlq = tenantRoutedDlq(mapOf(
                    "tenant-a" to listOf(listOf(fakeLetter("a1", payload = "acc-1"))),
                    "tenant-b" to listOf(listOf(fakeLetter("b1", payload = "acc-2"))),
            ))
            every { dlq.clear(any()) } answers {
                openDuringCall += open
                cleared += tenantOf(firstArg())
                CompletableFuture.completedFuture(null)
            }
            val manager = managerWith(
                    queue to dlq,
                    ehc = accountPolicy(),
                    tenancy = tenancy,
                    unitOfWorkFactory = TransactionalUnitOfWorkFactory(
                            transactionManager,
                            SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE),
                    ),
            )

            manager.deleteAll("notifier")

            assertEquals(listOf("tenant-a", "tenant-b"), cleared)
            assertEquals(listOf(1, 1), openDuringCall)
            assertEquals(0, open)
            assertEquals(4, committed)
        }

        @Nested
        inner class SequenceIdUnitsOfWork {

            private val contextsPerTenant = mutableMapOf<String, MutableSet<ProcessingContext>>()
            private val ehc = accountPolicy().also { ehc ->
                every { ehc.sequenceIdentifierFor(any(), any()) } answers {
                    val context = secondArg<ProcessingContext>()
                    contextsPerTenant.getOrPut(tenantOf(context)) { mutableSetOf() } += context
                    firstArg<EventMessage>().payload() as Any
                }
            }
            private val manager = managerWith(
                    queue to tenantRoutedDlq(mapOf(
                            "tenant-a" to listOf(
                                    listOf(fakeLetter("a1", payload = "acc-1")),
                                    listOf(fakeLetter("a2", payload = "acc-2")),
                                    listOf(fakeLetter("a3", payload = "acc-3")),
                            ),
                            "tenant-b" to listOf(
                                    listOf(fakeLetter("b1", payload = "acc-4")),
                                    listOf(fakeLetter("b2", payload = "acc-5")),
                            ),
                    )),
                    ehc = ehc,
                    tenancy = tenancy,
            )

            private fun unitsOfWorkPerTenant() = contextsPerTenant.mapValues { it.value.size }

            @Test
            fun `resolves the ids of a page in one unit of work per tenant`() {
                val response = manager.deadLetters("notifier")

                assertEquals(
                        listOf("acc-1", "acc-2", "acc-3", "acc-4", "acc-5"),
                        response.sequences.map { it.single().sequenceIdentifier },
                )
                assertEquals(mapOf("tenant-a" to 1, "tenant-b" to 1), unitsOfWorkPerTenant())
            }

            @Test
            fun `finds a sequence among many in one unit of work`() {
                assertEquals(1, manager.delete("notifier", "acc-3", tenant = "tenant-a"))

                assertEquals(listOf("tenant-a" to "a3"), evicted)
                assertEquals(mapOf("tenant-a" to 1), unitsOfWorkPerTenant())
            }

            @Test
            fun `gives retry candidates the ids of their own tenant`() {
                val candidates = manager.deadLettersForAutomaticRetry(DeadLettersForAutomaticRetryRequest(
                        processingGroup = "notifier",
                        backoff = AutomaticRetryBackoff.CONSISTENT,
                        backoffMinutes = 0,
                        maxRetries = 3,
                        limit = 10,
                )).candidates

                assertEquals(
                        listOf(
                                "tenant-a" to "acc-1", "tenant-a" to "acc-2", "tenant-a" to "acc-3",
                                "tenant-b" to "acc-4", "tenant-b" to "acc-5",
                        ),
                        candidates.map { it.tenant to it.sequenceIdentifier },
                )
                assertEquals(mapOf("tenant-a" to 1, "tenant-b" to 1), unitsOfWorkPerTenant())
            }

            @Test
            fun `falls back to the message id for one letter without affecting the others`() {
                every { ehc.sequenceIdentifierFor(any(), any()) } answers {
                    val payload = firstArg<EventMessage>().payload()
                    if (payload == "acc-2") throw IllegalStateException("policy failed") else payload as Any
                }

                val response = manager.deadLetters("notifier", tenant = "tenant-a")

                assertEquals(listOf("acc-1", "a2", "acc-3"), response.sequences.map { it.single().sequenceIdentifier })
            }
        }

        @Nested
        inner class TenantSizeCache {

            private val clock = MutableClock()
            private val tenantA = mutableListOf(listOf(fakeLetter("a1", payload = "acc-1")))
            private val dlq = tenantRoutedDlq(mapOf(
                    "tenant-a" to tenantA,
                    "tenant-b" to listOf(listOf(fakeLetter("b1", payload = "acc-2"))),
            ))
            private val manager = managerWith(queue to dlq, ehc = accountPolicy(), tenancy = tenancy, clock = clock)

            @Test
            fun `reuses the sizes within the TTL`() {
                manager.infoFor("notifier")
                tenantA += listOf(fakeLetter("a2", payload = "acc-3"))
                clock.now = clock.now.plusSeconds(29)

                val info = manager.infoFor("notifier").single()

                assertEquals(mapOf("tenant-a" to 1L, "tenant-b" to 1L), info.dlqSizePerTenant)
                verify(exactly = 2) { dlq.amountOfSequences(any()) }
            }

            @Test
            fun `counts again once the TTL has passed`() {
                manager.infoFor("notifier")
                tenantA += listOf(fakeLetter("a2", payload = "acc-3"))
                clock.now = clock.now.plusSeconds(30)

                val info = manager.infoFor("notifier").single()

                assertEquals(mapOf("tenant-a" to 2L, "tenant-b" to 1L), info.dlqSizePerTenant)
                assertEquals(3, info.dlqSize)
                verify(exactly = 4) { dlq.amountOfSequences(any()) }
            }

            @Test
            fun `counts again right after a mutation`() {
                manager.infoFor("notifier")
                every { dlq.evict(any<DeadLetter<EventMessage>>(), any()) } answers {
                    evicted += tenantOf(secondArg()) to firstArg<DeadLetter<EventMessage>>().message().identifier()
                    tenantA.clear()
                    CompletableFuture.completedFuture(null)
                }

                manager.delete("notifier", "acc-1", tenant = "tenant-a")
                val info = manager.infoFor("notifier").single()

                assertEquals(mapOf("tenant-a" to 0L, "tenant-b" to 1L), info.dlqSizePerTenant)
            }

            @Test
            fun `counts again after a mutation that fails halfway`() {
                manager.infoFor("notifier")
                every { dlq.clear(any()) } answers {
                    tenantA.clear()
                    CompletableFuture.failedFuture<Void>(IllegalStateException("queue store went away"))
                }

                assertThrows(Exception::class.java) { manager.deleteAll("notifier") }
                val info = manager.infoFor("notifier").single()

                assertEquals(0L, info.dlqSizePerTenant?.get("tenant-a"))
            }
        }

        @Nested
        inner class AllTenantsPaging {

            private val tenantA = tenantDlq(prefix = "a", sequences = 2)
            private val tenantB = tenantDlq(prefix = "b", sequences = 3)
            private val tenantC = tenantDlq(prefix = "c", sequences = 1)
            private val perTenant = mutableMapOf("tenant-a" to tenantA, "tenant-b" to tenantB, "tenant-c" to tenantC)
            private val tenants = mutableListOf("tenant-c", "tenant-a", "tenant-b")
            private val unorderedTenancy = object : PlatformTenancy {
                override fun tenants() = tenants.toList()
                override fun bind(context: ProcessingContext, tenantId: String) = tenancy.bind(context, tenantId)
                override fun positionsIn(token: TrackingToken): Map<String, Long>? = null
            }
            private val clock = MutableClock()
            private val manager = managerWith(
                    queue to routingDlq(), ehc = accountPolicy(), tenancy = unorderedTenancy, clock = clock)

            @Suppress("UNCHECKED_CAST")
            private fun tenantDlq(prefix: String, sequences: Int): SequencedDeadLetterQueue<EventMessage> {
                val dlq = mockk<SequencedDeadLetterQueue<EventMessage>>(relaxed = true)
                val letters = (1..sequences).map { listOf(fakeLetter("$prefix$it", payload = "acc-$prefix$it")) }
                every { dlq.deadLetters(any()) } returns CompletableFuture.completedFuture(
                        letters as Iterable<Iterable<DeadLetter<out EventMessage>>>)
                every { dlq.amountOfSequences(any()) } returns CompletableFuture.completedFuture(letters.size.toLong())
                return dlq
            }

            /** Hands every call to the queue of the context's tenant, as the framework's tenant-routing queue does. */
            private fun routingDlq(): SequencedDeadLetterQueue<EventMessage> {
                val dlq = mockk<SequencedDeadLetterQueue<EventMessage>>(relaxed = true)
                every { dlq.deadLetters(any()) } answers {
                    perTenant.getValue(tenantOf(firstArg())).deadLetters(firstArg())
                }
                every { dlq.amountOfSequences(any()) } answers {
                    perTenant.getValue(tenantOf(firstArg())).amountOfSequences(firstArg())
                }
                return dlq
            }

            private fun DeadLetterResponse.messageIds() = sequences.map { it.single().messageIdentifier }

            @Test
            fun `serves a page inside one tenant from that tenant only`() {
                val response = manager.deadLetters("notifier", offset = 3, size = 2)

                assertEquals(listOf("b2", "b3"), response.messageIds())
                assertEquals(6, response.totalCount)
                verify(exactly = 0) { tenantA.deadLetters(any()) }
                verify(exactly = 1) { tenantB.deadLetters(any()) }
                verify(exactly = 0) { tenantC.deadLetters(any()) }
            }

            @Test
            fun `serves a page spanning two tenants in tenant id order`() {
                val response = manager.deadLetters("notifier", offset = 1, size = 3)

                assertEquals(listOf("a2", "b1", "b2"), response.messageIds())
                assertEquals(listOf("tenant-a", "tenant-b", "tenant-b"), response.sequences.map { it.single().tenant })
                assertEquals(6, response.totalCount)
                verify(exactly = 0) { tenantC.deadLetters(any()) }
            }

            @Test
            fun `returns an empty page beyond the end without loading any tenant`() {
                val response = manager.deadLetters("notifier", offset = 6, size = 5)

                assertTrue(response.sequences.isEmpty())
                assertEquals(6, response.totalCount)
                perTenant.values.forEach { verify(exactly = 0) { it.deadLetters(any()) } }
            }

            @Test
            fun `cuts the page by the counts it reports when a tenant grew after counting`() {
                every { tenantA.amountOfSequences(any()) } returns CompletableFuture.completedFuture(1L)

                val response = manager.deadLetters("notifier", offset = 0, size = 3)

                assertEquals(listOf("a1", "b1", "b2"), response.messageIds())
                assertEquals(5, response.totalCount)
            }

            @Nested
            inner class CachedCounts {

                @Test
                fun `pages from the counts the processor report cached`() {
                    manager.infoFor("notifier")

                    val response = manager.deadLetters("notifier", offset = 2, size = 2)

                    assertEquals(listOf("b1", "b2"), response.messageIds())
                    assertEquals(6, response.totalCount)
                    perTenant.values.forEach { verify(exactly = 1) { it.amountOfSequences(any()) } }
                }

                @Test
                fun `counts again when a tenant on the page no longer matches its cached count`() {
                    manager.deadLetters("notifier", offset = 0, size = 2)
                    every { tenantB.deadLetters(any()) } returns CompletableFuture.completedFuture(listOf(
                            listOf(fakeLetter("b1", payload = "acc-b1")),
                            listOf(fakeLetter("b3", payload = "acc-b3")),
                    ))
                    every { tenantB.amountOfSequences(any()) } returns CompletableFuture.completedFuture(2L)

                    val response = manager.deadLetters("notifier", offset = 2, size = 3)

                    assertEquals(listOf("b1", "b3", "c1"), response.messageIds())
                    assertEquals(5, response.totalCount)
                    perTenant.values.forEach { verify(exactly = 2) { it.amountOfSequences(any()) } }
                }

                @Test
                fun `counts again when a tenant was added`() {
                    manager.deadLetters("notifier", offset = 0, size = 2)
                    perTenant["tenant-d"] = tenantDlq(prefix = "d", sequences = 1)
                    tenants += "tenant-d"

                    val response = manager.deadLetters("notifier", offset = 6, size = 5)

                    assertEquals(listOf("d1"), response.messageIds())
                    assertEquals(7, response.totalCount)
                }

                @Test
                fun `shows a change outside the page once the TTL has passed`() {
                    manager.deadLetters("notifier", offset = 0, size = 2)
                    every { tenantC.amountOfSequences(any()) } returns CompletableFuture.completedFuture(2L)

                    assertEquals(6, manager.deadLetters("notifier", offset = 0, size = 2).totalCount)

                    clock.now = clock.now.plusSeconds(30)
                    assertEquals(7, manager.deadLetters("notifier", offset = 0, size = 2).totalCount)
                }
            }
        }
    }

    @Nested
    inner class SingleTenantSize {

        @Test
        fun `counts the queue on every report`() {
            val dlq = fakeDlq(sequences = listOf(listOf(fakeLetter("m1"))))
            val manager = managerWith("DeadLetterQueue[EventHandlingComponent[notifier][Notifier]]" to dlq)

            manager.infoFor("notifier")
            manager.infoFor("notifier")

            verify(exactly = 2) { dlq.amountOfSequences(null) }
        }
    }

    // ---------------------------------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------------------------------

    /**
     * Builds a manager backed by a synthetic configuration that exposes the given DLQs and matching
     * processors / event-handling components. Pass [ehc] = `null` (the default) to use a delegate
     * whose `sequenceIdentifierFor` returns null — exercises the manager's policy-null → message-id
     * fallback path.
     */
    private fun managerWith(
            vararg dlqsByName: Pair<String, SequencedDeadLetterQueue<EventMessage>>,
            ehc: EventHandlingComponent? = null,
            dlqMode: AxoniqConsoleDlqMode = AxoniqConsoleDlqMode.FULL,
            whitelist: List<String> = emptyList(),
            tenancy: PlatformTenancy? = null,
            unitOfWorkFactory: UnitOfWorkFactory = SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE),
            clock: Clock = Clock.systemUTC(),
    ): DeadLetterManager {
        val configuration = configurationWith(dlqsByName.asList(), ehc, unitOfWorkFactory)
        return DeadLetterManager(
                configuration = configuration,
                dlqMode = dlqMode,
                dlqDiagnosticsWhitelist = whitelist,
                clock = clock,
                tenantSizeTtl = Duration.ofSeconds(30),
        ) { tenancy }.also { it.start() }
    }

    private class MutableClock(var now: Instant = Instant.EPOCH) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    private fun configurationWith(
            dlqsByName: List<Pair<String, SequencedDeadLetterQueue<EventMessage>>>,
            ehc: EventHandlingComponent?,
            unitOfWorkFactory: UnitOfWorkFactory,
    ): Configuration {
        val module = mockk<Configuration>(relaxed = true)
        every { module.getComponents(SequencedDeadLetterQueue::class.java) } returns
                dlqsByName.toMap().mapValues { it.value as SequencedDeadLetterQueue<*> }
        dlqsByName.forEach { (name, _) ->
            val match = Regex("""^DeadLetterQueue\[EventHandlingComponent\[([^]]+)]\[(.+)]]$""").find(name)
            if (match != null) {
                val ehcName = "EventHandlingComponent[${match.groupValues[1]}][${match.groupValues[2]}]"

                val delegate = ehc ?: defaultDelegateMock()
                val wrapper = AxoniqPlatformEventHandlingComponent(
                        delegate,
                        match.groupValues[1],
                        mockk(relaxed = true),
                        mockk(relaxed = true),
                )
                every {
                    module.getOptionalComponent(EventHandlingComponent::class.java, ehcName)
                } returns Optional.of(wrapper)
            }
        }
        val root = mockk<Configuration>(relaxed = true)
        every { root.moduleConfigurations } returns listOf(module)
        every { root.getComponent(UnitOfWorkFactory::class.java) } returns unitOfWorkFactory
        return root
    }

    private fun defaultDelegateMock(): EventHandlingComponent {
        val mock = mockk<EventHandlingComponent>(
                moreInterfaces = arrayOf(SequencedDeadLetterProcessor::class),
                relaxed = true,
        )
        @Suppress("UNCHECKED_CAST")
        every { mock.sequenceIdentifierFor(any(), any()) } answers { null as Any }
        return mock
    }

    private fun ehcWithPolicy(policy: (EventMessage) -> Any?): EventHandlingComponent {
        val ehc = mockk<EventHandlingComponent>(
                moreInterfaces = arrayOf(SequencedDeadLetterProcessor::class),
                relaxed = true,
        )

        @Suppress("UNCHECKED_CAST")
        every { ehc.sequenceIdentifierFor(any(), any()) } answers {
            policy(firstArg<EventMessage>()) as Any
        }
        return ehc
    }

    private fun fakeDlq(
            sequences: List<List<DeadLetter<EventMessage>>> = emptyList(),
            sequenceCount: Long = sequences.size.toLong(),
            totalSize: Long = sequences.sumOf { it.size.toLong() },
    ): SequencedDeadLetterQueue<EventMessage> {
        val dlq = mockk<SequencedDeadLetterQueue<EventMessage>>(relaxed = true)
        every { dlq.deadLetters(null) } returns CompletableFuture.completedFuture(
                sequences as Iterable<Iterable<DeadLetter<out EventMessage>>>,
        )
        every { dlq.amountOfSequences(null) } returns CompletableFuture.completedFuture(sequenceCount)
        every { dlq.size(null) } returns CompletableFuture.completedFuture(totalSize)
        every { dlq.clear(null) } returns CompletableFuture.completedFuture(null)
        every { dlq.evict(any<DeadLetter<EventMessage>>(), null) } returns CompletableFuture.completedFuture(null)
        every {
            dlq.requeue(any<DeadLetter<EventMessage>>(), any(), null)
        } returns CompletableFuture.completedFuture(null)
        return dlq
    }

    private fun fakeLetter(
            messageId: String,
            payload: Any? = "payload-$messageId",
            payloadType: Class<*> = (payload?.javaClass ?: String::class.java),
            causeType: String? = "java.lang.RuntimeException",
            causeMessage: String? = "boom",
            diagnostics: Map<String, String> = emptyMap(),
            lastTouched: Instant = Instant.EPOCH,
    ): DeadLetter<EventMessage> {
        val message = fakeEventMessage(messageId, payload, payloadType)
        return fakeLetterFromMessage(message, causeType, causeMessage, diagnostics, lastTouched)
    }

    private fun fakeLetterFromMessage(
            message: EventMessage,
            causeType: String? = "java.lang.RuntimeException",
            causeMessage: String? = "boom",
            diagnostics: Map<String, String> = emptyMap(),
            lastTouched: Instant = Instant.EPOCH,
    ): DeadLetter<EventMessage> {
        val letter = mockk<DeadLetter<EventMessage>>(relaxed = true)
        every { letter.message() } returns message
        val cause: Optional<Cause> = if (causeType == null) Optional.empty() else {
            val c = mockk<Cause>()
            every { c.type() } returns causeType
            every { c.message() } returns (causeMessage ?: "")
            Optional.of(c)
        }
        every { letter.cause() } returns cause
        every { letter.enqueuedAt() } returns Instant.EPOCH
        every { letter.lastTouched() } returns lastTouched
        every { letter.diagnostics() } returns
                if (diagnostics.isEmpty()) Metadata.emptyInstance() else Metadata.from(diagnostics)
        return letter
    }

    private fun fakeEventMessage(
            id: String,
            payload: Any?,
            payloadType: Class<*>,
            typeName: String = "com.example.${payloadType.simpleName ?: "Anonymous"}",
    ): EventMessage {
        val message = mockk<EventMessage>(relaxed = true)
        every { message.identifier() } returns id
        every { message.payload() } returns payload
        every { message.payloadType() } returns payloadType
        val type = mockk<MessageType>()
        every { type.name() } returns typeName
        every { message.type() } returns type
        return message
    }
}
