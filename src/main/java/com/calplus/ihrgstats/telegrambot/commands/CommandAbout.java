package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.Main;
import com.calplus.ihrgstats.databasemanager.F16_Admins;
import com.calplus.ihrgstats.telegrambot.listener.TelegramListener;
import com.calplus.ihrgstats.utils.EnvironmentManager;
import com.calplus.ihrgstats.utils.LogHelper;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse;
import com.calplus.ihrgstats.utils.TelegramHtml;
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
import java.util.List;

/**
 * Command handler for /about command.
 * Displays bot information including author, version, timestamps, and admin contact.
 */
public class CommandAbout {
    private final LogHelper logHelper;
    private final String version;
    private final String author = "Calplus";
    private final String lastUpdated = "05 Jan 2026";
    private final String botToken;
    private final HttpClient httpClient;
    private final Gson gson;

    public CommandAbout(String botToken) {
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();

        this.logHelper = new LogHelper();
        this.version = loadVersion();
        this.botToken = botToken;
        this.httpClient = HttpClient.newHttpClient();
        this.gson = new Gson();
    }

    /**
     * Reads the running build's version from the shaded jar's manifest
     * (Implementation-Version, populated from this pom's own &lt;version&gt;
     * by the shade plugin's addDefaultImplementationEntries - see pom.xml) so
     * this can't independently drift from the pom the way a separate
     * hardcoded literal previously did. Falls back to a fixed label when run
     * unpackaged (IDE/test runs have no jar manifest to read).
     */
    private static String loadVersion() {
        String implVersion = CommandAbout.class.getPackage().getImplementationVersion();
        return implVersion != null && !implVersion.isEmpty() ? implVersion : "Development Build";
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
        String userInfo = TelegramListener.formatUserInfo(userId);
        logHelper.logInfo(String.format("%s requested /about", userInfo));
        
        try {
            List<F16_Admins.Admin> telegramAdmins = new F16_Admins().getAllAdmins().stream()
                    .filter(admin -> F16_Admins.PLATFORM_TELEGRAM.equals(admin.platform))
                    .collect(java.util.stream.Collectors.toList());
            ZoneId timezone = TimezoneHelper.getConfiguredZoneId();
            ZonedDateTime now = TimezoneHelper.now();
            
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
            
            StringBuilder message = new StringBuilder();
            message.append("ℹ️ <b>About IHRG Stats Bot</b>\n\n");

            message.append("<b>Developer:</b> ").append(TelegramHtml.escape(author)).append("\n");
            message.append("<b>Version:</b> ").append(TelegramHtml.escape(version)).append("\n");
            message.append("<b>Timezone:</b> ").append(TelegramHtml.escape(TimezoneHelper.getFormattedTimezone())).append("\n");
            message.append("<b>GitHub:</b> https://github.com/Calplus/ihrgstats\n\n");

            message.append("<b>Bot Launched:</b>\n");
            message.append("<code>").append(TimezoneHelper.toConfiguredZone(Main.LAUNCH_TIME).format(formatter)).append("</code>\n\n");

            message.append("<b>Last Updated:</b>\n");
            message.append("<code>").append(TelegramHtml.escape(lastUpdated)).append("</code>\n\n");

            message.append("<b>Current Time:</b>\n");
            message.append("<code>").append(now.format(formatter)).append("</code>\n\n");

            if (!telegramAdmins.isEmpty()) {
                message.append("<b>Bot Administrator").append(telegramAdmins.size() > 1 ? "s" : "").append(":</b>\n");
                for (F16_Admins.Admin admin : telegramAdmins) {
                    // Fetch the username for the admin's Telegram user ID - a
                    // profile field the admin controls, so it must be escaped
                    // like any other dynamic content.
                    String adminHandle = fetchUsername(admin.platformUserId);
                    message.append("@").append(TelegramHtml.escape(adminHandle)).append("\n");
                }
                message.append("\n");
            }

            message.append("<i>For help with commands, use</i> <code>/help</code>");
            String userInfo2 = TelegramListener.formatUserInfo(userId);
            logHelper.logSuccess(String.format("%s received about information", userInfo2));
            return new CommandResponse(message.toString(), (java.nio.file.Path) null);
            
        } catch (Exception e) {
            String userInfo3 = TelegramListener.formatUserInfo(userId);
            logHelper.logError(String.format("Error generating about info for %s: %s", userInfo3, e.getMessage()));
            return new CommandResponse("❌ Error generating about information. Please try again later.", (java.nio.file.Path) null);
        }
    }
}
