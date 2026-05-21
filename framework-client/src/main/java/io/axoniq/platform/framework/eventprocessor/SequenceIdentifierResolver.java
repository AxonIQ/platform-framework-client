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

import org.axonframework.messaging.eventhandling.DelegatingEventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;

import java.lang.reflect.Field;

/**
 * Resolves the sequence identifier for an event by walking the {@link EventHandlingComponent} decorator chain.
 *
 * <p>The AF5 framework wraps every user-provided event handling component in a chain of decorators that includes
 * {@code SequenceCachingEventHandlingComponent}. That decorator's {@code sequenceIdentifierFor} reads from a
 * {@code ProcessingContext} resource — which means it NPEs when called outside of any live unit of work. The DLQ
 * manager, however, is exactly in that position: it inspects already-enqueued letters with no context to provide.</p>
 *
 * <p>This resolver unwraps {@link DelegatingEventHandlingComponent} layers until it finds a component whose
 * {@code sequenceIdentifierFor} does not require a context (the stock {@code SimpleEventHandlingComponent} and
 * {@code SequenceOverridingEventHandlingComponent} do not), and calls it. Calling with a {@code null}
 * {@code ProcessingContext} is safe for the stock sequencing policies (constant, property, metadata, hierarchical,
 * fallback) — none of them read the context.</p>
 *
 * <p>If every attempt throws, the caller falls back to the letter's own message identifier (see
 * {@code DeadLetterManager.sequenceIdentifierFor}).</p>
 */
final class SequenceIdentifierResolver {

    private SequenceIdentifierResolver() {
    }

    /**
     * Walks the decorator chain on {@code component} and invokes {@code sequenceIdentifierFor(event, null)} on the
     * first component whose method completes without throwing. Returns {@code null} when every layer either throws
     * or has no policy that can run outside a unit of work — the caller treats {@code null} as "fall back to the
     * letter's message identifier".
     */
    static Object resolve(EventHandlingComponent component, EventMessage event) {
        EventHandlingComponent current = component;
        // Bound the unwrap depth so an exotic delegate chain can't loop forever. 16 is comfortably
        // higher than the standard AF5 decorator stack (sequence-caching, sequence-overriding,
        // dead-lettering, intercepting, tracing, axoniq-platform — six layers in the worst case).
        for (int i = 0; i < 16 && current != null; i++) {
            try {
                return current.sequenceIdentifierFor(event, null);
            } catch (RuntimeException ignore) {
                // This layer needed a ProcessingContext (e.g. SequenceCachingEventHandlingComponent).
                // Walk one step deeper and try again.
            }
            current = unwrap(current);
        }
        return null;
    }

    /**
     * Reads the private {@code delegate} field that every {@link DelegatingEventHandlingComponent} carries — and
     * that {@code SequenceOverridingEventHandlingComponent}, which does NOT extend that base class, also keeps under
     * the same name. Falling back to introspection by field name keeps us independent of API additions in the AF5
     * decorator chain.
     */
    private static EventHandlingComponent unwrap(EventHandlingComponent component) {
        Class<?> cls = component.getClass();
        while (cls != null) {
            try {
                Field delegate = cls.getDeclaredField("delegate");
                delegate.setAccessible(true);
                Object value = delegate.get(component);
                if (value instanceof EventHandlingComponent) {
                    return (EventHandlingComponent) value;
                }
                return null;
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }
}
