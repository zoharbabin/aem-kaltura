package com.example.core.services;

import com.example.core.config.KalturaConfig;

import java.io.IOException;
import java.util.Map;

public interface KalturaConfigManager {

    /**
     * Retrieves the current Kaltura configuration.
     * @return KalturaConfig object, or null if not configured.
     */
    KalturaConfig getConfiguration();

    /**
     * Updates the Kaltura configuration.
     * @param newProperties A map of properties to update. Keys should match KalturaConfig method names.
     * @throws IOException if persistence fails.
     */
    void updateConfiguration(Map<String, Object> newProperties) throws IOException;

    /**
     * Gets a specific configuration property.
     * @param propertyName The name of the property (e.g., "kalturaClientId").
     * @return The property value, or null if not found.
     */
    String getConfigProperty(String propertyName);

     /**
     * Gets a specific configuration property as long.
     * @param propertyName The name of the property (e.g., "kalturaTokenExpiryTime").
     * @return The property value, or 0L if not found or not a long.
     */
    long getConfigPropertyAsLong(String propertyName);
}
