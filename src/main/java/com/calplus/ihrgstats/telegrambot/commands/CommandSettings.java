package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.discordbot.logs.DiscordLog;
import com.calplus.ihrgstats.telegrambot.logs.TelegramLog;
import com.calplus.ihrgstats.utils.*;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.*;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Command handler for /settings command.
 * Allows admin to view and toggle boolean settings from application.properties
 */
public class CommandSettings {
    private final DiscordLog discordLog;
    private final TelegramLog telegramLog;
    private final String adminUserId;
    private final String dbPath;
    private static final Map<String, SettingsSelectionState> userSelectionStates = new ConcurrentHashMap<>();

    /**
     * Selection state for settings command
     */
    private static class SettingsSelectionState extends SelectionState {
        boolean awaitingManualInput = false;
        String settingKey = null; // The setting key being modified (e.g., "settings.homeHall" or "settings.maxSeeds")
    }

    public CommandSettings() {
        // Load environment variables
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();
        
        this.discordLog = new DiscordLog();
        this.telegramLog = new TelegramLog();
        this.adminUserId = PropertyResolver.getProperty("telegram.admin.userId", "");
        this.dbPath = DatabaseHelper.getDefaultDatabasePathString();
    }

    /**
     * Checks if a user is an admin
     * @param userId The user ID to check
     * @return true if user is admin, false otherwise
     */
    public boolean isAdmin(String userId) {
        return !adminUserId.isEmpty() && adminUserId.equals(userId);
    }

    /**
     * Handles the /settings command
     * @param userId The user ID who issued the command
     * @return SettingsResponse containing the message and button options
     */
    public SettingsResponse handleCommand(String userId) {
        discordLog.logInfo(String.format("User %s requested /settings command", userId));
        telegramLog.logInfo(String.format("User %s requested /settings command", userId));

        // Check admin authorization
        if (!isAdmin(userId)) {
            String errorMsg = "❌ Access Denied: Only administrators can use the /settings command.";
            discordLog.logWarning(String.format("Non-admin user %s attempted to use /settings", userId));
            telegramLog.logWarning(String.format("Non-admin user %s attempted to use /settings", userId));
            return new SettingsResponse(errorMsg, null);
        }

        // Get all settings properties
        Map<String, String> settings = PropertyManager.getPropertiesByPrefix("settings.");
        
        if (settings.isEmpty()) {
            String errorMsg = "⚠️ No configurable settings found in application.properties";
            discordLog.logWarning("No settings.* properties found");
            telegramLog.logWarning("No settings.* properties found");
            return new SettingsResponse(errorMsg, null);
        }

        // Build message
        StringBuilder message = new StringBuilder();
        message.append("⚙️ **Application Settings**\n\n");
        message.append("Configure boolean settings below:\n\n");

        List<String> buttonLabels = new ArrayList<>();
        List<String> buttonCallbacks = new ArrayList<>();

        for (Map.Entry<String, String> entry : settings.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            String description = PropertyManager.getSettingDescription(key);
            
            // Special handling for homeHall (non-boolean)
            if (key.equals("settings.homeHall")) {
                String currentHall = value.isEmpty() ? "Not set" : value;
                message.append(String.format("🏠 **Home Hall: %s**\n", currentHall));
                message.append(String.format("   %s\n\n", description));
                
                // Create button for homeHall selection
                buttonLabels.add("🏠 Change Home Hall");
                buttonCallbacks.add("setting_homeHall_select");
                continue;
            }
            
            // Special handling for timezone
            if (key.equals("settings.timezone")) {
                String currentTimezone = value.isEmpty() ? "Not set" : formatTimezoneDisplay(value);
                message.append(String.format("🌍 **Timezone: %s**\n", currentTimezone));
                message.append(String.format("   %s\n\n", description));
                
                // Create button for timezone selection
                buttonLabels.add("🌍 Change Timezone");
                buttonCallbacks.add("setting_timezone_select");
                continue;
            }
            
            // Special handling for maxSeeds (integer)
            if (key.equals("settings.maxSeeds")) {
                String currentSeeds = value.isEmpty() ? "Not set" : value;
                message.append(String.format("🎯 **Max Seeds: %s**\n", currentSeeds));
                message.append(String.format("   %s\n\n", description));
                
                // Create button for maxSeeds input
                buttonLabels.add("🎯 Change Max Seeds");
                buttonCallbacks.add("setting_maxSeeds_select");
                continue;
            }
            
            // Determine current status (for boolean settings)
            boolean isEnabled = value.equalsIgnoreCase("true");
            String statusEmoji = isEnabled ? "✅" : "❌";
            String statusText = isEnabled ? "Enabled" : "Disabled";
            
            message.append(String.format("%s **%s**\n", statusEmoji, statusText));
            message.append(String.format("   %s\n\n", description));

            // Create button label and callback
            String toggleText = isEnabled ? "Disable" : "Enable";
            String buttonLabel = String.format("%s %s", toggleText, extractSettingName(key));
            String buttonCallback = String.format("setting_toggle_%s", key);
            
            buttonLabels.add(buttonLabel);
            buttonCallbacks.add(buttonCallback);
        }
        
        // Add cancel button
        buttonLabels.add("❌ Cancel");
        buttonCallbacks.add("settings_cancel");

        discordLog.logSuccess(String.format("Sent settings list to admin user %s", userId));
        telegramLog.logSuccess(String.format("Sent settings list to admin user %s", userId));

        return new SettingsResponse(message.toString(), 
            new ButtonConfig(buttonLabels.toArray(new String[0]), 
                           buttonCallbacks.toArray(new String[0])));
    }
    
