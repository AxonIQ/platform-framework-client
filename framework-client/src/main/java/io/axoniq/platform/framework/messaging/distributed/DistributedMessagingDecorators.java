/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.messaging.distributed;

import io.axoniq.framework.messaging.commandhandling.distributed.DistributedCommandBusConfiguration;
import io.axoniq.framework.messaging.queryhandling.distributed.DistributedQueryBusConfiguration;
import io.axoniq.platform.framework.application.ApplicationMetricRegistry;
import io.axoniq.platform.framework.application.BusType;
import io.axoniq.platform.framework.application.MeasuringExecutorServiceDecorator;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.DecoratorDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holder of the actual decorator registrations against {@code axoniq-distributed-messaging} types. Kept separate from
 * {@link AxoniqPlatformDistributedMessagingConfigurerEnhancer} so that the enhancer class can be loaded even when
 * {@code axoniq-distributed-messaging} is not on the classpath — this class is only touched after a
 * {@code Class.forName} probe confirms the module is present.
 */
final class DistributedMessagingDecorators {

    private static final Logger LOGGER = LoggerFactory.getLogger(DistributedMessagingDecorators.class);

    private DistributedMessagingDecorators() {
    }

    static void apply(ComponentRegistry registry) {
        registry.registerDecorator(DecoratorDefinition.forType(DistributedCommandBusConfiguration.class)
                                                      .with((cc, name, delegate) ->
                                                                    new DistributedCommandBusConfiguration(
                                                                            delegate.loadFactor(),
                                                                            delegate.commandThreads(),
                                                                            (config, queue) -> {
                                                                                var built = delegate.executorServiceFactory()
                                                                                                    .createExecutorService(
                                                                                                            config,
                                                                                                            queue);
                                                                                return new MeasuringExecutorServiceDecorator(
                                                                                        BusType.COMMAND,
                                                                                        built,
                                                                                        cc.getComponent(
                                                                                                ApplicationMetricRegistry.class));
                                                                            })));
        registry.registerDecorator(DecoratorDefinition.forType(DistributedQueryBusConfiguration.class)
                                                      .with((cc, name, delegate) -> new DistributedQueryBusConfiguration(
                                                              delegate.queryThreads(),
                                                              delegate.queryQueueCapacity(),
                                                              (config, queue) -> {
                                                                  var built = delegate.executorServiceFactory()
                                                                                      .createExecutorService(config, queue);
                                                                  return new MeasuringExecutorServiceDecorator(
                                                                          BusType.QUERY,
                                                                          built,
                                                                          cc.getComponent(ApplicationMetricRegistry.class));
                                                              },
                                                              delegate.preferLocalQueryHandler()
                                                      )));
    }
}
