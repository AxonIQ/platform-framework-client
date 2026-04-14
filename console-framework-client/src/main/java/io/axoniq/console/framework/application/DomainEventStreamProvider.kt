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

package io.axoniq.console.framework.application

import tools.jackson.databind.ObjectMapper
import org.axonframework.common.ReflectionUtils
import org.axonframework.config.AggregateConfiguration
import org.axonframework.config.Configuration
import org.axonframework.eventhandling.DomainEventMessage
import org.axonframework.eventhandling.EventMessage
import org.axonframework.eventsourcing.AggregateFactory
import org.axonframework.eventsourcing.EventSourcedAggregate
import org.axonframework.eventsourcing.SnapshotTrigger
import org.axonframework.eventsourcing.eventstore.AbstractEventStorageEngine
import org.axonframework.eventsourcing.eventstore.DomainEventStream
import org.axonframework.modelling.command.Repository
import org.axonframework.modelling.command.RepositoryProvider
import org.axonframework.modelling.command.inspection.AggregateModel

class DomainEventStreamProvider(
        private val configuration: Configuration,
        private val objectMapper: ObjectMapper
) {

    fun getDomainEventStream(entityIdentifier: String, firstSequenceNumber: Long = 0): List<DomainEventMessage<*>>? =
            configuration.eventStore()
                    .readEvents(entityIdentifier, firstSequenceNumber)
                    .iterator()
                    .asSequence()
                    .toList()
                    .takeIf { it.isNotEmpty() }

    fun <T> loadDomainStateAtSequence(type: String, entityIdentifier: String, maxSequenceNumber: Long): String? {
        val entityConfiguration = configuration.modules
                .filterIsInstance<AggregateConfiguration<*>>()
                .firstOrNull { it.aggregateType().simpleName == type }
                ?: throw IllegalArgumentException("No domain entity found for type $type")

        val factory: AggregateFactory<T> = entityConfiguration.aggregateFactory() as AggregateFactory<T>
        val model: AggregateModel<T> = entityConfiguration.repository().getPropertyValue<AggregateModel<T>>("aggregateModel")
                ?: throw IllegalArgumentException("No domain entity model found for type $type")

        val stream = readEvents(entityIdentifier)
        val loadingEntity: EventSourcedAggregate<T> = EventSourcedAggregate
                .initialize(factory.createAggregateRoot(entityIdentifier, stream.peek()),
                        model,
                        configuration.eventStore(),
                        object : RepositoryProvider {
                            override fun <T> repositoryFor(aggregateType: Class<T>): Repository<T> {
                                return entityConfiguration.repository() as Repository<T>
                            }

                        },
                        object : SnapshotTrigger {
                            override fun eventHandled(p0: EventMessage<*>) {
                                // Do nothing
                            }

                            override fun initializationFinished() {
                                // Do nothing
                            }
                        })

        loadingEntity.initializeState(stream.filter { it.sequenceNumber <= maxSequenceNumber })

        return objectMapper.writeValueAsString(loadingEntity.aggregateRoot)
    }

    private fun readEvents(identifier: String, firstSequenceNumber: Long = 0): DomainEventStream =
            configuration.eventStore()
                    .getPropertyValue<AbstractEventStorageEngine>("storageEngine")
                    ?.readEvents(identifier, firstSequenceNumber)
                    ?: throw IllegalStateException("Unable to find AbstractEventStorageEngine in event store")

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> Any.getPropertyValue(fieldName: String): T? =
            ReflectionUtils.fieldsOf(this::class.java)
                    .firstOrNull { it.name == fieldName }
                    ?.let { ReflectionUtils.getMemberValue(it, this) as? T }
}