    /**
     * Handles settings cancellation
     * @param userId The user ID who cancelled
     * @return Response message
     */
    public String handleCancel(String userId) {
        discordLog.logInfo(String.format("User %s cancelled settings", userId));
        telegramLog.logInfo(String.format("User %s cancelled settings", userId));
        return "ℹ️ Settings menu closed.";
    }

    /**
     * Handles a settings toggle callback
     * @param callbackData The callback data from the button (e.g., "setting_toggle_settings.perfElo.enabled")
     * @param userId The user ID who clicked the button
     * @return Response message after toggling the setting
     */
    public String handleToggle(String callbackData, String userId) {
        // Check admin authorization
        if (!isAdmin(userId)) {
            String errorMsg = "❌ Access Denied: Only administrators can change settings.";
            discordLog.logWarning(String.format("Non-admin user %s attempted to toggle setting", userId));
            telegramLog.logWarning(String.format("Non-admin user %s attempted to toggle setting", userId));
            return errorMsg;
        }

        // Extract setting key from callback data
        if (!callbackData.startsWith("setting_toggle_")) {
            return "❌ Error: Invalid callback data";
        }

        String settingKey = callbackData.substring("setting_toggle_".length());
        
        discordLog.logInfo(String.format("Admin %s toggling setting: %s", userId, settingKey));
        telegramLog.logInfo(String.format("Admin %s toggling setting: %s", userId, settingKey));

        // Get current value
        String currentValue = PropertyResolver.getProperty(settingKey);
        if (currentValue == null) {
            String errorMsg = String.format("❌ Error: Setting not found: %s", settingKey);
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            return errorMsg;
        }

        // Toggle value
        boolean currentBool = currentValue.equalsIgnoreCase("true");
        boolean newBool = !currentBool;
        String newValue = String.valueOf(newBool);

        // Update property
        boolean success = PropertyManager.updateProperty(settingKey, newValue);
        
        if (success) {
            String settingName = extractSettingName(settingKey);
            String statusEmoji = newBool ? "✅" : "❌";
            String statusText = newBool ? "enabled" : "disabled";
            String successMsg = String.format("%s Successfully %s **%s**\n\nUse /settings to see updated configuration.", 
                statusEmoji, statusText, settingName);
            
            discordLog.logSuccess(String.format("Setting %s toggled to %s", settingKey, newValue));
            telegramLog.logSuccess(String.format("Setting %s toggled to %s", settingKey, newValue));
            
            return successMsg;
        } else {
            String errorMsg = String.format("❌ Error: Failed to update setting %s", settingKey);
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            return errorMsg;
        }
    }

