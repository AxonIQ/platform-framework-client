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
import org.slf4j.LoggerFactory

open class RSocketDlqResponder(
        private val deadLetterManager: DeadLetterManager,
        private val registrar: RSocketHandlerRegistrar,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    fun start() {
        registrar.registerHandlerWithPayload(
                Routes.ProcessingGroup.DeadLetter.LETTERS,
                DeadLetterRequest::class.java,
                this::handleDeadLetterQuery,
        )
        registrar.registerHandlerWithPayload(
                Routes.ProcessingGroup.DeadLetter.SEQUENCE_SIZE,
                DeadLetterSequenceSize::class.java,
                this::handleSequenceSizeQuery,
        )
        registrar.registerHandlerWithPayload(
                Routes.ProcessingGroup.DeadLetter.SEQUENCE_LETTERS,
                FetchSequenceLettersRequest::class.java,
                this::handleSequenceLettersQuery,
        )
        registrar.registerHandlerWithPayload(
                Routes.ProcessingGroup.DeadLetter.DELETE_SEQUENCE,
                DeadLetterSequenceDeleteRequest::class.java,
                this::handleDeleteSequenceCommand,
        )
        registrar.registerHandlerWithPayload(
                Routes.ProcessingGroup.DeadLetter.DELETE_LETTER,
                DeadLetterSingleDeleteRequest::class.java,
                this::handleDeleteLetterCommand,
        )
        registrar.registerHandlerWithPayload(
                Routes.ProcessingGroup.DeadLetter.PROCESS,
                DeadLetterProcessRequest::class.java,
                this::handleProcessCommand,
        )
        registrar.registerHandlerWithPayload(
                Routes.ProcessingGroup.DeadLetter.PROCESS_ALL_SEQUENCES,
                ProcessAllDeadLetterSequencesRequest::class.java,
                this::handleProcessAllSequencesCommand,
        )
        registrar.registerHandlerWithPayload(
                Routes.ProcessingGroup.DeadLetter.DELETE_ALL_SEQUENCES,
                DeleteAllDeadLetterSequencesRequest::class.java,
                this::handleDeleteAllSequencesCommand,
        )
    }

    private fun handleDeadLetterQuery(request: DeadLetterRequest): DeadLetterResponse {
        logger.debug("Handling Axoniq Platform DEAD_LETTERS query [{}]", request)
        return deadLetterManager.deadLetters(
                request.processingGroup,
                request.offset,
                request.size,
                request.maxSequenceLetters,
        )
    }

    private fun handleSequenceSizeQuery(request: DeadLetterSequenceSize): Long {
        logger.debug(
                "Handling Axoniq Platform DEAD_LETTER_SEQUENCE_SIZE query for processing group [{}]",
                request.processingGroup,
        )
        return deadLetterManager.sequenceSize(request.processingGroup, request.sequenceIdentifier)
    }

    private fun handleSequenceLettersQuery(request: FetchSequenceLettersRequest): SequenceLettersResponse {
        logger.debug(
                "Handling Axoniq Platform DEAD_LETTER_SEQUENCE_LETTERS query for processing group [{}], sequence [{}], offset={}, size={}",
                request.processingGroup,
                request.sequenceIdentifier,
                request.offset,
                request.size,
        )
        return deadLetterManager.lettersForSequence(
                request.processingGroup,
                request.sequenceIdentifier,
                request.offset,
                request.size,
        )
    }

    private fun handleDeleteSequenceCommand(request: DeadLetterSequenceDeleteRequest) {
        logger.debug(
                "Handling Axoniq Platform DELETE_SEQUENCE command for processing group [{}], sequence [{}]",
                request.processingGroup, request.sequenceIdentifier,
        )
        val evicted = deadLetterManager.delete(request.processingGroup, request.sequenceIdentifier)
        logger.info(
                "DELETE_SEQUENCE for [{}] sequence [{}] → evicted {} letter(s)",
                request.processingGroup, request.sequenceIdentifier, evicted,
        )
    }

    private fun handleDeleteLetterCommand(request: DeadLetterSingleDeleteRequest) {
        logger.debug(
                "Handling Axoniq Platform DELETE_LETTER command for processing group [{}], sequence [{}], message [{}]",
                request.processingGroup, request.sequenceIdentifier, request.messageIdentifier,
        )
        val evicted = deadLetterManager.delete(
                request.processingGroup,
                request.sequenceIdentifier,
                request.messageIdentifier,
        )
        logger.info(
                "DELETE_LETTER for [{}] sequence [{}] message [{}] → {}",
                request.processingGroup, request.sequenceIdentifier, request.messageIdentifier,
                if (evicted) "evicted" else "no-op (id no longer resolves)",
        )
    }

    private fun handleProcessCommand(request: DeadLetterProcessRequest): Boolean {
        logger.debug(
                "Handling Axoniq Platform PROCESS command for processing group [{}]",
                request.processingGroup,
        )
        return deadLetterManager.process(request.processingGroup, request.messageIdentifier)
    }

    private fun handleProcessAllSequencesCommand(request: ProcessAllDeadLetterSequencesRequest): Int {
        logger.debug(
                "Handling Axoniq Platform PROCESS_ALL_SEQUENCES command for processing group [{}]",
                request.processingGroup,
        )
        return deadLetterManager.processAll(request.processingGroup, request.maxMessages)
    }

    private fun handleDeleteAllSequencesCommand(request: DeleteAllDeadLetterSequencesRequest): Int {
        logger.debug(
                "Handling Axoniq Platform DELETE_ALL_SEQUENCES command for processing group [{}]",
                request.processingGroup,
        )
        return deadLetterManager.deleteAll(request.processingGroup)
    }
}
