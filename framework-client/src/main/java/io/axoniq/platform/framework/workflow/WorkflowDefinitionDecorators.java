/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.workflow;

import io.axoniq.framework.workflow.runtime.execution.WorkflowConfigurationRegistry;
import io.axoniq.platform.framework.client.RSocketHandlerRegistrar;
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.DecoratorDefinition;
import org.axonframework.common.lifecycle.Phase;

import java.util.concurrent.CompletableFuture;

/**
 * Registers the {@link WorkflowDefinitionsResponder}, the {@link WorkflowExecutionsResponder} and the
 * {@link WorkflowMetricsRegistry} that feeds the statistics report, and hands the definitions responder every
 * workflow module's {@link WorkflowConfigurationRegistry}. Each workflow module registers its registry under its
 * own name; a single decorator by type reaches all of them, because nested modules share the application's component
 * registry.
 */
final class WorkflowDefinitionDecorators {

    private WorkflowDefinitionDecorators() {
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void apply(ComponentRegistry registry) {
        // Enhancers can run again as nested module configurations build; the responder marks this as done.
        if (registry.hasComponent(WorkflowDefinitionsResponder.class)) {
            return;
        }
        registry.registerComponent(ComponentDefinition
                                           .ofType(WorkflowDefinitionsResponder.class)
                                           .withBuilder(c -> new WorkflowDefinitionsResponder(
                                                   c.getComponent(RSocketHandlerRegistrar.class)))
                                           .onStart(Phase.EXTERNAL_CONNECTIONS, WorkflowDefinitionsResponder::start));
        registry.registerComponent(ComponentDefinition
                                           .ofType(WorkflowExecutionsResponder.class)
                                           .withBuilder(c -> new WorkflowExecutionsResponder(
                                                   c.getComponent(RSocketHandlerRegistrar.class), c))
                                           .onStart(Phase.EXTERNAL_CONNECTIONS, WorkflowExecutionsResponder::start));
        registry.registerComponent(ComponentDefinition
                                           .ofType(WorkflowStatisticsCollector.class)
                                           .withBuilder(WorkflowMetricsRegistry::new));
        registry.registerDecorator(DecoratorDefinition
                .forType((Class) WorkflowConfigurationRegistry.class)
                .with((config, name, delegate) -> delegate)
                .onStart(Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS, (configuration, component) -> {
                    configuration.getComponent(WorkflowDefinitionsResponder.class)
                                 .registerRegistry((WorkflowConfigurationRegistry<?>) component);
                    return CompletableFuture.completedFuture(null);
                }));
    }
}
