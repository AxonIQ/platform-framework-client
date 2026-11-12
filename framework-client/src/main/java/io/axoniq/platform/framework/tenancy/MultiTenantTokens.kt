/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.tenancy

import io.axoniq.framework.messaging.multitenancy.eventstreaming.MultiTenantTrackingToken
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken
import org.axonframework.messaging.eventhandling.processing.streaming.token.WrappedToken

/**
 * Reads per-tenant positions from `MultiTenantTrackingToken`, which the framework marks `@Internal`: free to use, but
 * it may change without notice. Kept in its own class so a removed or changed token type surfaces as a
 * [LinkageError] at this call only, which [AxoniqMultiTenancy] catches.
 */
internal object MultiTenantTokens {

    fun positions(token: TrackingToken, tenants: List<String>): Map<String, Long>? {
        val multiTenant = WrappedToken.unwrap(token, MultiTenantTrackingToken::class.java).orElse(null) ?: return null
        return tenants.associateWith { tenant ->
            multiTenant.tokenForTenant(tenant)?.position()?.orElse(-1) ?: -1
        }
    }
}
