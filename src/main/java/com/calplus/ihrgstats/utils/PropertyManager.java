package com.calplus.ihrgstats.utils;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Utility class for managing application.properties file.
 * Allows reading and updating property values while preserving comments and formatting.
 */
public class PropertyManager {
    private static final String PROPERTIES_FILE = "application.properties";
    private static final String CONFIG_DIR = "config";
    
    /**
     * Gets the path to the application.properties file.
     * For JAR execution, uses config/application.properties in working directory.
     * For development, uses src/main/resources/application.properties.
     */
    private static Path getPropertiesPath() {
        String userDir = System.getProperty("user.dir");
        
        // Check if running from JAR or if config directory exists
        Path configPath = Paths.get(userDir, CONFIG_DIR, PROPERTIES_FILE);
        Path devPath = Paths.get(userDir, "src", "main", "resources", PROPERTIES_FILE);
        
        // If config directory exists or dev path doesn't exist, use config path
        if (Files.exists(configPath) || !Files.exists(devPath)) {
            // Ensure config directory exists
            try {
                Files.createDirectories(configPath.getParent());
                
                // If config file doesn't exist, copy from classpath
                if (!Files.exists(configPath)) {
                    copyPropertiesFromClasspath(configPath);
                }
            } catch (IOException e) {
                System.err.println("Error creating config directory: " + e.getMessage());
            }
            return configPath;
        }
        
        // Development mode - use src/main/resources
        return devPath;
    }
    
    /**
     * Copies application.properties from classpath to external config directory
     */
    private static void copyPropertiesFromClasspath(Path targetPath) {
        try (InputStream is = PropertyManager.class.getClassLoader().getResourceAsStream(PROPERTIES_FILE)) {
            if (is != null) {
                Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Created external config file: " + targetPath);
            } else {
                System.err.println("Could not find " + PROPERTIES_FILE + " in classpath");
            }
        } catch (IOException e) {
            System.err.println("Error copying properties from classpath: " + e.getMessage());
        }
    }
    
    /**
     * Gets all properties starting with a specific prefix
     * @param prefix The prefix to filter by (e.g., "settings.")
     * @return Map of property keys to their current values
     */
    public static Map<String, String> getPropertiesByPrefix(String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        
        try {
            Properties props = PropertyResolver.loadAndResolve(PROPERTIES_FILE);
            for (String key : props.stringPropertyNames()) {
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
     * Updates a property value. For settings and internet properties that use environment variables,
     * updates the .env.properties file. For other properties, updates application.properties directly.
     * Preserves comments and formatting.
     * @param key The property key to update (e.g., "settings.timezone")
     * @param value The new value
     * @return true if successful, false otherwise
     */
    public static boolean updateProperty(String key, String value) {
        // Check if this is a settings or internet property that should go to .env.properties
        if (key.startsWith("settings.") || key.startsWith("internet.")) {
            return updateEnvironmentProperty(key, value);
        }
        
        // For other properties, update application.properties directly
        Path propsPath = getPropertiesPath();
        
        if (!Files.exists(propsPath)) {
            System.err.println("Properties file not found: " + propsPath);
            return false;
        }
        
        try {
            // Read all lines
            List<String> lines = Files.readAllLines(propsPath);
            boolean found = false;
            
            // Find and update the property
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                
                // Skip comments and empty lines
                if (line.startsWith("#") || line.isEmpty()) {
                    continue;
                }
                
                // Check if this line contains our property
                if (line.startsWith(key + "=")) {
                    lines.set(i, key + "=" + value);
                    found = true;
                    break;
                }
            }
            
            if (!found) {
                System.err.println("Property not found: " + key);
                return false;
            }
            
            // Write back to file
            Files.write(propsPath, lines);
            return true;
            
        } catch (IOException e) {
            System.err.println("Error updating property: " + e.getMessage());
            return false;
        }
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
            // settings.perfElo.enabled -> SETTINGS_PERFELO_ENABLED
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
            case "settings.maxSeeds":
                return "Sets maximum score for the game for score calculation (e.g: 64 for Othello, 368.5 for Weiqi)";
            case "settings.perfElo.enabled":
                return "Enable Performance ELO calculations (considers point margins)";
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
