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

import org.axonframework.common.infra.ComponentDescriptor
import org.axonframework.common.infra.DescribableComponent
import org.axonframework.messaging.core.unitofwork.ProcessingContext
import org.axonframework.modelling.StateManager
import org.axonframework.modelling.repository.ManagedEntity
import org.axonframework.modelling.repository.Repository
import java.util.concurrent.CompletableFuture

/**
 * [DescribableComponent] is implemented explicitly rather than inherited: since AF5.3-SNAPSHOT
 * `StateManager` extends it, but the currently targeted 5.3.0-RC1 does not. Declaring it here keeps
 * [describeTo] a valid `override` against both, so the nightly compatibility build against the
 * rolling snapshot stays green without breaking the pinned build.
 */
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

    override fun <ID : Any, T : Any> repository(entityType: Class<T>, idType: Class<ID>): Repository<ID, T> {
        return delegate.repository(entityType, idType)
    }

    override fun describeTo(descriptor: ComponentDescriptor) {
        descriptor.describeWrapperOf(delegate)
    }

}