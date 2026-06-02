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

import io.axoniq.platform.framework.api.DeadLetterProcessRequest
import io.axoniq.platform.framework.api.DeadLetterRequest
import io.axoniq.platform.framework.api.DeadLetterResponse
import io.axoniq.platform.framework.api.DeadLetterSequenceDeleteRequest
import io.axoniq.platform.framework.api.DeadLetterSequenceSize
import io.axoniq.platform.framework.api.DeadLetterSingleDeleteRequest
import io.axoniq.platform.framework.api.DeleteAllDeadLetterSequencesRequest
import io.axoniq.platform.framework.api.FetchSequenceLettersRequest
import io.axoniq.platform.framework.api.ProcessAllDeadLetterSequencesRequest
import io.axoniq.platform.framework.api.Routes
import io.axoniq.platform.framework.api.SequenceLettersResponse
import io.axoniq.platform.framework.client.RSocketHandlerRegistrar
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Verifies that [RSocketDlqResponder] registers a handler for every DLQ route on
 * [RSocketHandlerRegistrar.registerHandlerWithPayload] and that each captured handler delegates
 * to the corresponding [DeadLetterManager] method. We capture the lambda handed to the registrar
 * so we exercise both registration AND the handler's body in a single test per route.
 *
 * The slots are typed as `(T) -> Any` because that is the exact functional shape
 * `registerHandlerWithPayload` declares; the production handlers happen to return more specific
 * types but the registrar erases them down to `Any`.
 */
class RSocketDlqResponderTest {

    private lateinit var manager: DeadLetterManager
    private lateinit var registrar: RSocketHandlerRegistrar
    private lateinit var responder: RSocketDlqResponder

    private val letterHandler = slot<(DeadLetterRequest) -> Any>()
    private val sequenceSizeHandler = slot<(DeadLetterSequenceSize) -> Any>()
    private val sequenceLettersHandler = slot<(FetchSequenceLettersRequest) -> Any>()
    private val deleteSequenceHandler = slot<(DeadLetterSequenceDeleteRequest) -> Any>()
    private val deleteLetterHandler = slot<(DeadLetterSingleDeleteRequest) -> Any>()
    private val processHandler = slot<(DeadLetterProcessRequest) -> Any>()
    private val processAllHandler = slot<(ProcessAllDeadLetterSequencesRequest) -> Any>()
    private val deleteAllHandler = slot<(DeleteAllDeadLetterSequencesRequest) -> Any>()

    @BeforeEach
    fun setUp() {
        manager = mockk(relaxed = true)
        registrar = mockk(relaxed = true)
        captureHandler(Routes.ProcessingGroup.DeadLetter.LETTERS, DeadLetterRequest::class.java, letterHandler)
        captureHandler(Routes.ProcessingGroup.DeadLetter.SEQUENCE_SIZE, DeadLetterSequenceSize::class.java, sequenceSizeHandler)
        captureHandler(Routes.ProcessingGroup.DeadLetter.SEQUENCE_LETTERS, FetchSequenceLettersRequest::class.java, sequenceLettersHandler)
        captureHandler(Routes.ProcessingGroup.DeadLetter.DELETE_SEQUENCE, DeadLetterSequenceDeleteRequest::class.java, deleteSequenceHandler)
        captureHandler(Routes.ProcessingGroup.DeadLetter.DELETE_LETTER, DeadLetterSingleDeleteRequest::class.java, deleteLetterHandler)
        captureHandler(Routes.ProcessingGroup.DeadLetter.PROCESS, DeadLetterProcessRequest::class.java, processHandler)
        captureHandler(Routes.ProcessingGroup.DeadLetter.PROCESS_ALL_SEQUENCES, ProcessAllDeadLetterSequencesRequest::class.java, processAllHandler)
        captureHandler(Routes.ProcessingGroup.DeadLetter.DELETE_ALL_SEQUENCES, DeleteAllDeadLetterSequencesRequest::class.java, deleteAllHandler)

        responder = RSocketDlqResponder(manager, registrar)
        responder.start()
    }

