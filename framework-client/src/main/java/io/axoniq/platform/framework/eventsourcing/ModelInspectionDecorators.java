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

import io.axoniq.platform.framework.client.RSocketHandlerRegistrar;
import io.axoniq.platform.framework.modelling.AxoniqPlatformRepository;
import io.axoniq.platform.framework.modelling.EntityMetricsRegistry;
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.DecoratorDefinition;
import org.axonframework.common.lifecycle.Phase;
import org.axonframework.conversion.Converter;
import org.axonframework.conversion.GeneralConverter;
import org.axonframework.eventsourcing.CriteriaResolver;
import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.EventSourcingRepository;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.handler.EntityLifecycleHandler;
import org.axonframework.eventsourcing.handler.InitializingEntityEvolver;
import org.axonframework.eventsourcing.handler.SimpleEntityLifecycleHandler;
import org.axonframework.eventsourcing.handler.SnapshottingEntityLifecycleHandler;
import org.axonframework.eventsourcing.snapshot.api.SnapshotPolicy;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.modelling.EntityEvolver;
import org.axonframework.modelling.entity.EntityMetamodel;
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
        // The decorator rebuilds each event-sourced Repository with its entity evolver wrapped in
        // AxoniqPlatformEntityEvolver. We deliberately do NOT decorate the registered EntityMetamodel
        // (which is the evolver) — AnnotatedEventSourcedEntityModule casts the registered metamodel to
        // AnnotatedEntityMetamodel inside its EntityIdResolver builder, and a wrapper would make that
        // cast fail at startup. So the wrapping happens at the repository-build layer instead, leaving
        // the registered metamodel component untouched.
        //
        // The .onStart hook then registers the rebuilt repository instance with the responder so it
        // knows about this entity for the registered-entities query.
        registry.registerDecorator(DecoratorDefinition
                .forType(Repository.class)
                .with((config, name, delegate) -> installInspectionEvolver(config, name, delegate))
                .onStart(Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS, (configuration, component) -> {
                    configuration.getComponent(RSocketModelInspectionResponder.class)
                                 .registerRepository(component);
                    return CompletableFuture.completedFuture(null);
                }));
    }

    /**
     * Rebuilds the event-sourced {@code delegate} repository with its entity evolver wrapped in
     * {@link AxoniqPlatformEntityEvolver}, so inspection replay can fire per-event BEFORE/AFTER hooks.
     * Returns {@code delegate} unchanged for anything that isn't an event-sourced entity.
     *
     * <p>Why rebuild rather than swap a field: AF5.2's snapshotting refactor buries the evolver three
     * layers deep — {@link EventSourcingRepository} → {@link EntityLifecycleHandler} →
     * {@link InitializingEntityEvolver} → the {@code entityEvolver} (the entity's
     * {@link EntityMetamodel}). All those fields are {@code final}, and mutating {@code final} fields
     * reflectively is unsafe on JDK 21+. So we reconstruct the whole tree from the component graph,
     * mirroring {@code SimpleEventSourcedEntityModule#repository()} exactly — including the
     * Simple-vs-Snapshotting choice keyed on the presence of a {@link SnapshotPolicy} — so the rebuilt
     * repository preserves production behaviour (this same instance handles commands too), with only the
     * inspection wrapper added. The wrapper is a no-op unless the matching context resources are set.
     *
     * <p>The evolver we wrap is the {@link EntityMetamodel} instance we retrieve here; the <em>registered</em>
     * {@code EntityMetamodel} component is left untouched, so {@code AnnotatedEventSourcedEntityModule}'s
     * {@code (AnnotatedEntityMetamodel) getComponent(EntityMetamodel.class, ...)} cast still succeeds.
     *
     * <p>The decorator resolves siblings by {@code name}: the repository component is registered under
     * the entity name, and its {@code EntityMetamodel} / {@code CriteriaResolver} /
     * {@code EventSourcedEntityFactory} / {@code SnapshotPolicy} share that same name.
     *
     * <p>Logged-and-passthrough on failure: if the component graph shifts in a future AF release we lose
     * inspection hooks for the entity, but command handling is unaffected.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Repository<?, ?> installInspectionEvolver(Configuration config, String name, Repository<?, ?> delegate) {
        // Only event-sourced entities register an EntityMetamodel component under this name. Anything
        // else (e.g. state-based repositories) has no evolver to wrap and is left as-is.
        var metamodel = config.getOptionalComponent(EntityMetamodel.class, name).orElse(null);
        if (metamodel == null) {
            return delegate;
        }
        // CriteriaResolver is registered only by event-sourced entity modules — state-based
        // entities register an EntityMetamodel too, so the metamodel alone isn't enough.
        CriteriaResolver criteriaResolver = config.getOptionalComponent(CriteriaResolver.class, name).orElse(null);
        if (criteriaResolver == null) {
            return delegate;
        }
        try {
            Class idType = delegate.idType();
            Class entityType = delegate.entityType();

            EventStore eventStore = config.getComponent(EventStore.class);
            EventSourcedEntityFactory entityFactory = config.getComponent(EventSourcedEntityFactory.class, name);
            SnapshotPolicy snapshotPolicy = config.getOptionalComponent(SnapshotPolicy.class, name).orElse(null);

            // The entity's EntityMetamodel is its EntityEvolver — wrap that, then rebuild the
            // InitializingEntityEvolver / lifecycle handler / repository around it.
            EntityEvolver wrappedEvolver = new AxoniqPlatformEntityEvolver((EntityEvolver) metamodel);
            InitializingEntityEvolver initializingEvolver = new InitializingEntityEvolver(entityFactory, wrappedEvolver);

            EntityLifecycleHandler lifecycleHandler;
            if (snapshotPolicy == null) {
                lifecycleHandler = new SimpleEntityLifecycleHandler(eventStore, criteriaResolver, initializingEvolver);
            } else {
                // Snapshotting entity: mirror the extra dependencies SimpleEventSourcedEntityModule resolves.
                Converter converter = config.getOptionalComponent(GeneralConverter.class)
                        .orElseThrow(() -> new IllegalStateException("A Converter must be configured to use snapshotting."));
                SnapshotStore snapshotStore = config.getOptionalComponent(SnapshotStore.class)
                        .orElseThrow(() -> new IllegalStateException("A SnapshotStore must be configured to use snapshotting."));
                MessageType messageType = config.getOptionalComponent(MessageTypeResolver.class)
                        .flatMap(resolver -> resolver.resolve(entityType))
                        .orElseThrow(() -> new IllegalStateException(
                                "A MessageTypeResolver capable of resolving " + entityType + " must be configured to use snapshotting."));
                lifecycleHandler = new SnapshottingEntityLifecycleHandler(
                        eventStore, criteriaResolver, initializingEvolver,
                        snapshotPolicy, messageType, converter, entityType, snapshotStore);
            }

            EventSourcingRepository rebuilt = new EventSourcingRepository(idType, entityType, lifecycleHandler);

            // Whatever we return replaces the Repository component (command handling uses it too), so
            // preserve the metrics wrapper the modelling layer adds at Integer.MIN_VALUE — directly
            // around the ESR — by re-wrapping the rebuilt repository in a fresh one.
            if (delegate instanceof AxoniqPlatformRepository) {
                return new AxoniqPlatformRepository(rebuilt, config.getComponent(EntityMetricsRegistry.class));
            }
            return rebuilt;
        } catch (Exception e) {
            logger.warn("[ModelInspection] Could not reconstruct EventSourcingRepository for [{}] — " +
                                "inspection hooks will be unavailable for this entity, but command handling is unaffected: {}",
                        delegate.entityType().getName(), e.getMessage());
            return delegate;
        }
    }
}
