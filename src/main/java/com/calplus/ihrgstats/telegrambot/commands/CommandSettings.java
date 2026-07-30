package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.A3_Halls;
import com.calplus.ihrgstats.utils.*;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.*;
import com.calplus.ihrgstats.utils.TelegramHtml;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Command handler for /settings command.
 * Allows admin to view and toggle boolean settings from application.properties
 */
public class CommandSettings {
    private final LogHelper logHelper;
    private final com.calplus.ihrgstats.databasemanager.F16_Admins admins = new com.calplus.ihrgstats.databasemanager.F16_Admins();
    private static final Map<String, SettingsSelectionState> userSelectionStates = new ConcurrentHashMap<>();

    /**
     * The only settings keys /settings' toggle buttons are allowed to flip.
     * Every other settings.* key (homeHall, currentYear, timezone) is
     * non-boolean and handled through its own dedicated flow - a toggle
     * callback must never be allowed to write "true"/"false" into one of
     * those, nor into any non-settings.* property.
     */
    private static final Set<String> TOGGLEABLE_SETTING_KEYS = Set.of(
            "settings.allowNonAdminUploads",
            "settings.allowAllChannelsProcessing"
    );

    /**
     * Selection state for settings command
     */
    private static class SettingsSelectionState extends SelectionState {
        boolean awaitingManualInput = false;
        String settingKey = null; // The setting key being modified (e.g., "settings.homeHall" or "settings.currentYear")
    }

    public CommandSettings() {
        // Load environment variables
        EnvironmentManager.ensureSystemPropertiesLoaded();

        this.logHelper = new LogHelper();
    }

    /**
     * Checks if a user is an admin. Fails closed (denies) on a database
     * error rather than risking a false "admin".
     * @param userId The user ID to check
     * @return true if user is admin, false otherwise
     */
    public boolean isAdmin(String userId) {
        try {
            return admins.isAdmin(com.calplus.ihrgstats.databasemanager.F16_Admins.PLATFORM_TELEGRAM, userId);
        } catch (java.sql.SQLException e) {
            logHelper.logError("Database error checking admin status: " + e.getMessage());
            return false;
        }
    }

    /**
     * Handles the /settings command
     * @param userId The user ID who issued the command
     * @return SettingsResponse containing the message and button options
     */
    public SettingsResponse handleCommand(String userId) {
        // Drop stale manual-input states (>10 min old) - same expiry every
        // other wizard command applies via cleanupOldStates; previously
        // /settings was the only state-holding command that never did this,
        // so an abandoned "reply with a value" prompt stayed armed forever.
        TelegramCommandUtils.cleanupOldStates(userSelectionStates);

        String userInfo = com.calplus.ihrgstats.telegrambot.listener.TelegramListener.formatUserInfo(userId);
        logHelper.logInfo(String.format("%s requested /settings command", userInfo));

        // Check admin authorization
        if (!isAdmin(userId)) {
            String errorMsg = "❌ Access Denied: Only administrators can use the /settings command.";
            logHelper.logWarning(String.format("Non-admin %s attempted to use /settings", userInfo));
            return new SettingsResponse(errorMsg, null);
        }

        // Get all settings properties
        Map<String, String> settings = PropertyManager.getPropertiesByPrefix("settings.");
        
        if (settings.isEmpty()) {
            String errorMsg = "⚠️ No configurable settings found in application.properties";
            logHelper.logWarning("No settings.* properties found");
            return new SettingsResponse(errorMsg, null);
        }

        // Build message
        StringBuilder message = new StringBuilder();
        message.append("⚙️ <b>Application Settings</b>\n\n");
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
                message.append(String.format("🏠 <b>Home Hall: %s</b>\n", TelegramHtml.escape(currentHall)));
                message.append(String.format("   %s\n\n", TelegramHtml.escape(description)));

                // Create button for homeHall selection
                buttonLabels.add("🏠 Change Home Hall");
                buttonCallbacks.add("setting_homeHall_select");
                continue;
            }