    /**
     * Handles the home hall selection request
     * @param userId The user ID who requested hall selection
     * @return Response containing hall selection buttons
     */
    public SettingsResponse handleHomeHallSelection(String userId) {
        // Check admin authorization
        if (!isAdmin(userId)) {
            String errorMsg = "❌ Access Denied: Only administrators can change settings.";
            discordLog.logWarning(String.format("Non-admin user %s attempted to change home hall", userId));
            telegramLog.logWarning(String.format("Non-admin user %s attempted to change home hall", userId));
            return new SettingsResponse(errorMsg, null);
        }

        discordLog.logInfo(String.format("Admin %s requested home hall selection", userId));
        telegramLog.logInfo(String.format("Admin %s requested home hall selection", userId));

        try {
            // Query database for all distinct halls
            List<String> halls = new ArrayList<>();
            try (Connection conn = DatabaseHelper.getConnection(dbPath);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT DISTINCT hall FROM A1_PlayerStats WHERE hall IS NOT NULL AND hall != '' ORDER BY hall")) {
                
                while (rs.next()) {
                    String hall = rs.getString("hall");
                    if (hall != null && !hall.trim().isEmpty()) {
                        halls.add(hall);
                    }
                }
            }

            if (halls.isEmpty()) {
                String errorMsg = "⚠️ No halls found in database.";
                discordLog.logWarning(errorMsg);
                telegramLog.logWarning(errorMsg);
                return new SettingsResponse(errorMsg, null);
            }

            // Sort halls: numeric first, then alphabetic
            halls.sort((h1, h2) -> {
                boolean h1Numeric = h1.matches("\\d+");
                boolean h2Numeric = h2.matches("\\d+");
                
                if (h1Numeric && h2Numeric) {
                    // Both numeric, compare as integers
                    return Integer.compare(Integer.parseInt(h1), Integer.parseInt(h2));
                } else if (h1Numeric) {
                    // h1 is numeric, h2 is not - h1 comes first
                    return -1;
                } else if (h2Numeric) {
                    // h2 is numeric, h1 is not - h2 comes first
                    return 1;
                } else {
                    // Both non-numeric, compare alphabetically
                    return h1.compareToIgnoreCase(h2);
                }
            });

            // Get current home hall
            String currentHomeHall = PropertyResolver.getProperty("settings.homeHall", "");

            // Build message
            StringBuilder message = new StringBuilder();
            message.append("🏠 **Select Your Home Hall**\n\n");
            message.append("Choose your home hall from the list below.\n");
            if (!currentHomeHall.isEmpty()) {
                message.append(String.format("Current home hall: **%s**\n", currentHomeHall));
            }
            message.append("\nSelect a hall or choose manual input:");

            // Create buttons in 4-column layout
            List<String> buttonLabels = new ArrayList<>();
            List<String> buttonCallbacks = new ArrayList<>();

            for (String hall : halls) {
                buttonLabels.add(hall);
                buttonCallbacks.add("setting_homeHall_" + hall);
            }

            // Add manual input and cancel buttons
            buttonLabels.add("✏️ Manual Input");
            buttonCallbacks.add("setting_homeHall_manual");
            buttonLabels.add("❌ Cancel");
            buttonCallbacks.add("settings_cancel");

            discordLog.logSuccess(String.format("Sent hall selection menu to admin %s with %d halls", userId, halls.size()));
            telegramLog.logSuccess(String.format("Sent hall selection menu to admin %s with %d halls", userId, halls.size()));

            return new SettingsResponse(message.toString(), 
                new ButtonConfig(buttonLabels.toArray(new String[0]), 
                               buttonCallbacks.toArray(new String[0]), 
                               4)); // 4 columns per row

        } catch (Exception e) {
            String errorMsg = "❌ Error: Failed to load halls from database: " + e.getMessage();
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            return new SettingsResponse(errorMsg, null);
        }
    }

