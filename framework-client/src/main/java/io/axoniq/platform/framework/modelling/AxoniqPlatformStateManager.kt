/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.modelling

import org.axonframework.common.infra.ComponentDescriptor
import org.axonframework.common.infra.DescribableComponent
import org.axonframework.messaging.core.unitofwork.ProcessingContext
import org.axonframework.modelling.StateManager
import org.axonframework.modelling.repository.ManagedEntity
import org.axonframework.modelling.repository.Repository
import java.util.concurrent.CompletableFuture

class AxoniqPlatformStateManager(
        private val delegate: StateManager,
        private val entityMetricsRegistry: EntityMetricsRegistry,
): StateManager, DescribableComponent {
    override fun <ID: Any, T: Any> register(repository: Repository<ID, T>): StateManager {
        if(repository is AxoniqPlatformRepository<ID, T>) {
            delegate.register<ID, T>(repository)
            return this
        }
        delegate.register<ID, T>(AxoniqPlatformRepository(repository, entityMetricsRegistry))
        return this
    }

    override fun <ID : Any, T : Any> loadManagedEntity(type: Class<T>, id: ID, context: ProcessingContext): CompletableFuture<ManagedEntity<ID, T>> {
        return delegate.loadManagedEntity(type, id, context)
    }

    override fun registeredEntities(): Set<Class<*>> {
        return delegate.registeredEntities()
    }

    override fun registeredIdsFor(entityType: Class<*>): Set<Class<*>> {
        return delegate.registeredIdsFor(entityType)
    }

    override fun <ID : Any, T : Any> repository(entityType: Class<T>, idType: Class<ID>): Repository<ID, T>? {
        return delegate.repository(entityType, idType)
    }

    override fun describeTo(descriptor: ComponentDescriptor) {
        descriptor.describeWrapperOf(delegate)
    }

}