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

import io.axoniq.framework.messaging.deadletter.GenericDeadLetter
import io.axoniq.framework.messaging.deadletter.SequencedDeadLetterQueue
import io.axoniq.framework.messaging.eventhandling.deadletter.DeadLetterQueueConfiguration
import org.axonframework.common.configuration.AxonConfiguration
import org.axonframework.common.configuration.ComponentDefinition
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer
import org.axonframework.messaging.core.MessageType
import org.axonframework.messaging.core.QualifiedName
import org.axonframework.messaging.core.sequencing.SequencingPolicy
import org.axonframework.messaging.eventhandling.EventMessage
import org.axonframework.messaging.eventhandling.GenericEventMessage
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorModule
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.SequenceOverridingEventHandlingComponent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * End-to-end integration test for the AF5 DLQ inspection wiring.
 *
 * Boots a real AF5 configuration (via [EventSourcingConfigurer]) with a single Pooled Streaming
 * event processor whose [SimpleEventHandlingComponent] is wrapped in a
 * [SequenceOverridingEventHandlingComponent] using a deterministic [SequencingPolicy]. With the
 * DLQ enabled on the processor, the framework materialises a real [SequencedDeadLetterQueue]
 * under the `DeadLetterQueue[EventHandlingComponent[<processor>][<component>]]` name that
 * [DeadLetterManager] discovers.
 *
 * Rather than driving the processor end-to-end (which requires an event store, an event sink, and
 * deterministic failure timing), we enqueue letters directly onto the materialised DLQ — the bit
 * the test is verifying is the discovery + sequence-identifier resolution + mutation flow, not the
 * processor's own machinery.
 *
 * What we assert:
 *  - the manager exposes the processor name as a single processing group;
 *  - `deadLetters(...)` returns the policy-derived sequence identifier (NOT the synthetic first
 *    message id, which is the bug the AF4 sequencing policy port fixed);
 *  - the sequence id stays stable across `delete(seq, messageId)` — deleting the first letter
 *    must not rename the sequence;
 *  - `lettersForSequence(...)` paginates correctly;
 *  - `delete(seq)` evicts every letter.
 *
 * DLQ-mode behaviour is intentionally out of scope here — that's covered exhaustively in the
 * unit-test [DeadLetterManagerTest]'s `DlqModes` nested class, which can mock cheaply.
 */
class RSocketDlqResponderIntegrationTest {

    companion object {
        private const val PROCESSOR_NAME = "audit"
        private const val COMPONENT_NAME = "AuditProjector"
        private const val SEQUENCE_ID = "tenant-42"
        private val EVENT_NAME = QualifiedName(TestEvent::class.java)
    }

    /**
     * Payload type used by the integration test. A real AF5 `EventMessage` requires a `MessageType`
     * which we derive from this class via [QualifiedName].
     */
    data class TestEvent(val value: String)

    private lateinit var configuration: AxonConfiguration
    private lateinit var manager: DeadLetterManager

    @BeforeEach
    fun boot() {
        configuration = EventSourcingConfigurer.create()
                .componentRegistry { registry ->
                    registry.registerComponent(ComponentDefinition.ofType(DeadLetterManager::class.java)
                            .withBuilder { c -> DeadLetterManager(c) })
                }
                .messaging { messaging ->
                    messaging.eventProcessing { eventProcessing ->
                        eventProcessing.pooledStreaming { psep ->
                            psep.processor(
                                    PooledStreamingEventProcessorModule(PROCESSOR_NAME)
                                            .eventHandlingComponents { components ->
                                                components.declarative(COMPONENT_NAME) { _ ->
                                                    SequenceOverridingEventHandlingComponent(
                                                            constantSequencingPolicy(),
                                                            SimpleEventHandlingComponent.create(COMPONENT_NAME),
                                                    )
                                                }
                                            }
                                            .customized { _, cfg ->
                                                cfg.extend(
                                                        DeadLetterQueueConfiguration::class.java,
                                                ) { DeadLetterQueueConfiguration().enabled() }
                                            },
                            )
                        }
                    }
                }
                .build()
        configuration.start()
        manager = configuration.getComponent(DeadLetterManager::class.java)
        manager.start()
        // Push two letters with the same policy-derived sequence id so we can test pagination and
        // confirm the id remains stable as letters get evicted.
        val dlq = resolveDlq()
        enqueue(dlq, sequenceId = SEQUENCE_ID, "letter-1")
        enqueue(dlq, sequenceId = SEQUENCE_ID, "letter-2")
        enqueue(dlq, sequenceId = SEQUENCE_ID, "letter-3")
    }

    @AfterEach
    fun shutdown() {
        configuration.shutdown()
    }

    @Test
    fun `manager discovers the configured processor as a processing group`() {
        val infos = manager.infoFor(PROCESSOR_NAME)
        assertEquals(1, infos.size)
        assertEquals(PROCESSOR_NAME, infos[0].processingGroup)
    }

