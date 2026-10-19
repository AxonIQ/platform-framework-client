/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.application
import io.axoniq.platform.framework.api.ThreadDumpEntry
import io.axoniq.platform.framework.api.ThreadDumpResult
import java.lang.management.ManagementFactory

class ApplicationThreadDumpProvider() {
    private val threadBean = ManagementFactory.getThreadMXBean()

    fun collectThreadDumps(instance: String): ThreadDumpResult {
        val threadDumpEntries = threadBean.dumpAllThreads(true, true).map { threadInfo ->
            ThreadDumpEntry(
                    instance = instance,
                    thread = threadInfo.threadName,
                    trace = threadInfo.toString()
            )
        }
        return ThreadDumpResult(timestamp = System.currentTimeMillis(), threadDumpEntries = threadDumpEntries)
    }
}