    /**
     * Handles a home hall selection callback
     * @param callbackData The callback data from the button (e.g., "setting_homeHall_4")
     * @param userId The user ID who clicked the button
     * @return Response message after setting the home hall
     */
    public String handleHomeHallCallback(String callbackData, String userId) {
        // Check admin authorization
        if (!isAdmin(userId)) {
            String errorMsg = "❌ Access Denied: Only administrators can change settings.";
            discordLog.logWarning(String.format("Non-admin user %s attempted to set home hall", userId));
            telegramLog.logWarning(String.format("Non-admin user %s attempted to set home hall", userId));
            return errorMsg;
        }

        // Handle manual input request
        if (callbackData.equals("setting_homeHall_manual")) {
            discordLog.logInfo(String.format("Admin %s requested manual home hall input", userId));
            telegramLog.logInfo(String.format("Admin %s requested manual home hall input", userId));
            
            // Set user state to await manual input
            SettingsSelectionState state = userSelectionStates.computeIfAbsent(userId, k -> new SettingsSelectionState());
            state.awaitingManualInput = true;
            state.settingKey = "settings.homeHall";
            
            String currentHomeHall = PropertyResolver.getProperty("settings.homeHall", "");
            String currentValueDisplay = currentHomeHall.isEmpty() ? "Not set" : currentHomeHall;
            
            return String.format("✏️ **Manual Home Hall Input**\n\n" +
                                "Please reply with the hall number or name you want to set as your home hall.\n\n" +
                                "**Current home hall:** %s\n\n" +
                                "_Example: Type '4' or 'Hall 4' to set Hall 4 as your home hall_", 
                                currentValueDisplay);
        }

        // Extract hall value from callback data
        if (!callbackData.startsWith("setting_homeHall_")) {
            return "❌ Error: Invalid callback data";
        }

        String hallValue = callbackData.substring("setting_homeHall_".length());
        
        discordLog.logInfo(String.format("Admin %s setting home hall to: %s", userId, hallValue));
        telegramLog.logInfo(String.format("Admin %s setting home hall to: %s", userId, hallValue));

        // Update property
        boolean success = PropertyManager.updateProperty("settings.homeHall", hallValue);
        
        if (success) {
            String successMsg = String.format("🏠 Successfully set home hall to **%s**\n\nUse /settings to see updated configuration.", hallValue);
            
            discordLog.logSuccess(String.format("Home hall set to %s", hallValue));
            telegramLog.logSuccess(String.format("Home hall set to %s", hallValue));
            
            return successMsg;
        } else {
            String errorMsg = "❌ Error: Failed to update home hall setting";
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            return errorMsg;
        }
    }

    /**
     * Handles text input for manual home hall entry
     * @param userId The user ID who sent the text
     * @param text The text message content
     * @return Response message after processing the input
     */
    public String handleTextInput(String userId, String text) {
        // Check if user is in manual input mode
        SettingsSelectionState state = userSelectionStates.get(userId);
        if (state == null || !state.awaitingManualInput) {
            return null; // Not awaiting input, ignore
        }
        
        // Check admin authorization
        if (!isAdmin(userId)) {
            userSelectionStates.remove(userId);
            return "❌ Access Denied: Only administrators can change settings.";
        }
        
        // Clean up state
        String settingKey = state.settingKey;
        userSelectionStates.remove(userId);
        
        // Handle timezone setting
        if ("settings.timezone".equals(settingKey)) {
            String inputValue = text.trim();
            
            // Remove UTC prefix if present
            inputValue = inputValue.replaceAll("(?i)^UTC\\s*", "");
            
            // Validate timezone offset format
            try {
                double offset = Double.parseDouble(inputValue);
                if (offset < -12 || offset > 14) {
                    return "❌ Invalid input: Timezone offset must be between -12 and +14.";
                }
                
                String timezoneValue = String.valueOf(offset);
                
                discordLog.logInfo(String.format("Admin %s setting timezone to: %s (manual input)", userId, timezoneValue));
                telegramLog.logInfo(String.format("Admin %s setting timezone to: %s (manual input)", userId, timezoneValue));
                
                // Update property
                boolean success = PropertyManager.updateProperty("settings.timezone", timezoneValue);
                
                if (success) {
                    String successMsg = String.format("🌍 Successfully set timezone to **%s**\n\nUse /settings to see updated configuration.", 
                                                    formatTimezoneDisplay(timezoneValue));
                    
                    discordLog.logSuccess(String.format("Timezone set to %s (manual input)", timezoneValue));
                    telegramLog.logSuccess(String.format("Timezone set to %s (manual input)", timezoneValue));
                    
                    return successMsg;
                } else {
                    String errorMsg = "❌ Error: Failed to update timezone setting";
                    discordLog.logError(errorMsg);
                    telegramLog.logError(errorMsg);
                    return errorMsg;
                }
            } catch (NumberFormatException e) {
                return "❌ Invalid input: Timezone offset must be a valid number (e.g., +8, -5, +9.5).";
            }
        }
        
        // Handle maxSeeds setting
        if ("settings.maxSeeds".equals(settingKey)) {
            String inputValue = text.trim();
            
            // Validate positive integer
            try {
                double maxSeedsValue = Double.parseDouble(inputValue);
                if (maxSeedsValue <= 0) {
                    return "❌ Invalid input: Max seeds must be a positive integer.";
                }
                
                discordLog.logInfo(String.format("Admin %s setting maxSeeds to: %d", userId, maxSeedsValue));
                telegramLog.logInfo(String.format("Admin %s setting maxSeeds to: %d", userId, maxSeedsValue));
                
                // Update property
                boolean success = PropertyManager.updateProperty("settings.maxSeeds", String.valueOf(maxSeedsValue));
                
                if (success) {
                    String successMsg = String.format("🎯 Successfully set maxSeeds to **%d**\n\nUse /settings to see updated configuration.", maxSeedsValue);
                    
                    discordLog.logSuccess(String.format("MaxSeeds set to %d", maxSeedsValue));
                    telegramLog.logSuccess(String.format("MaxSeeds set to %d", maxSeedsValue));
                    
                    return successMsg;
                } else {
                    String errorMsg = "❌ Error: Failed to update maxSeeds setting";
                    discordLog.logError(errorMsg);
                    telegramLog.logError(errorMsg);
                    return errorMsg;
                }
            } catch (NumberFormatException e) {
                return "❌ Invalid input: Max seeds must be a valid positive integer.";
            }
        }
        
        // Handle homeHall setting (default)
        String hallValue = text.trim();
        if (hallValue.isEmpty()) {
            return "❌ Invalid input: Hall value cannot be empty.";
        }
        
        discordLog.logInfo(String.format("Admin %s setting home hall to: %s (manual input)", userId, hallValue));
        telegramLog.logInfo(String.format("Admin %s setting home hall to: %s (manual input)", userId, hallValue));
        
        // Update property
        boolean success = PropertyManager.updateProperty("settings.homeHall", hallValue);
        
        if (success) {
            String successMsg = String.format("🏠 Successfully set home hall to **%s**\n\nUse /settings to see updated configuration.", hallValue);
            
            discordLog.logSuccess(String.format("Home hall set to %s (manual input)", hallValue));
            telegramLog.logSuccess(String.format("Home hall set to %s (manual input)", hallValue));
            
            return successMsg;
        } else {
            String errorMsg = "❌ Error: Failed to update home hall setting";
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            return errorMsg;
        }
    }

