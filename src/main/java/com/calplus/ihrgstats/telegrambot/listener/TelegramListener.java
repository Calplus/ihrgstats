package com.calplus.ihrgstats.telegrambot.listener;

import com.calplus.ihrgstats.databasemanager.A1_PlayerStats;
import com.calplus.ihrgstats.databasemanager.A2_CappedPlayers;
import com.calplus.ihrgstats.discordbot.logs.DiscordLog;
import com.calplus.ihrgstats.telegrambot.logs.TelegramLog;
import com.calplus.ihrgstats.utils.EnvironmentManager;
import com.calplus.ihrgstats.utils.PropertyResolver;
import com.calplus.ihrgstats.utils.TelegramFileDownloader;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Telegram bot listener that monitors for file uploads and processes them.
 * Supports both webhook and long-polling modes.
 */
public class TelegramListener {
    private final DiscordLog discordLog;
    private final TelegramLog telegramLog;
    private final HttpClient httpClient;
    private final Gson gson;
    
    private String botToken;
    private String publicChatId;
    private String publicChatIdFileupload;
    private String devChatId;  // The dev chat ID for status messages
    private String devChatIdLog;  // The dev chat ID for log messages
    private String publicChatIdStatus;
    private String publicChatIdCommands;
    private String adminUserId;
    private boolean allowNonAdminUploads;
    private boolean allowAllChannelsProcessing;
    
    private String webhookUrl;
    private int webhookPort;
    private int webhookTimeoutMs;
    
    private boolean useWebhook;
    private boolean isRunning;
    private long lastUpdateId = 0;
    
    private ScheduledExecutorService statusHeartbeatExecutor;
    private static final long STATUS_HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes
    
    // Pending confirmations: using a single key for file processing confirmations
    private final Map<String, ConfirmationRequest> pendingConfirmations = new ConcurrentHashMap<>();
    private final Map<String, MultiChoiceConfirmationRequest> pendingMultiChoiceConfirmations = new ConcurrentHashMap<>();
    private static final String FILE_PROCESSING_CONFIRMATION_KEY = "file_processing";
    private static final String FILE_PROCESSING_MULTI_CHOICE_KEY = "file_processing_multi";
    
    private static class ConfirmationRequest {
        String message;
        CompletableFuture<Boolean> future;
        long timestamp;
        
