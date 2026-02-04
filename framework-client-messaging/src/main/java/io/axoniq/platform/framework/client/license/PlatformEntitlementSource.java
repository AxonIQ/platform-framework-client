/*
 * Copyright (c) 2022-2026. AxonIQ B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.axoniq.platform.framework.client.license;

import io.axoniq.license.entitlement.License;
import io.axoniq.license.entitlement.source.EntitlementSource;

import java.util.Optional;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Entitlement source that fetches license from AxonIQ Platform via RSocket.
 * <p>
 * This source reads from the {@link LicenseHolder} singleton which receives
 * license updates via the Platform RSocket connection. The license is pushed
 * to clients either:
 * <ul>
 *   <li>On successful connection (client requests via LICENSE_REQUEST route)</li>
 *   <li>When a new license is generated (server pushes via LICENSE route)</li>
 * </ul>
 * <p>
 * This class is discovered via {@link java.util.ServiceLoader} and provides
 * the primary entitlement source for applications connected to AxonIQ Platform.
 *
 * @see LicenseHolder
 * @see EntitlementSource
 */
public class PlatformEntitlementSource implements EntitlementSource {

    private static final Logger logger = Logger.getLogger(PlatformEntitlementSource.class.getName());

    private final LicenseHolder licenseHolder;

    /**
     * Default constructor used by ServiceLoader.
     * <p>
     * Uses the global {@link LicenseHolder#getInstance()} singleton.
     */
    public PlatformEntitlementSource() {
        this(LicenseHolder.getInstance());
    }

    /**
     * Constructor for testing with a custom LicenseHolder.
     *
     * @param licenseHolder the license holder to use
     */
    public PlatformEntitlementSource(LicenseHolder licenseHolder) {
        this.licenseHolder = licenseHolder;
    }

    @Override
    public Optional<License> fetchLicense() {
        Properties properties = licenseHolder.getLicenseProperties();

        if (properties == null) {
            logger.fine("No license available from Platform connection");
            return Optional.empty();
        }

        logger.fine("Returning license from Platform connection");
        return Optional.of(new License(properties));
    }

    @Override
    public String getSourceName() {
        return "AxonIqPlatform[RSocket]";
    }

    /**
     * Returns whether a license is currently available.
     */
    public boolean hasLicense() {
        return licenseHolder.hasLicense();
    }
}