    @Test
    fun `the framework wraps the EHC in a caching decorator and the resolver unwraps past it`() {
        // Sanity check that exercises the live decorator chain: AF5 wraps every EHC with
        // SequenceCachingEventHandlingComponent whose sequenceIdentifierFor NPEs without a live
        // ProcessingContext. The resolver must unwrap past it and reach a layer whose policy can
        // run with `null` context. If this regresses, the four flow tests below also fail — this
        // test fails first with a clearer signal.
        val expectedEhcName = "EventHandlingComponent[$PROCESSOR_NAME][$COMPONENT_NAME]"
        val ehc = configuration.moduleConfigurations.asSequence()
                .mapNotNull { module ->
                    module.getOptionalComponent(
                            org.axonframework.messaging.eventhandling.EventHandlingComponent::class.java,
                            expectedEhcName,
                    ).orElse(null)
                }
                .firstOrNull()
        assertNotNull(ehc, "Expected an EventHandlingComponent registered as [$expectedEhcName]")
        val event: EventMessage = GenericEventMessage(MessageType(EVENT_NAME), TestEvent("sanity"))
        assertEquals(SEQUENCE_ID, SequenceIdentifierResolver.resolve(ehc!!, event))
    }

    @Test
    fun `deadLetters returns the policy-derived sequence id (not the first letter's message id)`() {
        val response = manager.deadLetters(PROCESSOR_NAME)
        assertEquals(1, response.sequences.size)
        val letters = response.sequences[0]
        // Helpful failure context: surface the actual ids when the assertion fails so future
        // breakage points at "got <UUID>" vs "got <tenant-42>" without re-running with a debugger.
        val seqIds = letters.map { it.sequenceIdentifier }
        assertTrue(
                letters.all { it.sequenceIdentifier == SEQUENCE_ID },
                "Expected every letter in the sequence to carry policy id [$SEQUENCE_ID] but got $seqIds",
        )
        assertTrue(letters.first().messageIdentifier != SEQUENCE_ID)
    }

    @Test
    fun `lettersForSequence paginates within the sequence`() {
        val response = manager.lettersForSequence(PROCESSOR_NAME, SEQUENCE_ID, offset = 1, size = 1)
        assertEquals(3L, response.totalCount)
        assertEquals(1, response.letters.size)
    }

    @Test
    fun `sequence id stays stable across delete-letter (the AF4 regression that motivated this rewrite)`() {
        // Capture the first letter's message id, delete it, then re-fetch the sequence: the
        // sequence id MUST still be `tenant-42`, not the message id of the (now-second) letter.
        val firstLetterId = manager.deadLetters(PROCESSOR_NAME).sequences[0][0].messageIdentifier
        val evicted = manager.delete(PROCESSOR_NAME, SEQUENCE_ID, firstLetterId)
        assertTrue(evicted)
        val after = manager.deadLetters(PROCESSOR_NAME).sequences[0]
        assertEquals(2, after.size)
        assertTrue(after.all { it.sequenceIdentifier == SEQUENCE_ID })
        assertTrue(after.none { it.messageIdentifier == firstLetterId })
    }

    @Test
    fun `delete-sequence evicts every letter in the sequence`() {
        val evicted = manager.delete(PROCESSOR_NAME, SEQUENCE_ID)
        assertEquals(3, evicted)
        // After deletion the manager's infoFor returns zero sequences for this processor.
        assertEquals(0L, manager.infoFor(PROCESSOR_NAME).single().dlqSize)
    }

    // -----------------------------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Walks the module configurations the same way [DeadLetterManager.discoverEntries] does and
     * returns the materialised DLQ for the test processor's component. Failing here means the
     * framework wiring didn't produce a DLQ — likely a misconfigured
     * [DeadLetterQueueConfiguration] in [boot].
     */
    @Suppress("UNCHECKED_CAST")
    private fun resolveDlq(): SequencedDeadLetterQueue<EventMessage> {
        val expectedName = "DeadLetterQueue[EventHandlingComponent[$PROCESSOR_NAME][$COMPONENT_NAME]]"
        val dlq = configuration.moduleConfigurations.asSequence()
                .flatMap { it.getComponents(SequencedDeadLetterQueue::class.java).entries.asSequence() }
                .firstOrNull { it.key == expectedName }
                ?.value
        assertNotNull(dlq, "Expected the framework to materialise a DLQ named [$expectedName]")
        return dlq as SequencedDeadLetterQueue<EventMessage>
    }

    private fun enqueue(
            dlq: SequencedDeadLetterQueue<EventMessage>,
            sequenceId: String,
            payloadValue: String,
    ) {
        val message: EventMessage = GenericEventMessage(
                MessageType(EVENT_NAME),
                TestEvent(payloadValue),
        )
        val letter = GenericDeadLetter<EventMessage>(
                sequenceId,
                message,
                RuntimeException("deliberate failure for [$payloadValue]"),
        )
        dlq.enqueue(sequenceId, letter, null).join()
    }

    /**
     * Returns the same sequence identifier for every event in the test, so all enqueued letters
     * end up in a single sequence — that's what lets the assertions about pagination and stable
     * sequence id work.
     */
    private fun constantSequencingPolicy(): SequencingPolicy<EventMessage> =
            SequencingPolicy { _, _ -> Optional.of(SEQUENCE_ID) }
}
