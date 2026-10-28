/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.workflow

import io.axoniq.platform.framework.api.metrics.WorkflowStatisticsWithIdentifier
import org.axonframework.messaging.eventhandling.EventMessage

/**
 * Collects workflow statistics from the events this node appends, for the periodic statistics report.
 *
 * The client core calls it without knowing the workflow engine, which may be missing from the classpath: this type
 * refers to no engine class, and only [WorkflowMetricsRegistry] implements it, registered when the engine is present.
 */
interface WorkflowStatisticsCollector {

    /** The events of one append that was committed. */
    fun onAppended(events: List<EventMessage>)

    /** What happened since the previous call, and the open instances and waits now. */
    fun drain(): List<WorkflowStatisticsWithIdentifier>
}