            // Special handling for timezone
            if (key.equals("settings.timezone")) {
                String currentTimezone = value.isEmpty() ? "Not set" : formatTimezoneDisplay(value);
                message.append(String.format("🌍 <b>Timezone: %s</b>\n", TelegramHtml.escape(currentTimezone)));
                message.append(String.format("   %s\n\n", TelegramHtml.escape(description)));

                // Create button for timezone selection
                buttonLabels.add("🌍 Change Timezone");
                buttonCallbacks.add("setting_timezone_select");
                continue;
            }

            // Special handling for currentYear (integer)
            if (key.equals("settings.currentYear")) {
                String currentYearValue = value.isEmpty() ? "Not set" : value;
                message.append(String.format("📅 <b>Current Year: %s</b>\n", TelegramHtml.escape(currentYearValue)));
                message.append(String.format("   %s\n\n", TelegramHtml.escape(description)));

                // Create button for currentYear input
                buttonLabels.add("📅 Change Current Year");
                buttonCallbacks.add("setting_currentYear_select");
                continue;
            }

            // Determine current status (for boolean settings)
            boolean isEnabled = value.equalsIgnoreCase("true");
            String statusEmoji = isEnabled ? "✅" : "❌";
            String statusText = isEnabled ? "Enabled" : "Disabled";

            message.append(String.format("%s <b>%s</b>\n", statusEmoji, statusText));
            message.append(String.format("   %s\n\n", TelegramHtml.escape(description)));

