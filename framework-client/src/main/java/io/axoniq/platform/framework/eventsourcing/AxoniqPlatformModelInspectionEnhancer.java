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

import io.axoniq.platform.framework.AxoniqPlatformConfigurerEnhancer;
import io.axoniq.platform.framework.client.RSocketHandlerRegistrar;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service-loaded enhancer that wires the {@link RSocketModelInspectionResponder} when the
 * application has the platform client connected ({@link RSocketHandlerRegistrar} present) and
 * {@code axon-eventsourcing} is on the classpath.
 *
 * <p>This class is deliberately free of direct references to {@code axon-eventsourcing} types
 * so it can be loaded even when event sourcing is absent from the classpath. The actual
 * decorator wiring lives in {@link ModelInspectionDecorators} and is only touched after the
 * runtime classpath probe succeeds.</p>
 *
 * <p>We deliberately do <em>not</em> probe for {@code StateManager} either: it's registered by
 * {@code ModellingConfigurationDefaults} at {@link Integer#MAX_VALUE}, after this enhancer's
 * order, so the probe would falsely return {@code false} during boot.</p>
 */
public class AxoniqPlatformModelInspectionEnhancer implements ConfigurationEnhancer {

    private static final Logger logger = LoggerFactory.getLogger(AxoniqPlatformModelInspectionEnhancer.class);
    private static final String EVENTSOURCING_PROBE_CLASS = "org.axonframework.eventsourcing.eventstore.EventStorageEngine";

    @Override
    public void enhance(ComponentRegistry registry) {
        if (!registry.hasComponent(RSocketHandlerRegistrar.class)) {
            return;
        }
        if (!isClasspathAvailable()) {
            logger.debug("axon-eventsourcing not on classpath; skipping model inspection wiring.");
            return;
        }
        ModelInspectionDecorators.apply(registry);
    }

    @Override
    public int order() {
        return AxoniqPlatformConfigurerEnhancer.PLATFORM_ENHANCER_ORDER + 1;
    }

    private static boolean isClasspathAvailable() {
        try {
            Class.forName(EVENTSOURCING_PROBE_CLASS, false,
                          AxoniqPlatformModelInspectionEnhancer.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
