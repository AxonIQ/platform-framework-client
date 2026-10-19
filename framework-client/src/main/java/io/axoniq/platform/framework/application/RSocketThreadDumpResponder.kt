/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.application

import io.axoniq.platform.framework.api.Routes
import io.axoniq.platform.framework.api.ThreadDumpQuery
import io.axoniq.platform.framework.api.ThreadDumpResult
import io.axoniq.platform.framework.client.RSocketHandlerRegistrar
import org.slf4j.LoggerFactory

open class RSocketThreadDumpResponder(
        private val applicationThreadDumpProvider: ApplicationThreadDumpProvider,
        private val registrar: RSocketHandlerRegistrar
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    fun start() {
        registrar.registerHandlerWithPayload(
                Routes.Management.THREAD_DUMP,
                ThreadDumpQuery::class.java,
                this::handleThreadDumpQuery
        )
    }

    private fun handleThreadDumpQuery(query: ThreadDumpQuery): ThreadDumpResult {
        logger.debug("Handling Axoniq Platform  THREAD_DUMP query for request [{}]", query)
        return applicationThreadDumpProvider.collectThreadDumps(query.instance)
    }
}