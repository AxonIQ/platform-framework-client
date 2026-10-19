/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.modelling

import io.axoniq.platform.framework.api.metrics.EntityStatisticIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class EntityMetricsRegistryTest {

    private val order = EntityStatisticIdentifier(entityName = "Order")
    private val reservation = EntityStatisticIdentifier(entityName = "Reservation")

    @Test
    fun `separate entity names produce separate report entries`() {
        val registry = EntityMetricsRegistry()

        registry.registerLoad(order, 10_000_000, success = true)
        registry.registerLoad(reservation, 20_000_000, success = true)

        val stats = registry.getStats().associateBy { it.entity }
        assertEquals(2, stats.size)
        assertNotNull(stats[order])
        assertNotNull(stats[reservation])
    }

    @Test
    fun `load records a total timer with non-empty snapshot`() {
        val registry = EntityMetricsRegistry()

        registry.registerLoad(order, 5_000_000, success = true)

        val stats = registry.getStats().single()
        assertNotNull(stats.statistics.timer)
        // mean is reported in ms — 5ms input should yield > 0
        assertTrue((stats.statistics.timer?.mean ?: 0.0) > 0.0)
    }

    @Test
    fun `additional timer is exposed under the requested metric name`() {
        val registry = EntityMetricsRegistry()

        registry.registerLoad(order, 5_000_000, success = true)
        registry.registerAdditionalTimer(
                order,
                EntityMetricsRegistry.METRIC_CRITERIA_SIZE,
                42L,
                TimeUnit.NANOSECONDS,
        )

        val stats = registry.getStats().single()
        assertTrue(stats.statistics.metrics.containsKey(EntityMetricsRegistry.METRIC_CRITERIA_SIZE))
    }
}
