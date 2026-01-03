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
    
    /**
     * Gets the path to the application.properties file in src/main/resources
     */
    private static Path getPropertiesPath() {
        String userDir = System.getProperty("user.dir");
        return Paths.get(userDir, "src", "main", "resources", PROPERTIES_FILE);
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
     * Updates a property value in the application.properties file
     * Preserves comments and formatting
     * @param key The property key to update
     * @param value The new value
     * @return true if successful, false otherwise
     */
    public static boolean updateProperty(String key, String value) {
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
