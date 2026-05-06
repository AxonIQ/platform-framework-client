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

import org.axonframework.messaging.core.Context
import org.axonframework.messaging.core.unitofwork.ProcessingContext
import org.axonframework.messaging.eventhandling.EventMessage
import org.axonframework.modelling.EntityEvolver
import java.util.function.BiConsumer

/**
 * Wraps the underlying [EntityEvolver] of an [org.axonframework.eventsourcing.EventSourcingRepository]
 * so that inspection-time replay can fire BEFORE/AFTER hooks per event without reimplementing
 * AF5's dispatch.
 *
 * The hooks are no-ops unless the matching [Context.ResourceKey] resources are present on the
 * [ProcessingContext], so command handling and normal event sourcing go through unchanged.
 *
 * Constructed by [AxoniqPlatformModelInspectionEnhancer], which detects the inner
 * [org.axonframework.eventsourcing.EventSourcingRepository] in the decorator chain and reconstructs
 * it with this wrapper substituted for its `entityEvolver` argument.
 */
class AxoniqPlatformEntityEvolver<E : Any>(
        private val delegate: EntityEvolver<E>,
) : EntityEvolver<E> {

    companion object {
        /** Called before [delegate.evolve]. Receives the event and the pre-evolve entity state. */
        val BEFORE_CONSUMER: Context.ResourceKey<BiConsumer<EventMessage, Any?>> =
                Context.ResourceKey.withLabel("AxoniqPlatformEntityEvolver.BEFORE_CONSUMER")

        /** Called after [delegate.evolve]. Receives the event and the post-evolve entity state. */
        val AFTER_CONSUMER: Context.ResourceKey<BiConsumer<EventMessage, Any?>> =
                Context.ResourceKey.withLabel("AxoniqPlatformEntityEvolver.AFTER_CONSUMER")

        /**
         * Zero-based event index — when present, [evolve] returns the current entity unchanged
         * once it has applied this many events. Lets inspection reconstruct state up to a given
         * sequence without doing the bookkeeping outside the framework.
         */
        val MAX_INDEX: Context.ResourceKey<Long> =
                Context.ResourceKey.withLabel("AxoniqPlatformEntityEvolver.MAX_INDEX")

        /** Internal counter advanced by [evolve] when [MAX_INDEX] is set. */
        val INDEX_COUNTER: Context.ResourceKey<LongCounter> =
                Context.ResourceKey.withLabel("AxoniqPlatformEntityEvolver.INDEX_COUNTER")
    }

    /** Tiny mutable holder so we can advance the index without re-putting a Long resource each call. */
    class LongCounter(var value: Long = 0)

    override fun evolve(entity: E, event: EventMessage, context: ProcessingContext): E {
        val maxIndex = context.getResource(MAX_INDEX)
        if (maxIndex != null) {
            val counter = context.computeResourceIfAbsent(INDEX_COUNTER) { LongCounter() }
            if (counter.value > maxIndex) {
                return entity
            }
            counter.value++
        }
        context.getResource(BEFORE_CONSUMER)?.accept(event, entity)
        val result = delegate.evolve(entity, event, context)
        context.getResource(AFTER_CONSUMER)?.accept(event, result)
        return result
    }
}
