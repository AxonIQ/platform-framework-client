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

package io.axoniq.platform.framework.client.license

import org.slf4j.LoggerFactory
import java.io.StringReader
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe holder for license content received from AxonIQ Platform via RSocket.
 *
 * This class stores the license properties and notifies registered listeners when
 * the license changes. It is used by [PlatformEntitlementSource] to provide
 * license data to the entitlement-manager library.
 *
 * License content is received either:
 * - Pushed from the server when a new license is generated (LICENSE route)
 * - Pulled by the client after connection (LICENSE_REQUEST route)
 */
class LicenseHolder {

    private val logger = LoggerFactory.getLogger(LicenseHolder::class.java)

    private val licenseContent = AtomicReference<String?>(null)
    private val licenseProperties = AtomicReference<Properties?>(null)
    private val listeners = CopyOnWriteArrayList<LicenseChangeListener>()

    /**
     * Updates the license content. Called when license is received from Platform.
     *
     * @param content the license content in properties format
     */
    fun updateLicense(content: String?) {
        if (content.isNullOrBlank()) {
            logger.debug("Received empty license content, ignoring")
            return
        }

        val previousContent = licenseContent.get()
        if (previousContent == content) {
            logger.debug("License content unchanged, ignoring")
            return
        }

        try {
            val properties = Properties()
            properties.load(StringReader(content))

            licenseContent.set(content)
            licenseProperties.set(properties)

            logger.info("License updated successfully")
            notifyListeners()
        } catch (e: Exception) {
            logger.warn("Failed to parse license content: ${e.message}")
        }
    }

    /**
     * Clears the stored license. Called when connection is lost.
     */
    fun clearLicense() {
        val hadLicense = licenseContent.get() != null
        licenseContent.set(null)
        licenseProperties.set(null)
        if (hadLicense) {
            logger.debug("License cleared")
        }
    }

    /**
     * Returns the current license properties, or null if no license is available.
     */
    fun getLicenseProperties(): Properties? = licenseProperties.get()

    /**
     * Returns the raw license content, or null if no license is available.
     */
    fun getLicenseContent(): String? = licenseContent.get()

    /**
     * Returns true if a license is currently available.
     */
    fun hasLicense(): Boolean = licenseProperties.get() != null

    /**
     * Registers a listener to be notified when the license changes.
     */
    fun addListener(listener: LicenseChangeListener) {
        listeners.add(listener)
    }

    /**
     * Removes a previously registered listener.
     */
    fun removeListener(listener: LicenseChangeListener) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val properties = licenseProperties.get() ?: return
        listeners.forEach { listener ->
            try {
                listener.onLicenseChanged(properties)
            } catch (e: Exception) {
                logger.warn("Listener threw exception: ${e.message}")
            }
        }
    }

    /**
     * Listener interface for license change notifications.
     */
    fun interface LicenseChangeListener {
        /**
         * Called when the license is updated.
         *
         * @param properties the new license properties
         */
        fun onLicenseChanged(properties: Properties)
    }

    companion object {
        private val _instance = LicenseHolder()

        /**
         * Returns the global singleton instance.
         *
         * This is used by [PlatformEntitlementSource] which is loaded via ServiceLoader
         * and cannot have constructor dependencies injected.
         */
        @JvmStatic
        fun getInstance(): LicenseHolder = _instance

        /**
         * Global singleton instance (Kotlin property access).
         */
        val INSTANCE: LicenseHolder
            get() = _instance
    }
}
