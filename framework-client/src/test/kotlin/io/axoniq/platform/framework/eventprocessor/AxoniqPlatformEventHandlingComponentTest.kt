package io.axoniq.platform.framework.eventprocessor

import io.mockk.every
import io.mockk.mockk
import org.axonframework.messaging.core.QualifiedName
import org.axonframework.messaging.eventhandling.EventHandlingComponent
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AxoniqPlatformEventHandlingComponentTest {
    private val delegate = mockk<EventHandlingComponent>()
    private val component = AxoniqPlatformEventHandlingComponent(
        delegate = delegate,
        processorName = "Workflow",
        handlerMetricsRegistry = mockk(),
        processorMetricRegistry = mockk(),
    )

    @Nested
    inner class SupportedEvents {
        @Test
        fun `preserves dynamic event support without an enumerated event set`() {
            val event = QualifiedName("OrderPlaced")
            every { delegate.supportedEvents() } returns emptySet()
            every { delegate.supports(event) } returns true

            assertTrue(component.supportedEvents().isEmpty())
            assertTrue(component.supports(event))
        }

        @Test
        fun `retains delegate exclusions`() {
            val event = QualifiedName("UnrelatedEvent")
            every { delegate.supports(event) } returns false

            assertFalse(component.supports(event))
        }
    }
}