        ConfirmationRequest(String message, CompletableFuture<Boolean> future) {
            this.message = message;
            this.future = future;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    private static class MultiChoiceConfirmationRequest {
        String message;
        String[] options;
        CompletableFuture<Integer> future;
        long timestamp;
        
        MultiChoiceConfirmationRequest(String message, String[] options, CompletableFuture<Integer> future) {
            this.message = message;
            this.options = options;
            this.future = future;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public TelegramListener() {
        // Load environment variables
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();
        
        this.discordLog = new DiscordLog();
        this.telegramLog = new TelegramLog();
        this.httpClient = HttpClient.newHttpClient();
        this.gson = new Gson();
        this.isRunning = false;
        
        loadConfig();
    }

    /**
     * Loads configuration from application.properties
     */
    private void loadConfig() {
        try {
            this.botToken = PropertyResolver.getProperty("telegram.bot.token", "");
            this.publicChatId = PropertyResolver.getProperty("telegram.publicChatId", "");
            this.publicChatIdFileupload = PropertyResolver.getProperty("telegram.publicChatId.fileupload", "");
            this.devChatId = PropertyResolver.getProperty("telegram.devChatId", "");  // Load dev chat ID
            this.devChatIdLog = PropertyResolver.getProperty("telegram.devChatId.log", "");  // Load dev chat log ID
            this.publicChatIdStatus = PropertyResolver.getProperty("telegram.devChatId.status", "");
            this.publicChatIdCommands = PropertyResolver.getProperty("telegram.publicChatId.commands", "");
            this.adminUserId = PropertyResolver.getProperty("telegram.admin.userId", "");
            this.allowNonAdminUploads = Boolean.parseBoolean(PropertyResolver.getProperty("settings.allowNonAdminUploads", "true"));
            this.allowAllChannelsProcessing = Boolean.parseBoolean(PropertyResolver.getProperty("settings.allowAllChannelsProcessing", "false"));
            
            this.webhookUrl = PropertyResolver.getProperty("internet.webhook.url", "");
            String portStr = PropertyResolver.getProperty("internet.webhook.port", "8443");
            this.webhookPort = Integer.parseInt(portStr);
            String timeoutStr = PropertyResolver.getProperty("internet.webhook.timeoutMs", "5000");
            this.webhookTimeoutMs = Integer.parseInt(timeoutStr);
            
            // Determine if webhook is configured
            this.useWebhook = !webhookUrl.isEmpty();
            
        } catch (Exception e) {
            System.err.println("Error loading configuration: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Helper methods for intelligent chat/thread routing
     * These methods determine the correct chat ID and thread ID to use based on:
     * 1. Whether sub-channel values exist (prefer sub-channel over main channel)
     * 2. Whether main channel is configured (fallback to main if sub-channel empty)
     * 3. Current context (allowAllChannelsProcessing, original message context)
     */

    /**
     * Gets the chat ID and thread ID for upload messages
     * @return String[] with [chatId, threadId] or [chatId, null] if no thread
     */
    private String[] getUploadChatIdAndThread() {
        // If subchannel exists, use it (it's a thread in the main channel)
        if (!publicChatIdFileupload.isEmpty()) {
            return new String[]{publicChatId, publicChatIdFileupload};
        }
        // Otherwise use main channel without thread
        return new String[]{publicChatId, null};
    }

    /**
     * Gets the chat ID and thread ID for command responses
     * @return String[] with [chatId, threadId] or [chatId, null] if no thread
     */
    private String[] getCommandsChatIdAndThread() {
        // If subchannel exists, use it (it's a thread in the main channel)
        if (!publicChatIdCommands.isEmpty()) {
            return new String[]{publicChatId, publicChatIdCommands};
        }
        // Otherwise use main channel without thread
        return new String[]{publicChatId, null};
    }

    /**
     * Gets the chat ID and thread ID for status messages
     * @return String[] with [chatId, threadId] or [chatId, null] if no thread, or null if devChatId is empty
     */
    private String[] getStatusChatIdAndThread() {
        // If devChatId is empty, don't send status messages
        if (devChatId.isEmpty()) {
            return null;
        }
        // If subchannel exists, use it (it's a thread in the dev channel)
        if (!publicChatIdStatus.isEmpty()) {
            return new String[]{devChatId, publicChatIdStatus};
        }
        // Otherwise use dev channel without thread
        return new String[]{devChatId, null};
    }

    /**
     * Gets the chat ID and thread ID for log messages
     * @return String[] with [chatId, threadId] or [chatId, null] if no thread, or null if devChatId is empty
     */
    private String[] getLogChatIdAndThread() {
        // If devChatId is empty, don't send log messages
        if (devChatId.isEmpty()) {
            return null;
        }
        // If subchannel exists, use it (it's a thread in the dev channel)
        if (!devChatIdLog.isEmpty()) {
            return new String[]{devChatId, devChatIdLog};
        }
        // Otherwise use dev channel without thread
        return new String[]{devChatId, null};
    }

    /**
     * Starts the listener
     */
    public void start() {
        if (isRunning) {
            System.out.println("Telegram listener is already running");
            return;
        }

        discordLog.logInfo("Starting Telegram file listener...");
        telegramLog.logInfo("Starting Telegram file listener...");

        if (botToken.isEmpty()) {
            String errorMsg = "Telegram bot token not configured. Cannot start listener.";
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            return;
        }

        // If publicChatId is empty, the bot will accept messages from any channel
        if (publicChatId.isEmpty()) {
            discordLog.logInfo("Telegram publicChatId not configured. Bot will process messages from any channel it has access to.");
            telegramLog.logInfo("Telegram publicChatId not configured. Bot will process messages from any channel it has access to.");
            // Set allowAllChannelsProcessing to true when publicChatId is empty
            this.allowAllChannelsProcessing = true;
        }

        isRunning = true;

        // Start status heartbeat if configured
        startStatusHeartbeat();

        if (useWebhook) {
            startWebhookMode();
        } else {
            startLongPollingMode();
        }
    }

    /**
     * Stops the listener
     */
    public void stop() {
        isRunning = false;
        stopStatusHeartbeat();
        discordLog.logInfo("Telegram listener stopped");
        telegramLog.logInfo("Telegram listener stopped");
    }

    /**
     * Starts webhook mode
     */
    private void startWebhookMode() {
        // Test webhook accessibility
        boolean webhookAccessible = testWebhookAccessibility();
        
        if (webhookAccessible) {
            discordLog.logInfo("Webhook is accessible. Starting webhook mode...");
            telegramLog.logInfo("Webhook is accessible. Starting webhook mode...");
            
            // Set webhook URL in Telegram
            boolean webhookSet = setTelegramWebhook();
            if (webhookSet) {
                discordLog.logSuccess("Telegram webhook mode activated at: " + webhookUrl);
                telegramLog.logSuccess("Telegram webhook mode activated at: " + webhookUrl);
                
                // Note: Actual webhook server implementation would go here
                // For now, fall back to long polling
                System.out.println("Note: Webhook server implementation not included. Falling back to long polling.");
                startLongPollingMode();
            } else {
                discordLog.logWarning("Failed to set webhook. Falling back to long polling.");
                telegramLog.logWarning("Failed to set webhook. Falling back to long polling.");
                startLongPollingMode();
            }
        } else {
            discordLog.logWarning("Webhook not accessible. Falling back to long polling.");
            telegramLog.logWarning("Webhook not accessible. Falling back to long polling.");
            startLongPollingMode();
        }
    }

    /**
     * Tests if webhook URL is accessible
     */
    private boolean testWebhookAccessibility() {
        if (webhookUrl.isEmpty()) {
            return false;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .timeout(java.time.Duration.ofMillis(webhookTimeoutMs))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() < 500; // Any response except server error is good
            
        } catch (Exception e) {
            System.err.println("Webhook accessibility test failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Sets the Telegram webhook URL
     */
    private boolean setTelegramWebhook() {
        try {
            String setWebhookUrl = "https://api.telegram.org/bot" + botToken + "/setWebhook?url=" + webhookUrl;
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(setWebhookUrl))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains("\"ok\":true");
            
        } catch (Exception e) {
            System.err.println("Failed to set webhook: " + e.getMessage());
            return false;
        }
    }

    /**
     * Starts long polling mode
     */
    private void startLongPollingMode() {
        discordLog.logInfo("Starting long polling mode...");
        telegramLog.logInfo("Starting long polling mode...");

        // Delete webhook if exists
        deleteWebhook();

        // Get current update_id to skip old messages
        initializeUpdateId();

        // Start polling in background thread
        Thread pollingThread = new Thread(() -> {
            discordLog.logSuccess("Telegram long polling started successfully");
            telegramLog.logSuccess("Telegram long polling started successfully");

            while (isRunning) {
                try {
                    pollForUpdates();
                    Thread.sleep(1000); // Poll every second
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("Error in polling loop: " + e.getMessage());
                    e.printStackTrace();
                    try {
                        Thread.sleep(5000); // Wait 5 seconds before retrying
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
        pollingThread.setDaemon(false); // Keep application alive
        pollingThread.start();
    }

    /**
     * Deletes the Telegram webhook
     */
    private void deleteWebhook() {
        try {
            String deleteWebhookUrl = "https://api.telegram.org/bot" + botToken + "/deleteWebhook";
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(deleteWebhookUrl))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            System.err.println("Failed to delete webhook: " + e.getMessage());
        }
    }

    /**
     * Initializes lastUpdateId to skip old messages
     */
    private void initializeUpdateId() {
        try {
            String getUpdatesUrl = "https://api.telegram.org/bot" + botToken + "/getUpdates?offset=-1&limit=1";
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getUpdatesUrl))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);
                if (jsonResponse.has("result")) {
                    JsonArray updates = jsonResponse.getAsJsonArray("result");
                    if (updates.size() > 0) {
                        JsonObject lastUpdate = updates.get(0).getAsJsonObject();
                        lastUpdateId = lastUpdate.get("update_id").getAsLong();
                        discordLog.logInfo("Skipping " + (lastUpdateId + 1) + " old messages. Only processing new files.");
                        telegramLog.logInfo("Skipping " + (lastUpdateId + 1) + " old messages. Only processing new files.");
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to initialize update ID: " + e.getMessage());
        }
    }

    /**
     * Polls for new updates from Telegram
     */
    private void pollForUpdates() throws IOException, InterruptedException {
        String getUpdatesUrl = "https://api.telegram.org/bot" + botToken + "/getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=30";
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(getUpdatesUrl))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            processUpdates(response.body());
        }
    }

    /**
     * Processes updates from Telegram
     */
    private void processUpdates(String json) {
        try {
            JsonObject response = gson.fromJson(json, JsonObject.class);
            
            if (!response.has("result")) {
                return;
            }
            
            JsonArray updates = response.getAsJsonArray("result");
            
            for (JsonElement updateElement : updates) {
                JsonObject update = updateElement.getAsJsonObject();
                long updateId = update.get("update_id").getAsLong();
                lastUpdateId = Math.max(lastUpdateId, updateId);
                
                // Handle callback queries (button clicks)
                if (update.has("callback_query")) {
                    JsonObject callbackQuery = update.getAsJsonObject("callback_query");
                    handleCallbackQuery(callbackQuery);
                    continue;
                }
                
                if (!update.has("message")) continue;
                JsonObject message = update.getAsJsonObject("message");
                
                // Check if it's from the right chat
                JsonObject chat = message.getAsJsonObject("chat");
                String chatId = chat.get("id").getAsString();
            
            // If allowAllChannelsProcessing is true OR publicChatId is empty, accept messages from any channel
            if (allowAllChannelsProcessing || publicChatId.isEmpty()) {
                // Accept message from any channel - no filtering needed
                // When publicChatId is empty, the bot processes messages from any channel it has access to
            } else {
                // Check chat match
                boolean isPublicChat = chatId.equals(publicChatId);
                boolean hasValidThread = false;
                
                // If we're waiting for a confirmation, accept ANY message from the correct chat
                boolean waitingForConfirmation = pendingConfirmations.containsKey(FILE_PROCESSING_CONFIRMATION_KEY);
                
                if (waitingForConfirmation) {
                    // Accept any message from the correct chat while waiting for confirmation
                    hasValidThread = true;
                    String msgThreadId = message.has("message_thread_id") ? message.get("message_thread_id").getAsString() : "none";
                    System.out.println("[CONFIRMATION MODE] Accepting message from chat " + chatId + " with thread ID: " + msgThreadId);
                } else if (message.has("message_thread_id")) {
                    // Normal mode - validate based on message type
                    String threadId = message.get("message_thread_id").getAsString();
                    
                    // Check if message contains a document (file upload)
                    boolean isFileUpload = message.has("document");
                    // Check if message contains text starting with / (command)
                    boolean isCommand = message.has("text") && message.get("text").getAsString().trim().startsWith("/");
                    
                    if (isFileUpload) {
                        // File uploads should ONLY be accepted from fileupload thread
                        if (!publicChatIdFileupload.isEmpty() && threadId.equals(publicChatIdFileupload)) {
                            hasValidThread = true;
                        }
                    } else if (isCommand) {
                        // Commands should ONLY be accepted from commands thread
                        if (!publicChatIdCommands.isEmpty() && threadId.equals(publicChatIdCommands)) {
                            hasValidThread = true;
                        }
                    } else {
                        // Other text messages (confirmations, etc.) - accept from both threads
                        if (!publicChatIdFileupload.isEmpty() && threadId.equals(publicChatIdFileupload)) {
                            hasValidThread = true;
                        }
                        if (!publicChatIdCommands.isEmpty() && threadId.equals(publicChatIdCommands)) {
                            hasValidThread = true;
                        }
                    }
                } else {
                    // No thread ID in message - accept if both fileupload and commands are empty (base chat)
                    hasValidThread = publicChatIdFileupload.isEmpty() && publicChatIdCommands.isEmpty();
                }
                
                if (!isPublicChat || !hasValidThread) {
                    if (waitingForConfirmation) {
                        System.out.println("[CONFIRMATION MODE] Rejecting message - isPublicChat: " + isPublicChat + ", hasValidThread: " + hasValidThread);
                    } else {
                        // Send error message if user is trying to interact from wrong channel
                        if (message.has("text") || message.has("document")) {
                            // Determine specific error message based on what they're trying to do
                            String errorMsg;
                            if (message.has("document")) {
                                errorMsg = "❌ **Wrong Channel**\n\nPlease upload files to Thread ID " + publicChatIdFileupload + " (file upload channel)";
                            } else if (message.has("text") && message.get("text").getAsString().trim().startsWith("/")) {
                                errorMsg = "❌ **Wrong Channel**\n\nPlease send commands to Thread ID " + publicChatIdCommands + " (commands channel)";
                            } else {
                                errorMsg = "❌ **Wrong Channel**\n\nPlease use:\n";
                                if (!publicChatIdFileupload.isEmpty()) {
                                    errorMsg += "• Thread ID " + publicChatIdFileupload + " for file uploads\n";
                                }
                                if (!publicChatIdCommands.isEmpty()) {
                                    errorMsg += "• Thread ID " + publicChatIdCommands + " for commands\n";
                                }
                            }
                            // Get thread ID from original message if available
                            String msgThreadId = message.has("message_thread_id") ? 
                                message.get("message_thread_id").getAsString() : null;
                            sendMessageToChat(chatId, errorMsg, msgThreadId);
                        }
                    }
                    continue; // Not from target chat/thread
                }
            }
            
            // Check for text message (might be confirmation response or command)
            if (message.has("text")) {
                String text = message.get("text").getAsString();
                JsonObject from = message.getAsJsonObject("from");
                String userId = from.get("id").getAsString();
                String msgThreadId = message.has("message_thread_id") ? message.get("message_thread_id").getAsString() : "none";
                System.out.println("[TEXT MESSAGE] Received from chat " + chatId + ", thread " + msgThreadId + ", user " + userId + ": '" + text + "'");
                handleTextMessage(userId, text.trim(), message);
                continue;
            }
            
            // Check for file (document)
            if (message.has("document")) {
                JsonObject document = message.getAsJsonObject("document");
                    handleFileUpload(message, document);
                }
            }
        } catch (Exception e) {
            System.err.println("Error processing updates: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Handles callback queries from inline keyboard buttons
     */
    private void handleCallbackQuery(JsonObject callbackQuery) {
        try {
            String callbackId = callbackQuery.get("id").getAsString();
            String data = callbackQuery.get("data").getAsString();
            JsonObject from = callbackQuery.getAsJsonObject("from");
            String userId = from.get("id").getAsString();
            String userName = from.has("username") ? from.get("username").getAsString() : "User";
            
            discordLog.logInfo(String.format("Button clicked by %s (ID: %s): %s", userName, userId, data));
            telegramLog.logInfo(String.format("Button clicked by %s (ID: %s): %s", userName, userId, data));
            
            // Answer the callback query to remove loading state
            answerCallbackQuery(callbackId);
            
            // Handle settings toggle callbacks
            if (data.startsWith("setting_toggle_")) {
                com.calplus.ihrgstats.telegrambot.commands.CommandSettings settingsCommand = 
                    new com.calplus.ihrgstats.telegrambot.commands.CommandSettings();
                
                String toggleResponse = settingsCommand.handleToggle(data, userId);
                
                // Get original message info to send response to same chat
                JsonObject message = callbackQuery.has("message") ? callbackQuery.getAsJsonObject("message") : null;
                
                if (message != null) {
                    JsonObject chat = message.getAsJsonObject("chat");
                    String chatId = chat.get("id").getAsString();
                    String threadId = message.has("message_thread_id") ? message.get("message_thread_id").getAsString() : null;
                    
                    // Remove buttons from original message
                    removeInlineKeyboard(chatId, message.get("message_id").getAsString());
                    
                    // Send response to same chat/thread
                    sendMessageToChat(chatId, toggleResponse, threadId);
                }
                return;
            }
            
            // Handle home hall selection request callback
            if (data.equals("setting_homeHall_select")) {
                com.calplus.ihrgstats.telegrambot.commands.CommandSettings settingsCommand = 
                    new com.calplus.ihrgstats.telegrambot.commands.CommandSettings();
                
                com.calplus.ihrgstats.telegrambot.commands.CommandSettings.SettingsResponse hallSelectionResponse = 
                    settingsCommand.handleHomeHallSelection(userId);
                
                // Get original message info to send response to same chat
                JsonObject message = callbackQuery.has("message") ? callbackQuery.getAsJsonObject("message") : null;
                
                if (message != null) {
                    JsonObject chat = message.getAsJsonObject("chat");
                    String chatId = chat.get("id").getAsString();
                    String threadId = message.has("message_thread_id") ? message.get("message_thread_id").getAsString() : null;
                    
                    // Remove buttons from original message
                    removeInlineKeyboard(chatId, message.get("message_id").getAsString());
                    
                    // Send response with buttons using the same method as settings command
                    if (hallSelectionResponse.buttons != null) {
                        sendMessageWithSettingsButtons(hallSelectionResponse.message, 
                            hallSelectionResponse.buttons, 
                            message);
                    } else {
                        sendMessageToChat(chatId, hallSelectionResponse.message, threadId);
                    }
                }
                return;
            }
            
            // Handle home hall selection callbacks
            if (data.startsWith("setting_homeHall_")) {
                com.calplus.ihrgstats.telegrambot.commands.CommandSettings settingsCommand = 
                    new com.calplus.ihrgstats.telegrambot.commands.CommandSettings();
                
                String hallResponse = settingsCommand.handleHomeHallCallback(data, userId);
                
                // Get original message info to send response to same chat
                JsonObject message = callbackQuery.has("message") ? callbackQuery.getAsJsonObject("message") : null;
                
                if (message != null) {
                    JsonObject chat = message.getAsJsonObject("chat");
                    String chatId = chat.get("id").getAsString();
                    String threadId = message.has("message_thread_id") ? message.get("message_thread_id").getAsString() : null;
                    
                    // Remove buttons from original message
                    removeInlineKeyboard(chatId, message.get("message_id").getAsString());
                    
                    // Send response to same chat/thread
                    sendMessageToChat(chatId, hallResponse, threadId);
                }
                return;
            }
            
            // Handle settings cancel callback
            if (data.equals("settings_cancel")) {
                com.calplus.ihrgstats.telegrambot.commands.CommandSettings settingsCommand = 
                    new com.calplus.ihrgstats.telegrambot.commands.CommandSettings();
                
                String cancelResponse = settingsCommand.handleCancel(userId);
                
                // Get original message info to send response to same chat
                JsonObject message = callbackQuery.has("message") ? callbackQuery.getAsJsonObject("message") : null;
                
                if (message != null) {
                    JsonObject chat = message.getAsJsonObject("chat");
                    String chatId = chat.get("id").getAsString();
                    String threadId = message.has("message_thread_id") ? message.get("message_thread_id").getAsString() : null;
                    
                    // Remove buttons from original message
                    removeInlineKeyboard(chatId, message.get("message_id").getAsString());
                    
                    // Send response to same chat/thread
                    sendMessageToChat(chatId, cancelResponse, threadId);
                }
                return;
            }
            
            // Handle export database confirmation callbacks
            if (data.startsWith("export_db_confirm_") || data.startsWith("export_db_cancel_")) {
                String[] parts = data.split("_");
                if (parts.length >= 4) {
                    String requestUserId = parts[3];
                    
                    // Verify user matches
                    if (!userId.equals(requestUserId)) {
                        sendMessageToChat(userId, "❌ This confirmation is not for you.");
                        return;
                    }
                    
                    // Get original message info
                    JsonObject message = callbackQuery.has("message") ? callbackQuery.getAsJsonObject("message") : null;
                    
                    if (message != null) {
                        JsonObject chat = message.getAsJsonObject("chat");
                        String chatId = chat.get("id").getAsString();
                        String threadId = message.has("message_thread_id") ? message.get("message_thread_id").getAsString() : null;
                        String messageId = message.get("message_id").getAsString();
                        
                        // Remove buttons from original message
                        removeInlineKeyboard(chatId, messageId);
                        
                        com.calplus.ihrgstats.telegrambot.commands.CommandExportDatabase exportCommand = 
                            new com.calplus.ihrgstats.telegrambot.commands.CommandExportDatabase();
                        
                        if (data.startsWith("export_db_confirm_")) {
                            // Execute export
                            com.calplus.ihrgstats.telegrambot.commands.CommandExportDatabase.ExportResponse response = 
                                exportCommand.executeExport(userId);
                            
                            if (response.success && response.exportedFilePath != null) {
                                // Send file to user's DM
                                sendFileToUser(userId, response.exportedFilePath.toString());
                                
                                // Send success message to original chat/thread (where button was clicked)
                                sendMessageToChat(chatId, response.message, threadId);
                                
                                // Also send success message to commands channel
                                sendMessageToCommandsChannel(response.message, message);
                            } else {
                                // Send error message to original chat/thread
                                sendMessageToChat(chatId, response.message, threadId);
                            }
                        } else {
                            // Cancel
                            String cancelMessage = exportCommand.handleCancel(userId);
                            sendMessageToChat(chatId, cancelMessage, threadId);
                        }
                    }
                }
                return;
            }
            
            // Handle multi-choice confirmation
            MultiChoiceConfirmationRequest request = pendingMultiChoiceConfirmations.get(FILE_PROCESSING_MULTI_CHOICE_KEY);
            if (request != null) {
                // Parse the callback data (format: "choice_0", "choice_1", etc.)
                if (data.startsWith("choice_")) {
                    try {
                        int choice = Integer.parseInt(data.substring(7));
                        if (choice >= 0 && choice < request.options.length) {
                            String selectedOption = request.options[choice];
                            discordLog.logInfo(String.format("User selected option %d: %s", choice, selectedOption));
                            telegramLog.logInfo(String.format("User selected option %d: %s", choice, selectedOption));
                            
                            // Send confirmation message to chat
                            String confirmMsg = String.format("✅ Selected: %s", selectedOption);
                            sendMessageToUploadChat(confirmMsg);
                            
                            request.future.complete(choice);
                            pendingMultiChoiceConfirmations.remove(FILE_PROCESSING_MULTI_CHOICE_KEY);
                        } else {
                            sendMessageToUploadChat("❌ Invalid choice index");
                        }
                    } catch (NumberFormatException e) {
                        sendMessageToUploadChat("❌ Invalid callback data format");
                    }
                }
            }
            
        } catch (Exception e) {
            discordLog.logError("Error handling callback query: " + e.getMessage());
            telegramLog.logError("Error handling callback query: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Answers a callback query to remove loading state from button
     */
    private void answerCallbackQuery(String callbackId) {
        try {
            String url = String.format("https://api.telegram.org/bot%s/answerCallbackQuery", botToken);
            
            JsonObject payload = new JsonObject();
            payload.addProperty("callback_query_id", callbackId);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build();
            
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
        } catch (Exception e) {
            // Non-critical error, just log
            System.err.println("Error answering callback query: " + e.getMessage());
        }
    }
    
    /**
     * Handles text messages (possibly confirmation responses or commands)
     */
    private void handleTextMessage(String userId, String text, JsonObject message) {
        // Check for commands
        if (text.startsWith("/")) {
            handleCommand(text.trim(), message);
            return;
        }

        // Check for any pending confirmation (file processing uses global key)
        ConfirmationRequest request = pendingConfirmations.get(FILE_PROCESSING_CONFIRMATION_KEY);
        
        if (request != null) {
            System.out.println("Pending confirmation found. User text: '" + text + "'");
            String lowerText = text.toLowerCase();
            if (lowerText.equals("yes") || lowerText.equals("y")) {
                System.out.println("User confirmed YES - completing future with true");
                request.future.complete(true);
                pendingConfirmations.remove(FILE_PROCESSING_CONFIRMATION_KEY);
            } else if (lowerText.equals("no") || lowerText.equals("n")) {
                System.out.println("User confirmed NO - completing future with false");
                request.future.complete(false);
                pendingConfirmations.remove(FILE_PROCESSING_CONFIRMATION_KEY);
            } else {
                System.out.println("Text does not match yes/no: '" + lowerText + "'");
            }
        } else {
            System.out.println("No pending confirmation found for text: '" + text + "'");
        }
    }

    /**
     * Handles file upload
     */
    private void handleFileUpload(JsonObject message, JsonObject document) {
        try {
            String fileName = document.get("file_name").getAsString();
            String fileId = document.get("file_id").getAsString();
            
            JsonObject from = message.getAsJsonObject("from");
            String userId = from.get("id").getAsString();
            String username = from.has("username") ? from.get("username").getAsString() : "Unknown";
            
            discordLog.logInfo(String.format("File upload detected: %s from user %s (ID: %s)", fileName, username, userId));
            telegramLog.logInfo(String.format("File upload detected: %s from user %s (ID: %s)", fileName, username, userId));
            
            // Additional safety check: validate file upload channel when allowAllChannelsProcessing is false
            if (!allowAllChannelsProcessing && !publicChatId.isEmpty()) {
                JsonObject chat = message.getAsJsonObject("chat");
                String chatId = chat.get("id").getAsString();
                
                boolean isValidChannel = false;
                
                // Check if message is from correct chat
                if (chatId.equals(publicChatId)) {
                    // Check thread ID
                    if (!publicChatIdFileupload.isEmpty()) {
                        // Must be in fileupload thread
                        if (message.has("message_thread_id")) {
                            String threadId = message.get("message_thread_id").getAsString();
                            if (threadId.equals(publicChatIdFileupload)) {
                                isValidChannel = true;
                            }
                        }
                    } else {
                        // No specific fileupload thread configured, accept from base chat
                        isValidChannel = !message.has("message_thread_id");
                    }
                }
                
                if (!isValidChannel) {
                    String errorMsg = "❌ **Wrong Channel**\n\nPlease upload files to Thread ID " + publicChatIdFileupload + " (file upload channel)";
                    String msgThreadId = message.has("message_thread_id") ? message.get("message_thread_id").getAsString() : null;
                    sendMessageToChat(chatId, errorMsg, msgThreadId);
                    
                    String logMsg = String.format("File upload rejected from wrong channel. User: %s, File: %s", username, fileName);
                    discordLog.logWarning(logMsg);
                    telegramLog.logWarning(logMsg);
                    return;
                }
            }
            
            // Check admin status
            boolean isAdmin = userId.equals(adminUserId);
            
            if (!isAdmin) {
                if (!allowNonAdminUploads) {
                    String errorMsg = String.format("User %s is not an admin. File upload rejected.", username);
                    discordLog.logError(errorMsg);
                    telegramLog.logError(errorMsg);
                    return;
                }
                
                // Request confirmation for non-admin upload
                String confirmMsg = String.format("⚠️ User %s is not an admin. Do you want to process their file '%s'? Reply with 'yes' or 'no'.", 
                    username, fileName);
                boolean confirmed = requestUserConfirmationViaChat(userId, confirmMsg);
                
                if (!confirmed) {
                    String cancelMsg = "File processing cancelled - user did not confirm.";
                    discordLog.logWarning(cancelMsg);
                    telegramLog.logWarning(cancelMsg);
                    return;
                }
            }
            
            // Process file in a separate thread to avoid blocking the polling thread
            // This is CRITICAL - if we process synchronously, the polling thread can't receive
            // the "yes/no" confirmation responses!
            Thread processingThread = new Thread(() -> {
                processFile(fileId, fileName, userId, message);
            });
            processingThread.setDaemon(true);
            processingThread.start();
            
        } catch (Exception e) {
            String errorMsg = "Error handling file upload: " + e.getMessage();
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            e.printStackTrace();
        }
    }

    /**
     * Requests user confirmation via Telegram chat
     */
    private boolean requestUserConfirmationViaChat(String userId, String message) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pendingConfirmations.put(FILE_PROCESSING_CONFIRMATION_KEY, new ConfirmationRequest(message, future));
        
        // Send confirmation request message
        telegramLog.logInfo(message);
        
        try {
            // Wait up to 60 seconds for response
            return future.get(60, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pendingConfirmations.remove(FILE_PROCESSING_CONFIRMATION_KEY);
            telegramLog.logWarning("Confirmation timeout - no response received within 60 seconds.");
            sendMessageToUploadChat("⏱️ Confirmation timeout - processing cancelled.");
            return false;
        } catch (Exception e) {
            pendingConfirmations.remove(FILE_PROCESSING_CONFIRMATION_KEY);
            telegramLog.logError("Error waiting for confirmation: " + e.getMessage());
            return false;
        }
    }

    /**
     * Processes a file based on its name
     */
    private void processFile(String fileId, String fileName, String userId, JsonObject originalMessage) {
        fileName = fileName.toLowerCase();
        
        // Extract chat and thread info from original message for response routing
        // Make these final for use in lambda expressions
        final String responseChatId;
        final String responseThreadId;
        
        if (allowAllChannelsProcessing && originalMessage != null) {
            // Use the channel where the file was uploaded
            JsonObject chat = originalMessage.getAsJsonObject("chat");
            responseChatId = chat.get("id").getAsString();
            responseThreadId = originalMessage.has("message_thread_id") ? 
                originalMessage.get("message_thread_id").getAsString() : null;
        } else {
            responseChatId = publicChatId;
            responseThreadId = publicChatIdFileupload;
        }
        
        // Download file
        TelegramFileDownloader downloader = new TelegramFileDownloader(botToken);
        Path downloadedFile = downloader.downloadToTemp(fileId, fileName);
        
        if (downloadedFile == null) {
            String errorMsg = "Failed to download file from Telegram";
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            // Send error to chat where file was uploaded
            sendMessageToChatWithThread(responseChatId, formatStatusMessage("🔴", "ERROR", errorMsg), responseThreadId);
            return;
        }
        
        try {
            if (fileName.equals("cappedlist.csv")) {
                // Process with A2_CappedPlayers
                discordLog.logInfo("Processing cappedlist.csv...");
                telegramLog.logInfo("Processing cappedlist.csv...");
                
                A2_CappedPlayers processor = new A2_CappedPlayers();
                
                // Set up callback to send success message to upload chat
                processor.setUploadChatCallback((msg) -> {
                    sendMessageToChatWithThread(responseChatId, msg, responseThreadId);
                });
                
                boolean success = processor.processCappedList(downloadedFile.toString());
                
                if (!success) {
                    String errorMsg = "Failed to process cappedlist.csv";
                    discordLog.logError(errorMsg);
                    telegramLog.logError(errorMsg);
                    // Send error to chat where file was uploaded
                    sendMessageToChatWithThread(responseChatId, formatStatusMessage("🔴", "ERROR", errorMsg), responseThreadId);
                }
                
            } else if (fileName.matches("round_[1-6t1628]+(\\d+)?\\.csv")) {
                // Extract round number
                String roundName = extractRoundName(fileName);
                
                if (roundName == null) {
                    String errorMsg = "Invalid round filename format: " + fileName;
                    discordLog.logError(errorMsg);
                    telegramLog.logError(errorMsg);
                    // Send error to chat where file was uploaded
                    sendMessageToChatWithThread(responseChatId, formatStatusMessage("🔴", "ERROR", errorMsg), responseThreadId);
                    return;
                }
                
                // Process with A1_PlayerStats
                discordLog.logInfo(String.format("Processing round_%s.csv...", roundName));
                telegramLog.logInfo(String.format("Processing round_%s.csv...", roundName));
                
                A1_PlayerStats processor = new A1_PlayerStats();
                
                // Set up confirmation callback for Telegram
                processor.setConfirmationCallback((msg) -> {
                    // Send confirmation request to upload chat (only once)
                    String formattedMsg = formatStatusMessage("⚠️", "CONFIRMATION", msg + "\n\nPlease reply 'yes' or 'no'.");
                    sendMessageToUploadChat(formattedMsg);
                    
                    // Wait for user response via Telegram (don't log again in requestUserConfirmationViaChat)
                    CompletableFuture<Boolean> future = new CompletableFuture<>();
                    pendingConfirmations.put(FILE_PROCESSING_CONFIRMATION_KEY, new ConfirmationRequest(msg, future));
                    
                    try {
                        return future.get(60, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        pendingConfirmations.remove(FILE_PROCESSING_CONFIRMATION_KEY);
                        sendMessageToUploadChat("⏱️ Confirmation timeout - processing cancelled.");
                        return false;
                    } catch (Exception e) {
                        pendingConfirmations.remove(FILE_PROCESSING_CONFIRMATION_KEY);
                        telegramLog.logError("Error waiting for confirmation: " + e.getMessage());
                        return false;
                    }
                });
                
                // Set up multi-choice callback for Telegram with buttons
                processor.setMultiChoiceCallback((msg, options) -> {
                    // Send message with inline keyboard buttons
                    sendMessageWithButtons(msg, options);
                    
                    // Wait for button click response
                    CompletableFuture<Integer> future = new CompletableFuture<>();
                    pendingMultiChoiceConfirmations.put(FILE_PROCESSING_MULTI_CHOICE_KEY, 
                        new MultiChoiceConfirmationRequest(msg, options, future));
                    
                    try {
                        return future.get(120, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        pendingMultiChoiceConfirmations.remove(FILE_PROCESSING_MULTI_CHOICE_KEY);
                        sendMessageToUploadChat("⏱️ Button selection timeout - processing cancelled.");
                        return -1;
                    } catch (Exception e) {
                        pendingMultiChoiceConfirmations.remove(FILE_PROCESSING_MULTI_CHOICE_KEY);
                        telegramLog.logError("Error waiting for button selection: " + e.getMessage());
                        return -1;
                    }
                });
                
                // Set up callback to send success message to upload chat
                processor.setUploadChatCallback((msg) -> {
                    sendMessageToChatWithThread(responseChatId, msg, responseThreadId);
                });
                
                boolean success = processor.processRound(downloadedFile.toString(), roundName);
                
                if (!success) {
                    String errorMsg = String.format("Failed to process round_%s.csv", roundName);
                    discordLog.logError(errorMsg);
                    telegramLog.logError(errorMsg);
                    // Send error to chat where file was uploaded
                    sendMessageToChatWithThread(responseChatId, formatStatusMessage("🔴", "ERROR", errorMsg), responseThreadId);
                }
                
            } else if (fileName.matches("playerexport_\\d{8}_\\d{6}\\.csv")) {
                // Process player import from export file
                discordLog.logInfo("Processing player import file: " + fileName);
                telegramLog.logInfo("Processing player import file: " + fileName);
                
                A1_PlayerStats processor = new A1_PlayerStats();
                
                // Set up multi-choice callback for Telegram with buttons
                processor.setMultiChoiceCallback((msg, options) -> {
                    // Send message with inline keyboard buttons
                    sendMessageWithButtons(msg, options);
                    
                    // Wait for button click response
                    CompletableFuture<Integer> future = new CompletableFuture<>();
                    pendingMultiChoiceConfirmations.put(FILE_PROCESSING_MULTI_CHOICE_KEY, 
                        new MultiChoiceConfirmationRequest(msg, options, future));
                    
                    try {
                        return future.get(120, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        pendingMultiChoiceConfirmations.remove(FILE_PROCESSING_MULTI_CHOICE_KEY);
                        sendMessageToChatWithThread(responseChatId, "⏱️ Button selection timeout - processing cancelled.", responseThreadId);
                        return -1;
                    } catch (Exception e) {
                        pendingMultiChoiceConfirmations.remove(FILE_PROCESSING_MULTI_CHOICE_KEY);
                        telegramLog.logError("Error waiting for button selection: " + e.getMessage());
                        return -1;
                    }
                });
                
                // Set up callback to send success message to upload chat
                processor.setUploadChatCallback((msg) -> {
                    sendMessageToChatWithThread(responseChatId, msg, responseThreadId);
                });
                
                boolean success = processor.importPlayerExport(downloadedFile.toString());
                
                if (!success) {
                    String errorMsg = "Failed to import player export file: " + fileName;
                    discordLog.logError(errorMsg);
                    telegramLog.logError(errorMsg);
                    // Send error to chat where file was uploaded
                    sendMessageToChatWithThread(responseChatId, formatStatusMessage("🔴", "ERROR", errorMsg), responseThreadId);
                }
                
            } else {
                String errorMsg = String.format("Unknown file type: %s. Accepted files: cappedlist.csv, round_[n].csv, playerExport_[datetime].csv", fileName);
                discordLog.logError(errorMsg);
                telegramLog.logError(errorMsg);
                // Send error to chat where file was uploaded
                sendMessageToChatWithThread(responseChatId, formatStatusMessage("🔴", "ERROR", errorMsg), responseThreadId);
            }
            
        } finally {
            // Clean up temp file
            TelegramFileDownloader.deleteTempFile(downloadedFile);
        }
    }

    /**
     * Extracts round name from filename
     */
    private String extractRoundName(String fileName) {
        Pattern pattern = Pattern.compile("round_([1-6]|t16|t8|t4|t2)\\.csv");
        Matcher matcher = pattern.matcher(fileName);
        
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * Formats a message like TelegramLog (with emote, timestamp, filename, type)
     */
    private String formatStatusMessage(String emote, String type, String message) {
        String timestamp = java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        );
        String filename = "TelegramListener";
        return String.format("%s [%s] [%s] %s: %s", emote, timestamp, filename, type, message);
    }

    /**
     * Handles commands (e.g., /exportplayers)
     */
    private void handleCommand(String command, JsonObject message) {
        // Strip @botname suffix from command (e.g., /exportplayers@h4weiqi_bot -> /exportplayers)
        int atIndex = command.indexOf('@');
        if (atIndex > 0) {
            command = command.substring(0, atIndex);
        }
        
        // Check if message is in commands channel/thread
        boolean isCommandsChannel = false;
        
        if (allowAllChannelsProcessing) {
            // If allowAllChannelsProcessing is true, accept commands from any channel
            isCommandsChannel = true;
        } else if (publicChatIdCommands != null && !publicChatIdCommands.isEmpty()) {
            // Get chat ID from message
            String messageChatId = null;
            if (message.has("chat")) {
                JsonObject chat = message.getAsJsonObject("chat");
                if (chat.has("id")) {
                    messageChatId = chat.get("id").getAsString();
                }
            }
            
            // Check if message is in the public chat and has the correct thread ID
            if (messageChatId != null && messageChatId.equals(publicChatId)) {
                if (message.has("message_thread_id")) {
                    String threadId = message.get("message_thread_id").getAsString();
                    if (threadId.equals(publicChatIdCommands)) {
                        isCommandsChannel = true;
                    }
                }
            }
        } else {
            // If no specific commands channel, allow commands in any chat
            isCommandsChannel = true;
        }

        if (!isCommandsChannel) {
            // Send error message to the channel where command was sent
            JsonObject chat = message.getAsJsonObject("chat");
            String chatId = chat.get("id").getAsString();
            String threadId = message.has("message_thread_id") ? message.get("message_thread_id").getAsString() : null;
            
            String errorMsg = "❌ **Wrong Channel**\n\nPlease send commands to Thread ID " + publicChatIdCommands + " (commands channel)";
            sendMessageToChat(chatId, errorMsg, threadId);
            
            System.out.println("Command received but not in commands channel: " + command + " - Error sent to user");
            return;
        }

        // Parse command (already stripped of @botname)
        if (command.equalsIgnoreCase("/exportplayers")) {
            handleExportPlayersCommand(message);
        } else if (command.equalsIgnoreCase("/settings")) {
            handleSettingsCommand(message);
        } else if (command.equalsIgnoreCase("/exportdatabase")) {
            handleExportDatabaseCommand(message);
        } else if (command.equalsIgnoreCase("/rankplayers")) {
            handleRankPlayersCommand(message);
        } else if (command.equalsIgnoreCase("/rankhalls")) {
            handleRankHallsCommand(message);
        } else {
            System.out.println("Unknown command: " + command);
        }
    }

    /**
     * Handles /exportplayers command
     */
    private void handleExportPlayersCommand(JsonObject message) {
        discordLog.logInfo("Processing /exportplayers command...");
        telegramLog.logInfo("Processing /exportplayers command...");

        try {
            com.calplus.ihrgstats.telegrambot.commands.CommandExportPlayers exporter = 
                new com.calplus.ihrgstats.telegrambot.commands.CommandExportPlayers();
            
            java.nio.file.Path csvPath = exporter.exportLatestPlayerData();

            if (csvPath != null && java.nio.file.Files.exists(csvPath)) {
                // Send the file to the commands channel
                sendFileToCommandsChannel(csvPath, message);
                
                discordLog.logSuccess("Player data export file sent to commands channel");
                telegramLog.logSuccess("Player data export file sent to commands channel");
            } else {
                String errorMsg = "Failed to export player data";
                discordLog.logError(errorMsg);
                telegramLog.logError(errorMsg);
                sendMessageToCommandsChannel(formatStatusMessage("🔴", "ERROR", errorMsg), message);
            }
        } catch (Exception e) {
            String errorMsg = "Error processing /exportplayers command: " + e.getMessage();
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            e.printStackTrace();
            sendMessageToCommandsChannel(formatStatusMessage("🔴", "ERROR", errorMsg), message);
        }
    }

    /**
     * Sends a file to the commands channel
     */
    private void sendFileToCommandsChannel(java.nio.file.Path filePath, JsonObject message) {
        try {
            String url = "https://api.telegram.org/bot" + botToken + "/sendDocument";
            
            // Read file content
            byte[] fileBytes = java.nio.file.Files.readAllBytes(filePath);
            String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
            
            // Determine where to send the file
            String targetChatId;
            String targetThreadId = null;
            
            if (allowAllChannelsProcessing && message != null) {
                // Send to the same channel where the command was received
                JsonObject chat = message.getAsJsonObject("chat");
                targetChatId = chat.get("id").getAsString();
                if (message.has("message_thread_id")) {
                    targetThreadId = message.get("message_thread_id").getAsString();
                }
            } else {
                // Send to the configured commands channel using helper method
                String[] chatAndThread = getCommandsChatIdAndThread();
                if (chatAndThread == null || chatAndThread[0] == null || chatAndThread[0].isEmpty()) {
                    System.err.println("Cannot send file: commands chat ID is not configured");
                    return;
                }
                targetChatId = chatAndThread[0];
                targetThreadId = chatAndThread[1];
            }
            
            // Build multipart request body
            java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
            java.io.PrintWriter writer = new java.io.PrintWriter(new java.io.OutputStreamWriter(outputStream, java.nio.charset.StandardCharsets.UTF_8), true);
            
            // Add chat_id
            writer.append("--" + boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"chat_id\"").append("\r\n");
            writer.append("\r\n");
            writer.append(targetChatId).append("\r\n");
            
            // Add message_thread_id if specified
            if (targetThreadId != null && !targetThreadId.isEmpty()) {
                writer.append("--" + boundary).append("\r\n");
                writer.append("Content-Disposition: form-data; name=\"message_thread_id\"").append("\r\n");
                writer.append("\r\n");
                writer.append(targetThreadId).append("\r\n");
            }
            
            // Add file
            writer.append("--" + boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"document\"; filename=\"" + filePath.getFileName().toString() + "\"").append("\r\n");
            writer.append("Content-Type: text/csv").append("\r\n");
            writer.append("\r\n");
            writer.flush();
            
            outputStream.write(fileBytes);
            outputStream.flush();
            
            writer.append("\r\n");
            writer.append("--" + boundary + "--").append("\r\n");
            writer.flush();
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(outputStream.toByteArray()))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                System.err.println("Failed to send file (HTTP " + response.statusCode() + "): " + response.body());
            }
        } catch (Exception e) {
            System.err.println("Error sending file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Sends a message to the commands channel
     * Intelligently routes based on allowAllChannelsProcessing and subchannel configuration
     */
    private void sendMessageToCommandsChannel(String message, JsonObject originalMessage) {
        try {
            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
            
            JsonObject payload = new JsonObject();
            
            // Determine where to send the message
            if (allowAllChannelsProcessing && originalMessage != null && originalMessage.has("chat")) {
                // Send to the same channel where the command was received
                JsonObject chat = originalMessage.getAsJsonObject("chat");
                payload.addProperty("chat_id", chat.get("id").getAsString());
                
                // Add thread ID if the original message was in a thread
                if (originalMessage.has("message_thread_id")) {
                    payload.addProperty("message_thread_id", originalMessage.get("message_thread_id").getAsString());
                }
            } else {
                // Send to the configured commands channel using helper method
                String[] chatAndThread = getCommandsChatIdAndThread();
                if (chatAndThread == null || chatAndThread[0] == null || chatAndThread[0].isEmpty()) {
                    System.err.println("Cannot send command response: commands chat ID is not configured");
                    return;
                }
                
                payload.addProperty("chat_id", chatAndThread[0]);
                
                // Add thread ID if specified
                if (chatAndThread[1] != null && !chatAndThread[1].isEmpty()) {
                    payload.addProperty("message_thread_id", chatAndThread[1]);
                }
            }
            
            payload.addProperty("text", message);
            
            // Add parse_mode for markdown if message contains code blocks or formatting
            if (message.contains("```") || message.contains("**") || message.contains("__")) {
                payload.addProperty("parse_mode", "Markdown");
            }
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                System.err.println("Failed to send message to commands channel (HTTP " + response.statusCode() + "): " + response.body());
            }
        } catch (Exception e) {
            System.err.println("Error sending message to commands channel: " + e.getMessage());
        }
    }

    /**
     * Starts the status heartbeat that sends a message every 5 minutes
     */
    private void startStatusHeartbeat() {
        String[] chatAndThread = getStatusChatIdAndThread();
        if (chatAndThread == null) {
            System.out.println("Dev chat ID not configured, status heartbeat disabled");
            return;
        }

        statusHeartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        statusHeartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                String message = formatStatusMessage("🟢", "SUCCESS", "Bot is online and monitoring for file uploads");
                sendMessageToStatusChat(message);
            } catch (Exception e) {
                System.err.println("Error sending status heartbeat: " + e.getMessage());
            }
        }, 0, STATUS_HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);

        System.out.println("Status heartbeat started (5 minute interval, first message sending now)");
    }

    /**
     * Stops the status heartbeat
     */
    private void stopStatusHeartbeat() {
        if (statusHeartbeatExecutor != null && !statusHeartbeatExecutor.isShutdown()) {
            statusHeartbeatExecutor.shutdown();
            try {
                if (!statusHeartbeatExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    statusHeartbeatExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                statusHeartbeatExecutor.shutdownNow();
            }
        }
    }

    /**
     * Sends a fatal error message to status chat and stops the bot
     */
    private void sendFatalErrorAndStop(String errorMessage) {
        if (devChatId != null && !devChatId.isEmpty()) {
            String message = formatStatusMessage("🔴", "ERROR", "Fatal error: " + errorMessage + " - Bot shutting down");
            sendMessageToStatusChat(message);
        }
        stop();
    }

    /**
     * Sends a message to the status chat/thread
     * Intelligently routes to subchannel if exists, otherwise main dev channel
     */
    private void sendMessageToStatusChat(String message) {
        try {
            String[] chatAndThread = getStatusChatIdAndThread();
            if (chatAndThread == null) {
                System.err.println("Cannot send status message: devChatId is not configured");
                return;
            }
            
            String chatId = chatAndThread[0];
            String threadId = chatAndThread[1];
            
            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
            
            JsonObject payload = new JsonObject();
            payload.addProperty("chat_id", chatId);
            payload.addProperty("text", message);
            
            // Add thread ID if specified
            if (threadId != null && !threadId.isEmpty()) {
                try {
                    int threadIdInt = Integer.parseInt(threadId);
                    payload.addProperty("message_thread_id", threadIdInt);
                    System.out.println("Sending status to chat " + chatId + " with thread ID " + threadId);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid thread ID format: " + threadId + " - sending without thread ID");
                }
            } else {
                System.out.println("Sending status to chat " + chatId + " without thread ID");
            }
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                System.err.println("Failed to send status message (HTTP " + response.statusCode() + "): " + response.body());
            } else {
                System.out.println("Status message sent successfully");
            }
        } catch (Exception e) {
            System.err.println("Error sending status message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Sends a message with inline keyboard buttons to the upload chat
     * Intelligently routes to subchannel if exists, otherwise main channel
     */
    private void sendMessageWithButtons(String message, String[] options) {
        try {
            String[] chatAndThread = getUploadChatIdAndThread();
            if (chatAndThread == null || chatAndThread[0] == null || chatAndThread[0].isEmpty()) {
                System.err.println("Cannot send message with buttons: upload chat ID is not configured");
                return;
            }
            
            String chatId = chatAndThread[0];
            String threadId = chatAndThread[1];
            
            String url = String.format("https://api.telegram.org/bot%s/sendMessage", botToken);
            
            JsonObject payload = new JsonObject();
            payload.addProperty("chat_id", chatId);
            
            // Add thread ID if specified
            if (threadId != null && !threadId.isEmpty()) {
                try {
                    payload.addProperty("message_thread_id", Integer.parseInt(threadId));
                } catch (NumberFormatException e) {
                    // Ignore if not a valid number
                }
            }
            
            // Don't use Markdown parse mode with buttons - it can cause conflicts
            // Send message as plain text to avoid parsing errors
            payload.addProperty("text", message);
            
            // Create inline keyboard with buttons
            JsonObject replyMarkup = new JsonObject();
            JsonArray keyboard = new JsonArray();
            
            for (int i = 0; i < options.length; i++) {
                JsonArray row = new JsonArray();
                JsonObject button = new JsonObject();
                button.addProperty("text", options[i]);
                button.addProperty("callback_data", "choice_" + i);
                row.add(button);
                keyboard.add(row);
            }
            
            replyMarkup.add("inline_keyboard", keyboard);
            payload.add("reply_markup", replyMarkup);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                String errorMsg = String.format("Failed to send message with buttons. Status: %d, Response: %s", 
                    response.statusCode(), response.body());
                discordLog.logError(errorMsg);
                telegramLog.logError(errorMsg);
                System.err.println("[Button Error] " + errorMsg);
            }
            
        } catch (Exception e) {
            discordLog.logError("Error sending message with buttons: " + e.getMessage());
            telegramLog.logError("Error sending message with buttons: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Sends a message to the upload chat/thread
     * Intelligently routes to subchannel if exists, otherwise main channel
     */
    private void sendMessageToUploadChat(String message) {
        try {
            String[] chatAndThread = getUploadChatIdAndThread();
            if (chatAndThread == null || chatAndThread[0] == null || chatAndThread[0].isEmpty()) {
                System.err.println("Cannot send message: upload chat ID is not configured");
                discordLog.logWarning("Cannot send message to Telegram: upload chat not configured");
                return;
            }
            
            String chatId = chatAndThread[0];
            String threadId = chatAndThread[1];
            
            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
            
            JsonObject payload = new JsonObject();
            payload.addProperty("chat_id", chatId);
            payload.addProperty("text", message);
            
            // Add thread ID if specified
            if (threadId != null && !threadId.isEmpty()) {
                payload.addProperty("message_thread_id", threadId);
                System.out.println("Sending to upload chat " + chatId + " with thread ID " + threadId);
            } else {
                System.out.println("Sending to upload chat " + chatId + " without thread ID");
            }
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                System.out.println("Successfully sent message to upload chat: " + message);
            } else {
                System.err.println("Failed to send message (HTTP " + response.statusCode() + "): " + response.body());
                discordLog.logError("Failed to send Telegram message: HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            System.err.println("Error sending message to upload chat: " + e.getMessage());
            e.printStackTrace();
            discordLog.logError("Error sending Telegram message: " + e.getMessage());
        }
    }

    /**
     * Sends a message to a specific Telegram chat
     */
    private void sendMessageToChat(String chatId, String message) {
        sendMessageToChat(chatId, message, null);
    }

    /**
     * Sends a message to a specific Telegram chat with optional thread ID
     */
    private void sendMessageToChat(String chatId, String message, String threadId) {
        try {
            if (chatId == null || chatId.isEmpty()) {
                System.err.println("Cannot send message: chatId is empty or null");
                discordLog.logWarning("Cannot send message to Telegram upload chat: chatId not configured");
                return;
            }
            
            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
            
            JsonObject payload = new JsonObject();
            payload.addProperty("chat_id", chatId);
            payload.addProperty("text", message);
            
            // Add parse_mode for markdown if message contains code blocks or formatting
            if (message.contains("```") || message.contains("**") || message.contains("__")) {
                payload.addProperty("parse_mode", "Markdown");
            }
            
            // Add thread ID if provided
            if (threadId != null && !threadId.isEmpty()) {
                try {
                    payload.addProperty("message_thread_id", Integer.parseInt(threadId));
                } catch (NumberFormatException e) {
                    // Ignore if not a valid number
                }
            }
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                System.out.println("Successfully sent message to chat " + chatId + ": " + message);
            } else {
                System.err.println("Failed to send message to chat " + chatId + " (HTTP " + response.statusCode() + "): " + response.body());
                discordLog.logError("Failed to send Telegram message: HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            System.err.println("Error sending message to chat: " + e.getMessage());
            e.printStackTrace();
            discordLog.logError("Error sending Telegram message: " + e.getMessage());
        }
    }

    /**
     * Sends a message to a specific chat with thread
     */
    private void sendMessageToChatWithThread(String chatId, String message, String threadId) {
        try {
            if (chatId == null || chatId.isEmpty()) {
                System.err.println("Cannot send message: chatId is empty or null");
                return;
            }
            
            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
            
            JsonObject payload = new JsonObject();
            payload.addProperty("chat_id", chatId);
            payload.addProperty("text", message);
            
            // Add thread ID if specified
            if (threadId != null && !threadId.isEmpty()) {
                try {
                    payload.addProperty("message_thread_id", Integer.parseInt(threadId));
                } catch (NumberFormatException e) {
                    // Ignore if not a valid number
                }
            }
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                System.err.println("Failed to send message (HTTP " + response.statusCode() + "): " + response.body());
            }
        } catch (Exception e) {
            System.err.println("Error sending message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Handles /settings command
     */
    private void handleSettingsCommand(JsonObject message) {
        try {
            // Get user ID from message
            JsonObject from = message.getAsJsonObject("from");
            String userId = from.get("id").getAsString();

            com.calplus.ihrgstats.telegrambot.commands.CommandSettings settingsCommand = 
                new com.calplus.ihrgstats.telegrambot.commands.CommandSettings();
            
            com.calplus.ihrgstats.telegrambot.commands.CommandSettings.SettingsResponse response = 
                settingsCommand.handleCommand(userId);

            if (response.buttons != null) {
                // Send message with buttons
                sendMessageWithSettingsButtons(response.message, response.buttons, message);
            } else {
                // Just send message (e.g., unauthorized)
                sendMessageToCommandsChannel(response.message, message);
            }

        } catch (Exception e) {
            String errorMsg = "Error processing /settings command: " + e.getMessage();
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            e.printStackTrace();
            sendMessageToCommandsChannel(formatStatusMessage("🔴", "ERROR", errorMsg), message);
        }
    }

    /**
     * Handles /exportdatabase command
     */
    private void handleExportDatabaseCommand(JsonObject message) {
        try {
            // Get user ID from message
            JsonObject from = message.getAsJsonObject("from");
            String userId = from.get("id").getAsString();

            com.calplus.ihrgstats.telegrambot.commands.CommandExportDatabase exportCommand = 
                new com.calplus.ihrgstats.telegrambot.commands.CommandExportDatabase();
            
            com.calplus.ihrgstats.telegrambot.commands.CommandExportDatabase.ExportResponse response = 
                exportCommand.requestConfirmation(userId);

            if (response.buttons != null) {
                // Send confirmation message with buttons
                sendMessageWithExportButtons(response.message, response.buttons, message, userId);
            } else {
                // Just send message (e.g., unauthorized)
                sendMessageToCommandsChannel(response.message, message);
            }

        } catch (Exception e) {
            String errorMsg = "Error processing /exportdatabase command: " + e.getMessage();
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            e.printStackTrace();
            sendMessageToCommandsChannel(formatStatusMessage("🔴", "ERROR", errorMsg), message);
        }
    }

    /**
     * Handles /rankplayers command
     */
    private void handleRankPlayersCommand(JsonObject message) {
        try {
            com.calplus.ihrgstats.telegrambot.commands.CommandRankPlayers rankCommand = 
                new com.calplus.ihrgstats.telegrambot.commands.CommandRankPlayers();
            
            com.calplus.ihrgstats.telegrambot.commands.CommandRankPlayers.RankResponse response = 
                rankCommand.handleCommand();
            
            // Send text message
            sendLongMessageToCommandsChannel(response.message, message);
            
            // Send image if available
            if (response.imagePath != null) {
                sendImageToCommandsChannel(response.imagePath, message);
            }

        } catch (Exception e) {
            String errorMsg = "Error processing /rankplayers command: " + e.getMessage();
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            e.printStackTrace();
            sendMessageToCommandsChannel(formatStatusMessage("🔴", "ERROR", errorMsg), message);
        }
    }

    /**
     * Handles /rankhalls command
     */
    private void handleRankHallsCommand(JsonObject message) {
        try {
            com.calplus.ihrgstats.telegrambot.commands.CommandRankHalls rankCommand = 
                new com.calplus.ihrgstats.telegrambot.commands.CommandRankHalls();
            
            com.calplus.ihrgstats.telegrambot.commands.CommandRankHalls.RankResponse response = 
                rankCommand.handleCommand();
            
            // Send text message
            sendLongMessageToCommandsChannel(response.message, message);
            
            // Send image if available
            if (response.imagePath != null) {
                sendImageToCommandsChannel(response.imagePath, message);
            }

        } catch (Exception e) {
            String errorMsg = "Error processing /rankhalls command: " + e.getMessage();
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            e.printStackTrace();
            sendMessageToCommandsChannel(formatStatusMessage("🔴", "ERROR", errorMsg), message);
        }
    }

    /**
     * Sends a long message to the commands channel, splitting if necessary
     * Telegram has a 4096 character limit per message
     */
    private void sendLongMessageToCommandsChannel(String message, JsonObject originalMessage) {
        final int MAX_LENGTH = 4000; // Leave some buffer
        
        if (message.length() <= MAX_LENGTH) {
            sendMessageToCommandsChannel(message, originalMessage);
            return;
        }
        
        // For very large tables, split into smaller chunks by lines within code blocks
        int codeBlockStart = message.indexOf("```");
        int codeBlockEnd = message.lastIndexOf("```");
        
        if (codeBlockStart >= 0 && codeBlockEnd > codeBlockStart) {
            String prefix = message.substring(0, codeBlockStart);
            String codeContent = message.substring(codeBlockStart + 3, codeBlockEnd);
            String suffix = message.substring(codeBlockEnd + 3);
            
            // Send prefix (header text)
            if (!prefix.trim().isEmpty()) {
                sendMessageToCommandsChannel(prefix.trim(), originalMessage);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            // Split code content by lines
            String[] lines = codeContent.split("\n");
            StringBuilder currentChunk = new StringBuilder("```\n");
            
            for (String line : lines) {
                // Check if adding this line would exceed limit
                if (currentChunk.length() + line.length() + 5 > MAX_LENGTH) { // +5 for \n and closing ```
                    // Send current chunk
                    currentChunk.append("```");
                    sendMessageToCommandsChannel(currentChunk.toString(), originalMessage);
                    currentChunk = new StringBuilder("```\n");
                    
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                
                currentChunk.append(line).append("\n");
            }
            
            // Send remaining content
            if (currentChunk.length() > 4) { // More than just ```\n
                currentChunk.append("```");
                sendMessageToCommandsChannel(currentChunk.toString(), originalMessage);
            }
            
            // Send suffix if any
            if (!suffix.trim().isEmpty()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                sendMessageToCommandsChannel(suffix.trim(), originalMessage);
            }
        } else {
            // Fallback: just split at arbitrary boundaries
            for (int i = 0; i < message.length(); i += MAX_LENGTH) {
                int end = Math.min(i + MAX_LENGTH, message.length());
                sendMessageToCommandsChannel(message.substring(i, end), originalMessage);
                
                if (end < message.length()) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    /**
     * Sends an image to the commands channel (both compressed and uncompressed)
     */
    private void sendImageToCommandsChannel(java.nio.file.Path imagePath, JsonObject originalMessage) {
        // Send as photo (compressed)
        sendImageAsPhoto(imagePath, originalMessage);
        
        // Wait a bit before sending uncompressed version
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Send as document (uncompressed PNG)
        sendImageAsDocument(imagePath, originalMessage);
    }
    
    /**
     * Sends an image as a photo (compressed) to the commands channel
     */
    private void sendImageAsPhoto(java.nio.file.Path imagePath, JsonObject originalMessage) {
        try {
            String url = "https://api.telegram.org/bot" + botToken + "/sendPhoto";
            
            // Read image bytes
            byte[] imageBytes = java.nio.file.Files.readAllBytes(imagePath);
            String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
            
            // Determine where to send
            String targetChatId;
            String targetThreadId = null;
            
            if (allowAllChannelsProcessing && originalMessage != null && originalMessage.has("chat")) {
                // Send to the same channel where the command was received
                JsonObject chat = originalMessage.getAsJsonObject("chat");
                targetChatId = chat.get("id").getAsString();
                if (originalMessage.has("message_thread_id")) {
                    targetThreadId = originalMessage.get("message_thread_id").getAsString();
                }
            } else {
                // Send to the configured commands channel
                String[] chatAndThread = getCommandsChatIdAndThread();
                if (chatAndThread == null || chatAndThread[0] == null || chatAndThread[0].isEmpty()) {
                    System.err.println("Cannot send image: commands chat ID is not configured");
                    return;
                }
                targetChatId = chatAndThread[0];
                targetThreadId = chatAndThread[1];
            }
            
            // Build multipart request body
            java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
            java.io.PrintWriter writer = new java.io.PrintWriter(new java.io.OutputStreamWriter(outputStream, java.nio.charset.StandardCharsets.UTF_8), true);
            
            // Add chat_id
            writer.append("--" + boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"chat_id\"").append("\r\n");
            writer.append("\r\n");
            writer.append(targetChatId).append("\r\n");
            
            // Add message_thread_id if specified
            if (targetThreadId != null && !targetThreadId.isEmpty()) {
                writer.append("--" + boundary).append("\r\n");
                writer.append("Content-Disposition: form-data; name=\"message_thread_id\"").append("\r\n");
                writer.append("\r\n");
                writer.append(targetThreadId).append("\r\n");
            }
            
            // Add photo
            writer.append("--" + boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"photo\"; filename=\"" + imagePath.getFileName().toString() + "\"").append("\r\n");
            writer.append("Content-Type: image/png").append("\r\n");
            writer.append("\r\n");
            writer.flush();
            
            outputStream.write(imageBytes);
            outputStream.flush();
            
            writer.append("\r\n");
            writer.append("--" + boundary + "--").append("\r\n");
            writer.flush();
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(outputStream.toByteArray()))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                System.err.println("Failed to send image as photo (HTTP " + response.statusCode() + "): " + response.body());
            }
        } catch (Exception e) {
            System.err.println("Error sending image as photo: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Sends an image as a document (uncompressed PNG) to the commands channel
     */
    private void sendImageAsDocument(java.nio.file.Path imagePath, JsonObject originalMessage) {
        try {
            String url = "https://api.telegram.org/bot" + botToken + "/sendDocument";
            
            // Read image bytes
            byte[] imageBytes = java.nio.file.Files.readAllBytes(imagePath);
            String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
            
            // Determine where to send
            String targetChatId;
            String targetThreadId = null;
            
            if (allowAllChannelsProcessing && originalMessage != null && originalMessage.has("chat")) {
                // Send to the same channel where the command was received
                JsonObject chat = originalMessage.getAsJsonObject("chat");
                targetChatId = chat.get("id").getAsString();
                if (originalMessage.has("message_thread_id")) {
                    targetThreadId = originalMessage.get("message_thread_id").getAsString();
                }
            } else {
                // Send to the configured commands channel
                String[] chatAndThread = getCommandsChatIdAndThread();
                if (chatAndThread == null || chatAndThread[0] == null || chatAndThread[0].isEmpty()) {
                    System.err.println("Cannot send document: commands chat ID is not configured");
                    return;
                }
                targetChatId = chatAndThread[0];
                targetThreadId = chatAndThread[1];
            }
            
            // Build multipart request body
            java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
            java.io.PrintWriter writer = new java.io.PrintWriter(new java.io.OutputStreamWriter(outputStream, java.nio.charset.StandardCharsets.UTF_8), true);
            
            // Add chat_id
            writer.append("--" + boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"chat_id\"").append("\r\n");
            writer.append("\r\n");
            writer.append(targetChatId).append("\r\n");
            
            // Add message_thread_id if specified
            if (targetThreadId != null && !targetThreadId.isEmpty()) {
                writer.append("--" + boundary).append("\r\n");
                writer.append("Content-Disposition: form-data; name=\"message_thread_id\"").append("\r\n");
                writer.append("\r\n");
                writer.append(targetThreadId).append("\r\n");
            }
            
            // Add document
            writer.append("--" + boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"document\"; filename=\"" + imagePath.getFileName().toString() + "\"").append("\r\n");
            writer.append("Content-Type: image/png").append("\r\n");
            writer.append("\r\n");
            writer.flush();
            
            outputStream.write(imageBytes);
            outputStream.flush();
            
            writer.append("\r\n");
            writer.append("--" + boundary + "--").append("\r\n");
            writer.flush();
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(outputStream.toByteArray()))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                System.err.println("Failed to send image as document (HTTP " + response.statusCode() + "): " + response.body());
            }
        } catch (Exception e) {
            System.err.println("Error sending image as document: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Sends a message with settings buttons
     */
    private void sendMessageWithSettingsButtons(String message, 
            com.calplus.ihrgstats.telegrambot.commands.CommandSettings.ButtonConfig buttonConfig, 
            JsonObject originalMessage) {
        try {
            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
            
            JsonObject payload = new JsonObject();
            
            // Determine where to send
            if (allowAllChannelsProcessing && originalMessage != null && originalMessage.has("chat")) {
                JsonObject chat = originalMessage.getAsJsonObject("chat");
                payload.addProperty("chat_id", chat.get("id").getAsString());
                
                if (originalMessage.has("message_thread_id")) {
                    payload.addProperty("message_thread_id", originalMessage.get("message_thread_id").getAsString());
                }
            } else {
                String[] chatAndThread = getCommandsChatIdAndThread();
                if (chatAndThread == null || chatAndThread[0] == null) return;
                
                payload.addProperty("chat_id", chatAndThread[0]);
                if (chatAndThread[1] != null && !chatAndThread[1].isEmpty()) {
                    payload.addProperty("message_thread_id", chatAndThread[1]);
                }
            }
            
            payload.addProperty("text", message);
            
            // Create inline keyboard with support for multiple columns
            JsonObject replyMarkup = new JsonObject();
            JsonArray keyboard = new JsonArray();
            
            int columnsPerRow = buttonConfig.columnsPerRow;
            JsonArray currentRow = new JsonArray();
            
            for (int i = 0; i < buttonConfig.labels.length; i++) {
                JsonObject button = new JsonObject();
                button.addProperty("text", buttonConfig.labels[i]);
                button.addProperty("callback_data", buttonConfig.callbacks[i]);
                currentRow.add(button);
                
                // Add row when we reach columnsPerRow or it's the last button
                if (currentRow.size() >= columnsPerRow || i == buttonConfig.labels.length - 1) {
                    keyboard.add(currentRow);
                    currentRow = new JsonArray();
                }
            }
            
            replyMarkup.add("inline_keyboard", keyboard);
            payload.add("reply_markup", replyMarkup);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build();
            
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
        } catch (Exception e) {
            discordLog.logError("Error sending settings buttons: " + e.getMessage());
            telegramLog.logError("Error sending settings buttons: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Sends a message with export database buttons
     */
    private void sendMessageWithExportButtons(String message, 
            com.calplus.ihrgstats.telegrambot.commands.CommandExportDatabase.ButtonConfig buttonConfig, 
            JsonObject originalMessage, String userId) {
        try {
            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
            
            JsonObject payload = new JsonObject();
            
            // Determine where to send
            if (allowAllChannelsProcessing && originalMessage != null && originalMessage.has("chat")) {
                JsonObject chat = originalMessage.getAsJsonObject("chat");
                payload.addProperty("chat_id", chat.get("id").getAsString());
                
                if (originalMessage.has("message_thread_id")) {
                    payload.addProperty("message_thread_id", originalMessage.get("message_thread_id").getAsString());
                }
            } else {
                String[] chatAndThread = getCommandsChatIdAndThread();
                if (chatAndThread == null || chatAndThread[0] == null) return;
                
                payload.addProperty("chat_id", chatAndThread[0]);
                if (chatAndThread[1] != null && !chatAndThread[1].isEmpty()) {
                    payload.addProperty("message_thread_id", chatAndThread[1]);
                }
            }
            
            payload.addProperty("text", message);
            
            // Create inline keyboard
            JsonObject replyMarkup = new JsonObject();
            JsonArray keyboard = new JsonArray();
            
            JsonArray row = new JsonArray();
            for (int i = 0; i < buttonConfig.labels.length; i++) {
                JsonObject button = new JsonObject();
                button.addProperty("text", buttonConfig.labels[i]);
                button.addProperty("callback_data", buttonConfig.callbacks[i] + "_" + userId);
                row.add(button);
            }
            keyboard.add(row);
            
            replyMarkup.add("inline_keyboard", keyboard);
            payload.add("reply_markup", replyMarkup);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build();
            
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
        } catch (Exception e) {
            discordLog.logError("Error sending export buttons: " + e.getMessage());
            telegramLog.logError("Error sending export buttons: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Sends a file to a user's DM
     */
    private void sendFileToUser(String userId, String filePath) {
        try {
            String url = "https://api.telegram.org/bot" + botToken + "/sendDocument";
            
            // Read file content
            byte[] fileBytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath));
            String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
            
            // Build multipart request body
            java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
            java.io.PrintWriter writer = new java.io.PrintWriter(new java.io.OutputStreamWriter(outputStream, java.nio.charset.StandardCharsets.UTF_8), true);
            
            // Add chat_id (user's DM)
            writer.append("--" + boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"chat_id\"").append("\r\n");
            writer.append("\r\n");
            writer.append(userId).append("\r\n");
            
            // Add file
            writer.append("--" + boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"document\"; filename=\"" + 
                java.nio.file.Paths.get(filePath).getFileName().toString() + "\"").append("\r\n");
            writer.append("Content-Type: application/octet-stream").append("\r\n");
            writer.append("\r\n");
            writer.flush();
            
            outputStream.write(fileBytes);
            outputStream.flush();
            
            writer.append("\r\n");
            writer.append("--" + boundary + "--").append("\r\n");
            writer.flush();
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(outputStream.toByteArray()))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                System.err.println("Failed to send file to user (HTTP " + response.statusCode() + "): " + response.body());
                discordLog.logError("Failed to send database file to user DM: HTTP " + response.statusCode());
                telegramLog.logError("Failed to send database file to user DM: HTTP " + response.statusCode());
            } else {
                discordLog.logSuccess("Database file sent to user DM successfully");
                telegramLog.logSuccess("Database file sent to user DM successfully");
            }
        } catch (Exception e) {
            System.err.println("Error sending file to user: " + e.getMessage());
            discordLog.logError("Error sending database file to user: " + e.getMessage());
            telegramLog.logError("Error sending database file to user: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Removes inline keyboard from a message (disables buttons after action)
     */
    private void removeInlineKeyboard(String chatId, String messageId) {
        try {
            String url = "https://api.telegram.org/bot" + botToken + "/editMessageReplyMarkup";
            
            JsonObject payload = new JsonObject();
            payload.addProperty("chat_id", chatId);
            payload.addProperty("message_id", messageId);
            // Empty reply_markup removes the keyboard
            payload.add("reply_markup", new JsonObject());
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build();
            
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
        } catch (Exception e) {
            // Non-critical error, just log
            System.err.println("Error removing inline keyboard: " + e.getMessage());
        }
    }

    /**
     * Main method for testing
     */
    public static void main(String[] args) {
        TelegramListener listener = new TelegramListener();
        listener.start();
        
        // Keep running
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            listener.stop();
        }
    }
}
