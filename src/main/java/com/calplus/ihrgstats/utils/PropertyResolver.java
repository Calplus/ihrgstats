package com.calplus.ihrgstats.utils;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class to resolve properties with ${VAR:default} placeholders.
 * Resolves values from system properties first, then falls back to defaults.
 */
public class PropertyResolver {
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^:}]+):([^}]*)\\}");

    /**
     * Loads properties from a resource file and resolves ${} placeholders.
     * @param resourceName The name of the resource file (e.g., "application.properties")
     * @return Properties object with resolved values
     * @throws IOException if the resource cannot be read
     */
    public static Properties loadAndResolve(String resourceName) throws IOException {
        Properties properties = new Properties();
        
        try (InputStream inputStream = PropertyResolver.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (inputStream == null) {
                throw new IOException("Resource not found: " + resourceName);
            }
            properties.load(inputStream);
        }
        
        // Resolve all placeholders
        Properties resolved = new Properties();
        for (String key : properties.stringPropertyNames()) {
            String value = properties.getProperty(key);
            resolved.setProperty(key, resolvePlaceholders(value));
        }
        
        return resolved;
    }

    /**
     * Resolves ${VAR:default} placeholders in a string.
     * Checks system properties first, then uses the default value.
     * @param value The string containing placeholders
     * @return Resolved string
     */
    public static String resolvePlaceholders(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(value);
        StringBuffer result = new StringBuffer();
        
        while (matcher.find()) {
            String varName = matcher.group(1);
            String defaultValue = matcher.group(2);
            
            // Check system property first
            String resolvedValue = System.getProperty(varName);
            
            // Fall back to default if not found or empty
            if (resolvedValue == null || resolvedValue.isEmpty()) {
                resolvedValue = defaultValue;
            }
            
            matcher.appendReplacement(result, Matcher.quoteReplacement(resolvedValue));
        }
        matcher.appendTail(result);
        
        return result.toString();
    }

    /**
     * Gets a resolved property value from application.properties.
     * @param key The property key
     * @return The resolved value, or null if not found
     */
    public static String getProperty(String key) {
        try {
            Properties props = loadAndResolve("application.properties");
            return props.getProperty(key);
        } catch (IOException e) {
            System.err.println("Error loading property " + key + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Gets a resolved property value with a default.
     * @param key The property key
     * @param defaultValue The default value if not found
     * @return The resolved value, or defaultValue if not found
     */
    public static String getProperty(String key, String defaultValue) {
        String value = getProperty(key);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }
}
