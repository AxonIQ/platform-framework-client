/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.api;

public enum DomainEventAccessMode {
    /**
     * Full access: payload is visible and loading domain state is supported.
     */
    FULL,

    /**
     * Payload is hidden (e.g., masked), but loading domain state is still supported.
     */
    LOAD_DOMAIN_STATE_ONLY,

    /**
     * Payload is visible, but loading domain state is not supported.
     */
    PREVIEW_PAYLOAD_ONLY,

    /**
     * No access: payload is hidden and loading domain state is not supported.
     */
    NONE
}