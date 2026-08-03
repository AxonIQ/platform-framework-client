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

package io.axoniq.platform.framework.eventsourcing

import org.axonframework.common.infra.ComponentDescriptor
import org.axonframework.messaging.commandhandling.CommandMessage
import org.axonframework.messaging.commandhandling.CommandResultMessage
import org.axonframework.messaging.core.MessageStream
import org.axonframework.messaging.core.QualifiedName
import org.axonframework.messaging.core.unitofwork.ProcessingContext
import org.axonframework.messaging.eventhandling.EventMessage
import org.axonframework.modelling.entity.EntityMetamodel

/**
 * Decorates an [EntityMetamodel] so inspection replay can fire per-event BEFORE/AFTER hooks through a wrapped
 * [AxoniqPlatformEntityEvolver], while faithfully delegating everything else (command handling, supported commands,
 * describe) to the wrapped metamodel.
 *
 * Since AF5.3 the `EntityMetamodel` component is freely decoratable — the id-resolver no longer casts it to the
 * concrete `AnnotatedEntityMetamodel` — so this plain delegating wrapper is all that is needed; no repository
 * reconstruction. The evolve hooks are no-ops unless the matching [AxoniqPlatformEntityEvolver] context resources are
 * present, so command handling and normal event sourcing are unaffected.
 */
class InspectionEntityMetamodel<E : Any>(
        private val delegate: EntityMetamodel<E>,
) : EntityMetamodel<E> {

    private val hookEvolver = AxoniqPlatformEntityEvolver(delegate)

    override fun evolve(entity: E, event: EventMessage, context: ProcessingContext): E =
            hookEvolver.evolve(entity, event, context)

    override fun entityType(): Class<E> = delegate.entityType()

    override fun handleCreate(message: CommandMessage, context: ProcessingContext): MessageStream.Single<CommandResultMessage> =
            delegate.handleCreate(message, context)

    override fun handleInstance(message: CommandMessage, entity: E, context: ProcessingContext): MessageStream.Single<CommandResultMessage> =
            delegate.handleInstance(message, entity, context)

    override fun supportedCreationalCommands(): Set<QualifiedName> = delegate.supportedCreationalCommands()

    override fun supportedInstanceCommands(): Set<QualifiedName> = delegate.supportedInstanceCommands()

    override fun supportedCommands(): Set<QualifiedName> = delegate.supportedCommands()

    override fun describeTo(descriptor: ComponentDescriptor) {
        descriptor.describeWrapperOf(delegate)
    }
}
