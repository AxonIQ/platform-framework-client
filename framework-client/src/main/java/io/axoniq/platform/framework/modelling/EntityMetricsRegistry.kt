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
import io.axoniq.platform.framework.api.metrics.EntityStatistics
import io.axoniq.platform.framework.api.metrics.EntityStatisticsWithIdentifier
import io.axoniq.platform.framework.computeIfAbsentWithRetry
import io.axoniq.platform.framework.createCountTimer
import io.axoniq.platform.framework.createTimer
import io.axoniq.platform.framework.messaging.RollingCountMeasure
import io.axoniq.platform.framework.messaging.toDistribution
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Registry collecting metrics per entity load by `(entityName, entityId, messageType, messageName)`.
 *
 * Drained by [io.axoniq.platform.framework.messaging.HandlerMetricsRegistry] on its periodic reporting
 * tick and shipped to the platform as part of [io.axoniq.platform.framework.api.metrics.StatisticReport].
 */
class EntityMetricsRegistry {
    private val meterRegistry = SimpleMeterRegistry()
    private val entities: MutableMap<EntityStatisticIdentifier, EntityRegistryStatistics> = ConcurrentHashMap()

    fun registerLoad(identifier: EntityStatisticIdentifier, durationNs: Long, success: Boolean) {
        val stats = entry(identifier)
        stats.totalTimer.record(durationNs, TimeUnit.NANOSECONDS)
        stats.totalCount.increment()
        if (!success) {
            stats.failureCount.increment()
        }
    }

    fun registerCreation(identifier: EntityStatisticIdentifier) {
        entry(identifier).creationCount.increment()
    }

    fun registerAdditionalTimer(identifier: EntityStatisticIdentifier, name: String, value: Long, unit: TimeUnit) {
        entry(identifier).metrics
                .computeIfAbsentWithRetry(name) { timerFactory(name)(meterRegistry, "${identifier}_timer_$name") }
                .record(value, unit)
    }

    /**
     * Picks a higher-precision histogram for count-style metrics so reported percentiles round-trip
     * small integers cleanly (otherwise `1` shows up as `0.98` and `0` as `0.98` on the Inspector UI).
     * Time-typed metrics keep the default precision — their values span many orders of magnitude where
     * the extra precision is wasted memory.
     */
    private fun timerFactory(name: String): (MeterRegistry, String) -> Timer {
        return when (name) {
            METRIC_EVENT_STREAM_SIZE, METRIC_CRITERIA_SIZE -> ::createCountTimer
            else -> ::createTimer
        }
    }

    fun getStats(): List<EntityStatisticsWithIdentifier> {
        return entities.entries.map {
            EntityStatisticsWithIdentifier(
                    it.key,
                    EntityStatistics(
                            count = it.value.totalCount.count(),
                            creations = it.value.creationCount.count(),
                            failed = it.value.failureCount.count(),
                            timer = it.value.totalTimer.takeSnapshot().toDistribution(),
                            metrics = it.value.metrics.map { (k, v) -> k to v.takeSnapshot().toDistribution() }.toMap()
                    )
            )
        }
    }

    private fun entry(identifier: EntityStatisticIdentifier): EntityRegistryStatistics {
        return entities.computeIfAbsentWithRetry(identifier) { _ ->
            EntityRegistryStatistics(createTimer(meterRegistry, "${identifier}_timer_total"))
        }
    }

    private data class EntityRegistryStatistics(
            val totalTimer: Timer,
            val totalCount: RollingCountMeasure = RollingCountMeasure(),
            val creationCount: RollingCountMeasure = RollingCountMeasure(),
            val failureCount: RollingCountMeasure = RollingCountMeasure(),
            val metrics: MutableMap<String, Timer> = ConcurrentHashMap(),
    )

    companion object {
        const val METRIC_LOCK_WAIT_TIME = "lock_wait_time"
        const val METRIC_EVENT_COMMIT_TIME = "event_commit_time"
        const val METRIC_EVENT_STREAM_SIZE = "event_stream_size"
        const val METRIC_CRITERIA_SIZE = "criteria_size"
    }
}
