/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.modelling

import io.axoniq.platform.framework.api.metrics.EntityStatisticIdentifier
import io.axoniq.platform.framework.api.metrics.LoadModelMetric
import io.axoniq.platform.framework.messaging.HandlerMeasurement
import org.axonframework.common.infra.ComponentDescriptor
import org.axonframework.messaging.core.unitofwork.ProcessingContext
import org.axonframework.modelling.repository.ManagedEntity
import org.axonframework.modelling.repository.Repository
import java.util.concurrent.CompletableFuture

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
        val entityId = entityIdentifier()
        processingContext.putResource(CurrentEntityContext.RESOURCE_KEY, entityId)
        val startTime = System.nanoTime()
        return delegate.load(identifier, processingContext)
                .whenComplete { _, error ->
                    val duration = System.nanoTime() - startTime
                    entityMetricsRegistry.registerLoad(entityId, duration, success = error == null)
                    HandlerMeasurement.onContext(processingContext) {
                        it.registerMetricValue(LoadModelMetric(identifier = "load:${entityType().simpleName}"), duration)
                    }
                }
    }

    override fun loadOrCreate(identifier: ID, processingContext: ProcessingContext): CompletableFuture<ManagedEntity<ID, E>> {
        val entityId = entityIdentifier()
        processingContext.putResource(CurrentEntityContext.RESOURCE_KEY, entityId)
        val startTime = System.nanoTime()
        return delegate.loadOrCreate(identifier, processingContext).whenComplete { _, error ->
            val duration = System.nanoTime() - startTime
            entityMetricsRegistry.registerLoad(entityId, duration, success = error == null)
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

    private fun entityIdentifier(): EntityStatisticIdentifier {
        return EntityStatisticIdentifier(entityName = entityType().simpleName)
    }
}
