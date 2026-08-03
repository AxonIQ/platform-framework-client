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
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.DecoratorDefinition;
import org.axonframework.common.lifecycle.Phase;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.modelling.entity.EntityMetamodel;
import org.axonframework.modelling.repository.Repository;

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
 * single decorator at the top covers every event-sourced entity in the application — top-level or
 * arbitrarily nested.</p>
 */
final class ModelInspectionDecorators {

    private ModelInspectionDecorators() {
    }

    static void apply(ComponentRegistry registry) {
        if (!registry.hasComponent(EventStorageEngine.class)) {
            return;
        }
        // The enhancer pipeline can fire multiple times against the same registry as nested
        // module configurations build. Idempotency guard: once the responder is in place, the
        // decorators and their lifecycle hook are already registered, so re-running would
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

        // Wrap every entity's EntityMetamodel (which is the entity's EntityEvolver) in
        // InspectionEntityMetamodel, so inspection replay can fire per-event BEFORE/AFTER hooks.
        //
        // Since AF5.3 the EntityMetamodel component is freely decoratable: AnnotatedEventSourcedEntityModule
        // no longer casts it to the concrete AnnotatedEntityMetamodel (it sources representation info from
        // its own cached metamodel and wraps the id-resolver in a RepresentationConvertingEntityIdResolver).
        // So a plain delegating EntityMetamodel wrapper is all we need — no repository reconstruction.
        registry.registerDecorator(DecoratorDefinition
                .forType(EntityMetamodel.class)
                .with((config, name, delegate) -> delegate instanceof InspectionEntityMetamodel
                        ? delegate
                        : new InspectionEntityMetamodel<>(delegate)));

        // A single Repository decorator at the top registers every event-sourced Repository with the
        // responder (top-level or nested — AF5's nested modules share the same component registry) so it
        // can drive inspection loads. The repository itself is returned untouched; the evolve hooks live
        // on the EntityMetamodel decorator above.
        registry.registerDecorator(DecoratorDefinition
                .forType(Repository.class)
                .with((config, name, delegate) -> delegate)
                .onStart(Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS, (configuration, component) -> {
                    configuration.getComponent(RSocketModelInspectionResponder.class)
                                 .registerRepository(component);
                    return CompletableFuture.completedFuture(null);
                }));
    }
}
