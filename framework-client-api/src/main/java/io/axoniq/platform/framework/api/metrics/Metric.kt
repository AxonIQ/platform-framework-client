/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.api.metrics

import io.axoniq.platform.framework.api.metrics.MetricTargetType.AGGREGATE
import io.axoniq.platform.framework.api.metrics.MetricTargetType.HANDLER


interface Metric {
    val type: MetricType
    val identifier: String
    val description: String
    val targetTypes: List<MetricTargetType>


    val fullIdentifier: String
        get() = "${type.metricPrefix}_${identifier}"
}

data class ChildHandlerMetric(
        val handler: HandlerStatisticsMetricIdentifier
) : Metric {
    override val type: MetricType = MetricType.TIMER
    override val description: String = "Time it took for the event handler to process an event as subscriber"
    override val identifier: String = "${handler.type.shortIdentifier}_\"${handler.component}\"_\"${handler.message.type}\"_\"${handler.message.name}\""
    override val targetTypes: List<MetricTargetType> = listOf(HANDLER)
}

data class LoadModelMetric(
        override val type: MetricType = MetricType.TIMER,
        override val identifier: String,
        override val description: String = "Time it took to load the model",
        override val targetTypes: List<MetricTargetType> = listOf(HANDLER),
) : Metric


enum class PreconfiguredMetric(
        override val type: MetricType,
        override val identifier: String,
        override val description: String,
        override val targetTypes: List<MetricTargetType>,
) : Metric {
    MESSAGE_HANDLER_TIME(
            type = MetricType.TIMER,
            identifier = "handler",
            description = "Time it took for the actual user-made handler function to be invoked",
            targetTypes = listOf(AGGREGATE, HANDLER),
    ),
    EVENT_COMMIT_TIME(
            type = MetricType.TIMER,
            identifier = "event_commit",
            description = "Time it took to commit events to the event store",
            targetTypes = listOf(AGGREGATE, HANDLER)
    ),
    AGGREGATE_EVENTS_SIZE(
            type = MetricType.COUNTER,
            identifier = "agg_events_size",
            description = "Amount of events loaded during aggregate initialization",
            targetTypes = listOf(AGGREGATE)
    )

    ;
}
