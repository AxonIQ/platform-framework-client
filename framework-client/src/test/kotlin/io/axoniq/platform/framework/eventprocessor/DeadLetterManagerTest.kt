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

package io.axoniq.platform.framework.eventprocessor

import io.axoniq.framework.messaging.deadletter.Cause
import io.axoniq.framework.messaging.deadletter.DeadLetter
import io.axoniq.framework.messaging.deadletter.SequencedDeadLetterProcessor
import io.axoniq.framework.messaging.deadletter.SequencedDeadLetterQueue
import io.axoniq.platform.framework.api.AxoniqConsoleDlqMode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.commons.codec.digest.DigestUtils
import org.axonframework.common.configuration.Configuration
import org.axonframework.messaging.core.Metadata
import org.axonframework.messaging.core.MessageType
import org.axonframework.messaging.eventhandling.EventHandlingComponent
import org.axonframework.messaging.eventhandling.EventMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture

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
            // The sequence contains m1 + m2 + m3; only the first letter's id is used.
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
            // Note: no ehc parameter — the configurationWith helper registers a relaxed mock that returns
            // Optional.empty() for EventHandlingComponent lookup, exercising the null-EHC fallback path.
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
            // "č" is U+010D, two bytes in UTF-8. Filling beyond 1024 bytes guarantees the cutoff
            // lands inside a multi-byte sequence — a naive byte slice would yield a malformed
            // codepoint there. The implementation must round down to the previous valid boundary.
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
        fun `messageType falls back to message type name when payload class is ByteArray`() {
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
        fun `messageType uses payload class simple name for non-ByteArray payloads`() {
            val sequence = listOf(fakeLetter("m1", payload = "hello", payloadType = String::class.java))
            val manager = managerWith(
                    "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequences = listOf(sequence)),
            )

            val apiLetter = manager.deadLetters("orders").sequences[0][0]

            assertEquals("String", apiLetter.messageType)
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
            // Regression: the detail modal sends the hashed sequence id back; the manager must
            // re-hash candidate ids while walking the DLQ so the lookup succeeds and the modal
            // doesn't render an empty slice.
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
            // Every letter in the response carries the hashed id verbatim — no double-hashing.
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
    }

    // ---------------------------------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------------------------------

    /**
     * Builds a manager backed by a synthetic configuration that exposes the given DLQs and matching
     * processors / event-handling components. Pass [ehc] = `null` (the default) to leave the EHC
     * lookup empty — which exercises the manager's "no EHC available → message-id fallback" path.
     */
    private fun managerWith(
            vararg dlqsByName: Pair<String, SequencedDeadLetterQueue<EventMessage>>,
            ehc: EventHandlingComponent? = null,
            dlqMode: AxoniqConsoleDlqMode = AxoniqConsoleDlqMode.FULL,
            whitelist: List<String> = emptyList(),
    ): DeadLetterManager {
        val configuration = configurationWith(dlqsByName.asList(), ehc)
        return DeadLetterManager(configuration, dlqMode, whitelist).also { it.start() }
    }

    private fun configurationWith(
            dlqsByName: List<Pair<String, SequencedDeadLetterQueue<EventMessage>>>,
            ehc: EventHandlingComponent?,
    ): Configuration {
        val module = mockk<Configuration>(relaxed = true)
        every { module.getComponents(SequencedDeadLetterQueue::class.java) } returns
                dlqsByName.toMap().mapValues { it.value as SequencedDeadLetterQueue<*> }
        dlqsByName.forEach { (name, _) ->
            val match = Regex("""^DeadLetterQueue\[EventHandlingComponent\[([^]]+)]\[(.+)]]$""").find(name)
            if (match != null) {
                val ehcName = "EventHandlingComponent[${match.groupValues[1]}][${match.groupValues[2]}]"
                val processor = mockk<SequencedDeadLetterProcessor<EventMessage>>(relaxed = true)
                every {
                    module.getOptionalComponent(SequencedDeadLetterProcessor::class.java, ehcName)
                } returns Optional.of(processor)
                every {
                    module.getOptionalComponent(EventHandlingComponent::class.java, ehcName)
                } returns Optional.ofNullable(ehc)
            }
        }
        val root = mockk<Configuration>(relaxed = true)
        every { root.moduleConfigurations } returns listOf(module)
        return root
    }

    private fun ehcWithPolicy(policy: (EventMessage) -> Any?): EventHandlingComponent {
        val ehc = mockk<EventHandlingComponent>(relaxed = true)
        // `sequenceIdentifierFor` is `@NullMarked` non-null in source, but in real life policies do
        // return `null` (and the manager's branch on that is what we want to exercise). MockK lets
        // us answer with whatever object reference; the unchecked cast keeps the compiler happy.
        @Suppress("UNCHECKED_CAST")
        every { ehc.sequenceIdentifierFor(any(), any()) } answers {
            policy(firstArg<EventMessage>()) as Any
        }
        @Suppress("UNCHECKED_CAST")
        every { ehc.sequenceIdentifierFor(any(), isNull()) } answers {
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
        return dlq
    }

    private fun fakeLetter(
            messageId: String,
            payload: Any? = "payload-$messageId",
            payloadType: Class<*> = (payload?.javaClass ?: String::class.java),
            causeType: String? = "java.lang.RuntimeException",
            causeMessage: String? = "boom",
            diagnostics: Map<String, String> = emptyMap(),
    ): DeadLetter<EventMessage> {
        val message = fakeEventMessage(messageId, payload, payloadType)
        return fakeLetterFromMessage(message, causeType, causeMessage, diagnostics)
    }

    private fun fakeLetterFromMessage(
            message: EventMessage,
            causeType: String? = "java.lang.RuntimeException",
            causeMessage: String? = "boom",
            diagnostics: Map<String, String> = emptyMap(),
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
        every { letter.lastTouched() } returns Instant.EPOCH
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
