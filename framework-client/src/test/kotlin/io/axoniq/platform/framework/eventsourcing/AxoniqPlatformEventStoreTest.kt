/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.eventsourcing

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.axonframework.eventsourcing.eventstore.AppendCondition
import org.axonframework.eventsourcing.eventstore.EventStore
import org.axonframework.eventsourcing.eventstore.EventStoreTransaction
import org.axonframework.messaging.core.unitofwork.ProcessingContext
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.function.UnaryOperator

class AxoniqPlatformEventStoreTest {
    private val delegate = mockk<EventStore>()
    private val transaction = mockk<EventStoreTransaction>()
    private val context = mockk<ProcessingContext>()
    private val store = AxoniqPlatformEventStore(delegate = delegate, entityMetricsRegistry = mockk())

    @Nested
    inner class AppendConditions {
        @Test
        fun `workflow append condition override reaches the underlying transaction`() {
            val original = mockk<AppendCondition>()
            val workflowCondition = mockk<AppendCondition>()
            val modifier = UnaryOperator<AppendCondition> {
                assertSame(original, it)
                workflowCondition
            }
            every { delegate.transaction(context) } returns transaction
            every { transaction.overrideAppendCondition(any()) } answers {
                assertSame(workflowCondition, firstArg<UnaryOperator<AppendCondition>>().apply(original))
            }

            store.transaction(context).overrideAppendCondition(modifier)

            verify(exactly = 1) { transaction.overrideAppendCondition(modifier) }
        }
    }
}
