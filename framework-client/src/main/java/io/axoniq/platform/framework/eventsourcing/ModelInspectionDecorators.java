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

package io.axoniq.platform.framework.eventsourcing;

import io.axoniq.platform.framework.ReflectionKt;
import io.axoniq.platform.framework.client.RSocketHandlerRegistrar;
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.DecoratorDefinition;
import org.axonframework.common.lifecycle.Phase;
import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.EventSourcingRepository;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.handler.SourcingHandler;
import org.axonframework.modelling.EntityEvolver;
import org.axonframework.modelling.repository.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Holds the actual decorator and component wiring for model inspection. Kept separate from
 * {@link AxoniqPlatformModelInspectionEnhancer} so the enhancer class can be loaded even when
 * {@code axon-eventsourcing} is not on the classpath — this class is only touched after a
 * {@code Class.forName} probe confirms the module is present.
 *
 * <p>We do <em>not</em> walk submodules: AF5's nested module structure shares a single
 * {@link org.axonframework.common.configuration.DefaultComponentRegistry} (each {@code BaseModule}
 * resolves the parent's {@code ComponentRegistry} component instead of creating its own), so a
 * single {@code Repository} decorator at the top covers every event-sourced entity in the
 * application — top-level or arbitrarily nested.</p>
 */
final class ModelInspectionDecorators {

    private static final Logger logger = LoggerFactory.getLogger(ModelInspectionDecorators.class);

    private ModelInspectionDecorators() {
    }

    static void apply(ComponentRegistry registry) {
        if (!registry.hasComponent(EventStorageEngine.class)) {
            return;
        }
        // The enhancer pipeline can fire multiple times against the same registry as nested
        // module configurations build. Idempotency guard: once the responder is in place, the
        // decorator and its lifecycle hook are already registered, so re-running would
        // duplicate the wrapping and double-evolve every event.
        if (registry.hasComponent(RSocketModelInspectionResponder.class)) {
            return;
        }

        registry.registerComponent(ComponentDefinition
                                           .ofType(RSocketModelInspectionResponder.class)
                                           .withBuilder(c -> new RSocketModelInspectionResponder(
                                                   c.getComponent(EventStorageEngine.class),
                                                   c.getComponent(RSocketHandlerRegistrar.class),
                                                   c))
                                           .onStart(Phase.EXTERNAL_CONNECTIONS, RSocketModelInspectionResponder::start));

        // Single decorator at the top covers every Repository registered in the application,
        // top-level or nested — AF5's nested modules share the same component registry.
        //
        // The decorator reconstructs the underlying EventSourcingRepository with entityEvolver
        // wrapped in AxoniqPlatformEntityEvolver. We deliberately do NOT decorate the registered
        // EntityMetamodel — AnnotatedEventSourcedEntityModule casts the registered metamodel to
        // AnnotatedEntityMetamodel inside its EntityIdResolver builder, and a wrapper would
        // make that cast fail at startup.
        //
        // The .onStart hook then registers the rebuilt repository with the responder so it
        // knows about this entity for the registered-entities query.
        registry.registerDecorator(DecoratorDefinition
                .forType(Repository.class)
                .with((config, name, delegate) -> rebuildIfEventSourcingRepository(delegate))
                .onStart(Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS, (configuration, component) -> {
                    configuration.getComponent(RSocketModelInspectionResponder.class)
                                 .registerRepository(component);
                    return CompletableFuture.completedFuture(null);
                }));
    }

    /**
     * Walks the wrapper chain from {@code delegate} downward to find an
     * {@link EventSourcingRepository}, reconstructs that ESR with {@code entityEvolver} wrapped
     * in {@link AxoniqPlatformEntityEvolver}, and swaps the wrapping component's {@code delegate}
     * field to point at the new ESR. The outer wrapper(s) are kept intact so any platform-side
     * decoration (e.g. {@code AxoniqPlatformRepository} for metrics) still applies.
     *
     * <p>Why peel rather than match {@code instanceof EventSourcingRepository} on the input:
     * by the time this decorator runs, lower-order decorators (notably the metrics-adding
     * {@code AxoniqPlatformRepository} from the modelling layer at {@code Integer.MIN_VALUE})
     * have already wrapped the ESR. Matching directly would miss every real configuration.</p>
     *
     * <p>Logged-and-passthrough on reflection failure: if the field layout shifts in a future
     * AF release we don't want to break command handling, just lose inspection hooks.</p>
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Repository<?, ?> rebuildIfEventSourcingRepository(Repository<?, ?> delegate) {
        Object current = delegate;
        Object parent = null;
        while (current != null && !(current instanceof EventSourcingRepository<?, ?>)) {
            parent = current;
            current = ReflectionKt.getPropertyValue(current, "delegate");
        }
        if (!(current instanceof EventSourcingRepository<?, ?> esr)) {
            return delegate;
        }
        try {
            Class<?> idType = ReflectionKt.getPropertyValue(esr, "idType");
            Class<?> entityType = ReflectionKt.getPropertyValue(esr, "entityType");
            EventStore eventStore = ReflectionKt.getPropertyValue(esr, "eventStore");
            EventSourcedEntityFactory factory = ReflectionKt.getPropertyValue(esr, "entityFactory");
            EntityEvolver evolver = ReflectionKt.getPropertyValue(esr, "entityEvolver");
            SourcingHandler sourcingHandler = ReflectionKt.getPropertyValue(esr, "sourcingHandler");

            EntityEvolver wrappedEvolver = new AxoniqPlatformEntityEvolver(evolver);
            EventSourcingRepository rebuilt = new EventSourcingRepository(
                    idType,
                    entityType,
                    eventStore,
                    factory,
                    wrappedEvolver,
                    sourcingHandler
            );
            if (parent == null) {
                // No wrapper between us and the ESR — return the rebuilt ESR directly.
                return rebuilt;
            }
            // Swap the parent wrapper's delegate to point at the rebuilt ESR. Keeps any outer
            // wrappers (metrics, etc.) intact, just rewires the bottom of the chain.
            ReflectionKt.setPropertyValue(parent, "delegate", rebuilt);
            return delegate;
        } catch (Exception e) {
            logger.warn("[ModelInspection] Could not reconstruct EventSourcingRepository for [{}] — " +
                                "inspection hooks will be unavailable for this entity, but command handling is unaffected: {}",
                        esr.entityType().getName(), e.getMessage());
            return delegate;
        }
    }
}
