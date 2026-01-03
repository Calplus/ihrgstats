package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.Main;
import com.calplus.ihrgstats.utils.EnvironmentManager;
import com.calplus.ihrgstats.utils.LogHelper;
import com.calplus.ihrgstats.utils.PropertyResolver;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse;
import com.calplus.ihrgstats.utils.TimezoneHelper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Command handler for /about command.
 * Displays bot information including author, version, timestamps, and admin contact.
 */
public class CommandAbout {
    private final LogHelper logHelper;
    private final String version = "1.0.0";
    private final String author = "Calplus";
    private final String botToken;
    private final HttpClient httpClient;
    private final Gson gson;
    
    public CommandAbout(String botToken) {
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();
        
        this.logHelper = new LogHelper();
        this.botToken = botToken;
        this.httpClient = HttpClient.newHttpClient();
        this.gson = new Gson();
    }
    
    /**
     * Formats timezone offset as UTC+/-n
     */
    private String formatTimezone(ZoneId zoneId, ZonedDateTime dateTime) {
        ZoneOffset offset = dateTime.getOffset();
        int totalSeconds = offset.getTotalSeconds();
        int hours = totalSeconds / 3600;
        
        if (hours == 0) {
            return "UTC";
        } else if (hours > 0) {
            return "UTC+" + hours;
        } else {
            return "UTC" + hours;  // Already has negative sign
        }
    }
    
    /**
     * Fetches the username for a given userId using Telegram API
     * @param userId The Telegram user ID
     * @return The username (without @) or the userId if unable to fetch
     */
    private String fetchUsername(String userId) {
        try {
            String url = "https://api.telegram.org/bot" + botToken + "/getChat";
            
            JsonObject payload = new JsonObject();
            payload.addProperty("chat_id", userId);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);
                if (jsonResponse.has("result")) {
                    JsonObject result = jsonResponse.getAsJsonObject("result");
                    // Try to get username first
                    if (result.has("username") && !result.get("username").isJsonNull()) {
                        return result.get("username").getAsString();
                    }
                    // Fallback to first_name or full name
                    if (result.has("first_name") && !result.get("first_name").isJsonNull()) {
                        String firstName = result.get("first_name").getAsString();
                        if (result.has("last_name") && !result.get("last_name").isJsonNull()) {
                            return firstName + " " + result.get("last_name").getAsString();
                        }
                        return firstName;
                    }
                }
            }
        } catch (Exception e) {
            logHelper.logWarning("Failed to fetch username for userId " + userId + ": " + e.getMessage());
        }
        // Return userId as fallback
        return userId;
    }
    
    /**
     * Handles the /about command
     */
    public CommandResponse handleCommand(String userId) {
        logHelper.logInfo(String.format("User %s requested /about", userId));
        
        try {
            String adminUserId = PropertyResolver.getProperty("telegram.admin.userId", "");
            ZoneId timezone = TimezoneHelper.getConfiguredZoneId();
            ZonedDateTime now = TimezoneHelper.now();
            
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
            
            StringBuilder message = new StringBuilder();
            message.append("ℹ️ **About IHRG Stats Bot**\n\n");
            
            message.append("**Developer:** ").append(author).append("\n");
            message.append("**Version:** ").append(version).append("\n");
            message.append("**Timezone:** ").append(TimezoneHelper.getFormattedTimezone()).append("\n");
            message.append("**GitHub:** https://github.com/Calplus/ihrgstats\n\n");
            
            message.append("**Bot Launched:**\n");
            message.append("`").append(TimezoneHelper.toConfiguredZone(Main.LAUNCH_TIME).format(formatter)).append("`\n\n");
            
            message.append("**Last Updated:**\n");
            message.append("`02 Jan 2026`\n\n");
            
            message.append("**Current Time:**\n");
            message.append("`").append(now.format(formatter)).append("`\n\n");
            
            if (!adminUserId.isEmpty()) {
                // Fetch the username for the admin user ID
                String adminHandle = fetchUsername(adminUserId);
                message.append("**Bot Administrator:**\n");
                message.append("@").append(adminHandle).append("\n\n");
            }
            
            message.append("_For help with commands, use_ `/help`");
            
            logHelper.logSuccess(String.format("User %s received about information", userId));
            return new CommandResponse(message.toString(), (java.nio.file.Path) null);
            
        } catch (Exception e) {
            logHelper.logError(String.format("Error generating about info for user %s: %s", userId, e.getMessage()));
            return new CommandResponse("❌ Error generating about information. Please try again later.", (java.nio.file.Path) null);
        }
    }
}
