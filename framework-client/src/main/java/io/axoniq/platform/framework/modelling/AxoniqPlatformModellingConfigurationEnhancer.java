/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.modelling;

import io.axoniq.platform.framework.AxoniqPlatformConfiguration;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.axoniq.platform.framework.AxoniqPlatformConfigurerEnhancer.PLATFORM_ENHANCER_ORDER;

/**
 * Service-loaded enhancer that decorates Axon Framework modelling components. Kept free of direct
 * references to {@code axon-modelling} types so the class can be loaded even when modelling is absent
 * from the classpath; the decorator wiring lives in {@link ModellingDecorators} and is only touched
 * after a runtime classpath check.
 */
public class AxoniqPlatformModellingConfigurationEnhancer implements ConfigurationEnhancer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AxoniqPlatformModellingConfigurationEnhancer.class);
    private static final String MODELLING_PROBE_CLASS = "org.axonframework.modelling.StateManager";

    @Override
    public void enhance(ComponentRegistry registry) {
        if (!registry.hasComponent(AxoniqPlatformConfiguration.class)) {
            return;
        }
        if (!isClasspathAvailable()) {
            LOGGER.debug("axon-modelling not on classpath; skipping modelling decorators.");
            return;
        }
        ModellingDecorators.apply(registry);
    }

    @Override
    public int order() {
        return PLATFORM_ENHANCER_ORDER;
    }

    private static boolean isClasspathAvailable() {
        try {
            Class.forName(MODELLING_PROBE_CLASS, false,
                          AxoniqPlatformModellingConfigurationEnhancer.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