    /**
     * Handles the max seeds selection request (direct to manual input)
     * @param userId The user ID who requested maxSeeds change
     * @return Response prompting for manual input
     */
    public SettingsResponse handleMaxSeedsSelection(String userId) {
        // Check admin authorization
        if (!isAdmin(userId)) {
            String errorMsg = "❌ Access Denied: Only administrators can change settings.";
            discordLog.logWarning(String.format("Non-admin user %s attempted to change maxSeeds", userId));
            telegramLog.logWarning(String.format("Non-admin user %s attempted to change maxSeeds", userId));
            return new SettingsResponse(errorMsg, null);
        }

        discordLog.logInfo(String.format("Admin %s requested maxSeeds change", userId));
        telegramLog.logInfo(String.format("Admin %s requested maxSeeds change", userId));

        // Set manual input mode
        SettingsSelectionState state = new SettingsSelectionState();
        state.awaitingManualInput = true;
        state.settingKey = "settings.maxSeeds";
        userSelectionStates.put(userId, state);

        String currentMaxSeeds = PropertyResolver.getProperty("settings.maxSeeds", "361");
        String message = String.format("Please enter the new maxSeeds value (positive integer).\n\nCurrent value: **%s**", currentMaxSeeds);

        return new SettingsResponse(message, null);
    }

    /**
     * Handles the maxSeeds callback
     * This is only for cancel operation as selection goes directly to manual input
     */
    public String handleMaxSeedsCallback(String callbackData, String userId) {
        // Check admin authorization
        if (!isAdmin(userId)) {
            String errorMsg = "❌ Access Denied: Only administrators can change settings.";
            discordLog.logWarning(String.format("Non-admin user %s attempted unauthorized maxSeeds callback", userId));
            telegramLog.logWarning(String.format("Non-admin user %s attempted unauthorized maxSeeds callback", userId));
            return errorMsg;
        }

        // Only cancel is expected here
        if (callbackData.equals("settings_cancel")) {
            userSelectionStates.remove(userId);
            return "🔄 Cancelled maxSeeds change.";
        }

        return "❌ Unknown callback for maxSeeds";
    }

