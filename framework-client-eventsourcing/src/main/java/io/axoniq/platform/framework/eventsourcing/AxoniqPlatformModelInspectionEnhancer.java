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
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.common.lifecycle.Phase;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.modelling.StateManager;

/**
 * Enhancer that registers the {@link RSocketModelInspectionResponder} when both
 * {@link StateManager} and {@link EventStorageEngine} are available (AF5 applications).
 */
public class AxoniqPlatformModelInspectionEnhancer implements ConfigurationEnhancer {

    @Override
    public void enhance(ComponentRegistry registry) {
        if (!registry.hasComponent(StateManager.class)
                || !registry.hasComponent(EventStorageEngine.class)
                || !registry.hasComponent(RSocketHandlerRegistrar.class)) {
            return;
        }

        registry.registerComponent(ComponentDefinition
                                           .ofType(RSocketModelInspectionResponder.class)
                                           .withBuilder(c -> new RSocketModelInspectionResponder(
                                                   c.getComponent(StateManager.class),
                                                   c.getComponent(EventStorageEngine.class),
                                                   c.getComponent(RSocketHandlerRegistrar.class)))
                                           .onStart(Phase.EXTERNAL_CONNECTIONS, RSocketModelInspectionResponder::start));
    }

    @Override
    public int order() {
        return AxoniqPlatformConfigurerEnhancer.PLATFORM_ENHANCER_ORDER + 1;
    }
}
