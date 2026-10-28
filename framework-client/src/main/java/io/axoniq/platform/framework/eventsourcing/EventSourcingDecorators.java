/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.eventsourcing;

import io.axoniq.platform.framework.messaging.HandlerMetricsRegistry;
import io.axoniq.platform.framework.modelling.EntityMetricsRegistry;
import io.axoniq.platform.framework.workflow.WorkflowStatisticsCollector;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.DecoratorDefinition;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.EventStore;

/**
 * Holder of the actual decorator registration against {@code axon-eventsourcing} types. Kept separate from
 * {@link AxoniqPlatformEventsourcingConfigurerEnhancer} so that the enhancer class can be loaded even when
 * {@code axon-eventsourcing} is not on the classpath — this class is only touched after a {@code Class.forName}
 * probe confirms the module is present.
 */
final class EventSourcingDecorators {

    private EventSourcingDecorators() {
    }

    static void apply(ComponentRegistry registry) {
        registry.registerDecorator(
                DecoratorDefinition.forType(EventStorageEngine.class)
                                   .with((c, name, delegate) -> {
                                       HandlerMetricsRegistry metricsRegistry = c.getComponent(HandlerMetricsRegistry.class);
                                       EntityMetricsRegistry entityMetricsRegistry = c.getComponent(EntityMetricsRegistry.class);
                                       WorkflowStatisticsCollector workflowStatistics =
                                               c.getOptionalComponent(WorkflowStatisticsCollector.class).orElse(null);
                                       return new AxoniqPlatformEventStorageEngine(delegate, metricsRegistry, entityMetricsRegistry, workflowStatistics);
                                   }).order(Integer.MAX_VALUE));

        registry.registerDecorator(
                DecoratorDefinition.forType(EventStore.class)
                                   .with((c, name, delegate) -> {
                                       EntityMetricsRegistry entityMetricsRegistry = c.getComponent(EntityMetricsRegistry.class);
                                       return new AxoniqPlatformEventStore(delegate, entityMetricsRegistry);
                                   }).order(Integer.MAX_VALUE));
    }
}