    /**
     * Handles the timezone selection request
     * @param userId The user ID who requested timezone change
     * @return Response containing timezone selection buttons
     */
    public SettingsResponse handleTimezoneSelection(String userId) {
        // Check admin authorization
        if (!isAdmin(userId)) {
            String errorMsg = "❌ Access Denied: Only administrators can change settings.";
            discordLog.logWarning(String.format("Non-admin user %s attempted to change timezone", userId));
            telegramLog.logWarning(String.format("Non-admin user %s attempted to change timezone", userId));
            return new SettingsResponse(errorMsg, null);
        }

        discordLog.logInfo(String.format("Admin %s requested timezone selection", userId));
        telegramLog.logInfo(String.format("Admin %s requested timezone selection", userId));

        // Get current timezone
        String currentTimezone = PropertyResolver.getProperty("settings.timezone", "");

        // Build message
        StringBuilder message = new StringBuilder();
        message.append("🌍 **Select Timezone**\n\n");
        message.append("Choose your timezone offset from UTC.\n");
        if (!currentTimezone.isEmpty()) {
            message.append(String.format("Current timezone: **%s**\n", formatTimezoneDisplay(currentTimezone)));
        }
        message.append("\nSelect a timezone or choose manual input:");

        // Create buttons for common UTC offsets from -12 to +14
        List<String> buttonLabels = new ArrayList<>();
        List<String> buttonCallbacks = new ArrayList<>();

        // Negative offsets (UTC-12 to UTC-1)
        for (int offset = -12; offset <= -1; offset++) {
            buttonLabels.add(String.format("UTC%d", offset));
            buttonCallbacks.add("setting_timezone_" + offset);
        }

        // UTC+0
        buttonLabels.add("UTC");
        buttonCallbacks.add("setting_timezone_0");

        // Positive offsets (UTC+1 to UTC+14)
        for (int offset = 1; offset <= 14; offset++) {
            buttonLabels.add(String.format("UTC+%d", offset));
            buttonCallbacks.add("setting_timezone_+" + offset);
        }

        // Add common half-hour offsets
        buttonLabels.add("UTC+3.5");
        buttonCallbacks.add("setting_timezone_+3.5");
        buttonLabels.add("UTC+4.5");
        buttonCallbacks.add("setting_timezone_+4.5");
        buttonLabels.add("UTC+5.5");
        buttonCallbacks.add("setting_timezone_+5.5");
        buttonLabels.add("UTC+6.5");
        buttonCallbacks.add("setting_timezone_+6.5");
        buttonLabels.add("UTC+9.5");
        buttonCallbacks.add("setting_timezone_+9.5");
        buttonLabels.add("UTC+10.5");
        buttonCallbacks.add("setting_timezone_+10.5");

        // Add manual input and cancel buttons
        buttonLabels.add("✏️ Manual Input");
        buttonCallbacks.add("setting_timezone_manual");
        buttonLabels.add("❌ Cancel");
        buttonCallbacks.add("settings_cancel");

        discordLog.logSuccess(String.format("Sent timezone selection menu to admin %s", userId));
        telegramLog.logSuccess(String.format("Sent timezone selection menu to admin %s", userId));

        return new SettingsResponse(message.toString(), 
            new ButtonConfig(buttonLabels.toArray(new String[0]), 
                           buttonCallbacks.toArray(new String[0]), 
                           4)); // 4 columns per row
    }

