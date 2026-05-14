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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.axonframework.common.configuration.Configuration
import org.axonframework.messaging.core.Metadata
import org.axonframework.messaging.core.MessageType
import org.axonframework.messaging.eventhandling.EventMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture

/**
 * Unit tests for [DeadLetterManager] — discovery, synthetic-id mapping, pagination, payload
 * truncation, and delegation to the underlying [SequencedDeadLetterQueue]. Builds the queue
 * fakes with mockk; the real DLQ implementation isn't on the classpath we want to exercise.
 *
 * Intentionally out of scope:
 *  - `findDeadLetterProcessor` reflective walk over `EventHandlingComponent` decorators — that
 *    needs the AF5 module wiring to materialise, which is integration territory.
 *  - `process(...)` / `processAll(...)` — these delegate to the resolved
 *    `SequencedDeadLetterProcessor` whose `process(Predicate)` future-form is asymmetric to
 *    construct from the test side; the colleague explicitly said no integration tests.
 */
class DeadLetterManagerTest {

    // ---------------------------------------------------------------------------------------
    //  Discovery / processing group naming
    // ---------------------------------------------------------------------------------------

    @Test
    fun `exposes processor name as processing group when the processor has a single DLQ`() {
        val dlq = fakeDlq()
        val configuration = configurationWith(
                "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
        )
        val manager = DeadLetterManager(configuration).also { it.start() }

        val infos = manager.infoFor("orders")

        assertEquals(listOf("orders"), infos.map { it.processingGroup })
    }

    @Test
    fun `exposes processor__component identifier when a processor has multiple DLQs`() {
        val configuration = configurationWith(
                "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(),
                "DeadLetterQueue[EventHandlingComponent[orders][AuditProjector]]" to fakeDlq(),
        )
        val manager = DeadLetterManager(configuration).also { it.start() }

        val infos = manager.infoFor("orders")

        assertEquals(
                setOf("orders::OrderProjector", "orders::AuditProjector"),
                infos.map { it.processingGroup }.toSet(),
        )
    }

    @Test
    fun `ignores components whose names do not match the DLQ pattern`() {
        val configuration = configurationWith(
                "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(),
                "SomeOtherComponent" to fakeDlq(),
                "DeadLetterQueue[Other][format]" to fakeDlq(),
        )
        val manager = DeadLetterManager(configuration).also { it.start() }

        assertEquals(listOf("orders"), manager.infoFor("orders").map { it.processingGroup })
    }

    @Test
    fun `infoFor returns only DLQs belonging to the requested processor`() {
        val configuration = configurationWith(
                "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(sequenceCount = 3),
                "DeadLetterQueue[EventHandlingComponent[shipping][ShipmentProjector]]" to fakeDlq(sequenceCount = 7),
        )
        val manager = DeadLetterManager(configuration).also { it.start() }

        val ordersInfo = manager.infoFor("orders")
        val shippingInfo = manager.infoFor("shipping")

        assertEquals(listOf("orders" to 3L), ordersInfo.map { it.processingGroup to it.dlqSize })
        assertEquals(listOf("shipping" to 7L), shippingInfo.map { it.processingGroup to it.dlqSize })
    }

    // ---------------------------------------------------------------------------------------
    //  Synthetic sequence id
    // ---------------------------------------------------------------------------------------

    @Test
    fun `deadLetters stamps every letter in a sequence with the first letter's message id`() {
        val sequence = listOf(
                fakeLetter(messageId = "first"),
                fakeLetter(messageId = "second"),
                fakeLetter(messageId = "third"),
        )
        val dlq = fakeDlq(sequences = listOf(sequence))
        val configuration = configurationWith(
                "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
        )
        val manager = DeadLetterManager(configuration).also { it.start() }

        val response = manager.deadLetters("orders")

        assertEquals(1, response.sequences.size)
        assertEquals(listOf("first", "first", "first"), response.sequences[0].map { it.sequenceIdentifier })
    }

