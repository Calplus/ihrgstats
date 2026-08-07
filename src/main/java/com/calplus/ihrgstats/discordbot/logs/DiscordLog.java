package com.calplus.ihrgstats.discordbot.logs;

import com.calplus.ihrgstats.utils.ChannelLog;
import com.calplus.ihrgstats.utils.HttpClientFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * Sends log messages to a Discord channel via the Discord bot.
 * Messages are queued and processed sequentially to maintain order.
 * Reacts to Discord rate limits (429) with retry_after delays.
 * Queue/batch/INFO-accumulation machinery lives in {@link ChannelLog}.
 */
public class DiscordLog extends ChannelLog {
    private static final int DISCORD_CHARACTER_LIMIT = 2000;
    // Inline 429 retries are per-message and block the whole queue behind
    // that message, so they must be finite - a persistently rate-limited
    // channel would otherwise spin the log worker forever.
    private static final int MAX_RATE_LIMIT_RETRIES = 5;

    private String botToken;
    private String channelId;
    private String adminUserId;
    private String discordApiUrl;

    public DiscordLog() {
        super(DISCORD_CHARACTER_LIMIT);
    }

    /**
     * Loads the Discord bot token and channel ID from application.properties
     * @return true if Discord logging is enabled, false otherwise
     */
    @Override
    protected boolean loadConfig() {
        try {
            java.util.Properties properties = com.calplus.ihrgstats.utils.PropertyResolver.loadAndResolve("application.properties");

            this.botToken = properties.getProperty("discord.bot.token");
            this.channelId = properties.getProperty("discord.log.channelId");
            this.adminUserId = properties.getProperty("discord.admin.userId");

            if (this.botToken == null || this.botToken.isEmpty()) {
                System.err.println("WARNING: discord.bot.token not found in application.properties. Discord logging disabled.");
                return false;
            }
            if (this.channelId == null || this.channelId.isEmpty()) {
                System.err.println("WARNING: discord.log.channelId not found in application.properties. Discord logging disabled.");
                return false;
            }

            if (this.adminUserId == null || this.adminUserId.isEmpty()) {
                System.err.println("INFO: discord.admin.userId not configured. Admin pings will be skipped.");
            }

            this.discordApiUrl = "https://discord.com/api/v10/channels/" + this.channelId + "/messages";
            return true;

        } catch (IOException e) {
            System.err.println("WARNING: Failed to read application.properties. Discord logging disabled. Error: " + e.getMessage());
            return false;
        }
    }

    /** Formats a log message - Discord sends plain content, so no escaping is applied. */
    @Override
    protected String formatMessage(String emote, String type, String message, String filename) {
        String timestamp = getTimestamp();
        return String.format("%s [%s] [%s] %s: %s", emote, timestamp, filename, type, message);
    }

    /** Adds a Discord admin ping when configured. */
    @Override
    protected String decorateError(String formattedMessage) {
        if (adminUserId != null && !adminUserId.isEmpty()) {
            return "<@" + adminUserId + "> " + formattedMessage;
        }
        return formattedMessage;
    }