    /**
     * Handles a timezone selection callback
     * @param callbackData The callback data from the button (e.g., "setting_timezone_+8")
     * @param userId The user ID who clicked the button
     * @return Response message after setting the timezone
     */
    public String handleTimezoneCallback(String callbackData, String userId) {
        // Check admin authorization
        if (!isAdmin(userId)) {
            String errorMsg = "❌ Access Denied: Only administrators can change settings.";
            discordLog.logWarning(String.format("Non-admin user %s attempted to set timezone", userId));
            telegramLog.logWarning(String.format("Non-admin user %s attempted to set timezone", userId));
            return errorMsg;
        }

        // Handle manual input request
        if (callbackData.equals("setting_timezone_manual")) {
            discordLog.logInfo(String.format("Admin %s requested manual timezone input", userId));
            telegramLog.logInfo(String.format("Admin %s requested manual timezone input", userId));
            
            // Set user state to await manual input
            SettingsSelectionState state = userSelectionStates.computeIfAbsent(userId, k -> new SettingsSelectionState());
            state.awaitingManualInput = true;
            state.settingKey = "settings.timezone";
            
            String currentTimezone = PropertyResolver.getProperty("settings.timezone", "");
            String currentValueDisplay = currentTimezone.isEmpty() ? "Not set" : formatTimezoneDisplay(currentTimezone);
            
            return String.format("✏️ **Manual Timezone Input**\n\n" +
                                "Please reply with the timezone offset you want to set.\n\n" +
                                "**Current timezone:** %s\n\n" +
                                "_Example: Type '+8' for UTC+8, '-5' for UTC-5, or '+9.5' for UTC+9.5_", 
                                currentValueDisplay);
        }

        // Extract timezone offset from callback data
        if (!callbackData.startsWith("setting_timezone_")) {
            return "❌ Error: Invalid callback data";
        }

        String timezoneValue = callbackData.substring("setting_timezone_".length());
        
        // Validate and format timezone value
        try {
            double offset = Double.parseDouble(timezoneValue);
            if (offset < -12 || offset > 14) {
                return "❌ Error: Timezone offset must be between -12 and +14";
            }
            timezoneValue = String.valueOf(offset);
        } catch (NumberFormatException e) {
            return "❌ Error: Invalid timezone format";
        }
        
        discordLog.logInfo(String.format("Admin %s setting timezone to: %s", userId, timezoneValue));
        telegramLog.logInfo(String.format("Admin %s setting timezone to: %s", userId, timezoneValue));

        // Update property
        boolean success = PropertyManager.updateProperty("settings.timezone", timezoneValue);
        
        if (success) {
            String successMsg = String.format("🌍 Successfully set timezone to **%s**\n\nUse /settings to see updated configuration.", 
                                            formatTimezoneDisplay(timezoneValue));
            
            discordLog.logSuccess(String.format("Timezone set to %s", timezoneValue));
            telegramLog.logSuccess(String.format("Timezone set to %s", timezoneValue));
            
            return successMsg;
        } else {
            String errorMsg = "❌ Error: Failed to update timezone setting";
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            return errorMsg;
        }
    }

    /**
     * Formats a timezone value for display
     */
    private String formatTimezoneDisplay(String timezoneValue) {
        if (timezoneValue == null || timezoneValue.isEmpty()) {
            return "Not set";
        }
        
        try {
            double offset = Double.parseDouble(timezoneValue);
            if (offset == 0) {
                return "UTC";
            } else if (offset > 0) {
                return String.format("UTC+%.1f", offset).replace(".0", "");
            } else {
                return String.format("UTC%.1f", offset).replace(".0", "");
            }
        } catch (NumberFormatException e) {
            return timezoneValue;
        }
    }

    /**
     * Extracts a user-friendly setting name from the property key
     */
    private String extractSettingName(String key) {
        // Remove "settings." prefix
        String name = key.replace("settings.", "");
        
        // Convert camelCase/dot notation to space-separated words
        name = name.replaceAll("([a-z])([A-Z])", "$1 $2");
        name = name.replace(".", " ");
        
        // Capitalize first letter of each word
        String[] words = name.split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.length() > 0) {
                result.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    result.append(word.substring(1));
                }
                result.append(" ");
            }
        }
        
        return result.toString().trim();
    }

    /**
     * Response object containing message and button configuration
     */
    public static class SettingsResponse {
        public final String message;
        public final ButtonConfig buttons;

        public SettingsResponse(String message, ButtonConfig buttons) {
            this.message = message;
            this.buttons = buttons;
        }
    }

    /**
     * Button configuration for inline keyboard
     */
    public static class ButtonConfig {
        public final String[] labels;
        public final String[] callbacks;
        public final int columnsPerRow; // Number of columns per row (default: 1)

        public ButtonConfig(String[] labels, String[] callbacks) {
            this(labels, callbacks, 1);
        }

        public ButtonConfig(String[] labels, String[] callbacks, int columnsPerRow) {
            this.labels = labels;
            this.callbacks = callbacks;
            this.columnsPerRow = columnsPerRow;
        }
    }
}
