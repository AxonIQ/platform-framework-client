/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.application

import io.axoniq.platform.framework.api.ClientSettingsV2
import io.axoniq.platform.framework.api.Routes
import io.axoniq.platform.framework.AxoniqPlatformConfiguration
import io.axoniq.platform.framework.api.ClientStatus
import io.axoniq.platform.framework.client.AxoniqConsoleRSocketClient
import io.axoniq.platform.framework.client.PlatformClientConnectionObserver
import io.axoniq.platform.framework.client.PlatformClientConnectionService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class ApplicationMetricReporter(
        private val client: AxoniqConsoleRSocketClient,
        private val reportCreator: ApplicationReportCreator,
        private val platformClientConnectionService: PlatformClientConnectionService,
        private val properties: AxoniqPlatformConfiguration,
) : PlatformClientConnectionObserver {
    private var reportTask: ScheduledFuture<*>? = null
    private val logger = KotlinLogging.logger { }
    private val executor = properties.reportingTaskExecutor

    init {
        platformClientConnectionService.subscribeToSettings(this)
    }

    override fun onConnected(clientStatus: ClientStatus, settings: ClientSettingsV2) {
        if (!clientStatus.enabled || reportTask != null) {
            return
        }
        logger.debug { "Sending application information every ${settings.applicationReportInterval}ms to Axoniq Platform" }
        this.reportTask = executor.scheduleWithFixedDelay({
            try {
                this.report()
            } catch (e: Exception) {
                logger.debug { "${"Was unable to report application metrics: {}"} ${e.message} $e" }
            }
        }, 0, settings.applicationReportInterval, TimeUnit.MILLISECONDS)
    }

    private fun report() {
        if (!client.isConnected()) {
            return
        }
        client.sendReport(Routes.Application.REPORT, reportCreator.createReport())
                .doOnError { e ->
                    logger.debug { "Failed to send application report: ${e.message}" }
                }
                .onErrorComplete()
                .subscribe()
    }

    override fun onDisconnected() {
        reportTask?.cancel(true)
        reportTask = null
    }
}

