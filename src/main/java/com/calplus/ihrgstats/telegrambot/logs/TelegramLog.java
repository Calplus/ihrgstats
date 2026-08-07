package com.calplus.ihrgstats.telegrambot.logs;

import com.calplus.ihrgstats.utils.ChannelLog;
import com.calplus.ihrgstats.utils.HttpClientFactory;
import com.calplus.ihrgstats.utils.TelegramHtml;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Sends log messages to a Telegram chat via the Telegram Bot API.
 * Messages are queued and processed sequentially to maintain order.
 * Reacts to Telegram rate limits (429) with retry_after delays.
 * Queue/batch/INFO-accumulation machinery lives in {@link ChannelLog}.
 */
public class TelegramLog extends ChannelLog {
    private static final int TELEGRAM_CHARACTER_LIMIT = 4096;

    private String botToken;
    private String chatId;
    private String chatIdLog; // Specific channel/topic within the group
    private String adminUserId;
    private String telegramApiUrl;

    public TelegramLog() {
        super(TELEGRAM_CHARACTER_LIMIT);
    }

    /**
     * Loads the Telegram bot token and chat ID from application.properties
     * @return true if Telegram logging is enabled, false otherwise
     */
    @Override
    protected boolean loadConfig() {
        try {
            java.util.Properties properties = com.calplus.ihrgstats.utils.PropertyResolver.loadAndResolve("application.properties");

            this.botToken = properties.getProperty("telegram.bot.token");
            this.chatId = properties.getProperty("telegram.devChatId");
            this.chatIdLog = properties.getProperty("telegram.devChatId.log");
            this.adminUserId = properties.getProperty("telegram.admin.userId");

            if (this.botToken == null || this.botToken.isEmpty()) {
                System.err.println("WARNING: telegram.bot.token not found in application.properties. Telegram logging disabled.");
                return false;
            }

            // If devChatId is empty, disable Telegram logging entirely
            if (this.chatId == null || this.chatId.isEmpty()) {
                System.out.println("INFO: telegram.devChatId not found in application.properties. Telegram logging disabled.");
                return false;
            }

            if (this.adminUserId == null || this.adminUserId.isEmpty()) {
                System.out.println("INFO: telegram.admin.userId not found in application.properties. Admin mentions disabled.");
            }

            this.telegramApiUrl = "https://api.telegram.org/bot" + this.botToken + "/sendMessage";
            return true;

        } catch (IOException e) {
            System.err.println("WARNING: Failed to read application.properties. Telegram logging disabled. Error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Formats a log message with emote, timestamp, filename, type, and message.
     * message/filename are escaped here, before entering the batch/info
     * buffers - every send in this class uses parse_mode=HTML, but
     * exception text and file paths are arbitrary content that can
     * contain "&amp;"/"&lt;"/"&gt;" (e.g. a SQL error, a stack trace fragment). An
     * unescaped one breaks Telegram's HTML parser and the whole message
     * silently fails to send (no fallback retry exists in this class).
     * Escaping here (not at send time) also means every length check
     * downstream (batch char-limit splitting) already measures the real
     * wire-format text, so there's no separate raw-vs-escaped mismatch.
     */
    @Override
    protected String formatMessage(String emote, String type, String message, String filename) {
        String timestamp = getTimestamp();
        return String.format("%s [%s] [%s] %s: %s", emote, timestamp, TelegramHtml.escape(filename), type, TelegramHtml.escape(message));
    }

    /** Adds an admin mention (tg://user link for numeric ids, @username otherwise) when configured. */
    @Override
    protected String decorateError(String formattedMessage) {
        if (adminUserId != null && !adminUserId.isEmpty()) {
            if (adminUserId.matches("\\d+")) {
                return String.format("<a href=\"tg://user?id=%s\">Admin</a> %s", adminUserId, formattedMessage);
            }
            return "@" + adminUserId + " " + formattedMessage;
        }
        return formattedMessage;
    }

    /**
     * Sends a message to the Telegram chat
     * @param message The message to send
     * @return Retry delay in milliseconds (0 if successful, -1 if failed, >0 if rate limited)
     */
    @Override
    protected long sendMessage(String message) {
        try {
            // URL encode the message and chat_id
            String encodedMessage = URLEncoder.encode(message, StandardCharsets.UTF_8);
            String encodedChatId = URLEncoder.encode(chatId, StandardCharsets.UTF_8);

            // Build the request body
            String requestBody = "chat_id=" + encodedChatId + "&text=" + encodedMessage + "&parse_mode=HTML";

            // Add message_thread_id if chatIdLog is set and not "none"
            if (chatIdLog != null && !chatIdLog.isEmpty() && !chatIdLog.equalsIgnoreCase("none")) {
                String encodedThreadId = URLEncoder.encode(chatIdLog, StandardCharsets.UTF_8);
                requestBody += "&message_thread_id=" + encodedThreadId;
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(telegramApiUrl))
                    .timeout(HttpClientFactory.REQUEST_TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return 0; // Success
            } else if (response.statusCode() == 429) {
                // Rate limited - extract retry_after from response
                try {
                    String responseBody = response.body();
                    // Simple JSON parsing for retry_after
                    int retryAfterIndex = responseBody.indexOf("\"retry_after\":");
                    if (retryAfterIndex != -1) {
                        String afterString = responseBody.substring(retryAfterIndex + 14);
                        int endIndex = afterString.indexOf(",");
                        if (endIndex == -1) {
                            endIndex = afterString.indexOf("}");
                        }
                        if (endIndex != -1) {
                            String retryAfterStr = afterString.substring(0, endIndex).trim();
                            long retryAfter = Long.parseLong(retryAfterStr);
                            System.err.println("Telegram rate limit hit. Retrying after " + retryAfter + " seconds.");
                            return retryAfter * 1000; // Convert to milliseconds
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Failed to parse retry_after from rate limit response: " + e.getMessage());
                }
                return 5000; // Default 5 seconds if parsing fails
            } else {
                System.err.println("Failed to send message to Telegram. Status: " + response.statusCode());
                System.err.println("Response: " + response.body());
                return -1; // Failed
            }

        } catch (Exception e) {
            System.err.println("Error sending message to Telegram: " + e.getMessage());
            return -1; // Failed
        }
    }

    /**
     * Processes the message queue sequentially with reactive rate limiting.
     * Telegram's strategy: a rate-limited message is re-queued and the
     * worker sleeps for retry_after; an interrupt fails all remaining
     * messages (unlike Discord's inline-retry strategy).
     */
    @Override
    protected void processQueue() {
        if (!isProcessing.compareAndSet(false, true)) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            boolean interrupted = false;
            try {
                while (!messageQueue.isEmpty() && !interrupted) {
                    QueuedMessage queuedMsg = messageQueue.poll();
                    if (queuedMsg != null) {
                        long retryDelay = sendMessage(queuedMsg.message);

                        if (retryDelay == 0) {
                            // Success
                            queuedMsg.future.complete(true);
                        } else if (retryDelay > 0) {
                            // Rate limited - put message back in queue and wait
                            messageQueue.add(queuedMsg);
                            try {
                                Thread.sleep(retryDelay);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                interrupted = true;
                                queuedMsg.future.complete(false);
                            }
                        } else {
                            // Failed
                            queuedMsg.future.complete(false);
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Error processing message queue: " + e.getMessage());
            } finally {
                isProcessing.set(false);

                // If interrupted, complete all remaining messages as failed
                if (interrupted) {
                    QueuedMessage remaining;
                    while ((remaining = messageQueue.poll()) != null) {
                        remaining.future.complete(false);
                    }
                } else if (!messageQueue.isEmpty()) {
                    // A message may have been queued after the loop's last isEmpty()
                    // check but before isProcessing was cleared - reprocess so it
                    // isn't stranded until another unrelated call happens to arrive.
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
            TelegramLog telegramLog = new TelegramLog();
            telegramLog.logInfo("Testing Telegram logging from Java");
            telegramLog.logSuccess("Database update completed successfully");
            telegramLog.logWarning("API rate limit approaching");
            telegramLog.logError("Failed to connect to database");
            telegramLog.log("Custom log message without prefix");

            // Wait a bit for messages to be sent
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return;
        }

        // CLI mode: java TelegramLog <logType> <message>
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

        TelegramLog telegramLog = new TelegramLog();

        try {
            switch (logType) {
                case "info":
                    telegramLog.logInfo(message);
                    break;
                case "success":
                    telegramLog.logSuccess(message);
                    break;
                case "warning":
                    telegramLog.logWarning(message);
                    break;
                case "error":
                    telegramLog.logError(message);
                    break;
                case "log":
                    telegramLog.log(message);
                    break;
                default:
                    System.err.println("Unknown log type: " + logType);
                    System.exit(1);
            }

            // Wait a bit for the message to be sent
            Thread.sleep(1000);

        } catch (Exception e) {
            System.err.println("Error logging to Telegram: " + e.getMessage());
            System.exit(1);
        }
    }
}