    @Test
    fun `empty sequence gets an empty-string synthetic id without crashing`() {
        // Degenerate but documented: if a sequence iterator yields no letters, the synthetic id
        // is the empty string. The mapped list is empty, so there's nothing to inspect on it —
        // we just need this not to throw.
        val dlq = fakeDlq(sequences = listOf(emptyList()))
        val configuration = configurationWith(
                "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
        )
        val manager = DeadLetterManager(configuration).also { it.start() }

        val response = manager.deadLetters("orders")

        assertEquals(1, response.sequences.size)
        assertTrue(response.sequences[0].isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    //  lettersForSequence pagination
    // ---------------------------------------------------------------------------------------

    @Test
    fun `lettersForSequence returns the requested slice in order with the correct total`() {
        val sequence = (1..5).map { fakeLetter(messageId = "m$it", payload = "p$it") }
        val dlq = fakeDlq(sequences = listOf(sequence))
        val manager = DeadLetterManager(configurationWith(
                "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
        )).also { it.start() }

        val response = manager.lettersForSequence("orders", "m1", offset = 1, size = 2)

        assertEquals(5L, response.totalCount)
        assertEquals(listOf("m2", "m3"), response.letters.map { it.messageIdentifier })
    }

    @Test
    fun `lettersForSequence coerces a negative offset to zero`() {
        val sequence = (1..3).map { fakeLetter(messageId = "m$it") }
        val dlq = fakeDlq(sequences = listOf(sequence))
        val manager = DeadLetterManager(configurationWith(
                "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
        )).also { it.start() }

        val response = manager.lettersForSequence("orders", "m1", offset = -5, size = 2)

        assertEquals(listOf("m1", "m2"), response.letters.map { it.messageIdentifier })
    }

    @Test
    fun `lettersForSequence coerces a non-positive size to one`() {
        val sequence = (1..3).map { fakeLetter(messageId = "m$it") }
        val dlq = fakeDlq(sequences = listOf(sequence))
        val manager = DeadLetterManager(configurationWith(
                "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
        )).also { it.start() }

        val response = manager.lettersForSequence("orders", "m1", offset = 0, size = 0)

        assertEquals(1, response.letters.size)
        assertEquals("m1", response.letters[0].messageIdentifier)
    }

    @Test
    fun `lettersForSequence returns an empty response when no sequence matches the synthetic id`() {
        val sequence = listOf(fakeLetter(messageId = "real"))
        val dlq = fakeDlq(sequences = listOf(sequence))
        val manager = DeadLetterManager(configurationWith(
                "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
        )).also { it.start() }

        val response = manager.lettersForSequence("orders", "stale-id", 0, 10)

        assertTrue(response.letters.isEmpty())
        assertEquals(0L, response.totalCount)
    }

    @Test
    fun `lettersForSequence caps the slice at the size argument even when the sequence is larger`() {
        val sequence = (1..10).map { fakeLetter(messageId = "m$it") }
        val dlq = fakeDlq(sequences = listOf(sequence))
        val manager = DeadLetterManager(configurationWith(
                "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
        )).also { it.start() }

        val response = manager.lettersForSequence("orders", "m1", offset = 0, size = 3)

        assertEquals(3, response.letters.size)
        assertEquals(10L, response.totalCount)
    }

    // ---------------------------------------------------------------------------------------
    //  delete / deleteAll delegation
    // ---------------------------------------------------------------------------------------

    @Test
    fun `delete by sequence evicts every letter in that sequence`() {
        val letters = listOf(fakeLetter("m1"), fakeLetter("m2"), fakeLetter("m3"))
        val dlq = fakeDlq(sequences = listOf(letters))
        val manager = DeadLetterManager(configurationWith(
                "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
        )).also { it.start() }

        val evicted = manager.delete("orders", "m1")

        assertEquals(3, evicted)
        letters.forEach { verify(exactly = 1) { dlq.evict(it, null) } }
    }

    @Test
    fun `delete by sequence is a no-op when the sequence does not exist`() {
        val dlq = fakeDlq(sequences = listOf(listOf(fakeLetter("real"))))
        val manager = DeadLetterManager(configurationWith(
                "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
        )).also { it.start() }

        val evicted = manager.delete("orders", "ghost")

        assertEquals(0, evicted)
        verify(exactly = 0) { dlq.evict(any<DeadLetter<EventMessage>>(), any()) }
    }

    @Test
    fun `delete by message evicts only the matching letter`() {
        val letter1 = fakeLetter("m1")
        val letter2 = fakeLetter("m2")
        val dlq = fakeDlq(sequences = listOf(listOf(letter1, letter2)))
        val manager = DeadLetterManager(configurationWith(
                "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
        )).also { it.start() }

        val evicted = manager.delete("orders", "m1", "m2")

        assertTrue(evicted)
        verify(exactly = 1) { dlq.evict(letter2, null) }
        verify(exactly = 0) { dlq.evict(letter1, null) }
    }

    @Test
    fun `delete by message is a no-op when the message id is unknown in the sequence`() {
        val letter1 = fakeLetter("m1")
        val dlq = fakeDlq(sequences = listOf(listOf(letter1)))
        val manager = DeadLetterManager(configurationWith(
                "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
        )).also { it.start() }

        val evicted = manager.delete("orders", "m1", "missing")

        assertFalse(evicted)
        verify(exactly = 0) { dlq.evict(any<DeadLetter<EventMessage>>(), any()) }
    }

    @Test
    fun `delete by message is a no-op when the sequence does not resolve`() {
        val dlq = fakeDlq(sequences = listOf(listOf(fakeLetter("real"))))
        val manager = DeadLetterManager(configurationWith(
                "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
        )).also { it.start() }

        val evicted = manager.delete("orders", "ghost", "anything")

        assertFalse(evicted)
        verify(exactly = 0) { dlq.evict(any<DeadLetter<EventMessage>>(), any()) }
    }

    @Test
    fun `deleteAll returns the queue size and clears the queue`() {
        val dlq = fakeDlq(totalSize = 42L)
        val manager = DeadLetterManager(configurationWith(
                "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
        )).also { it.start() }

        val deleted = manager.deleteAll("orders")

        assertEquals(42, deleted)
        verify(exactly = 1) { dlq.clear(null) }
    }

    @Test
    fun `sequenceSize returns the count of letters for the matching synthetic id`() {
        val sequence = (1..4).map { fakeLetter("m$it") }
        val dlq = fakeDlq(sequences = listOf(sequence))
        val manager = DeadLetterManager(configurationWith(
                "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
        )).also { it.start() }

        assertEquals(4L, manager.sequenceSize("orders", "m1"))
    }

    @Test
    fun `sequenceSize returns zero when the synthetic id does not resolve`() {
        val sequence = listOf(fakeLetter("real"))
        val dlq = fakeDlq(sequences = listOf(sequence))
        val manager = DeadLetterManager(configurationWith(
                "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
        )).also { it.start() }

        assertEquals(0L, manager.sequenceSize("orders", "ghost"))
    }

    // ---------------------------------------------------------------------------------------
    //  Payload truncation + messageType fallback
    // ---------------------------------------------------------------------------------------

    @Test
    fun `payload at or below 1024 UTF-8 bytes is returned untouched`() {
        val payload = "x".repeat(1024)
        val sequence = listOf(fakeLetter("m1", payload = payload))
        val dlq = fakeDlq(sequences = listOf(sequence))
        val manager = DeadLetterManager(configurationWith(
                "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
        )).also { it.start() }

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
        val dlq = fakeDlq(sequences = listOf(sequence))
        val manager = DeadLetterManager(configurationWith(
                "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
        )).also { it.start() }

        val result = manager.deadLetters("orders").sequences[0][0].message

        // Truncated string fits within the limit ...
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 1024)
        // ... and contains no replacement characters from a mid-codepoint split.
        assertFalse(result.contains('�'))
        // The resulting string is composed entirely of valid "č" codepoints.
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
        val dlq = fakeDlq(sequences = listOf(listOf(letter)))
        val manager = DeadLetterManager(configurationWith(
                "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
        )).also { it.start() }

        val apiLetter = manager.deadLetters("orders").sequences[0][0]

        assertEquals("com.example.OrderPlaced", apiLetter.messageType)
    }

    @Test
    fun `messageType uses payload class simple name for non-ByteArray payloads`() {
        val sequence = listOf(fakeLetter("m1", payload = "hello", payloadType = String::class.java))
        val dlq = fakeDlq(sequences = listOf(sequence))
        val manager = DeadLetterManager(configurationWith(
                "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to dlq,
        )).also { it.start() }

        val apiLetter = manager.deadLetters("orders").sequences[0][0]

        assertEquals("String", apiLetter.messageType)
    }

    // ---------------------------------------------------------------------------------------
    //  dlqFor error
    // ---------------------------------------------------------------------------------------

    @Test
    fun `unknown processing group throws IllegalArgumentException`() {
        val manager = DeadLetterManager(configurationWith(
                "DeadLetterQueue[EventHandlingComponent[orders][OrderProjector]]" to fakeDlq(),
        )).also { it.start() }

        assertThrows(IllegalArgumentException::class.java) {
            manager.sequenceSize("unknown-group", "whatever")
        }
    }

    // ---------------------------------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------------------------------

    /**
     * Builds a [Configuration] backed by a single module that exposes the given DLQs and a
     * matching [SequencedDeadLetterProcessor] for each — the manager looks up the latter by the
     * `EventHandlingComponent[<processor>][<component>]` name to materialise its
     * `DlqEntry.processor` field.
     */
    private fun configurationWith(
            vararg dlqsByName: Pair<String, SequencedDeadLetterQueue<EventMessage>>,
    ): Configuration {
        val module = mockk<Configuration>(relaxed = true)
        every { module.getComponents(SequencedDeadLetterQueue::class.java) } returns
                dlqsByName.toMap().mapValues { it.value as SequencedDeadLetterQueue<*> }
        // Every DLQ name carries the processor + component segment used to address its processor.
        dlqsByName.forEach { (name, _) ->
            val match = Regex("""^DeadLetterQueue\[EventHandlingComponent\[([^]]+)]\[(.+)]]$""").find(name)
            if (match != null) {
                val ehcName = "EventHandlingComponent[${match.groupValues[1]}][${match.groupValues[2]}]"
                val processor = mockk<SequencedDeadLetterProcessor<EventMessage>>(relaxed = true)
                every {
                    module.getOptionalComponent(SequencedDeadLetterProcessor::class.java, ehcName)
                } returns Optional.of(processor)
            }
        }
        val root = mockk<Configuration>(relaxed = true)
        every { root.moduleConfigurations } returns listOf(module)
        return root
    }

    private fun fakeDlq(
            sequences: List<List<DeadLetter<EventMessage>>> = emptyList(),
            sequenceCount: Long = sequences.size.toLong(),
            totalSize: Long = sequences.sumOf { it.size.toLong() },
    ): SequencedDeadLetterQueue<EventMessage> {
        val dlq = mockk<SequencedDeadLetterQueue<EventMessage>>(relaxed = true)
        // `deadLetters(null)` returns a CompletableFuture<Iterable<Iterable<DeadLetter>>>; the
        // manager calls `.join()` and iterates with `.toList()` on each inner sequence, so any
        // Iterable shape works here.
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
    ): DeadLetter<EventMessage> {
        val message = fakeEventMessage(messageId, payload, payloadType)
        return fakeLetterFromMessage(message, causeType, causeMessage)
    }

    private fun fakeLetterFromMessage(
            message: EventMessage,
            causeType: String? = "java.lang.RuntimeException",
            causeMessage: String? = "boom",
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
        every { letter.diagnostics() } returns Metadata.emptyInstance()
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
