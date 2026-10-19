/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.api;

public enum AxoniqConsoleDlqMode {
    /**
     * All data of messages in the DLQ are available in the API.
     */
    FULL,
    /**
     * In this mode the event payload will never send over the wire. From the diagnostics only the entries which are
     * included in the whitelist will be sent over the wire. The sequence identifier will not be hashed and send as-is.
     */
    LIMITED,
    /**
     * Limited data of messages in the DLQ are available in the API. Sensitive information is masked.
     * The sequence identifier is hashed through SHA256 to still enable processing actions.
     */
    MASKED,
    /**
     * No DLQ data is available in the API. Only the count in the overview. List actions will return an empty list.
     */
    NONE,
}
