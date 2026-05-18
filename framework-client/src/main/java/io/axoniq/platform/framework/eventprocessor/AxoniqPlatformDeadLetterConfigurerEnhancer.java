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

package io.axoniq.platform.framework.eventprocessor;

import io.axoniq.platform.framework.AxoniqPlatformConfiguration;
import io.axoniq.platform.framework.client.RSocketHandlerRegistrar;
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.common.lifecycle.Phase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.axoniq.platform.framework.AxoniqPlatformConfigurerEnhancer.PLATFORM_ENHANCER_ORDER;

/**
 * Service-loaded enhancer that registers the dead-letter queue inspection components only when the
 * {@code axoniq-dead-letter} module is present on the classpath. Kept free of direct references to
 * {@link DeadLetterManager} or {@link RSocketDlqResponder} (which import optional types) so the class can be
 * loaded even when the addon is absent.
 */
public class AxoniqPlatformDeadLetterConfigurerEnhancer implements ConfigurationEnhancer {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AxoniqPlatformDeadLetterConfigurerEnhancer.class);
    private static final String DEAD_LETTER_PROBE_CLASS =
            "io.axoniq.framework.messaging.deadletter.SequencedDeadLetterQueue";

    @Override
    public void enhance(ComponentRegistry registry) {
        if (!registry.hasComponent(AxoniqPlatformConfiguration.class)) {
            return;
        }
        // Enhancers can be invoked more than once during context refresh — bail out if the DLQ
        // components are already registered to avoid ComponentOverrideException.
        if (registry.hasComponent(DeadLetterManager.class)
                || registry.hasComponent(ProcessingGroupInfoSource.class)) {
            return;
        }
        if (!isClasspathAvailable()) {
            LOGGER.debug("axoniq-dead-letter not on classpath; skipping dead-letter queue inspection wiring.");
            return;
        }
        register(registry);
    }

    @Override
    public int order() {
        // Run after the main platform enhancer so the RSocketHandlerRegistrar component is already declared.
        return PLATFORM_ENHANCER_ORDER + 1;
    }

    private static void register(ComponentRegistry registry) {
        registry.registerComponent(ComponentDefinition
                                           .ofType(DeadLetterManager.class)
                                           .withBuilder(DeadLetterManager::new)
                                           // Discover DLQs after event processors have started, by which point the
                                           // EventHandlingComponent decorator chain has materialised every DLQ.
                                           .onStart(Phase.INSTRUCTION_COMPONENTS, DeadLetterManager::start));

        // The Spring-backed ComponentRegistry exposes a registered component under all of its
        // implemented interfaces automatically, so registering DeadLetterManager already makes
        // ProcessingGroupInfoSource available. The plain AF5 ComponentRegistry is exact-typed
        // though, so only register the seam there to keep ProcessorReportCreator's lookup
        // (`getOptionalComponent(ProcessingGroupInfoSource.class)`) working in both worlds.
        if (!registry.hasComponent(ProcessingGroupInfoSource.class)) {
            registry.registerComponent(ComponentDefinition
                                               .ofType(ProcessingGroupInfoSource.class)
                                               .withBuilder(c -> c.getComponent(DeadLetterManager.class)));
        }

        registry.registerComponent(ComponentDefinition
                                           .ofType(RSocketDlqResponder.class)
                                           .withBuilder(c -> new RSocketDlqResponder(
                                                   c.getComponent(DeadLetterManager.class),
                                                   c.getComponent(RSocketHandlerRegistrar.class)))
                                           .onStart(Phase.EXTERNAL_CONNECTIONS, RSocketDlqResponder::start));
    }

    private static boolean isClasspathAvailable() {
        try {
            Class.forName(DEAD_LETTER_PROBE_CLASS, false,
                          AxoniqPlatformDeadLetterConfigurerEnhancer.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
