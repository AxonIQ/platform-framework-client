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

package io.axoniq.platform.framework.modelling

import io.axoniq.platform.framework.api.metrics.EntityStatisticIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class EntityMetricsRegistryTest {

    private val orderCreated = EntityStatisticIdentifier(
            entityName = "Order",
            entityId = "order-1",
            messageType = "CommandMessage",
            messageName = "CreateOrder",
    )

    private val orderShipped = EntityStatisticIdentifier(
            entityName = "Order",
            entityId = "order-1",
            messageType = "CommandMessage",
            messageName = "ShipOrder",
    )

    @Test
    fun `separate keys produce separate report entries`() {
        val registry = EntityMetricsRegistry()

        registry.registerLoad(orderCreated, 10_000_000, success = true)
        registry.registerLoad(orderShipped, 20_000_000, success = true)
        registry.registerCreation(orderCreated)

        val stats = registry.getStats().associateBy { it.entity }
        assertEquals(2, stats.size)
        assertNotNull(stats[orderCreated])
        assertNotNull(stats[orderShipped])
    }

    @Test
    fun `load records a total timer with non-empty snapshot`() {
        val registry = EntityMetricsRegistry()

        registry.registerLoad(orderCreated, 5_000_000, success = true)

        val stats = registry.getStats().single()
        assertNotNull(stats.statistics.timer)
        // mean is reported in ms — 5ms input should yield > 0
        assertTrue((stats.statistics.timer?.mean ?: 0.0) > 0.0)
    }

    @Test
    fun `additional timer is exposed under the requested metric name`() {
        val registry = EntityMetricsRegistry()

        registry.registerLoad(orderCreated, 5_000_000, success = true)
        registry.registerAdditionalTimer(
                orderCreated,
                EntityMetricsRegistry.METRIC_CRITERIA_SIZE,
                42L,
                TimeUnit.NANOSECONDS,
        )

        val stats = registry.getStats().single()
        assertTrue(stats.statistics.metrics.containsKey(EntityMetricsRegistry.METRIC_CRITERIA_SIZE))
    }

    @Test
    fun `creation without load still produces a report entry`() {
        val registry = EntityMetricsRegistry()

        registry.registerCreation(orderCreated)

        val stats = registry.getStats()
        assertEquals(1, stats.size)
        assertEquals(orderCreated, stats.single().entity)
    }
}