    @Test
    fun `start registers a handler for each of the eight DLQ routes`() {
        verify(exactly = 1) { registrar.registerHandlerWithPayload(Routes.ProcessingGroup.DeadLetter.LETTERS, DeadLetterRequest::class.java, any()) }
        verify(exactly = 1) { registrar.registerHandlerWithPayload(Routes.ProcessingGroup.DeadLetter.SEQUENCE_SIZE, DeadLetterSequenceSize::class.java, any()) }
        verify(exactly = 1) { registrar.registerHandlerWithPayload(Routes.ProcessingGroup.DeadLetter.SEQUENCE_LETTERS, FetchSequenceLettersRequest::class.java, any()) }
        verify(exactly = 1) { registrar.registerHandlerWithPayload(Routes.ProcessingGroup.DeadLetter.DELETE_SEQUENCE, DeadLetterSequenceDeleteRequest::class.java, any()) }
        verify(exactly = 1) { registrar.registerHandlerWithPayload(Routes.ProcessingGroup.DeadLetter.DELETE_LETTER, DeadLetterSingleDeleteRequest::class.java, any()) }
        verify(exactly = 1) { registrar.registerHandlerWithPayload(Routes.ProcessingGroup.DeadLetter.PROCESS, DeadLetterProcessRequest::class.java, any()) }
        verify(exactly = 1) { registrar.registerHandlerWithPayload(Routes.ProcessingGroup.DeadLetter.PROCESS_ALL_SEQUENCES, ProcessAllDeadLetterSequencesRequest::class.java, any()) }
        verify(exactly = 1) { registrar.registerHandlerWithPayload(Routes.ProcessingGroup.DeadLetter.DELETE_ALL_SEQUENCES, DeleteAllDeadLetterSequencesRequest::class.java, any()) }
    }

    @Test
    fun `LETTERS handler forwards all request parameters to DeadLetterManager deadLetters`() {
        val expected = DeadLetterResponse(emptyList(), 7)
        every { manager.deadLetters("g", 1, 10, 5) } returns expected

        val result = letterHandler.captured(DeadLetterRequest("g", offset = 1, size = 10, maxSequenceLetters = 5))

        assertEquals(expected, result)
        verify(exactly = 1) { manager.deadLetters("g", 1, 10, 5) }
    }

    @Test
    fun `SEQUENCE_SIZE handler forwards processing group and sequence id`() {
        every { manager.sequenceSize("g", "seq-1") } returns 42L

        val result = sequenceSizeHandler.captured(DeadLetterSequenceSize("g", "seq-1"))

        assertEquals(42L, result)
        verify(exactly = 1) { manager.sequenceSize("g", "seq-1") }
    }

    @Test
    fun `SEQUENCE_LETTERS handler forwards pagination arguments`() {
        val expected = SequenceLettersResponse(emptyList(), 0)
        every { manager.lettersForSequence("g", "seq-1", 5, 25) } returns expected

        val result = sequenceLettersHandler.captured(FetchSequenceLettersRequest("g", "seq-1", offset = 5, size = 25))

        assertEquals(expected, result)
        verify(exactly = 1) { manager.lettersForSequence("g", "seq-1", 5, 25) }
    }

    @Test
    fun `DELETE_SEQUENCE handler delegates to DeadLetterManager delete-by-sequence`() {
        every { manager.delete("g", "seq-1") } returns 3

        deleteSequenceHandler.captured(DeadLetterSequenceDeleteRequest("g", "seq-1"))

        verify(exactly = 1) { manager.delete("g", "seq-1") }
    }

    @Test
    fun `DELETE_LETTER handler delegates to DeadLetterManager delete-by-message`() {
        every { manager.delete("g", "seq-1", "msg-1") } returns true

        deleteLetterHandler.captured(DeadLetterSingleDeleteRequest("g", "seq-1", "msg-1"))

        verify(exactly = 1) { manager.delete("g", "seq-1", "msg-1") }
    }

    @Test
    fun `PROCESS handler returns the manager's result`() {
        every { manager.process("g", "msg-1") } returns true

        val result = processHandler.captured(DeadLetterProcessRequest("g", "msg-1"))

        assertEquals(true, result)
        verify(exactly = 1) { manager.process("g", "msg-1") }
    }

    @Test
    fun `PROCESS_ALL_SEQUENCES handler forwards maxMessages`() {
        every { manager.processAll("g", 9) } returns 7

        val result = processAllHandler.captured(ProcessAllDeadLetterSequencesRequest("g", maxMessages = 9))

        assertEquals(7, result)
        verify(exactly = 1) { manager.processAll("g", 9) }
    }

    @Test
    fun `DELETE_ALL_SEQUENCES handler returns the cleared count`() {
        every { manager.deleteAll("g") } returns 11

        val result = deleteAllHandler.captured(DeleteAllDeadLetterSequencesRequest("g"))

        assertEquals(11, result)
        verify(exactly = 1) { manager.deleteAll("g") }
    }

    private fun <T> captureHandler(route: String, payloadType: Class<T>, handler: CapturingSlot<(T) -> Any>) {
        every {
            registrar.registerHandlerWithPayload(route, payloadType, capture(handler))
        } just runs
    }
}
