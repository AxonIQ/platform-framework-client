/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.modelling;

import io.axoniq.platform.framework.UtilsKt;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.DecoratorDefinition;
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.repository.Repository;

/**
 * Holder of the actual decorator registrations against {@code axon-modelling} types. Kept separate from
 * {@link AxoniqPlatformModellingConfigurationEnhancer} so that the enhancer class can be loaded even when
 * {@code axon-modelling} is not on the classpath — this class is only touched after a {@code Class.forName}
 * probe confirms the module is present.
 */
final class ModellingDecorators {

    private ModellingDecorators() {
    }

    static void apply(ComponentRegistry registry) {
        registry.registerDecorator(DecoratorDefinition.forType(StateManager.class)
                                                      .with((cc, name, delegate) -> {
                                                          if(delegate instanceof AxoniqPlatformStateManager) {
                                                              return delegate;
                                                          }
                                                          return new AxoniqPlatformStateManager(delegate, cc.getComponent(EntityMetricsRegistry.class));
                                                      })
                                                      .order(Integer.MAX_VALUE));

        UtilsKt.doOnSubModules(registry, (componentRegistry, module) -> {
            componentRegistry
                    .registerDecorator(DecoratorDefinition.forType(Repository.class)
                                                          .with((cc, name, delegate) ->
                                                                        delegate instanceof AxoniqPlatformRepository<?,?> ? delegate :
                                                                        new AxoniqPlatformRepository<>(delegate, cc.getComponent(EntityMetricsRegistry.class)))
                                                          .order(Integer.MIN_VALUE))
                    .registerDecorator(DecoratorDefinition.forType(StateManager.class)
                                                          .with((cc, name, delegate) ->
                                                                  delegate instanceof AxoniqPlatformStateManager ?  delegate :
                                                                        new AxoniqPlatformStateManager(delegate, cc.getComponent(EntityMetricsRegistry.class)))
                                                          .order(Integer.MAX_VALUE));
            return null;
        }, true);
    }
}
