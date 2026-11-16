/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.workflow

import io.axoniq.platform.framework.api.ClientSettingsV2
import io.axoniq.platform.framework.api.ClientStatus
import io.axoniq.platform.framework.api.Routes
import io.axoniq.platform.framework.client.AxoniqConsoleRSocketClient
import io.axoniq.platform.framework.client.PlatformClientConnectionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

class WorkflowDefinitionsReporterTest {

    private val client = mockk<AxoniqConsoleRSocketClient>()
    private val reporter = WorkflowDefinitionsReporter(client, mockk(relaxed = true))
    private val settings = ClientSettingsV2(
            heartbeatInterval = 1000, heartbeatTimeout = 1000, processorReportInterval = 1000,
            handlerReportInterval = 1000, applicationReportInterval = 1000,
    )

    @BeforeEach
    fun setUp() {
        every { client.connectionNumber() } returns 1
        every { client.sendReport(Routes.Workflow.DEFINITIONS_REPORT, any()) } returns Mono.just(Unit)
    }

    private fun sent(times: Int) = verify(exactly = times, timeout = 2000) { client.sendReport(Routes.Workflow.DEFINITIONS_REPORT, any()) }

    @Nested
    inner class OncePerConnection {

        @Test
        fun `new settings on the same connection do not report again`() {
            reporter.onConnected(ClientStatus.PROVISIONED, settings)
            reporter.onConnected(ClientStatus.PROVISIONED, settings)

            sent(1)
        }

        @Test
        fun `a new connection reports again`() {
            reporter.onConnected(ClientStatus.PROVISIONED, settings)
            sent(1)
            every { client.connectionNumber() } returns 2

            reporter.onConnected(ClientStatus.PROVISIONED, settings)

            sent(2)
        }

        @Test
        fun `a report that failed is sent again with the next settings`() {
            every { client.sendReport(Routes.Workflow.DEFINITIONS_REPORT, any()) } returns Mono.error(IllegalStateException("closed"))
            reporter.onConnected(ClientStatus.PROVISIONED, settings)
            sent(1)
            every { client.sendReport(Routes.Workflow.DEFINITIONS_REPORT, any()) } returns Mono.just(Unit)

            Thread.sleep(200)
            reporter.onConnected(ClientStatus.PROVISIONED, settings)

            sent(2)
        }

        @Test
        fun `a client that may not report sends nothing`() {
            reporter.onConnected(ClientStatus.PENDING, settings)

            sent(0)
        }
    }
}
