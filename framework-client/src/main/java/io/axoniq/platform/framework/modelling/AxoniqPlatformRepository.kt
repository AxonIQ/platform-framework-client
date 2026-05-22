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

package io.axoniq.platform.framework.modelling

import io.axoniq.platform.framework.api.metrics.EntityStatisticIdentifier
import io.axoniq.platform.framework.api.metrics.LoadModelMetric
import io.axoniq.platform.framework.messaging.HandlerMeasurement
import io.axoniq.platform.framework.messaging.toInformation
import org.axonframework.common.infra.ComponentDescriptor
import org.axonframework.messaging.core.unitofwork.ProcessingContext
import org.axonframework.modelling.repository.ManagedEntity
import org.axonframework.modelling.repository.Repository
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

class AxoniqPlatformRepository<ID : Any, E : Any>(
        private val delegate: Repository<ID, E>,
        private val entityMetricsRegistry: EntityMetricsRegistry,
) : Repository.LifecycleManagement<ID, E> {
    override fun attach(entity: ManagedEntity<ID, E>, processingContext: ProcessingContext): ManagedEntity<ID, E> {
        val lfcm = delegate as Repository.LifecycleManagement<ID, E>
        return lfcm.attach(entity, processingContext)
    }

    override fun entityType(): Class<E> {
        return delegate.entityType()
    }

    override fun idType(): Class<ID> {
        return delegate.idType()
    }

    override fun load(identifier: ID, processingContext: ProcessingContext): CompletableFuture<ManagedEntity<ID, E>> {
        val entityId = entityIdentifierFor(identifier, processingContext)
        processingContext.putResource(CurrentEntityContext.RESOURCE_KEY, entityId)
        CurrentEntityContext.setOnThread(entityId)
        val startTime = System.nanoTime()
        return try {
            delegate.load(identifier, processingContext)
        } finally {
            CurrentEntityContext.clearOnThread()
        }.whenComplete { _, error ->
            val duration = System.nanoTime() - startTime
            entityMetricsRegistry.registerLoad(entityId, duration, success = error == null)
            HandlerMeasurement.onContext(processingContext) {
                it.registerMetricValue(LoadModelMetric(identifier = "load:${entityType().simpleName}"), duration)
            }
        }
    }

    override fun loadOrCreate(identifier: ID, processingContext: ProcessingContext): CompletableFuture<ManagedEntity<ID, E>> {
        val entityId = entityIdentifierFor(identifier, processingContext)
        // Shared counter the event-store wrapper bumps for each sourced event. After loadOrCreate
        // completes, a zero count means no prior events existed → the framework created a fresh
        // entity. Counting once here (rather than registering creation unconditionally) keeps the
        // Creations/min rate aligned with reality — without this, every redeem/update on an existing
        // event-sourced entity would be reported as a creation too.
        val sourcedCounter = AtomicLong(0)
        processingContext.putResource(CurrentEntityContext.RESOURCE_KEY, entityId)
        CurrentEntityContext.setOnThread(entityId)
        CurrentEntityContext.setSourcedEventsCounterOnThread(sourcedCounter)
        val startTime = System.nanoTime()
        return try {
            delegate.loadOrCreate(identifier, processingContext)
        } finally {
            CurrentEntityContext.clearOnThread()
            CurrentEntityContext.clearSourcedEventsCounterOnThread()
        }.whenComplete { _, error ->
            val duration = System.nanoTime() - startTime
            entityMetricsRegistry.registerLoad(entityId, duration, success = error == null)
            if (error == null && sourcedCounter.get() == 0L) {
                entityMetricsRegistry.registerCreation(entityId)
            }
            HandlerMeasurement.onContext(processingContext) {
                it.registerMetricValue(LoadModelMetric(identifier = "load:${entityType().simpleName}"), duration)
            }
        }
    }

    override fun persist(identifier: ID, entity: E, processingContext: ProcessingContext): ManagedEntity<ID, E> {
        return delegate.persist(identifier, entity, processingContext)
    }

    override fun describeTo(descriptor: ComponentDescriptor) {
        descriptor.describeWrapperOf(delegate)
    }

    private fun entityIdentifierFor(identifier: ID, processingContext: ProcessingContext): EntityStatisticIdentifier {
        val messageInfo = HandlerMeasurement.fromContext(processingContext)?.message?.toInformation()
        return EntityStatisticIdentifier(
                entityName = entityType().simpleName,
                entityId = identifier.toString(),
                messageType = messageInfo?.type ?: UNKNOWN,
                messageName = messageInfo?.name ?: UNKNOWN,
        )
    }

    companion object {
        private const val UNKNOWN = "Unknown"
    }
}
