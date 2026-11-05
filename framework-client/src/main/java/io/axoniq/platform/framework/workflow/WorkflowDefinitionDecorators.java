/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.workflow;

import io.axoniq.framework.workflow.runtime.execution.WorkflowConfigurationRegistry;
import io.axoniq.platform.framework.client.AxoniqConsoleRSocketClient;
import io.axoniq.platform.framework.client.PlatformClientConnectionService;
import io.axoniq.platform.framework.client.RSocketHandlerRegistrar;
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.DecoratorDefinition;
import org.axonframework.common.lifecycle.Phase;

import java.util.concurrent.CompletableFuture;

/**
 * Registers the {@link WorkflowDefinitionsReporter}, the {@link WorkflowExecutionsProjection} with the
 * {@link WorkflowExecutionsResponder} that lists it, and the {@link WorkflowMetricsRegistry} that feeds the statistics
 * report, and hands the reporter every workflow module's {@link WorkflowConfigurationRegistry}. Each workflow module
 * registers its registry under its own name; a single decorator by type reaches all of them, because nested modules
 * share the application's component registry.
 */
final class WorkflowDefinitionDecorators {

    private WorkflowDefinitionDecorators() {
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void apply(ComponentRegistry registry) {
        // Enhancers can run again as nested module configurations build; the reporter marks this as done.
        if (registry.hasComponent(WorkflowDefinitionsReporter.class)) {
            return;
        }
        registry.registerComponent(ComponentDefinition
                                           .ofType(WorkflowDefinitionsReporter.class)
                                           .withBuilder(c -> new WorkflowDefinitionsReporter(
                                                   c.getComponent(AxoniqConsoleRSocketClient.class),
                                                   c.getComponent(PlatformClientConnectionService.class)))
                                           .onStart(Phase.EXTERNAL_CONNECTIONS, WorkflowDefinitionsReporter::start));
        registry.registerComponent(ComponentDefinition
                                           .ofType(WorkflowExecutionsProjection.class)
                                           .withBuilder(c -> {
                                               WorkflowDefinitionsReporter reporter = c.getComponent(WorkflowDefinitionsReporter.class);
                                               return new WorkflowExecutionsProjection(c, null, null, reporter::definitionNames);
                                           }));
        registry.registerComponent(ComponentDefinition
                                           .ofType(WorkflowExecutionsResponder.class)
                                           .withBuilder(c -> new WorkflowExecutionsResponder(
                                                   c.getComponent(RSocketHandlerRegistrar.class), c,
                                                   c.getComponent(WorkflowExecutionsProjection.class)))
                                           .onStart(Phase.EXTERNAL_CONNECTIONS, WorkflowExecutionsResponder::start));
        registry.registerComponent(ComponentDefinition
                                           .ofType(WorkflowStatisticsCollector.class)
                                           .withBuilder(c -> new WorkflowMetricsRegistry(
                                                   c, c.getComponent(WorkflowExecutionsProjection.class))));
        registry.registerDecorator(DecoratorDefinition
                .forType((Class) WorkflowConfigurationRegistry.class)
                .with((config, name, delegate) -> delegate)
                .onStart(Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS, (configuration, component) -> {
                    configuration.getComponent(WorkflowDefinitionsReporter.class)
                                 .registerRegistry((WorkflowConfigurationRegistry<?>) component);
                    return CompletableFuture.completedFuture(null);
                }));
    }
}
