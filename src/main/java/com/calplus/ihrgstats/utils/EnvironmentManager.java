package com.calplus.ihrgstats.utils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages environment variables stored in .env.properties file.
 * Provides methods to read, update, and overwrite environment variables.
 */
public class EnvironmentManager {
    private static final String ENV_FILE_NAME = ".env.properties";
    private final Path envFilePath;
    private final Properties envProperties;

    /**
     * Creates an EnvironmentManager instance.
     * Loads the .env.properties file from the project root.
     */
    public EnvironmentManager() {
        this.envFilePath = Paths.get(System.getProperty("user.dir"), ENV_FILE_NAME);
        this.envProperties = new Properties();
        loadEnvironmentFile();
    }

    /**
     * Creates an EnvironmentManager instance with a custom file path.
     * @param customPath The custom path to the environment file
     */
    public EnvironmentManager(String customPath) {
        this.envFilePath = Paths.get(customPath);
        this.envProperties = new Properties();
        loadEnvironmentFile();
    }

    /**
     * Loads the environment file into memory.
     * Creates the file if it doesn't exist.
     */
    private void loadEnvironmentFile() {
        if (!Files.exists(envFilePath)) {
            System.out.println("Environment file not found. Creating new file at: " + envFilePath);
            createDefaultEnvironmentFile();
            return;
        }

        try (InputStream input = new FileInputStream(envFilePath.toFile())) {
            envProperties.load(input);
            System.out.println("Environment file loaded successfully from: " + envFilePath);
        } catch (IOException e) {
            System.err.println("Error loading environment file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Creates a default environment file with empty values.
     */
    private void createDefaultEnvironmentFile() {
        try {
            Files.createFile(envFilePath);
            
            // Set default properties
            envProperties.setProperty("DISCORD_BOT_TOKEN", "");
            envProperties.setProperty("DISCORD_LOG_CHANNELID", "");
            envProperties.setProperty("DISCORD_ADMIN_USERID", "");
            envProperties.setProperty("TELEGRAM_BOT_TOKEN", "");
            envProperties.setProperty("TELEGRAM_ADMIN_USERID", "");
            envProperties.setProperty("TELEGRAM_DEV_CHATID", "");
            envProperties.setProperty("TELEGRAM_DEV_CHATID_LOG", "");
            envProperties.setProperty("TELEGRAM_PUBLIC_CHATID", "");
            envProperties.setProperty("TELEGRAM_PUBLIC_CHATID_FILEUPLOAD", "");
            
            saveEnvironmentFile();
            System.out.println("Default environment file created at: " + envFilePath);
        } catch (IOException e) {
            System.err.println("Error creating default environment file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Saves the current properties to the environment file.
     * Preserves comments and formatting.
     */
    private void saveEnvironmentFile() {
        try {
            // Create backup
            Path backupPath = Paths.get(envFilePath.toString() + ".backup");
            if (Files.exists(envFilePath)) {
                Files.copy(envFilePath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            }

            // Read existing file to preserve comments
            List<String> lines = new ArrayList<>();
            Map<String, String> newValues = new HashMap<>();
            
            for (String key : envProperties.stringPropertyNames()) {
                newValues.put(key, envProperties.getProperty(key));
            }

            if (Files.exists(envFilePath)) {
                lines = Files.readAllLines(envFilePath);
            }

            // Update lines or add new ones
            List<String> updatedLines = new ArrayList<>();
            Set<String> processedKeys = new HashSet<>();

            for (String line : lines) {
                String trimmedLine = line.trim();
                
                // Preserve comments and empty lines
                if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) {
                    updatedLines.add(line);
                    continue;
                }

                // Parse key=value
                int equalsIndex = line.indexOf('=');
                if (equalsIndex > 0) {
                    String key = line.substring(0, equalsIndex).trim();
                    
                    if (newValues.containsKey(key)) {
                        updatedLines.add(key + "=" + newValues.get(key));
                        processedKeys.add(key);
                    } else {
                        updatedLines.add(line);
                    }
                } else {
                    updatedLines.add(line);
                }
            }

            // Add new keys that weren't in the original file
            for (Map.Entry<String, String> entry : newValues.entrySet()) {
                if (!processedKeys.contains(entry.getKey())) {
                    updatedLines.add(entry.getKey() + "=" + entry.getValue());
                }
            }

            // Write to file
            Files.write(envFilePath, updatedLines);
            System.out.println("Environment file saved successfully.");

        } catch (IOException e) {
            System.err.println("Error saving environment file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Gets a property value from the environment file.
     * @param key The property key
     * @return The property value, or null if not found
     */
    public String getProperty(String key) {
        return envProperties.getProperty(key);
    }

    /**
     * Gets a property value with a default value if not found.
     * @param key The property key
     * @param defaultValue The default value
     * @return The property value, or defaultValue if not found
     */
    public String getProperty(String key, String defaultValue) {
        return envProperties.getProperty(key, defaultValue);
    }

    /**
     * Sets a property value in memory and saves to file.
     * @param key The property key
     * @param value The property value
     * @throws IllegalArgumentException if key or value contains a control
     *         character (e.g. a newline, which would otherwise let a single
     *         property write forge additional, attacker-chosen lines in the
     *         env file)
     */
    public void setProperty(String key, String value) {
        requireNoControlCharacters(key, value);
        envProperties.setProperty(key, value);
        saveEnvironmentFile();

        // Also set as system property for immediate use
        System.setProperty(key, value);
    }

    /**
     * Sets multiple properties at once.
     * @param properties Map of key-value pairs
     * @throws IllegalArgumentException if any key or value contains a control character
     */
    public void setProperties(Map<String, String> properties) {
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            requireNoControlCharacters(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            envProperties.setProperty(entry.getKey(), entry.getValue());
            System.setProperty(entry.getKey(), entry.getValue());
        }
        saveEnvironmentFile();
    }

    /**
     * Rejects control characters (e.g. \n, \r) in a property key/value pair.
     * The env file is a plain key=value-per-line format, so an unescaped
     * newline in either would let one property write silently inject a
     * second, attacker-chosen property line.
     */
    private static void requireNoControlCharacters(String key, String value) {
        if (containsControlCharacter(key) || containsControlCharacter(value)) {
            throw new IllegalArgumentException("Property keys/values must not contain control characters (e.g. newlines)");
        }
    }

    private static boolean containsControlCharacter(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isISOControl(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes a property from the environment file.
     * @param key The property key to remove
     */
    public void removeProperty(String key) {
        envProperties.remove(key);
        saveEnvironmentFile();
        System.clearProperty(key);
    }

    /**
     * Gets all properties as a map.
     * @return Map of all properties
     */
    public Map<String, String> getAllProperties() {
        return envProperties.stringPropertyNames().stream()
                .collect(Collectors.toMap(
                        key -> key,
                        envProperties::getProperty
                ));
    }

    /**
     * Overwrites the entire environment file with new properties.
     * WARNING: This will replace all existing properties.
     * @param newProperties Map of new properties
     */
    public void overwriteEnvironmentFile(Map<String, String> newProperties) {
        for (Map.Entry<String, String> entry : newProperties.entrySet()) {
            requireNoControlCharacters(entry.getKey(), entry.getValue());
        }

        envProperties.clear();

        for (Map.Entry<String, String> entry : newProperties.entrySet()) {
            envProperties.setProperty(entry.getKey(), entry.getValue());
            System.setProperty(entry.getKey(), entry.getValue());
        }
        
        saveEnvironmentFile();
        System.out.println("Environment file overwritten successfully.");
    }

    /**
     * Reloads the environment file from disk.
     */
    public void reload() {
        envProperties.clear();
        loadEnvironmentFile();
    }

    /**
     * Gets the path to the environment file.
     * @return Path to the environment file
     */
    public Path getEnvironmentFilePath() {
        return envFilePath;
    }

    /**
     * Loads environment variables into system properties.
     * This makes them available for Spring's ${} placeholder resolution.
     */
    public void loadIntoSystemProperties() {
        for (String key : envProperties.stringPropertyNames()) {
            String value = envProperties.getProperty(key);
            if (value != null && !value.isEmpty()) {
                System.setProperty(key, value);
            }
        }
        System.out.println("Environment variables loaded into system properties.");
    }

    /**
     * Main method for testing and CLI usage.
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("EnvironmentManager Usage:");
            System.out.println("  get <key>           - Get a property value");
            System.out.println("  set <key> <value>   - Set a property value");
            System.out.println("  remove <key>        - Remove a property");
            System.out.println("  list                - List all properties");
            System.out.println("  reload              - Reload environment file");
            System.out.println("  load-system         - Load env vars into system properties");
            return;
        }

        EnvironmentManager manager = new EnvironmentManager();
        String command = args[0].toLowerCase();

        switch (command) {
            case "get":
                if (args.length < 2) {
                    System.err.println("Error: Missing key argument");
                    System.exit(1);
                }
                String value = manager.getProperty(args[1]);
                System.out.println(value != null ? value : "(not set)");
                break;

            case "set":
                if (args.length < 3) {
                    System.err.println("Error: Missing key or value argument");
                    System.exit(1);
                }
                manager.setProperty(args[1], args[2]);
                System.out.println("Property set: " + args[1] + " = " + args[2]);
                break;

            case "remove":
                if (args.length < 2) {
                    System.err.println("Error: Missing key argument");
                    System.exit(1);
                }
                manager.removeProperty(args[1]);
                System.out.println("Property removed: " + args[1]);
                break;

            case "list":
                Map<String, String> allProps = manager.getAllProperties();
                System.out.println("All properties:");
                allProps.forEach((key, val) -> 
                    System.out.println("  " + key + " = " + (val.isEmpty() ? "(empty)" : val))
                );
                break;

            case "reload":
                manager.reload();
                System.out.println("Environment file reloaded.");
                break;

            case "load-system":
                manager.loadIntoSystemProperties();
                System.out.println("Environment variables loaded into system properties.");
                break;

            default:
                System.err.println("Unknown command: " + command);
                System.exit(1);
        }
    }
}