            // Only render a toggle button for keys handleToggle will actually
            // accept - keeps this listing and TOGGLEABLE_SETTING_KEYS from
            // being able to drift out of sync (an unlisted key is still
            // displayed above, just without a button that would only fail).
            if (TOGGLEABLE_SETTING_KEYS.contains(key)) {
                String toggleText = isEnabled ? "Disable" : "Enable";
                String buttonLabel = String.format("%s %s", toggleText, extractSettingName(key));
                String buttonCallback = String.format("setting_toggle_%s", key);

                buttonLabels.add(buttonLabel);
                buttonCallbacks.add(buttonCallback);
            }
        }
        
        // Add cancel button
        buttonLabels.add("❌ Cancel");
        buttonCallbacks.add("settings_cancel");

        logHelper.logSuccess(String.format("Sent settings list to admin %s", com.calplus.ihrgstats.telegrambot.listener.TelegramListener.formatUserInfo(userId)));

        return new SettingsResponse(message.toString(),
            new ButtonConfig(buttonLabels.toArray(new String[0]),
                           buttonCallbacks.toArray(new String[0]),
                           1)); // one setting per row
    }
    
    /**
     * Handles settings cancellation
     * @param userId The user ID who cancelled
     * @return Response message
     */
    public String handleCancel(String userId) {
        String userInfo = com.calplus.ihrgstats.telegrambot.listener.TelegramListener.formatUserInfo(userId);
        logHelper.logInfo(String.format("%s cancelled settings", userInfo));
        return "ℹ️ Settings menu closed.";
    }

    /**
     * Handles a settings toggle callback
     * @param callbackData The callback data from the button (e.g., "setting_toggle_settings.allowAllChannelsProcessing")
     * @param userId The user ID who clicked the button
     * @return Response message after toggling the setting
     */
    public String handleToggle(String callbackData, String userId) {
        String userInfo = com.calplus.ihrgstats.telegrambot.listener.TelegramListener.formatUserInfo(userId);
        // Check admin authorization
        if (!isAdmin(userId)) {
            String errorMsg = "❌ Access Denied: Only administrators can change settings.";
            logHelper.logWarning(String.format("Non-admin %s attempted to toggle setting", userInfo));
            return errorMsg;
        }

        // Extract setting key from callback data
        if (!callbackData.startsWith("setting_toggle_")) {
            return "❌ Error: Invalid callback data";
        }

        String settingKey = callbackData.substring("setting_toggle_".length());

        if (!TOGGLEABLE_SETTING_KEYS.contains(settingKey)) {
            String errorMsg = String.format("❌ Error: '%s' is not a valid toggleable setting.", settingKey);
            logHelper.logWarning(String.format("Admin %s attempted to toggle a non-allowlisted key: %s", userInfo, settingKey));
            return errorMsg;
        }

        logHelper.logInfo(String.format("Admin %s toggling setting: %s", userInfo, settingKey));

        // Get current value
        String currentValue = PropertyResolver.getProperty(settingKey);
        if (currentValue == null) {
            String errorMsg = String.format("❌ Error: Setting not found: %s", settingKey);
            logHelper.logError(errorMsg);
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
            String successMsg = String.format("%s Successfully %s <b>%s</b>\n\nUse /settings to see updated configuration.",
                statusEmoji, statusText, settingName);
            
            logHelper.logSuccess(String.format("Setting %s toggled to %s", settingKey, newValue));
            
            return successMsg;
        } else {
            String errorMsg = String.format("❌ Error: Failed to update setting %s", settingKey);
            logHelper.logError(errorMsg);
            return errorMsg;
        }
    }

    /**
     * Handles the home hall selection request
     * @param userId The user ID who requested hall selection
     * @return Response containing hall selection buttons
     */
    public SettingsResponse handleHomeHallSelection(String userId) {
        String userInfo = com.calplus.ihrgstats.telegrambot.listener.TelegramListener.formatUserInfo(userId);
        // Check admin authorization
        if (!isAdmin(userId)) {
            String errorMsg = "❌ Access Denied: Only administrators can change settings.";
            logHelper.logWarning(String.format("Non-admin %s attempted to change home hall", userInfo));
            return new SettingsResponse(errorMsg, null);
        }

        logHelper.logInfo(String.format("Admin %s requested home hall selection", userInfo));

        try {
            // Query all halls (excluding the reserved "unknown" fallback hall)
            List<String> halls = new ArrayList<>();
            A3_Halls hallsManager = new A3_Halls();
            for (A3_Halls.Hall hall : hallsManager.getAllHalls()) {
                if (hall.hallCode.equals(A3_Halls.UNKNOWN_HALL_CODE)) continue;
                halls.add(hall.hallName);
            }

            if (halls.isEmpty()) {
                String errorMsg = "⚠️ No halls found in database.";
                logHelper.logWarning(errorMsg);
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
            message.append("🏠 <b>Select Your Home Hall</b>\n\n");
            message.append("Choose your home hall from the list below.\n");
            if (!currentHomeHall.isEmpty()) {
                message.append(String.format("Current home hall: <b>%s</b>\n", TelegramHtml.escape(currentHomeHall)));
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

            logHelper.logSuccess(String.format("Sent hall selection menu to admin %s with %d halls", userId, halls.size()));

            return new SettingsResponse(message.toString(), 
                new ButtonConfig(buttonLabels.toArray(new String[0]), 
                               buttonCallbacks.toArray(new String[0]), 
                               4)); // 4 columns per row

        } catch (SQLException e) {
            String errorMsg = "❌ Error: Failed to load halls from database: " + e.getMessage();
            logHelper.logError(errorMsg);
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
        String userInfo = com.calplus.ihrgstats.telegrambot.listener.TelegramListener.formatUserInfo(userId);
        // Check admin authorization
        if (!isAdmin(userId)) {
            String errorMsg = "❌ Access Denied: Only administrators can change settings.";
            logHelper.logWarning(String.format("Non-admin %s attempted to set home hall", userInfo));
            return errorMsg;
        }

        // Handle manual input request
        if (callbackData.equals("setting_homeHall_manual")) {
            logHelper.logInfo(String.format("Admin %s requested manual home hall input", userId));
            
            // Set user state to await manual input
            SettingsSelectionState state = userSelectionStates.computeIfAbsent(userId, k -> new SettingsSelectionState());
            state.awaitingManualInput = true;
            state.settingKey = "settings.homeHall";
            
            String currentHomeHall = PropertyResolver.getProperty("settings.homeHall", "");
            String currentValueDisplay = currentHomeHall.isEmpty() ? "Not set" : currentHomeHall;
            
            return String.format("✏️ <b>Manual Home Hall Input</b>\n\n" +
                                "Please reply with the hall number or name you want to set as your home hall.\n\n" +
                                "<b>Current home hall:</b> %s\n\n" +
                                "<i>Example: Type '4' or 'Hall 4' to set Hall 4 as your home hall</i>",
                                TelegramHtml.escape(currentValueDisplay));
        }

        // Extract hall value from callback data
        if (!callbackData.startsWith("setting_homeHall_")) {
            return "❌ Error: Invalid callback data";
        }

        String hallValue = callbackData.substring("setting_homeHall_".length());

        String canonicalHallName;
        try {
            canonicalHallName = resolveHallNameOrNull(hallValue);
        } catch (SQLException e) {
            String errorMsg = "❌ Error: Failed to validate hall: " + e.getMessage();
            logHelper.logError(errorMsg);
            return errorMsg;
        }
        if (canonicalHallName == null) {
            String errorMsg = String.format("❌ Error: '%s' is not a recognized hall.", hallValue);
            logHelper.logWarning(String.format("Admin %s tried to set an unrecognized home hall: %s", userId, hallValue));
            return errorMsg;
        }

        logHelper.logInfo(String.format("Admin %s setting home hall to: %s", userId, canonicalHallName));

        // Update property
        boolean success = PropertyManager.updateProperty("settings.homeHall", canonicalHallName);

        if (success) {
            String successMsg = String.format("🏠 Successfully set home hall to <b>%s</b>\n\nUse /settings to see updated configuration.", TelegramHtml.escape(canonicalHallName));

            logHelper.logSuccess(String.format("Home hall set to %s", canonicalHallName));

            return successMsg;
        } else {
            String errorMsg = "❌ Error: Failed to update home hall setting";
            logHelper.logError(errorMsg);
            return errorMsg;
        }
    }

    /**
     * Looks up a hall by its raw name (case-insensitive), returning the
     * canonical hall_name value to store, or null if no such hall exists.
     * Used to keep settings.homeHall constrained to a real hall instead of
     * accepting arbitrary free text.
     */
    private String resolveHallNameOrNull(String hallValue) throws SQLException {
        A3_Halls.Hall hall = new A3_Halls().getHallByName(hallValue);
        return hall != null ? hall.hallName : null;
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
                
                logHelper.logInfo(String.format("Admin %s setting timezone to: %s (manual input)", userId, timezoneValue));
                
                // Update property
                boolean success = PropertyManager.updateProperty("settings.timezone", timezoneValue);
                
                if (success) {
                    String successMsg = String.format("🌍 Successfully set timezone to <b>%s</b>\n\nUse /settings to see updated configuration.",
                                                    TelegramHtml.escape(formatTimezoneDisplay(timezoneValue)));

                    logHelper.logSuccess(String.format("Timezone set to %s (manual input)", timezoneValue));
                    
                    return successMsg;
                } else {
                    String errorMsg = "❌ Error: Failed to update timezone setting";
                    logHelper.logError(errorMsg);
                    return errorMsg;
                }
            } catch (NumberFormatException e) {
                return "❌ Invalid input: Timezone offset must be a valid number (e.g., +8, -5, +9.5).";
            }
        }
        
        // Handle currentYear setting
        if ("settings.currentYear".equals(settingKey)) {
            String inputValue = text.trim();
            
            try {
                int yearValue = Integer.parseInt(inputValue);
                if (yearValue < 2000 || yearValue > 2100) {
                    return "❌ Invalid input: Current year must be between 2000 and 2100.";
                }
                
                String formattedValue = String.valueOf(yearValue);
                
                logHelper.logInfo(String.format("Admin %s setting currentYear to: %s", userId, formattedValue));
                
                // Update property
                boolean success = PropertyManager.updateProperty("settings.currentYear", formattedValue);
                
                if (success) {
                    String successMsg = String.format("📅 Successfully set current year to <b>%s</b>\n\nUse /settings to see updated configuration.", formattedValue);

                    logHelper.logSuccess(String.format("CurrentYear set to %s", formattedValue));
                    
                    return successMsg;
                } else {
                    String errorMsg = "❌ Error: Failed to update currentYear setting";
                    logHelper.logError(errorMsg);
                    return errorMsg;
                }
            } catch (NumberFormatException e) {
                return "❌ Invalid input: Current year must be a valid whole number (e.g., 2025).";
            }
        }
        
        // Handle homeHall setting (default)
        String hallValue = text.trim();
        if (hallValue.isEmpty()) {
            return "❌ Invalid input: Hall value cannot be empty.";
        }

        String canonicalHallName;
        try {
            canonicalHallName = resolveHallNameOrNull(hallValue);
        } catch (SQLException e) {
            String errorMsg = "❌ Error: Failed to validate hall: " + e.getMessage();
            logHelper.logError(errorMsg);
            return errorMsg;
        }
        if (canonicalHallName == null) {
            return String.format("❌ Invalid input: '%s' is not a recognized hall. Use /settings and select a hall from the list.",
                    TelegramHtml.escape(hallValue));
        }

        logHelper.logInfo(String.format("Admin %s setting home hall to: %s (manual input)", userId, canonicalHallName));

        // Update property
        boolean success = PropertyManager.updateProperty("settings.homeHall", canonicalHallName);

        if (success) {
            String successMsg = String.format("🏠 Successfully set home hall to <b>%s</b>\n\nUse /settings to see updated configuration.", TelegramHtml.escape(canonicalHallName));

            logHelper.logSuccess(String.format("Home hall set to %s (manual input)", canonicalHallName));

            return successMsg;
        } else {
            String errorMsg = "❌ Error: Failed to update home hall setting";
            logHelper.logError(errorMsg);
            return errorMsg;
        }
    }

    /**
     * Handles the current year selection request (direct to manual input)
     * @param userId The user ID who requested currentYear change
     * @return Response prompting for manual input
     */
    public SettingsResponse handleCurrentYearSelection(String userId) {
        String userInfo = com.calplus.ihrgstats.telegrambot.listener.TelegramListener.formatUserInfo(userId);
        // Check admin authorization
        if (!isAdmin(userId)) {
            String errorMsg = "❌ Access Denied: Only administrators can change settings.";
            logHelper.logWarning(String.format("Non-admin %s attempted to change currentYear", userInfo));
            return new SettingsResponse(errorMsg, null);
        }

        logHelper.logInfo(String.format("Admin %s requested currentYear change", userInfo));

        // Set manual input mode
        SettingsSelectionState state = new SettingsSelectionState();
        state.awaitingManualInput = true;
        state.settingKey = "settings.currentYear";
        userSelectionStates.put(userId, state);

        String currentValue = PropertyResolver.getProperty("settings.currentYear", "");
        String currentValueDisplay = currentValue.isEmpty() ? "Not set" : currentValue;
        String message = String.format("Please enter the tournament year currently being played (e.g., 2025).\n\nCurrent value: <b>%s</b>", TelegramHtml.escape(currentValueDisplay));

        return new SettingsResponse(message, null);
    }

    /**
     * Handles the currentYear callback
     * This is only for cancel operation as selection goes directly to manual input
     */
    public String handleCurrentYearCallback(String callbackData, String userId) {
        String userInfo = com.calplus.ihrgstats.telegrambot.listener.TelegramListener.formatUserInfo(userId);
        // Check admin authorization
        if (!isAdmin(userId)) {
            String errorMsg = "❌ Access Denied: Only administrators can change settings.";
            logHelper.logWarning(String.format("Non-admin %s attempted unauthorized currentYear callback", userInfo));
            return errorMsg;
        }

        // Only cancel is expected here
        if (callbackData.equals("settings_cancel")) {
            userSelectionStates.remove(userId);
            return "🔄 Cancelled currentYear change.";
        }

        return "❌ Unknown callback for currentYear";
    }

    /**
     * Handles the timezone selection request
     * @param userId The user ID who requested timezone change
     * @return Response containing timezone selection buttons
     */
    public SettingsResponse handleTimezoneSelection(String userId) {
        String userInfo = com.calplus.ihrgstats.telegrambot.listener.TelegramListener.formatUserInfo(userId);
        // Check admin authorization
        if (!isAdmin(userId)) {
            String errorMsg = "❌ Access Denied: Only administrators can change settings.";
            logHelper.logWarning(String.format("Non-admin %s attempted to change timezone", userInfo));
            return new SettingsResponse(errorMsg, null);
        }

        logHelper.logInfo(String.format("Admin %s requested timezone selection", userInfo));

        // Get current timezone
        String currentTimezone = PropertyResolver.getProperty("settings.timezone", "");

        // Build message
        StringBuilder message = new StringBuilder();
        message.append("🌍 <b>Select Timezone</b>\n\n");
        message.append("Choose your timezone offset from UTC.\n");
        if (!currentTimezone.isEmpty()) {
            message.append(String.format("Current timezone: <b>%s</b>\n", TelegramHtml.escape(formatTimezoneDisplay(currentTimezone))));
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

        logHelper.logSuccess(String.format("Sent timezone selection menu to admin %s", userId));

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
        String userInfo = com.calplus.ihrgstats.telegrambot.listener.TelegramListener.formatUserInfo(userId);
        // Check admin authorization
        if (!isAdmin(userId)) {
            String errorMsg = "❌ Access Denied: Only administrators can change settings.";
            logHelper.logWarning(String.format("Non-admin %s attempted to set timezone", userInfo));
            return errorMsg;
        }

        // Handle manual input request
        if (callbackData.equals("setting_timezone_manual")) {
            logHelper.logInfo(String.format("Admin %s requested manual timezone input", userId));
            
            // Set user state to await manual input
            SettingsSelectionState state = userSelectionStates.computeIfAbsent(userId, k -> new SettingsSelectionState());
            state.awaitingManualInput = true;
            state.settingKey = "settings.timezone";
            
            String currentTimezone = PropertyResolver.getProperty("settings.timezone", "");
            String currentValueDisplay = currentTimezone.isEmpty() ? "Not set" : formatTimezoneDisplay(currentTimezone);
            
            return String.format("✏️ <b>Manual Timezone Input</b>\n\n" +
                                "Please reply with the timezone offset you want to set.\n\n" +
                                "<b>Current timezone:</b> %s\n\n" +
                                "<i>Example: Type '+8' for UTC+8, '-5' for UTC-5, or '+9.5' for UTC+9.5</i>",
                                TelegramHtml.escape(currentValueDisplay));
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
        
        logHelper.logInfo(String.format("Admin %s setting timezone to: %s", userId, timezoneValue));

        // Update property
        boolean success = PropertyManager.updateProperty("settings.timezone", timezoneValue);
        
        if (success) {
            String successMsg = String.format("🌍 Successfully set timezone to <b>%s</b>\n\nUse /settings to see updated configuration.",
                                            TelegramHtml.escape(formatTimezoneDisplay(timezoneValue)));

            logHelper.logSuccess(String.format("Timezone set to %s", timezoneValue));
            
            return successMsg;
        } else {
            String errorMsg = "❌ Error: Failed to update timezone setting";
            logHelper.logError(errorMsg);
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
            return TimezoneHelper.formatOffsetDisplay(Double.parseDouble(timezoneValue));
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

}
