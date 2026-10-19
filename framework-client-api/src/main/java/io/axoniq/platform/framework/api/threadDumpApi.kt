/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.api

data class ThreadDumpEntry(
        val instance: String,
        val thread: String,
        val trace: String,
)

data class ThreadDumpResult(
        val timestamp: Long,
        val threadDumpEntries: List<ThreadDumpEntry>
)

data class ThreadDumpQuery(
        val instance: String
)