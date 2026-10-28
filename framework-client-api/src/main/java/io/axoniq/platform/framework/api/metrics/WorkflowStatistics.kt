/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.api.metrics

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * What a workflow, or one of its steps, did since the previous report: counts of its events, and the attempts whose
 * start and end are both known, measured as [measured] durations adding up to [durationMillis].
 *
 * [open] is the state at the time of the report instead: the instances of a workflow that have not ended, or the
 * instances waiting at a wait step. It is read from the event store, so every node of the application reports the
 * same value.
 */
data class WorkflowStatistics(
    @JsonProperty("st")
    val started: Long = 0,
    @JsonProperty("c")
    val completed: Long = 0,
    @JsonProperty("f")
    val failed: Long = 0,
    @JsonProperty("ca")
    val cancelled: Long = 0,
    @JsonProperty("to")
    val timedOut: Long = 0,
    @JsonProperty("r")
    val retries: Long = 0,
    @JsonProperty("me")
    val measured: Long = 0,
    @JsonProperty("dm")
    val durationMillis: Long = 0,
    @JsonProperty("t")
    val duration: StatisticDistribution? = null,
    @JsonProperty("o")
    val open: Long? = null,
)
