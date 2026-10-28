/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.workflow;

import io.axoniq.platform.framework.AxoniqPlatformConfigurerEnhancer;
import io.axoniq.platform.framework.client.RSocketHandlerRegistrar;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service-loaded enhancer that wires the {@link WorkflowDefinitionsResponder} when the platform client is connected
 * and the workflow engine is on the classpath. Free of workflow engine types, so it loads without the engine; the
 * wiring lives in {@link WorkflowDefinitionDecorators}, touched only after the classpath probe succeeds.
 */
public class AxoniqPlatformWorkflowEnhancer implements ConfigurationEnhancer {

    private static final Logger logger = LoggerFactory.getLogger(AxoniqPlatformWorkflowEnhancer.class);
    private static final String WORKFLOW_ENGINE_PROBE_CLASS =
            "io.axoniq.framework.workflow.runtime.execution.WorkflowConfigurationRegistry";

    @Override
    public void enhance(ComponentRegistry registry) {
        if (!registry.hasComponent(RSocketHandlerRegistrar.class)) {
            return;
        }
        if (!isClasspathAvailable()) {
            logger.debug("Workflow engine not on classpath; skipping workflow definition wiring.");
            return;
        }
        WorkflowDefinitionDecorators.apply(registry);
    }

    @Override
    public int order() {
        return AxoniqPlatformConfigurerEnhancer.PLATFORM_ENHANCER_ORDER + 1;
    }

    private static boolean isClasspathAvailable() {
        try {
            Class.forName(WORKFLOW_ENGINE_PROBE_CLASS, false, AxoniqPlatformWorkflowEnhancer.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