    /**
     * Sends a message to the Discord channel
     * @param message The message to send
     * @return Retry delay in milliseconds (0 if successful, -1 if failed, >0 if rate limited)
     */
    @Override
    protected long sendMessage(String message) {
        try {
            String payload = String.format("{\"content\":\"%s\"}",
                message.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t"));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(this.discordApiUrl))
                    .timeout(HttpClientFactory.REQUEST_TIMEOUT)
                    .header("Authorization", "Bot " + this.botToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 201) {
                return 0; // Success
            } else if (response.statusCode() == 429) {
                // Rate limited - parse retry_after
                System.err.println("Failed to send message to Discord. Status code: " + response.statusCode());
                System.err.println("Response: " + response.body());

                try {
                    String body = response.body();
                    // Parse JSON to extract retry_after
                    int retryAfterIndex = body.indexOf("\"retry_after\"");
                    if (retryAfterIndex != -1) {
                        int colonIndex = body.indexOf(":", retryAfterIndex);
                        int commaIndex = body.indexOf(",", colonIndex);
                        int braceIndex = body.indexOf("}", colonIndex);
                        int endIndex = commaIndex != -1 ? Math.min(commaIndex, braceIndex != -1 ? braceIndex : Integer.MAX_VALUE) : braceIndex;

                        if (colonIndex != -1 && endIndex != -1) {
                            String retryAfterStr = body.substring(colonIndex + 1, endIndex).trim();
                            double retryAfterSeconds = Double.parseDouble(retryAfterStr);
                            long retryAfterMs = (long) (retryAfterSeconds * 1000);
                            return retryAfterMs;
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Error parsing retry_after: " + e.getMessage());
                }

                // Default to 1 second if parsing fails
                return 1000;
            } else {
                System.err.println("Failed to send message to Discord. Status code: " + response.statusCode());
                System.err.println("Response: " + response.body());
                return -1; // Failed
            }

        } catch (Exception e) {
            System.err.println("Error sending message to Discord: " + e.getMessage());
            return -1; // Failed
        }
    }

    /**
     * Processes the message queue sequentially with reactive rate limiting.
     * Discord's strategy: a rate-limited message is retried inline (same
     * message, same slot) until it sends or fails outright - unlike
     * Telegram's re-queue-and-sleep strategy.
     */
    @Override
    protected void processQueue() {
        if (!isProcessing.compareAndSet(false, true)) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            boolean interrupted = false;
            try {
                while (!messageQueue.isEmpty()) {
                    QueuedMessage queuedMsg = messageQueue.poll();
                    if (queuedMsg != null) {
                        try {
                            // Try to send message, handle rate limiting
                            long result = sendMessage(queuedMsg.message);

                            int rateLimitRetries = 0;
                            while (result > 0 && rateLimitRetries < MAX_RATE_LIMIT_RETRIES) {
                                // Rate limited - wait for retry_after duration
                                try {
                                    Thread.sleep(result);
                                } catch (InterruptedException e) {
                                    interrupted = true;
                                    // Continue after interrupt
                                }
                                // Retry sending the message
                                result = sendMessage(queuedMsg.message);
                                rateLimitRetries++;
                            }
                            if (result > 0) {
                                System.err.println("Discord message dropped after "
                                        + MAX_RATE_LIMIT_RETRIES + " rate-limit retries");
                                result = -1;
                            }

                            // Complete future: result == 0 means success, result == -1 means failure
                            queuedMsg.future.complete(result == 0);
                        } catch (Exception e) {
                            // Complete the future with failure and continue processing
                            queuedMsg.future.complete(false);
                            System.err.println("Error processing message: " + e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Fatal error in queue processing: " + e.getMessage());
            } finally {
                isProcessing.set(false);

                // Restore interrupted status if needed
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }

                // Check if new messages arrived while we were finishing
                if (!messageQueue.isEmpty()) {
                    processQueue();
                }
            }
        });
    }

    /**
     * Main method for testing
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            // Test mode
            DiscordLog discordLog = new DiscordLog();
            discordLog.logInfo("Testing Discord logging from Java");
            discordLog.logSuccess("Database update completed successfully");
            discordLog.logWarning("API rate limit approaching");
            discordLog.logError("Failed to connect to database");
            discordLog.log("Custom log message without prefix");

            // Wait a bit for messages to be sent
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return;
        }

        // CLI mode: java DiscordLog <logType> <message>
        String logType = args[0].toLowerCase();
        StringBuilder messageBuilder = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (i > 1) messageBuilder.append(" ");
            messageBuilder.append(args[i]);
        }
        String message = messageBuilder.toString();

        if (message.isEmpty()) {
            System.err.println("Error: Message is required");
            System.exit(1);
        }

        DiscordLog discordLog = new DiscordLog();

        try {
            switch (logType) {
                case "info":
                    discordLog.logInfo(message);
                    break;
                case "success":
                    discordLog.logSuccess(message);
                    break;
                case "warning":
                    discordLog.logWarning(message);
                    break;
                case "error":
                    discordLog.logError(message);
                    break;
                case "log":
                    discordLog.log(message);
                    break;
                default:
                    System.err.println("Error: Unknown log type '" + logType + "'. Use: info, success, warning, error, or log");
                    System.exit(1);
            }

            // Wait a bit for the message to be sent
            Thread.sleep(1000);

        } catch (Exception e) {
            System.err.println("Error logging to Discord: " + e.getMessage());
            System.exit(1);
        }
    }
}
