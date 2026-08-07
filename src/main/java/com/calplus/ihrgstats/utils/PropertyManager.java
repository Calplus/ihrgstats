package com.calplus.ihrgstats.utils;

import java.io.*;
import java.util.*;

/**
 * Utility class for managing application.properties file.
 * Allows reading and updating property values while preserving comments and formatting.
 */
public class PropertyManager {
    private static final String PROPERTIES_FILE = "application.properties";

    /**
     * Gets all properties starting with a specific prefix
     * @param prefix The prefix to filter by (e.g., "settings.")
     * @return Map of property keys to their current values
     */
    public static Map<String, String> getPropertiesByPrefix(String prefix) {
        Map<String, String> result = new LinkedHashMap<>();

        try {
            Properties props = PropertyResolver.loadAndResolve(PROPERTIES_FILE);
            // Sorted: stringPropertyNames() is Hashtable-backed with no
            // defined order, so the /settings menu ordering was JVM-dependent
            // and could differ between restarts.
            List<String> keys = new ArrayList<>(props.stringPropertyNames());
            Collections.sort(keys);
            for (String key : keys) {
                if (key.startsWith(prefix)) {
                    result.put(key, props.getProperty(key));
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading properties: " + e.getMessage());
        }

        return result;
    }
    
    /**
     * Updates a settings.* or internet.* property by writing it to the
     * .env.properties file (resolved back via system properties - see
     * EnvironmentManager/PropertyResolver). This is the only kind of
     * property this app's runtime UI (e.g. /settings) ever needs to change;
     * application.properties itself is a compiled-in classpath resource,
     * never intended to be edited live, so non-settings/internet keys are
     * rejected rather than attempted (A12 - a prior "edit
     * application.properties directly" branch here wrote to an external
     * file PropertyResolver's classpath-only reader never read back, a
     * silent no-op that was already confirmed to have no live caller).
     * @param key The property key to update (e.g., "settings.timezone")
     * @param value The new value
     * @return true if successful, false otherwise (including for a
     *         non-settings/internet key, which this method doesn't support)
     */
    public static boolean updateProperty(String key, String value) {
        if (!key.startsWith("settings.") && !key.startsWith("internet.")) {
            System.err.println("Cannot update non-settings/internet property at runtime: " + key);
            return false;
        }
        return updateEnvironmentProperty(key, value);
    }
    
    /**
     * Updates a property in the .env.properties file by converting the property key
     * to the corresponding environment variable name.
     * @param propertyKey The property key (e.g., "settings.timezone")
     * @param value The new value
     * @return true if successful, false otherwise
     */
    private static boolean updateEnvironmentProperty(String propertyKey, String value) {
        try {
            // Convert property key to environment variable name
            // settings.currentYear -> SETTINGS_CURRENTYEAR
            // internet.webhook.url -> INTERNET_WEBHOOK_URL
            String envKey = propertyKey.toUpperCase().replace(".", "_");
            
            // Use EnvironmentManager to update the .env.properties file
            EnvironmentManager envManager = new EnvironmentManager();
            envManager.setProperty(envKey, value);
            
            System.out.println("Updated environment property: " + envKey + " = " + value);
            return true;
            
        } catch (Exception e) {
            System.err.println("Error updating environment property: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Gets a description for a setting property
     * @param key The property key
     * @return Human-readable description
     */
    public static String getSettingDescription(String key) {
        switch (key) {
            case "settings.timezone":
                return "Set your preferred timezone for displaying timestamps";
            case "settings.currentYear":
                return "Sets the tournament year currently being played (used to resolve which year's rounds/players to operate on)";
            case "settings.allowNonAdminUploads":
                return "Allow non-admin users to upload files for processing";
            case "settings.allowAllChannelsProcessing":
                return "Allow file processing from any channel (not just configured ones)";
            case "settings.homeHall":
                return "Set your home hall for highlighting in exports";
            default:
                return "Setting: " + key;
        }
    }
}
