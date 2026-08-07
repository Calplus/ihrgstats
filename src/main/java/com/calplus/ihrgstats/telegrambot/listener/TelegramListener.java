package com.calplus.ihrgstats.telegrambot.listener;

import com.calplus.ihrgstats.discordbot.logs.DiscordLog;
import com.calplus.ihrgstats.telegrambot.logs.TelegramLog;
import com.calplus.ihrgstats.telegrambot.commands.CommandSettings;
import com.calplus.ihrgstats.telegrambot.utils.CappedListProcessor;
import com.calplus.ihrgstats.telegrambot.utils.RoundCsvProcessor;
import com.calplus.ihrgstats.utils.EnvironmentManager;
import com.calplus.ihrgstats.utils.HttpClientFactory;
import com.calplus.ihrgstats.utils.PropertyResolver;
import com.calplus.ihrgstats.utils.TelegramFileDownloader;
import com.calplus.ihrgstats.utils.TimezoneHelper;
import com.calplus.ihrgstats.utils.YearContext;
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
    private final com.calplus.ihrgstats.utils.LogHelper logHelper;
    private final HttpClient httpClient;
    private final Gson gson;
    
    private String botToken;
    private String publicChatId;
    private String publicChatIdFileupload;
    private String devChatId;  // The dev chat ID for status messages
    private String devChatIdLog;  // The dev chat ID for log messages
    private String publicChatIdStatus;
    private String publicChatIdCommands;

    private String webhookUrl;
    private int webhookTimeoutMs;
    
    private boolean useWebhook;
    // volatile: read by the polling thread's while-loop, written by stop()
    // from another thread - without it the JMM permits the polling loop to
    // never observe the false write.
    private volatile boolean isRunning;
    private long lastUpdateId = 0;

    // Millis timestamp of the last completed getUpdates round-trip. 0 means
    // long polling is not active (webhook mode). Written by the polling
    // thread, read by the heartbeat so it can report a stuck/dead polling
    // loop instead of masking it with a green "online" message.
    private volatile long lastPollCompletedAt = 0;
    private static final long STALE_POLL_WARNING_MS = 2 * 60 * 1000; // 2 minutes

    private ScheduledExecutorService statusHeartbeatExecutor;
    private static final long STATUS_HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes

    // Matches "cappedlist.csv" or "{year}_cappedlist.csv" - group(1) is the
    // year digits, or null when the prefix is absent.
    private static final Pattern CAPPEDLIST_FILENAME = Pattern.compile("^(?:(\\d{4})_)?cappedlist\\.csv$", Pattern.CASE_INSENSITIVE);

    /** Result of {@link #parseCappedlistFilename(String)} - mirrors RoundCsvProcessor.ParsedFilename. */
    static class ParsedCappedlistFilename {
        final boolean matched;
        final Integer year; // null when the filename has no {year}_ prefix

        ParsedCappedlistFilename(boolean matched, Integer year) {
            this.matched = matched;
            this.year = year;
        }
    }

    /**
     * Parses "cappedlist.csv" or "{year}_cappedlist.csv". The year prefix,
     * when present, must be honored exactly like round files - previously it
     * was accepted by the filename pattern but silently ignored, always
     * processing under the currently-configured year regardless of what the
     * filename actually said.
     */
    static ParsedCappedlistFilename parseCappedlistFilename(String fileName) {
        Matcher m = CAPPEDLIST_FILENAME.matcher(fileName);
        if (!m.matches()) {
            return new ParsedCappedlistFilename(false, null);
        }
        String yearGroup = m.group(1);
        return new ParsedCappedlistFilename(true, yearGroup != null ? Integer.parseInt(yearGroup) : null);
    }

    /**
     * Destination phrase for wrong-channel error messages. Falls back to a
     * generic label when the thread ID is not configured, instead of
     * rendering "Thread ID  (...)" with a blank in the middle (reachable in
     * half-configured setups where only one of the two thread IDs is set).
     */
    private static String describeThread(String threadId, String channelLabel) {
        if (threadId == null || threadId.isEmpty()) {
            return "the configured " + channelLabel;
        }
        return "Thread ID " + threadId + " (" + channelLabel + ")";
    }


    // Pending confirmations, keyed per-user (not a single global slot) - so
    // two different users' pending dialogs can't clobber each other, and only
    // the user who was actually asked can answer their own dialog.
    private final Map<String, ConfirmationRequest> pendingConfirmations = new ConcurrentHashMap<>();
    private final Map<String, MultiChoiceConfirmationRequest> pendingMultiChoiceConfirmations = new ConcurrentHashMap<>();

    // User name cache: maps userId -> userName for logging purposes
    private static final Map<String, String> userNameCache = new ConcurrentHashMap<>();
    
    private static class ConfirmationRequest {
        final CompletableFuture<Boolean> future;

        ConfirmationRequest(CompletableFuture<Boolean> future) {
            this.future = future;
        }
    }

    private static class MultiChoiceConfirmationRequest {
        String[] options;
        CompletableFuture<Integer> future;
        JsonObject originalMessage;  // Store original message for channel routing
        // Unique per-dialog token embedded in every button's callback_data
        // (see sendMessageWithButtons) - pendingMultiChoiceConfirmations only
        // ever holds ONE request per user, so a stale button left over from
        // an earlier, already-resolved dialog for that SAME user would
        // otherwise be indistinguishable from a button belonging to whatever
        // NEW dialog now occupies that slot, silently resolving the new
        // dialog with an index meant for the old one's options.
        final String nonce = java.util.UUID.randomUUID().toString();

        MultiChoiceConfirmationRequest(String[] options, CompletableFuture<Integer> future, JsonObject originalMessage) {
            this.options = options;
            this.future = future;
            this.originalMessage = originalMessage;
        }
    }

    public TelegramListener() {
        // Load environment variables
        EnvironmentManager.ensureSystemPropertiesLoaded();
        
        this.discordLog = new DiscordLog();
        this.telegramLog = new TelegramLog();
        this.logHelper = new com.calplus.ihrgstats.utils.LogHelper(discordLog, telegramLog);
        this.httpClient = HttpClientFactory.newClient();
        this.gson = new Gson();
        this.isRunning = false;
        
        loadConfig();
    }

    /**
     * Formats user information for logging purposes.
     * Returns "@username (ID: <id>)" if username is available, otherwise "User (ID: <id>)"
     * This method can be called from Command classes to get formatted user info for logs.
     * 
     * @param userId The user's Telegram ID
     * @return Formatted user info string
     */
    public static String formatUserInfo(String userId) {
        String userName = userNameCache.get(userId);
        if (userName != null && !userName.isEmpty()) {
            return String.format("@%s (ID: %s)", userName, userId);
        }
        return String.format("User (ID: %s)", userId);
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

            this.webhookUrl = PropertyResolver.getProperty("internet.webhook.url", "");
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
     * Reads settings.allowNonAdminUploads live on every call instead of a
     * field cached at startup - this setting is toggleable via /settings at
     * runtime, and the old cached-field approach meant a toggle silently had
     * no effect until the bot was restarted, despite /help promising changes
     * take effect immediately. PropertyResolver only re-resolves ${} placeholders
     * per call (the underlying file read is already cached), so this is cheap.
     */
    boolean isAllowNonAdminUploads() {
        return Boolean.parseBoolean(PropertyResolver.getProperty("settings.allowNonAdminUploads", "true"));
    }

    /**
     * Reads settings.allowAllChannelsProcessing live on every call - see
     * {@link #isAllowNonAdminUploads()} for why. publicChatId is fixed at
     * startup (not itself a runtime-toggleable setting), so folding its
     * "treat as all-channels" force-true behavior into this getter (instead
     * of mutating a field once in start()) keeps this the single source of
     * truth without needing a separate reload path.
     */
    boolean isAllowAllChannelsProcessing() {
        return publicChatId.isEmpty()
                || Boolean.parseBoolean(PropertyResolver.getProperty("settings.allowAllChannelsProcessing", "false"));
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
     * Starts the listener
     */
    public void start() {
        if (isRunning) {
            System.out.println("Telegram listener is already running");
            return;
        }

        logHelper.logInfo("Starting Telegram file listener...");

        if (botToken.isEmpty()) {
            String errorMsg = "Telegram bot token not configured. Cannot start listener.";
            logHelper.logError(errorMsg);
            return;
        }

        // If publicChatId is empty, the bot will accept messages from any channel
        // (isAllowAllChannelsProcessing() folds this in on every call - no field to set here).
        if (publicChatId.isEmpty()) {
            logHelper.logInfo("Telegram publicChatId not configured. Bot will process messages from any channel it has access to.");
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
        logHelper.logInfo("Telegram listener stopped");
    }

    /**
     * Starts webhook mode
     */
    private void startWebhookMode() {
        // Test webhook accessibility
        boolean webhookAccessible = testWebhookAccessibility();
        
        if (webhookAccessible) {
            logHelper.logInfo("Webhook is accessible. Starting webhook mode...");
            
            // Set webhook URL in Telegram
            boolean webhookSet = setTelegramWebhook();
            if (webhookSet) {
                logHelper.logSuccess("Telegram webhook mode activated at: " + webhookUrl);
                
                // Note: Actual webhook server implementation would go here
                // For now, fall back to long polling
                System.out.println("Note: Webhook server implementation not included. Falling back to long polling.");
                startLongPollingMode();
            } else {
                logHelper.logWarning("Failed to set webhook. Falling back to long polling.");
                startLongPollingMode();
            }
        } else {
            logHelper.logWarning("Webhook not accessible. Falling back to long polling.");
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
                .timeout(HttpClientFactory.REQUEST_TIMEOUT)
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
        logHelper.logInfo("Starting long polling mode...");

        // Delete webhook if exists
        deleteWebhook();

        // Get current update_id to skip old messages
        initializeUpdateId();

        // Start polling in background thread
        Thread pollingThread = new Thread(() -> {
            logHelper.logSuccess("Telegram long polling started successfully");

            // Baseline for the heartbeat's staleness check - covers the case
            // where the very first poll never completes.
            lastPollCompletedAt = System.currentTimeMillis();

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
                .timeout(HttpClientFactory.REQUEST_TIMEOUT)
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
                .timeout(HttpClientFactory.REQUEST_TIMEOUT)
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
                        logHelper.logInfo("Skipping all pending messages up to update_id " + lastUpdateId + ". Only processing new files from now on.");
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
        
        // Request timeout must exceed the 30s long-poll hold requested in the
        // URL - without it, a silently dropped connection blocks this thread
        // forever and the bot goes permanently deaf (while the heartbeat,
        // on its own executor, keeps reporting it online).
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(getUpdatesUrl))
            .timeout(HttpClientFactory.LONG_POLL_TIMEOUT)
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // A completed round-trip (any status code) proves the polling loop is
        // alive - a hung or repeatedly-failing loop leaves this stale and the
        // heartbeat reports it.
        lastPollCompletedAt = System.currentTimeMillis();

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
            // (isAllowAllChannelsProcessing() already folds the empty-publicChatId case in).
            if (isAllowAllChannelsProcessing()) {
                // Accept message from any channel - no filtering needed
                // When publicChatId is empty, the bot processes messages from any channel it has access to
            } else {
                // Check chat match
                boolean isPublicChat = chatId.equals(publicChatId);
                boolean hasValidThread = false;

                // If THIS SPECIFIC sender has a pending confirmation, accept ANY
                // message from them in the correct chat (keyed per-user now, not
                // a single global slot, so one user's pending dialog no longer
                // relaxes validation for every other user's messages too).
                String senderUserId = message.has("from") ? message.getAsJsonObject("from").get("id").getAsString() : null;
                boolean waitingForConfirmation = senderUserId != null && pendingConfirmations.containsKey(senderUserId);
                
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
                                errorMsg = "❌ **Wrong Channel**\n\nPlease upload files to " + describeThread(publicChatIdFileupload, "file upload channel");
                            } else if (message.has("text") && message.get("text").getAsString().trim().startsWith("/")) {
                                errorMsg = "❌ **Wrong Channel**\n\nPlease send commands to " + describeThread(publicChatIdCommands, "commands channel");
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
                String userName = from.has("username") ? from.get("username").getAsString() : null;
                
                // Store userName in cache for later use by commands
                if (userName != null && !userName.isEmpty()) {
                    userNameCache.put(userId, userName);
                }
                
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
            String userName = from.has("username") ? from.get("username").getAsString() : null;
            
            // Store userName in cache for later use by commands
            if (userName != null && !userName.isEmpty()) {
                userNameCache.put(userId, userName);
            }
            
            String userInfo = userName != null ? String.format("@%s (ID: %s)", userName, userId) : String.format("User (ID: %s)", userId);
            
            logHelper.logInfo(String.format("Button clicked by %s: %s", userInfo, data));
            
            // Answer the callback query to remove loading state
            answerCallbackQuery(callbackId);
            
            // Handle settings toggle callbacks
            if (data.startsWith("setting_toggle_")) {
                deliverSettingsCallbackResponse(callbackQuery,
                    new com.calplus.ihrgstats.telegrambot.commands.CommandSettings().handleToggle(data, userId), null);
                return;
            }
            
            // Handle home hall selection request callback
            if (data.equals("setting_homeHall_select")) {
                com.calplus.ihrgstats.telegrambot.commands.CommandSettings.SettingsResponse hallSelectionResponse =
                    new com.calplus.ihrgstats.telegrambot.commands.CommandSettings().handleHomeHallSelection(userId);
                deliverSettingsCallbackResponse(callbackQuery, hallSelectionResponse.message, hallSelectionResponse.buttons);
                return;
            }
            
            // Handle home hall selection callbacks
            if (data.startsWith("setting_homeHall_")) {
                deliverSettingsCallbackResponse(callbackQuery,
                    new com.calplus.ihrgstats.telegrambot.commands.CommandSettings().handleHomeHallCallback(data, userId), null);
                return;
            }
            
            // Handle timezone selection request callback
            if (data.equals("setting_timezone_select")) {
                com.calplus.ihrgstats.telegrambot.commands.CommandSettings.SettingsResponse timezoneSelectionResponse =
                    new com.calplus.ihrgstats.telegrambot.commands.CommandSettings().handleTimezoneSelection(userId);
                deliverSettingsCallbackResponse(callbackQuery, timezoneSelectionResponse.message, timezoneSelectionResponse.buttons);
                return;
            }
            
            // Handle current year selection request callback
            if (data.equals("setting_currentYear_select")) {
                // Manual-input prompt - deliberately delivered without buttons.
                deliverSettingsCallbackResponse(callbackQuery,
                    new com.calplus.ihrgstats.telegrambot.commands.CommandSettings().handleCurrentYearSelection(userId).message, null);
                return;
            }
            
            // Handle timezone selection callbacks
            if (data.startsWith("setting_timezone_")) {
                deliverSettingsCallbackResponse(callbackQuery,
                    new com.calplus.ihrgstats.telegrambot.commands.CommandSettings().handleTimezoneCallback(data, userId), null);
                return;
            }
            
            // Handle settings cancel callback
            if (data.equals("settings_cancel")) {
                deliverSettingsCallbackResponse(callbackQuery,
                    new com.calplus.ihrgstats.telegrambot.commands.CommandSettings().handleCancel(userId), null);
                return;
            }
            
            // Handle export database format-choice callbacks
            if (data.startsWith("export_db_xlsx_") || data.startsWith("export_db_confirm_") || data.startsWith("export_db_cancel_")) {
                String[] parts = data.split("_");
                if (parts.length >= 4) {
                    String requestUserId = parts[parts.length - 1];

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

                        // Generating the export (especially the XLSX, which
                        // reads the whole database) can take a while - run it
                        // on its own thread so it doesn't stall the polling
                        // thread (and therefore every other incoming update)
                        // for its duration (A27).
                        Thread exportThread = new Thread(() -> {
                            try {
                                com.calplus.ihrgstats.telegrambot.commands.CommandExportDatabase exportCommand =
                                    new com.calplus.ihrgstats.telegrambot.commands.CommandExportDatabase();

                                if (data.startsWith("export_db_xlsx_") || data.startsWith("export_db_confirm_")) {
                                    // Execute the chosen export format
                                    com.calplus.ihrgstats.telegrambot.commands.CommandExportDatabase.ExportResponse response =
                                        data.startsWith("export_db_xlsx_") ? exportCommand.executeXlsxExport(userId) : exportCommand.executeDbExport(userId);

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
                            } catch (Exception e) {
                                // Without this, an exception here (e.g. from the
                                // export itself, or file sending) would escape as
                                // an uncaught exception on a bare thread - unlike
                                // before this ran on its own thread, when the
                                // enclosing handleCallbackQuery's own catch below
                                // would have logged it to Discord/Telegram.
                                String errorMsg = "Error processing export database callback: " + e.getMessage();
                                logHelper.logError(errorMsg);
                                e.printStackTrace();
                                sendMessageToChat(chatId, formatStatusMessage("🔴", "ERROR", errorMsg), threadId);
                            }
                        });
                        exportThread.setDaemon(true);
                        exportThread.start();
                    }
                }
                return;
            }
            
            // Handle compare halls callbacks
            if (data.startsWith("comparehalls_")) {
                handleCompareHallsCallback(callbackQuery, data, userId);
                return;
            }
            
            // Handle compare players callbacks
            if (data.startsWith("compareplayers_")) {
                handleComparePlayersCallback(callbackQuery, data, userId);
                return;
            }
            
            // Handle rank players callbacks
            if (data.startsWith("rankplayers_")) {
                handleRankPlayersCallback(callbackQuery, data, userId);
                return;
            }
            
            // Handle rank halls callbacks
            if (data.startsWith("rankhalls_")) {
                handleRankHallsCallback(callbackQuery, data, userId);
                return;
            }
            
            // Handle help callbacks
            if (data.startsWith("help_")) {
                handleHelpCallback(callbackQuery, data, userId);
                return;
            }
            
            // Handle info player callbacks
            if (data.startsWith("infoplayer_")) {
                handleInfoPlayerCallback(callbackQuery, data, userId);
                return;
            }
            
            // Handle info hall callbacks
            if (data.startsWith("infohall_")) {
                handleInfoHallCallback(callbackQuery, data, userId);
                return;
            }
            
            // Handle info match callbacks
            if (data.startsWith("infomatch_")) {
                handleInfoMatchCallback(callbackQuery, data, userId);
                return;
            }
            
            // Handle info match hall callbacks
            if (data.startsWith("infomatchhall_")) {
                handleInfoMatchHallCallback(callbackQuery, data, userId);
                return;
            }
            
            // Handle match types callbacks
            if (data.startsWith("matchtypes_")) {
                handleMatchTypesCallback(callbackQuery, data, userId);
                return;
            }

            // Handle predict callbacks
            if (data.startsWith("predict_")) {
                handlePredictCallback(callbackQuery, data, userId);
                return;
            }

            // Handle lineup callbacks
            if (data.startsWith("lineup_")) {
                handleLineupCallback(callbackQuery, data, userId);
                return;
            }

            // Handle admins callbacks
            if (data.startsWith("admins_")) {
                handleAdminsCallback(callbackQuery, data, userId);
                return;
            }
            
            // Handle multi-choice confirmation - keyed per-user (not a single
            // global slot), so this inherently only resolves the CLICKING
            // user's own pending dialog; another user's identical-looking
            // dialog is untouched, and a user with nothing pending clicking
            // a stale/expired button simply has no effect.
            MultiChoiceConfirmationRequest request = pendingMultiChoiceConfirmations.get(userId);
            if (request != null) {
                // Parse the callback data (format: "choice_{index}_{nonce}").
                // The nonce is checked against the CURRENTLY pending
                // request's own nonce before trusting the index - buttons
                // are never removed from an answered/expired dialog's
                // message, so a user clicking a stale button from an
                // earlier dialog (now resolved) must not have it silently
                // resolve whatever NEW dialog now occupies this same
                // per-user slot using an index meant for the old one.
                if (data.startsWith("choice_")) {
                    String rest = data.substring(7);
                    int separatorIdx = rest.indexOf('_');
                    String choiceStr = separatorIdx >= 0 ? rest.substring(0, separatorIdx) : rest;
                    String clickedNonce = separatorIdx >= 0 ? rest.substring(separatorIdx + 1) : "";
                    try {
                        int choice = Integer.parseInt(choiceStr);
                        if (!clickedNonce.equals(request.nonce)) {
                            telegramLog.logWarning(String.format("User %s clicked a stale choice button from a previous dialog", userId));
                            sendMessageToUploadChat("❌ This confirmation has expired - please use the latest prompt.", request.originalMessage);
                        } else if (choice >= 0 && choice < request.options.length) {
                            String selectedOption = request.options[choice];
                            logHelper.logInfo(String.format("User selected option %d: %s", choice, selectedOption));

                            // Send confirmation message to chat (use stored original message for routing)
                            String confirmMsg = String.format("✅ Selected: %s", selectedOption);
                            sendMessageToUploadChat(confirmMsg, request.originalMessage);

                            request.future.complete(choice);
                            pendingMultiChoiceConfirmations.remove(userId, request);
                        } else {
                            sendMessageToUploadChat("❌ Invalid choice index", request.originalMessage);
                        }
                    } catch (NumberFormatException e) {
                        sendMessageToUploadChat("❌ Invalid callback data format", request.originalMessage);
                    }
                }
            } else if (data.startsWith("choice_")) {
                // No pending dialog for this clicker - either it was never
                // theirs to answer, or it already resolved/expired. Say so
                // explicitly instead of silently no-oping (matches the
                // export_db_* callbacks' existing "not for you" pattern).
                telegramLog.logWarning(String.format("User %s clicked a choice button with no matching pending dialog", userId));
                sendMessageToUploadChat("❌ This confirmation is not for you, or it already expired.", callbackQuery.getAsJsonObject("message"));
            }

        } catch (Exception e) {
            logHelper.logError("Error handling callback query: " + e.getMessage());
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
                .timeout(HttpClientFactory.REQUEST_TIMEOUT)
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

        // A pending yes/no confirmation outranks wizard text input, but only
        // for an exact yes/y/no/n answer - a user simultaneously mid-wizard
        // (e.g. typing a year into /settings) AND awaiting an upload
        // confirmation would otherwise have their "yes" swallowed by the
        // wizard as invalid input while the confirmation timed out at 60s.
        // Any other text keeps the normal wizard-first priority.
        if (tryResolvePendingConfirmation(userId, text)) {
            return;
        }

        // Check if user is awaiting manual home hall input
        CommandSettings settingsCommand = new CommandSettings();
        String settingsResponse = settingsCommand.handleTextInput(userId, text);
        if (settingsResponse != null) {
            sendMessageToCommandsChannel(settingsResponse, message);
            return;
        }

        // Check if user is mid-wizard on /admins (add-admin text input)
        com.calplus.ihrgstats.telegrambot.commands.CommandAdmins adminsCommand =
            new com.calplus.ihrgstats.telegrambot.commands.CommandAdmins();
        if (adminsCommand.isAwaitingTextInput(userId)) {
            com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse adminsResponse =
                adminsCommand.handleTextInput(userId, text);
            if (adminsResponse != null) {
                sendMessageToCommandsChannel(adminsResponse.message, message);
                return;
            }
        }

        // Check if user is mid-wizard on /matchtypes (create/edit text input)
        com.calplus.ihrgstats.telegrambot.commands.CommandMatchTypes matchTypesCommand =
            new com.calplus.ihrgstats.telegrambot.commands.CommandMatchTypes();
        if (matchTypesCommand.isAwaitingTextInput(userId)) {
            com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse matchTypesResponse =
                matchTypesCommand.handleTextInput(userId, text);
            if (matchTypesResponse != null) {
                sendMessageToCommandsChannel(matchTypesResponse.message, message);
                return;
            }
        }

        // Exact yes/no answers with a pending confirmation were already
        // consumed by tryResolvePendingConfirmation at the top of this
        // method - anything reaching here is either non-answer text or has
        // no pending dialog. Keep the diagnostics that used to live here.
        if (pendingConfirmations.get(userId) != null) {
            System.out.println("Text does not match yes/no: '" + text.toLowerCase() + "'");
        } else {
            System.out.println("No pending confirmation found for text: '" + text + "'");
        }
    }

    /**
     * Resolves a pending yes/no confirmation for this user if the text is an
     * exact yes/y/no/n answer. Pending confirmations are keyed per-user (not
     * a single global slot) so two different users' dialogs can't clobber
     * each other, and only the person who was actually asked can answer
     * their own dialog. Returns true when a pending confirmation consumed
     * the text.
     */
    private boolean tryResolvePendingConfirmation(String userId, String text) {
        String lowerText = text.toLowerCase();
        boolean yes = lowerText.equals("yes") || lowerText.equals("y");
        boolean no = lowerText.equals("no") || lowerText.equals("n");
        if (!yes && !no) {
            return false;
        }
        ConfirmationRequest request = pendingConfirmations.get(userId);
        if (request == null) {
            return false;
        }
        System.out.println("Pending confirmation found. User text: '" + text + "' - completing future with " + yes);
        request.future.complete(yes);
        pendingConfirmations.remove(userId, request);
        return true;
    }
    
    /**
     * Extracts user information from a message for logging
     * Returns formatted string like "@username (ID: 123456)" or "User (ID: 123456)" if username not available
     */
    private String getUserInfoFromMessage(JsonObject message) {
        try {
            if (message.has("from")) {
                JsonObject from = message.getAsJsonObject("from");
                String userId = from.has("id") ? from.get("id").getAsString() : "unknown";
                String username = from.has("username") ? from.get("username").getAsString() : null;
                
                // Store userName in cache for later use by commands
                if (username != null && !username.isEmpty()) {
                    userNameCache.put(userId, username);
                    return String.format("@%s (ID: %s)", username, userId);
                } else {
                    return String.format("User (ID: %s)", userId);
                }
            }
            return "Unknown user";
        } catch (Exception e) {
            return "Unknown user";
        }
    }

    /**
     * Handles file upload
     */
    /**
     * Runs entirely on a background thread (like /recalculate's recalcThread)
     * so the polling thread stays free to receive the "yes"/"no" confirmation
     * reply for non-admin uploads. requestUserConfirmationViaChat blocks for
     * up to 60 seconds - if that wait happened on the polling thread itself
     * (as it previously did, since this method used to run synchronously from
     * processUpdates and only spawned a thread AFTER the confirmation
     * succeeded), the polling loop could never fetch the very reply it was
     * waiting for, and the confirmation would always time out regardless of
     * how fast the user answered.
     */
    private void handleFileUpload(JsonObject message, JsonObject document) {
        Thread uploadThread = new Thread(() -> {
            try {
                String fileName = document.get("file_name").getAsString();
                String fileId = document.get("file_id").getAsString();

                // Extract user information
                JsonObject from = message.getAsJsonObject("from");
                String userId = from.get("id").getAsString();
                String username = from.has("username") ? from.get("username").getAsString() : "Unknown";
                String userInfo = from.has("username") ? String.format("@%s (ID: %s)", username, userId) : String.format("User (ID: %s)", userId);

                logHelper.logInfo(String.format("File upload detected: %s from user %s", fileName, userInfo));

                // Additional safety check: validate file upload channel when allowAllChannelsProcessing is false
                // (isAllowAllChannelsProcessing() already folds the empty-publicChatId case in).
                if (!isAllowAllChannelsProcessing()) {
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
                        String errorMsg = "❌ **Wrong Channel**\n\nPlease upload files to " + describeThread(publicChatIdFileupload, "file upload channel");
                        String msgThreadId = message.has("message_thread_id") ? message.get("message_thread_id").getAsString() : null;
                        sendMessageToChat(chatId, errorMsg, msgThreadId);

                        String logMsg = String.format("File upload rejected from wrong channel. User: %s, File: %s", username, fileName);
                        logHelper.logWarning(logMsg);
                        return;
                    }
                }

                // Check admin status (via the admins table - see F16_Admins; the
                // old single telegram.admin.userId comparison would silently
                // stop recognizing an admin added via /admins).
                boolean isAdmin = new com.calplus.ihrgstats.databasemanager.F16_Admins()
                        .isAdminSafe(com.calplus.ihrgstats.databasemanager.F16_Admins.PLATFORM_TELEGRAM, userId);

                if (!isAdmin) {
                    if (!isAllowNonAdminUploads()) {
                        String errorMsg = String.format("%s is not an admin. File upload rejected.", userInfo);
                        logHelper.logError(errorMsg);
                        return;
                    }

                    // Request confirmation for non-admin upload. This is a
                    // self-confirm dialog (the uploader confirms their own
                    // intent), mirroring /recalculate's pattern - the pending
                    // request is keyed on this same uploader's userId, so only
                    // they can ever answer it, not some other admin watching
                    // the chat. Logged to the dev channel too (in addition to
                    // the actual chat prompt below) so admins monitoring only
                    // that channel still see that a confirmation was requested.
                    String confirmMsg = String.format("⚠️ %s, you are not an admin. Do you want us to process your file '%s'? Reply with 'yes' or 'no'.",
                        userInfo, fileName);
                    logHelper.logInfo(String.format("Requesting non-admin upload confirmation from %s for file %s", userInfo, fileName));
                    boolean confirmed = requestUserConfirmationViaChat(userId, confirmMsg, message);

                    if (!confirmed) {
                        String cancelMsg = "File processing cancelled - user did not confirm.";
                        logHelper.logWarning(cancelMsg);
                        return;
                    }
                }

                processFile(fileId, fileName, userId, message);

            } catch (Exception e) {
                String errorMsg = "Error handling file upload: " + e.getMessage();
                logHelper.logError(errorMsg);
                e.printStackTrace();
            }
        });
        uploadThread.setDaemon(true);
        uploadThread.start();
    }

    /**
     * Requests user confirmation via Telegram chat
     */
    private boolean requestUserConfirmationViaChat(String userId, String message, JsonObject originalMessage) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        ConfirmationRequest myRequest = new ConfirmationRequest(future);
        // Keyed per-user (not a single global slot) so two different non-admin
        // uploaders' pending confirmations can't clobber each other. Uses
        // putIfAbsent (not put) + a compare-and-remove cleanup below, so if
        // this SAME user already has a different pending confirmation (e.g.
        // two uploads in quick succession), the new one is rejected outright
        // instead of silently overwriting the map entry - which previously
        // could let a stale/timed-out request delete a newer, still-valid
        // one, or let an answer meant for one dialog resolve the other.
        if (pendingConfirmations.putIfAbsent(userId, myRequest) != null) {
            telegramLog.logWarning("User " + userId + " already has a pending confirmation - rejecting the new one.");
            sendMessageToUploadChat("⚠️ You already have a pending confirmation - please answer that one first.", originalMessage);
            return false;
        }

        // Send the actual yes/no question to the chat the file was uploaded
        // in - previously this only logged to the internal dev log channel
        // (batched, not the uploader's chat), so the uploader never actually
        // saw the question and the confirmation just silently timed out.
        sendMessageToUploadChat(message, originalMessage);

        try {
            // Wait up to 60 seconds for response
            return future.get(60, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pendingConfirmations.remove(userId, myRequest);
            telegramLog.logWarning("Confirmation timeout - no response received within 60 seconds.");
            sendMessageToUploadChat("⏱️ Confirmation timeout - processing cancelled.", originalMessage);
            return false;
        } catch (Exception e) {
            pendingConfirmations.remove(userId, myRequest);
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
        
        if (isAllowAllChannelsProcessing() && originalMessage != null) {
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
            logHelper.logError(errorMsg);
            // Send error to chat where file was uploaded
            sendMessageToChatWithThread(responseChatId, formatStatusMessage("🔴", "ERROR", errorMsg), responseThreadId);
            return;
        }
        
        try {
            // Accepts the plain literal or a {year}_-prefixed variant (e.g.
            // "2025_cappedlist.csv") - the year prefix, when present, is
            // honored exactly like round files (parseFilename below) instead
            // of being purely cosmetic; only a prefix-less "cappedlist.csv"
            // falls back to YearContext.getCurrentYear(). Without this, an
            // admin correcting a PAST year's list (e.g. "2024_cappedlist.csv"
            // while currentYear=2025) would silently apply it to 2025 -
            // clearing and re-flagging the CURRENT year's capped players
            // from a list that was never about this year at all.
            ParsedCappedlistFilename parsedCapped = parseCappedlistFilename(fileName);
            if (parsedCapped.matched) {
                Integer year = parsedCapped.year != null ? parsedCapped.year : YearContext.getCurrentYear();
                if (year == null) {
                    String errorMsg = "Cannot process cappedlist.csv: no current year set. An admin must set settings.currentYear first.";
                    logHelper.logError(errorMsg);
                    sendMessageToChatWithThread(responseChatId, formatStatusMessage("🔴", "ERROR", errorMsg), responseThreadId);
                    return;
                }

                logHelper.logInfo("Processing cappedlist.csv...");

                CappedListProcessor processor = new CappedListProcessor();

                // Set up callback to send success message to upload chat
                processor.setUploadChatCallback((msg) -> {
                    sendMessageToChatWithThread(responseChatId, msg, responseThreadId);
                });

                boolean success = processor.processCappedList(downloadedFile.toString(), year, nowTimestamp());

                if (!success) {
                    String errorMsg = "Failed to process cappedlist.csv";
                    logHelper.logError(errorMsg);
                    // Send error to chat where file was uploaded
                    sendMessageToChatWithThread(responseChatId, formatStatusMessage("🔴", "ERROR", errorMsg), responseThreadId);
                }

            } else if (RoundCsvProcessor.parseFilename(fileName).matched) {
                RoundCsvProcessor.ParsedFilename parsed = RoundCsvProcessor.parseFilename(fileName);
                Integer year = parsed.year != null ? parsed.year : YearContext.getCurrentYear();

                if (year == null) {
                    String errorMsg = String.format(
                        "Cannot process %s: filename has no year prefix and no current year is set. " +
                        "Either upload as {year}_round_%d.csv, or have an admin set settings.currentYear first.",
                        fileName, parsed.roundOrder);
                    logHelper.logError(errorMsg);
                    sendMessageToChatWithThread(responseChatId, formatStatusMessage("🔴", "ERROR", errorMsg), responseThreadId);
                    return;
                }

                logHelper.logInfo(String.format("Processing round_%d.csv for %d...", parsed.roundOrder, year));

                RoundCsvProcessor processor = new RoundCsvProcessor();

                // Set up multi-choice callback for Telegram with buttons (covers both
                // reprocess confirmation and player-identity-resolution dialogs)
                processor.setMultiChoiceCallback((msg, options) -> {
                    CompletableFuture<Integer> future = new CompletableFuture<>();
                    MultiChoiceConfirmationRequest myRequest =
                        new MultiChoiceConfirmationRequest(options, future, originalMessage);
                    // Keyed per-user (not a single global slot) - see the
                    // matching handleCallbackQuery lookup. putIfAbsent + the
                    // matching atomic remove(key, value) below guard against a
                    // second concurrent dialog for the same user silently
                    // clobbering this one's future.
                    if (pendingMultiChoiceConfirmations.putIfAbsent(userId, myRequest) != null) {
                        sendMessageToUploadChat("⚠️ You already have a pending selection - please answer that one first.", originalMessage);
                        return -1;
                    }

                    sendMessageWithButtons(msg, options, originalMessage, myRequest.nonce);

                    try {
                        return future.get(120, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        pendingMultiChoiceConfirmations.remove(userId, myRequest);
                        sendMessageToUploadChat("⏱️ Button selection timeout - processing cancelled.", originalMessage);
                        return -1;
                    } catch (Exception e) {
                        pendingMultiChoiceConfirmations.remove(userId, myRequest);
                        telegramLog.logError("Error waiting for button selection: " + e.getMessage());
                        return -1;
                    }
                });

                // Set up callback to send success message to upload chat
                processor.setUploadChatCallback((msg) -> {
                    sendMessageToChatWithThread(responseChatId, msg, responseThreadId);
                });

                boolean success = processor.processRound(downloadedFile.toString(), year, parsed.roundOrder, nowTimestamp());

                if (!success) {
                    String errorMsg = String.format("Failed to process round_%d.csv for %d", parsed.roundOrder, year);
                    logHelper.logError(errorMsg);
                    // Send error to chat where file was uploaded
                    sendMessageToChatWithThread(responseChatId, formatStatusMessage("🔴", "ERROR", errorMsg), responseThreadId);
                }

            } else {
                String errorMsg = String.format("Unknown file type: %s. Accepted files: cappedlist.csv, {year}_cappedlist.csv, {year}_round_[n].csv, round_[n].csv", fileName);
                logHelper.logError(errorMsg);
                // Send error to chat where file was uploaded
                sendMessageToChatWithThread(responseChatId, formatStatusMessage("🔴", "ERROR", errorMsg), responseThreadId);
            }
            
        } finally {
            // Clean up temp file
            TelegramFileDownloader.deleteTempFile(downloadedFile);
        }
    }

    /**
     * Returns the current timestamp in the standard storage format used
     * across the app (matches Main.java's seeding timestamps).
     */
    private String nowTimestamp() {
        return TimezoneHelper.formatNow("yyyy-MM-dd HH:mm:ss.SSS");
    }

    /**
     * Formats a message like TelegramLog (with emote, timestamp, filename, type)
     */
    private String formatStatusMessage(String emote, String type, String message) {
        // Uses the same settings.timezone-aware helper as nowTimestamp() and
        // every other timestamp in the app - this used to call
        // LocalDateTime.now() directly, showing server-local (JVM default
        // zone) time instead of the admin-configured timezone.
        String timestamp = nowTimestamp();
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
        
        if (isAllowAllChannelsProcessing()) {
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
            
            String errorMsg = "❌ **Wrong Channel**\n\nPlease send commands to " + describeThread(publicChatIdCommands, "commands channel");
            sendMessageToChat(chatId, errorMsg, threadId);
            
            System.out.println("Command received but not in commands channel: " + command + " - Error sent to user");
            return;
        }

        // Parse command (already stripped of @botname)
        if (command.equalsIgnoreCase("/settings")) {
            handleSettingsCommand(message);
        } else if (command.equalsIgnoreCase("/exportdatabase")) {
            handleExportDatabaseCommand(message);
        } else if (command.equalsIgnoreCase("/rankplayers")) {
            handleRankPlayersCommand(message);
        } else if (command.equalsIgnoreCase("/rankhalls")) {
            handleRankHallsCommand(message);
        } else if (command.equalsIgnoreCase("/comparehalls")) {
            handleCompareHallsCommand(message);
        } else if (command.equalsIgnoreCase("/compareplayers")) {
            handleComparePlayersCommand(message);
        } else if (command.equalsIgnoreCase("/about")) {
            handleAboutCommand(message);
        } else if (command.equalsIgnoreCase("/help")) {
            handleHelpCommand(message);
        } else if (command.equalsIgnoreCase("/infoplayer")) {
            handleInfoPlayerCommand(message);
        } else if (command.equalsIgnoreCase("/infohall")) {
            handleInfoHallCommand(message);
        } else if (command.equalsIgnoreCase("/infomatch")) {
            handleInfoMatchCommand(message);
        } else if (command.equalsIgnoreCase("/infomatchhall")) {
            handleInfoMatchHallCommand(message);
        } else if (command.equalsIgnoreCase("/matchtypes")) {
            handleMatchTypesCommand(message);
        } else if (command.equalsIgnoreCase("/recalculate")) {
            handleRecalculateCommand(message);
        } else if (command.equalsIgnoreCase("/admins")) {
            handleAdminsCommand(message);
        } else if (command.equalsIgnoreCase("/predict")) {
            handlePredictCommand(message);
        } else if (command.equalsIgnoreCase("/modelstats")) {
            handleModelStatsCommand(message);
        } else if (command.equalsIgnoreCase("/lineup")) {
            handleLineupCommand(message);
        } else {
            System.out.println("Unknown command: " + command);
        }
    }

    /**
     * Handles /recalculate command - admin-triggered whole-history rating
     * recalculation. Runs on a background thread (like file processing) so
     * the polling thread stays free to receive the confirmation button
     * callback, and reuses the same multi-choice confirmation future the
     * file-processing flow uses.
     */
    private void handleRecalculateCommand(JsonObject message) {
        String userInfo = getUserInfoFromMessage(message);
        logHelper.logInfo(String.format("%s: Processing /recalculate command", userInfo));

        Thread recalcThread = new Thread(() -> {
            try {
                JsonObject from = message.getAsJsonObject("from");
                String userId = from.get("id").getAsString();

                com.calplus.ihrgstats.telegrambot.commands.CommandRecalculate recalcCommand =
                    new com.calplus.ihrgstats.telegrambot.commands.CommandRecalculate();

                if (!recalcCommand.isAdmin(userId)) {
                    logHelper.logWarning(String.format("Non-admin %s attempted to use /recalculate", userInfo));
                    sendMessageToCommandsChannel("❌ Access Denied: Only administrators can run /recalculate.", message);
                    return;
                }

                String[] options = {"Start recalculation", "Cancel"};

                CompletableFuture<Integer> future = new CompletableFuture<>();
                MultiChoiceConfirmationRequest myRequest = new MultiChoiceConfirmationRequest(options, future, message);
                // Keyed per-user (not a single global slot) - see the matching
                // handleCallbackQuery lookup. Also means only the admin who
                // actually ran /recalculate can answer their own Start/Cancel
                // buttons, not anyone else clicking in the same chat first.
                // putIfAbsent + the matching atomic remove(key, value) below
                // guard against a second concurrent /recalculate from the same
                // admin silently clobbering this one's future.
                if (pendingMultiChoiceConfirmations.putIfAbsent(userId, myRequest) != null) {
                    sendMessageToCommandsChannel("⚠️ You already have a pending confirmation - please answer that one first.", message);
                    return;
                }

                sendMessageWithButtons(recalcCommand.buildConfirmationMessage(), options, message, myRequest.nonce);

                int choice;
                try {
                    choice = future.get(60, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    pendingMultiChoiceConfirmations.remove(userId, myRequest);
                    sendMessageToCommandsChannel("⏱️ Confirmation timeout - recalculation cancelled.", message);
                    return;
                } catch (Exception e) {
                    // Without this, any non-timeout failure here (e.g. an
                    // InterruptedException) would leak myRequest forever -
                    // the outer catch below can't reach it since userId/
                    // myRequest are out of scope there, permanently blocking
                    // this admin's future /recalculate and file-upload dialogs.
                    pendingMultiChoiceConfirmations.remove(userId, myRequest);
                    telegramLog.logError("Error waiting for recalculation confirmation: " + e.getMessage());
                    sendMessageToCommandsChannel("🔴 Error waiting for confirmation - recalculation cancelled.", message);
                    return;
                }

                if (choice != 0) {
                    sendMessageToCommandsChannel("🟡 Recalculation cancelled.", message);
                    return;
                }

                sendMessageToCommandsChannel("⏳ Recalculating all rounds across all years...", message);
                com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse response = recalcCommand.execute(userId);
                sendMessageToCommandsChannel(response.message, message);

            } catch (Exception e) {
                String errorMsg = "Error processing /recalculate command: " + e.getMessage();
                logHelper.logError(errorMsg);
                e.printStackTrace();
                sendMessageToCommandsChannel(formatStatusMessage("🔴", "ERROR", errorMsg), message);
            }
        });
        recalcThread.setDaemon(true);
        recalcThread.start();
    }

    /**
     * Sends a message to the commands channel
     * Intelligently routes based on allowAllChannelsProcessing and subchannel configuration
     */
    /**
     * POSTs a sendMessage payload to Telegram. On a non-200 response, logs
     * the failure via discordLog/telegramLog (not just System.err, which is
     * easy to miss) and - if the payload had parse_mode set - retries once
     * with parse_mode stripped (plain text). This is what makes a malformed
     * entity (e.g. an unescaped character that slipped through) degrade to
     * an unformatted-but-delivered message instead of silently vanishing -
     * the historically-reported "errors don't surface to chat" bug class.
     */
    private void sendMessagePayloadWithFallback(JsonObject payload, String context) {
        try {
            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HttpClientFactory.REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                String errorMsg = String.format("Failed to send %s (HTTP %d): %s", context, response.statusCode(), response.body());
                System.err.println(errorMsg);
                logHelper.logError(errorMsg);

                // 400 "message is too long": the text exceeded Telegram's
                // 4096 limit on a path that never went through the chunker
                // (e.g. an exception message interpolated into an error
                // report). Stripping parse_mode can't fix that - re-send the
                // same payload split into limit-sized chunks instead. The
                // size()>1 guard means an already-limit-sized text can never
                // re-enter this branch, so the recursion is bounded.
                if (response.statusCode() == 400 && response.body() != null
                        && response.body().contains("message is too long")
                        && payload.has("text")) {
                    List<String> chunks = com.calplus.ihrgstats.utils.MessageChunker.splitForTelegram(payload.get("text").getAsString());
                    if (chunks.size() > 1) {
                        logHelper.logWarning("Re-sending over-long " + context + " as " + chunks.size() + " chunks");
                        for (int i = 0; i < chunks.size(); i++) {
                            JsonObject chunkPayload = payload.deepCopy();
                            chunkPayload.addProperty("text", chunks.get(i));
                            if (i < chunks.size() - 1) {
                                // Buttons (if any) ride on the last chunk only.
                                chunkPayload.remove("reply_markup");
                            }
                            sendMessagePayloadWithFallback(chunkPayload, context + " (chunk " + (i + 1) + "/" + chunks.size() + ")");
                        }
                        return;
                    }
                }

                if (payload.has("parse_mode")) {
                    payload.remove("parse_mode");
                    HttpRequest retryRequest = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(HttpClientFactory.REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                        .build();
                    HttpResponse<String> retryResponse = httpClient.send(retryRequest, HttpResponse.BodyHandlers.ofString());
                    if (retryResponse.statusCode() != 200) {
                        String retryError = String.format("Plain-text fallback for %s also failed (HTTP %d): %s", context, retryResponse.statusCode(), retryResponse.body());
                        System.err.println(retryError);
                        logHelper.logError(retryError);
                    } else {
                        logHelper.logWarning("Sent " + context + " as plain text after formatted send failed");
                    }
                }
            }
        } catch (Exception e) {
            String errorMsg = "Error sending " + context + ": " + e.getMessage();
            System.err.println(errorMsg);
            logHelper.logError(errorMsg);
        }
    }

    private void sendMessageToCommandsChannel(String message, JsonObject originalMessage) {
        try {
            message = com.calplus.ihrgstats.utils.TelegramHtml.prepareForSending(message);

            JsonObject payload = new JsonObject();

            // Determine where to send the message
            if (isAllowAllChannelsProcessing() && originalMessage != null && originalMessage.has("chat")) {
                // Send to the same channel where the command was received
                JsonObject chat = originalMessage.getAsJsonObject("chat");
                payload.addProperty("chat_id", chat.get("id").getAsString());
                
                // Add thread ID if the original message was in a thread
                if (originalMessage.has("message_thread_id")) {
                    // Telegram's API expects message_thread_id as a number -
                    // same fix as the upload/status/button senders.
                    try {
                        payload.addProperty("message_thread_id", originalMessage.get("message_thread_id").getAsInt());
                    } catch (NumberFormatException e) {
                        // Ignore if not a valid number
                    }
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
                    try {
                        payload.addProperty("message_thread_id", Integer.parseInt(chatAndThread[1]));
                    } catch (NumberFormatException e) {
                        // Ignore if not a valid number
                    }
                }
            }
            
            payload.addProperty("text", message);
            
            // Add parse_mode for HTML if message contains HTML tags
            if (message.contains("<b>") || message.contains("<i>") || message.contains("<code>") || message.contains("<pre>")) {
                payload.addProperty("parse_mode", "HTML");
            }
            // No parse_mode is set otherwise - after TelegramHtml.prepareForSending
            // (called at the top of every send method), any remaining "**"/"```"/"__"
            // is never legitimate markdown intent, only accidental content residue
            // (a stray unpaired sequence, or literal characters in a name/label).
            // Sending as plain text is always safe; the fragile legacy "Markdown"
            // parse mode used to be selected here on that same residue and could
            // fail outright on a single unpaired "*"/"_" (A11).

            sendMessagePayloadWithFallback(payload, "message to commands channel");
        } catch (Exception e) {
            String errorMsg = "Error sending message to commands channel: " + e.getMessage();
            System.err.println(errorMsg);
            logHelper.logError(errorMsg);
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
                // The heartbeat runs on its own executor, so "this message
                // arrived" only proves the JVM is up - not that updates are
                // being received. Check the polling loop's liveness stamp so
                // a hung/dead polling thread is reported instead of masked
                // by a green "online" message.
                long lastPoll = lastPollCompletedAt;
                String message;
                if (lastPoll > 0 && System.currentTimeMillis() - lastPoll > STALE_POLL_WARNING_MS) {
                    long staleSeconds = (System.currentTimeMillis() - lastPoll) / 1000;
                    message = formatStatusMessage("🟡", "WARNING",
                        "Bot process is alive but NO Telegram poll has completed for " + staleSeconds
                            + "s - updates are not being received");
                } else {
                    message = formatStatusMessage("🟢", "SUCCESS", "Bot is online and monitoring for file uploads");
                }
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
     * Sends a message to the status chat/thread
     * Intelligently routes to subchannel if exists, otherwise main dev channel
     */
    private void sendMessageToStatusChat(String message) {
        try {
            message = com.calplus.ihrgstats.utils.TelegramHtml.prepareForSending(message);
            String[] chatAndThread = getStatusChatIdAndThread();
            if (chatAndThread == null) {
                System.err.println("Cannot send status message: devChatId is not configured");
                return;
            }
            
            String chatId = chatAndThread[0];
            String threadId = chatAndThread[1];

            JsonObject payload = new JsonObject();
            payload.addProperty("chat_id", chatId);
            payload.addProperty("text", message);

            // Add parse_mode for HTML if message contains HTML tags
            if (message.contains("<b>") || message.contains("<i>") || message.contains("<code>") || message.contains("<pre>")) {
                payload.addProperty("parse_mode", "HTML");
            }
            // No parse_mode is set otherwise - after TelegramHtml.prepareForSending
            // (called at the top of every send method), any remaining "**"/"```"/"__"
            // is never legitimate markdown intent, only accidental content residue
            // (a stray unpaired sequence, or literal characters in a name/label).
            // Sending as plain text is always safe; the fragile legacy "Markdown"
            // parse mode used to be selected here on that same residue and could
            // fail outright on a single unpaired "*"/"_" (A11).

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

            sendMessagePayloadWithFallback(payload, "status message");
        } catch (Exception e) {
            String errorMsg = "Error sending status message: " + e.getMessage();
            System.err.println(errorMsg);
            logHelper.logError(errorMsg);
            e.printStackTrace();
        }
    }

    /**
     * Sends a message with inline keyboard buttons to the upload chat
     * Intelligently routes based on allowAllChannelsProcessing and original message
     */
    /**
     * Sends a message with inline keyboard buttons, tagged with the
     * dialog's nonce (see MultiChoiceConfirmationRequest.nonce) so a stale
     * button from an earlier resolved dialog can never be mistaken for the
     * currently pending one. Intelligently routes based on
     * allowAllChannelsProcessing and original message.
     */
    private void sendMessageWithButtons(String message, String[] options, JsonObject originalMessage, String nonce) {
        try {
            message = com.calplus.ihrgstats.utils.TelegramHtml.prepareForSending(message);
            String chatId;
            String threadId;

            // Determine where to send the message
            if (isAllowAllChannelsProcessing() && originalMessage != null && originalMessage.has("chat")) {
                // Send to the same channel where the file was uploaded
                JsonObject chat = originalMessage.getAsJsonObject("chat");
                chatId = chat.get("id").getAsString();

                // Add thread ID if the original message was in a thread
                threadId = originalMessage.has("message_thread_id") ?
                    originalMessage.get("message_thread_id").getAsString() : null;
            } else {
                // Use configured upload chat
                String[] chatAndThread = getUploadChatIdAndThread();
                if (chatAndThread == null || chatAndThread[0] == null || chatAndThread[0].isEmpty()) {
                    System.err.println("Cannot send message with buttons: upload chat ID is not configured");
                    return;
                }
                
                chatId = chatAndThread[0];
                threadId = chatAndThread[1];
            }
            
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

            // Add parse_mode for HTML if message contains HTML tags
            if (message.contains("<b>") || message.contains("<i>") || message.contains("<code>") || message.contains("<pre>")) {
                payload.addProperty("parse_mode", "HTML");
            }
            // No parse_mode is set otherwise - after TelegramHtml.prepareForSending
            // (called at the top of this method), any remaining "**"/"```"/"__" is
            // never legitimate markdown intent, only accidental content residue.
            // Sending as plain text is always safe; the fragile legacy "Markdown"
            // parse mode used to be selected here on that same residue and could
            // fail outright on a single unpaired "*"/"_" (A11).

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
                button.addProperty("callback_data", "choice_" + i + "_" + nonce);
                row.add(button);
                keyboard.add(row);
            }

            replyMarkup.add("inline_keyboard", keyboard);
            payload.add("reply_markup", replyMarkup);

            sendMessagePayloadWithFallback(payload, "message with buttons");

        } catch (Exception e) {
            logHelper.logError("Error sending message with buttons: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Sends a message to the upload chat/thread
     * Intelligently routes to subchannel if exists, otherwise main channel
     */
    /**
     * Sends a message to the upload chat or original channel (if allowAllChannelsProcessing is enabled)
     */
    /**
     * Sends a message to the upload chat or original channel (if allowAllChannelsProcessing is enabled)
     * Intelligently routes based on allowAllChannelsProcessing and original message
     */
    private void sendMessageToUploadChat(String message, JsonObject originalMessage) {
        try {
            message = com.calplus.ihrgstats.utils.TelegramHtml.prepareForSending(message);
            String chatId;
            String threadId;

            // Determine where to send the message
            if (isAllowAllChannelsProcessing() && originalMessage != null && originalMessage.has("chat")) {
                // Send to the same channel where the file was uploaded
                JsonObject chat = originalMessage.getAsJsonObject("chat");
                chatId = chat.get("id").getAsString();

                // Add thread ID if the original message was in a thread
                threadId = originalMessage.has("message_thread_id") ?
                    originalMessage.get("message_thread_id").getAsString() : null;
            } else {
                // Use configured upload chat
                String[] chatAndThread = getUploadChatIdAndThread();
                if (chatAndThread == null || chatAndThread[0] == null || chatAndThread[0].isEmpty()) {
                    System.err.println("Cannot send message: upload chat ID is not configured");
                    discordLog.logWarning("Cannot send message to Telegram: upload chat not configured");
                    return;
                }
                
                chatId = chatAndThread[0];
                threadId = chatAndThread[1];
            }
            
            JsonObject payload = new JsonObject();
            payload.addProperty("chat_id", chatId);
            payload.addProperty("text", message);

            // Add parse_mode for HTML if message contains HTML tags
            if (message.contains("<b>") || message.contains("<i>") || message.contains("<code>") || message.contains("<pre>")) {
                payload.addProperty("parse_mode", "HTML");
            }
            // No parse_mode is set otherwise - after TelegramHtml.prepareForSending
            // (called at the top of every send method), any remaining "**"/"```"/"__"
            // is never legitimate markdown intent, only accidental content residue
            // (a stray unpaired sequence, or literal characters in a name/label).
            // Sending as plain text is always safe; the fragile legacy "Markdown"
            // parse mode used to be selected here on that same residue and could
            // fail outright on a single unpaired "*"/"_" (A11).

            // Add thread ID if specified - sent as an int, matching every
            // sibling send method (Telegram's API expects message_thread_id
            // as a number; this one used to send it as a JSON string).
            if (threadId != null && !threadId.isEmpty()) {
                try {
                    payload.addProperty("message_thread_id", Integer.parseInt(threadId));
                    System.out.println("Sending to upload chat " + chatId + " with thread ID " + threadId);
                } catch (NumberFormatException e) {
                    System.out.println("Sending to upload chat " + chatId + " without thread ID (invalid format: " + threadId + ")");
                }
            } else {
                System.out.println("Sending to upload chat " + chatId + " without thread ID");
            }

            sendMessagePayloadWithFallback(payload, "message to upload chat");
        } catch (Exception e) {
            String errorMsg = "Error sending message to upload chat: " + e.getMessage();
            System.err.println(errorMsg);
            logHelper.logError(errorMsg);
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
            message = com.calplus.ihrgstats.utils.TelegramHtml.prepareForSending(message);

            JsonObject payload = new JsonObject();
            payload.addProperty("chat_id", chatId);
            payload.addProperty("text", message);

            // Add parse_mode for HTML if message contains formatting tags
            if (message.contains("<b>") || message.contains("<i>") || message.contains("<code>") || message.contains("<pre>")) {
                payload.addProperty("parse_mode", "HTML");
            }

            // Add thread ID if provided
            if (threadId != null && !threadId.isEmpty()) {
                try {
                    payload.addProperty("message_thread_id", Integer.parseInt(threadId));
                } catch (NumberFormatException e) {
                    // Ignore if not a valid number
                }
            }

            // Routed through the shared sender - previously this was the ONE
            // send path with its own inline HTTP call and therefore no
            // plain-text fallback retry when a formatted send failed.
            sendMessagePayloadWithFallback(payload, "message to chat " + chatId);
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
            message = com.calplus.ihrgstats.utils.TelegramHtml.prepareForSending(message);

            JsonObject payload = new JsonObject();
            payload.addProperty("chat_id", chatId);
            payload.addProperty("text", message);

            // Add parse_mode for HTML if message contains HTML tags
            if (message.contains("<b>") || message.contains("<i>") || message.contains("<code>") || message.contains("<pre>")) {
                payload.addProperty("parse_mode", "HTML");
            }
            // No parse_mode is set otherwise - after TelegramHtml.prepareForSending
            // (called at the top of every send method), any remaining "**"/"```"/"__"
            // is never legitimate markdown intent, only accidental content residue
            // (a stray unpaired sequence, or literal characters in a name/label).
            // Sending as plain text is always safe; the fragile legacy "Markdown"
            // parse mode used to be selected here on that same residue and could
            // fail outright on a single unpaired "*"/"_" (A11).

            // Add thread ID if specified
            if (threadId != null && !threadId.isEmpty()) {
                try {
                    payload.addProperty("message_thread_id", Integer.parseInt(threadId));
                } catch (NumberFormatException e) {
                    // Ignore if not a valid number
                }
            }

            sendMessagePayloadWithFallback(payload, "message to chat with thread");
        } catch (Exception e) {
            String errorMsg = "Error sending message: " + e.getMessage();
            System.err.println(errorMsg);
            logHelper.logError(errorMsg);
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
            logHelper.logError(errorMsg);
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
                exportCommand.requestFormatChoice(userId);

            if (response.buttons != null) {
                // Send confirmation message with buttons
                sendMessageWithExportButtons(response.message, response.buttons, message, userId);
            } else {
                // Just send message (e.g., unauthorized)
                sendMessageToCommandsChannel(response.message, message);
            }

        } catch (Exception e) {
            String errorMsg = "Error processing /exportdatabase command: " + e.getMessage();
            logHelper.logError(errorMsg);
            e.printStackTrace();
            sendMessageToCommandsChannel(formatStatusMessage("🔴", "ERROR", errorMsg), message);
        }
    }

    /**
     * Handles /rankplayers command. Runs on a background thread (A27-style,
     * matching /about and /recalculate) - this is one of the heaviest code
     * paths in the app (point-in-time rating queries per player/round plus
     * image rendering), and running it synchronously here previously stalled
     * the polling thread for its whole duration, queuing up every other
     * user's button clicks and confirmation replies behind it.
     */
    private void handleRankPlayersCommand(JsonObject message) {
        Thread rankPlayersThread = new Thread(() -> {
            try {
                JsonObject from = message.getAsJsonObject("from");
                String userId = from.get("id").getAsString();

                com.calplus.ihrgstats.telegrambot.commands.CommandRankPlayers rankCommand =
                    new com.calplus.ihrgstats.telegrambot.commands.CommandRankPlayers();

                com.calplus.ihrgstats.telegrambot.commands.CommandRankPlayers.RankResponse response =
                    rankCommand.handleCommand(userId);

                // Send message with buttons if available
                if (response.buttonConfig != null) {
                    sendMessageWithRankPlayersButtons(response.message, response.buttonConfig, message);
                } else {
                    // Send text message
                    sendLongMessageToCommandsChannel(response.message, message);

                    // Send image if available
                    if (response.imagePath != null) {
                        sendImageToCommandsChannel(response.imagePath, message);
                    }
                }

            } catch (Exception e) {
                String errorMsg = "Error processing /rankplayers command: " + e.getMessage();
                logHelper.logError(errorMsg);
                e.printStackTrace();
                sendMessageToCommandsChannel(formatStatusMessage("🔴", "ERROR", errorMsg), message);
            }
        });
        rankPlayersThread.setDaemon(true);
        rankPlayersThread.start();
    }

    /**
     * Handles /rankhalls command. Runs on a background thread - see
     * {@link #handleRankPlayersCommand} for why.
     */
    private void handleRankHallsCommand(JsonObject message) {
        Thread rankHallsThread = new Thread(() -> {
            try {
                JsonObject from = message.getAsJsonObject("from");
                String userId = from.get("id").getAsString();

                com.calplus.ihrgstats.telegrambot.commands.CommandRankHalls rankCommand =
                    new com.calplus.ihrgstats.telegrambot.commands.CommandRankHalls();

                com.calplus.ihrgstats.telegrambot.commands.CommandRankHalls.RankResponse response =
                    rankCommand.handleCommand(userId);

                // Send message with buttons if available
                if (response.buttonConfig != null) {
                    sendMessageWithRankHallsButtons(response.message, response.buttonConfig, message);
                } else {
                    // Send text message
                    sendLongMessageToCommandsChannel(response.message, message);

                    // Send image if available
                    if (response.imagePath != null) {
                        sendImageToCommandsChannel(response.imagePath, message);
                    }
                }

            } catch (Exception e) {
                String errorMsg = "Error processing /rankhalls command: " + e.getMessage();
                logHelper.logError(errorMsg);
                e.printStackTrace();
                sendMessageToCommandsChannel(formatStatusMessage("🔴", "ERROR", errorMsg), message);
            }
        });
        rankHallsThread.setDaemon(true);
        rankHallsThread.start();
    }

    /**
     * Handles /comparehalls command. Runs on a background thread - see
     * {@link #handleRankPlayersCommand} for why.
     */
    private void handleCompareHallsCommand(JsonObject message) {
        Thread compareHallsThread = new Thread(() -> {
            try {
                JsonObject from = message.getAsJsonObject("from");
                String userId = from.get("id").getAsString();

                com.calplus.ihrgstats.telegrambot.commands.CommandCompareHalls compareCommand =
                    new com.calplus.ihrgstats.telegrambot.commands.CommandCompareHalls();

                com.calplus.ihrgstats.telegrambot.commands.CommandCompareHalls.CompareResponse response =
                    compareCommand.handleCommand(userId);

                // Send message with buttons if available
                if (response.buttonConfig != null) {
                    sendMessageWithCompareHallsButtons(response.message, response.buttonConfig, message);
                } else {
                    sendMessageToCommandsChannel(response.message, message);
                }

            } catch (Exception e) {
                String errorMsg = "Error processing /comparehalls command: " + e.getMessage();
                logHelper.logError(errorMsg);
                e.printStackTrace();
                sendMessageToCommandsChannel(formatStatusMessage("🔴", "ERROR", errorMsg), message);
            }
        });
        compareHallsThread.setDaemon(true);
        compareHallsThread.start();
    }

    /**
     * Handles compare halls callback queries. The button-removal prefix stays
     * synchronous (quick, single API call); the actual generation - the
     * heaviest code path here - runs on a background thread, matching the
     * export_db_* callback precedent, so it doesn't stall the polling thread.
     */
    /** Command-specific routing for one callback click; may throw - the scaffold logs failures. */
    private interface CallbackRouting {
        void route(JsonObject message) throws Exception;
    }

    /**
     * Shared scaffold for every per-command callback handler: strips the
     * keyboard off the clicked message synchronously, then runs the
     * command-specific routing - on a daemon worker thread when the step
     * can be slow (report/image generation), so the polling thread stays
     * free to receive further updates (including the very confirmations
     * some flows block on).
     */
    private void runCallbackRouting(JsonObject callbackQuery, String context, boolean onWorkerThread,
            CallbackRouting routing) {
        try {
            JsonObject message = callbackQuery.has("message") ? callbackQuery.getAsJsonObject("message") : null;
            if (message == null) return;

            JsonObject chat = message.getAsJsonObject("chat");
            removeInlineKeyboard(chat.get("id").getAsString(), message.get("message_id").getAsString());

            if (!onWorkerThread) {
                routing.route(message);
                return;
            }
            Thread worker = new Thread(() -> {
                try {
                    routing.route(message);
                } catch (Exception e) {
                    String errorMsg = "Error processing " + context + ": " + e.getMessage();
                    logHelper.logError(errorMsg);
                    e.printStackTrace();
                }
            });
            worker.setDaemon(true);
            worker.start();
        } catch (Exception e) {
            String errorMsg = "Error processing " + context + ": " + e.getMessage();
            logHelper.logError(errorMsg);
            e.printStackTrace();
        }
    }

    /** Wizard-step reply: keyboard steps use the generic button sender; plain steps go to the commands channel. */
    private void sendStepOrPlain(String messageText, com.calplus.ihrgstats.utils.TelegramCommandUtils.ButtonConfig buttonConfig, JsonObject message) {
        if (buttonConfig != null) {
            sendMessageWithGenericButtons(messageText, buttonConfig, message);
        } else {
            sendMessageToCommandsChannel(messageText, message);
        }
    }

    /** Terminal wizard step: chunked text report plus the rendered image when present. */
    private void sendReportWithImage(String messageText, java.nio.file.Path imagePath, JsonObject message) {
        sendLongMessageToCommandsChannel(messageText, message);
        if (imagePath != null) {
            sendImageToCommandsChannel(imagePath, message);
        }
    }

    /**
     * Scaffold shared by the /settings callback branches: strips the keyboard
     * off the clicked message, then delivers the response to that same
     * chat/thread - with a fresh settings keyboard when the step has one.
     */
    private void deliverSettingsCallbackResponse(JsonObject callbackQuery, String text,
            com.calplus.ihrgstats.utils.TelegramCommandUtils.ButtonConfig buttons) {
        JsonObject message = callbackQuery.has("message") ? callbackQuery.getAsJsonObject("message") : null;
        if (message == null) return;

        JsonObject chat = message.getAsJsonObject("chat");
        String chatId = chat.get("id").getAsString();
        String threadId = message.has("message_thread_id") ? message.get("message_thread_id").getAsString() : null;
        removeInlineKeyboard(chatId, message.get("message_id").getAsString());

        if (buttons != null) {
            sendMessageWithSettingsButtons(text, buttons, message);
        } else {
            sendMessageToChat(chatId, text, threadId);
        }
    }

    private void handleCompareHallsCallback(JsonObject callbackQuery, String data, String userId) {
        runCallbackRouting(callbackQuery, "compare halls callback", true, message -> {
            com.calplus.ihrgstats.telegrambot.commands.CommandCompareHalls compareCommand =
                new com.calplus.ihrgstats.telegrambot.commands.CommandCompareHalls();
            com.calplus.ihrgstats.telegrambot.commands.CommandCompareHalls.CompareResponse response;

            if (data.equals("comparehalls_cancel")) {
                response = compareCommand.handleCancel(userId);
                sendMessageToCommandsChannel(response.message, message);
            } else if (data.startsWith("comparehalls_select1_")) {
                int hallId = Integer.parseInt(data.substring("comparehalls_select1_".length()));
                response = compareCommand.handleFirstHallSelection(userId, hallId);
                if (response.buttonConfig != null) {
                    sendMessageWithCompareHallsButtons(response.message, response.buttonConfig, message);
                } else {
                    sendMessageToCommandsChannel(response.message, message);
                }
            } else if (data.startsWith("comparehalls_select2_")) {
                int hallId = Integer.parseInt(data.substring("comparehalls_select2_".length()));
                response = compareCommand.handleSecondHallSelection(userId, hallId);
                if (response.buttonConfig != null) {
                    sendMessageWithCompareHallsButtons(response.message, response.buttonConfig, message);
                } else {
                    sendMessageToCommandsChannel(response.message, message);
                }
            } else if (data.startsWith("comparehalls_selectround_")) {
                String round = data.substring("comparehalls_selectround_".length());
                response = compareCommand.handleRoundSelection(userId, round);
                sendReportWithImage(response.message, response.imagePath, message);
            }
        });
    }

    /**
     * Sends a long message to the commands channel, splitting if necessary
     * Telegram has a 4096 character limit per message
     */
    private void sendLongMessageToCommandsChannel(String message, JsonObject originalMessage) {
        // Chunking is sized on the POST-conversion (HTML-escaped) length, not
        // the raw text - see com.calplus.ihrgstats.utils.MessageChunker (A10).
        List<String> chunks = com.calplus.ihrgstats.utils.MessageChunker.splitForTelegram(message);
        for (int i = 0; i < chunks.size(); i++) {
            sendMessageToCommandsChannel(chunks.get(i), originalMessage);
            if (i < chunks.size() - 1) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Handles /compareplayers command. Runs on a background thread - see
     * {@link #handleRankPlayersCommand} for why.
     */
    private void handleComparePlayersCommand(JsonObject message) {
        Thread comparePlayersThread = new Thread(() -> {
            try {
                JsonObject from = message.getAsJsonObject("from");
                String userId = from.get("id").getAsString();

                com.calplus.ihrgstats.telegrambot.commands.CommandComparePlayers compareCommand =
                    new com.calplus.ihrgstats.telegrambot.commands.CommandComparePlayers();

                com.calplus.ihrgstats.telegrambot.commands.CommandComparePlayers.CompareResponse response =
                    compareCommand.handleCommand(userId);

                // Send message with buttons if available
                if (response.buttonConfig != null) {
                    sendMessageWithComparePlayersButtons(response.message, response.buttonConfig, message);
                } else {
                    sendMessageToCommandsChannel(response.message, message);
                }

            } catch (Exception e) {
                String errorMsg = "Error processing /compareplayers command: " + e.getMessage();
                logHelper.logError(errorMsg);
                e.printStackTrace();
                sendMessageToCommandsChannel(formatStatusMessage("🔴", "ERROR", errorMsg), message);
            }
        });
        comparePlayersThread.setDaemon(true);
        comparePlayersThread.start();
    }

    /**
     * Handles compare players callback queries. The button-removal prefix
     * stays synchronous; the actual generation runs on a background thread -
     * see {@link #handleCompareHallsCallback} for why.
     */
    private void handleComparePlayersCallback(JsonObject callbackQuery, String data, String userId) {
        runCallbackRouting(callbackQuery, "compare players callback", true, message -> {
            com.calplus.ihrgstats.telegrambot.commands.CommandComparePlayers compareCommand =
                new com.calplus.ihrgstats.telegrambot.commands.CommandComparePlayers();
            com.calplus.ihrgstats.telegrambot.commands.CommandComparePlayers.CompareResponse response;

            if (data.equals("compareplayers_cancel")) {
                response = compareCommand.handleCancel(userId);
                sendMessageToCommandsChannel(response.message, message);
            } else if (data.startsWith("compareplayers_selecthall1_")) {
                int hallId = Integer.parseInt(data.substring("compareplayers_selecthall1_".length()));
                response = compareCommand.handleFirstHallSelection(userId, hallId);
                if (response.buttonConfig != null) {
                    sendMessageWithComparePlayersButtons(response.message, response.buttonConfig, message, 1);
                } else {
                    sendMessageToCommandsChannel(response.message, message);
                }
            } else if (data.startsWith("compareplayers_selectplayer1_")) {
                String player = data.substring("compareplayers_selectplayer1_".length());
                response = compareCommand.handleFirstPlayerSelection(userId, player);
                if (response.buttonConfig != null) {
                    sendMessageWithComparePlayersButtons(response.message, response.buttonConfig, message);
                } else {
                    sendMessageToCommandsChannel(response.message, message);
                }
            } else if (data.startsWith("compareplayers_selecthall2_")) {
                int hallId = Integer.parseInt(data.substring("compareplayers_selecthall2_".length()));
                response = compareCommand.handleSecondHallSelection(userId, hallId);
                if (response.buttonConfig != null) {
                    sendMessageWithComparePlayersButtons(response.message, response.buttonConfig, message, 1);
                } else {
                    sendMessageToCommandsChannel(response.message, message);
                }
            } else if (data.startsWith("compareplayers_selectplayer2_")) {
                String player = data.substring("compareplayers_selectplayer2_".length());
                response = compareCommand.handleSecondPlayerSelection(userId, player);
                if (response.buttonConfig != null) {
                    sendMessageWithComparePlayersButtons(response.message, response.buttonConfig, message);
                } else {
                    sendMessageToCommandsChannel(response.message, message);
                }
            } else if (data.startsWith("compareplayers_selectround_")) {
                String round = data.substring("compareplayers_selectround_".length());
                response = compareCommand.handleRoundSelection(userId, round);
                sendReportWithImage(response.message, response.imagePath, message);
            }
        });
    }

    /**
     * Handles rank players callback queries. The button-removal prefix stays
     * synchronous; the actual generation runs on a background thread - see
     * {@link #handleCompareHallsCallback} for why.
     */
    private void handleRankPlayersCallback(JsonObject callbackQuery, String data, String userId) {
        runCallbackRouting(callbackQuery, "rank players callback", true, message -> {
            com.calplus.ihrgstats.telegrambot.commands.CommandRankPlayers rankCommand =
                new com.calplus.ihrgstats.telegrambot.commands.CommandRankPlayers();

            if (data.equals("rankplayers_cancel")) {
                sendMessageToCommandsChannel(rankCommand.handleCancel(userId).message, message);
            } else if (data.startsWith("rankplayers_round_")) {
                String round = data.substring("rankplayers_round_".length());
                com.calplus.ihrgstats.telegrambot.commands.CommandRankPlayers.RankResponse response =
                    rankCommand.handleRoundSelection(userId, round);
                sendReportWithImage(response.message, response.imagePath, message);
            }
        });
    }

    /**
     * Handles rank halls callback queries. The button-removal prefix stays
     * synchronous; the actual generation runs on a background thread - see
     * {@link #handleCompareHallsCallback} for why.
     */
    private void handleRankHallsCallback(JsonObject callbackQuery, String data, String userId) {
        runCallbackRouting(callbackQuery, "rank halls callback", true, message -> {
            com.calplus.ihrgstats.telegrambot.commands.CommandRankHalls rankCommand =
                new com.calplus.ihrgstats.telegrambot.commands.CommandRankHalls();

            if (data.equals("rankhalls_cancel")) {
                sendMessageToCommandsChannel(rankCommand.handleCancel(userId).message, message);
            } else if (data.startsWith("rankhalls_round_")) {
                String round = data.substring("rankhalls_round_".length());
                com.calplus.ihrgstats.telegrambot.commands.CommandRankHalls.RankResponse response =
                    rankCommand.handleRoundSelection(userId, round);
                sendReportWithImage(response.message, response.imagePath, message);
            }
        });
    }

    /**
     * Handles /about command. Runs on a background thread (A27) - fetching
     * each admin's display name makes one synchronous Telegram getChat HTTP
     * call per admin, which would otherwise stall the polling thread (and
     * therefore all other incoming updates) for as long as those calls take.
     */
    private void handleAboutCommand(JsonObject message) {
        Thread aboutThread = new Thread(() -> {
            try {
                JsonObject from = message.getAsJsonObject("from");
                String userId = from.get("id").getAsString();

                com.calplus.ihrgstats.telegrambot.commands.CommandAbout aboutCommand =
                    new com.calplus.ihrgstats.telegrambot.commands.CommandAbout(botToken);

                com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse response =
                    aboutCommand.handleCommand(userId);

                sendMessageToCommandsChannel(response.message, message);

            } catch (Exception e) {
                String errorMsg = "Error processing /about command: " + e.getMessage();
                logHelper.logError(errorMsg);
                e.printStackTrace();
                sendMessageToCommandsChannel(formatStatusMessage("🔴", "ERROR", errorMsg), message);
            }
        });
        aboutThread.setDaemon(true);
        aboutThread.start();
    }

    /**
     * Handles /help command
     */
    private void handleHelpCommand(JsonObject message) {
        try {
            JsonObject from = message.getAsJsonObject("from");
            String userId = from.get("id").getAsString();
            
            com.calplus.ihrgstats.telegrambot.commands.CommandHelp helpCommand = 
                new com.calplus.ihrgstats.telegrambot.commands.CommandHelp();
            
            com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse response = 
                helpCommand.handleCommand(userId);
            
            // Send message with buttons if available
            if (response.buttonConfig != null) {
                sendMessageWithGenericButtons(response.message, response.buttonConfig, message);
            } else {
                sendMessageToCommandsChannel(response.message, message);
            }

        } catch (Exception e) {
            String errorMsg = "Error processing /help command: " + e.getMessage();
            logHelper.logError(errorMsg);
            e.printStackTrace();
            sendMessageToCommandsChannel(formatStatusMessage("🔴", "ERROR", errorMsg), message);
        }
    }

    /**
     * Handles /infoplayer command. Runs on a background thread - see
     * {@link #handleRankPlayersCommand} for why.
     */
    private void handleInfoPlayerCommand(JsonObject message) {
        Thread infoPlayerThread = new Thread(() -> {
            try {
                JsonObject from = message.getAsJsonObject("from");
                String userId = from.get("id").getAsString();

                com.calplus.ihrgstats.telegrambot.commands.CommandInfoPlayer infoCommand =
                    new com.calplus.ihrgstats.telegrambot.commands.CommandInfoPlayer();

                com.calplus.ihrgstats.telegrambot.commands.CommandInfoPlayer.InfoResponse response =
                    infoCommand.handleCommand(userId);

                // Send message with buttons if available
                if (response.buttonConfig != null) {
                    sendMessageWithGenericButtons(response.message, response.buttonConfig, message);
                } else {
                    // Send text message
                    sendLongMessageToCommandsChannel(response.message, message);

                    // Send image if available
                    if (response.imagePath != null) {
                        sendImageToCommandsChannel(response.imagePath, message);
                    }
                }

            } catch (Exception e) {
                String errorMsg = "Error processing /infoplayer command: " + e.getMessage();
                logHelper.logError(errorMsg);
                e.printStackTrace();
                sendMessageToCommandsChannel(formatStatusMessage("🔴", "ERROR", errorMsg), message);
            }
        });
        infoPlayerThread.setDaemon(true);
        infoPlayerThread.start();
    }

    /**
     * Handles /infohall command. Runs on a background thread - see
     * {@link #handleRankPlayersCommand} for why.
     */
    private void handleInfoHallCommand(JsonObject message) {
        Thread infoHallThread = new Thread(() -> {
            try {
                JsonObject from = message.getAsJsonObject("from");
                String userId = from.get("id").getAsString();

                com.calplus.ihrgstats.telegrambot.commands.CommandInfoHall infoCommand =
                    new com.calplus.ihrgstats.telegrambot.commands.CommandInfoHall();

                com.calplus.ihrgstats.telegrambot.commands.CommandInfoHall.InfoResponse response =
                    infoCommand.handleCommand(userId);

                // Send message with buttons if available
                if (response.buttonConfig != null) {
                    sendMessageWithGenericButtons(response.message, response.buttonConfig, message);
                } else {
                    // Send text message
                    sendLongMessageToCommandsChannel(response.message, message);

                    // Send image if available
                    if (response.imagePath != null) {
                        sendImageToCommandsChannel(response.imagePath, message);
                    }
                }

            } catch (Exception e) {
                String errorMsg = "Error processing /infohall command: " + e.getMessage();
                logHelper.logError(errorMsg);
                e.printStackTrace();
                sendMessageToCommandsChannel(formatStatusMessage("🔴", "ERROR", errorMsg), message);
            }
        });
        infoHallThread.setDaemon(true);
        infoHallThread.start();
    }

    /**
     * Handles /infomatch command. Runs on a background thread - see
     * {@link #handleRankPlayersCommand} for why.
     */
    private void handleInfoMatchCommand(JsonObject message) {
        Thread infoMatchThread = new Thread(() -> {
            try {
                JsonObject from = message.getAsJsonObject("from");
                String userId = from.get("id").getAsString();

                com.calplus.ihrgstats.telegrambot.commands.CommandInfoMatch infoCommand =
                    new com.calplus.ihrgstats.telegrambot.commands.CommandInfoMatch();

                com.calplus.ihrgstats.telegrambot.commands.CommandInfoMatch.MatchResponse response =
                    infoCommand.handleCommand(userId);

                // Send message with buttons if available
                if (response.buttonConfig != null) {
                    sendMessageWithGenericButtons(response.message, response.buttonConfig, message);
                } else {
                    // Send text message
                    sendLongMessageToCommandsChannel(response.message, message);

                    // Send image if available
                    if (response.imagePath != null) {
                        sendImageToCommandsChannel(response.imagePath, message);
                    }
                }

            } catch (Exception e) {
                String errorMsg = "Error processing /infomatch command: " + e.getMessage();
                logHelper.logError(errorMsg);
                e.printStackTrace();
                sendMessageToCommandsChannel(formatStatusMessage("🔴", "ERROR", errorMsg), message);
            }
        });
        infoMatchThread.setDaemon(true);
        infoMatchThread.start();
    }

    /**
     * Handles /infomatchhall command. Runs on a background thread - see
     * {@link #handleRankPlayersCommand} for why.
     */
    private void handleInfoMatchHallCommand(JsonObject message) {
        Thread infoMatchHallThread = new Thread(() -> {
            try {
                JsonObject from = message.getAsJsonObject("from");
                String userId = from.get("id").getAsString();

                com.calplus.ihrgstats.telegrambot.commands.CommandInfoMatchHall infoCommand =
                    new com.calplus.ihrgstats.telegrambot.commands.CommandInfoMatchHall();

                com.calplus.ihrgstats.telegrambot.commands.CommandInfoMatchHall.InfoResponse response =
                    infoCommand.handleCommand(userId);

                // Send message with buttons if available
                if (response.buttonConfig != null) {
                    sendMessageWithGenericButtons(response.message, response.buttonConfig, message);
                } else {
                    // Send text message
                    sendLongMessageToCommandsChannel(response.message, message);

                    // Send image if available
                    if (response.imagePath != null) {
                        sendImageToCommandsChannel(response.imagePath, message);
                    }
                }

            } catch (Exception e) {
                String errorMsg = "Error processing /infomatchhall command: " + e.getMessage();
                logHelper.logError(errorMsg);
                e.printStackTrace();
                sendMessageToCommandsChannel(formatStatusMessage("🔴", "ERROR", errorMsg), message);
            }
        });
        infoMatchHallThread.setDaemon(true);
        infoMatchHallThread.start();
    }

    /**
     * Handles /matchtypes command (admin-only)
     */
    private void handleMatchTypesCommand(JsonObject message) {
        try {
            JsonObject from = message.getAsJsonObject("from");
            String userId = from.get("id").getAsString();

            com.calplus.ihrgstats.telegrambot.commands.CommandMatchTypes matchTypesCommand =
                new com.calplus.ihrgstats.telegrambot.commands.CommandMatchTypes();

            com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse response =
                matchTypesCommand.handleCommand(userId);

            if (response.buttonConfig != null) {
                sendMessageWithGenericButtons(response.message, response.buttonConfig, message);
            } else {
                sendMessageToCommandsChannel(response.message, message);
            }

        } catch (Exception e) {
            String errorMsg = "Error processing /matchtypes command: " + e.getMessage();
            logHelper.logError(errorMsg);
            e.printStackTrace();
            sendMessageToCommandsChannel(formatStatusMessage("🔴", "ERROR", errorMsg), message);
        }
    }

    /**
     * Handles /admins command (admin-only)
     */
    private void handleAdminsCommand(JsonObject message) {
        try {
            JsonObject from = message.getAsJsonObject("from");
            String userId = from.get("id").getAsString();

            com.calplus.ihrgstats.telegrambot.commands.CommandAdmins adminsCommand =
                new com.calplus.ihrgstats.telegrambot.commands.CommandAdmins();

            com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse response =
                adminsCommand.handleCommand(userId);

            if (response.buttonConfig != null) {
                sendMessageWithGenericButtons(response.message, response.buttonConfig, message);
            } else {
                sendMessageToCommandsChannel(response.message, message);
            }

        } catch (Exception e) {
            String errorMsg = "Error processing /admins command: " + e.getMessage();
            logHelper.logError(errorMsg);
            e.printStackTrace();
            sendMessageToCommandsChannel(formatStatusMessage("🔴", "ERROR", errorMsg), message);
        }
    }

    /**
     * Handles admins callback queries (admin-only)
     */
    private void handleAdminsCallback(JsonObject callbackQuery, String data, String userId) {
        runCallbackRouting(callbackQuery, "admins callback", false, message -> {
            com.calplus.ihrgstats.telegrambot.commands.CommandAdmins adminsCommand =
                new com.calplus.ihrgstats.telegrambot.commands.CommandAdmins();
            com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse response;

            if (data.equals("admins_cancel")) {
                response = adminsCommand.handleCancel(userId);
                sendMessageToCommandsChannel(response.message, message);
            } else if (data.equals("admins_list")) {
                response = adminsCommand.handleList(userId);
                sendLongMessageToCommandsChannel(response.message, message);
            } else if (data.equals("admins_addstart")) {
                response = adminsCommand.handleAddStart(userId);
                sendMessageToCommandsChannel(response.message, message);
            } else if (data.equals("admins_removeselect")) {
                response = adminsCommand.handleRemoveSelect(userId);
                sendStepOrPlain(response.message, response.buttonConfig, message);
            } else if (data.startsWith("admins_remove_")) {
                int adminRowId = Integer.parseInt(data.substring("admins_remove_".length()));
                response = adminsCommand.handleRemoveConfirm(userId, adminRowId);
                sendMessageToCommandsChannel(response.message, message);
            }
        });
    }

    /**
     * Handles match types callback queries (admin-only)
     */
    private void handleMatchTypesCallback(JsonObject callbackQuery, String data, String userId) {
        runCallbackRouting(callbackQuery, "match types callback", false, message -> {
            com.calplus.ihrgstats.telegrambot.commands.CommandMatchTypes matchTypesCommand =
                new com.calplus.ihrgstats.telegrambot.commands.CommandMatchTypes();
            com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse response;

            if (data.equals("matchtypes_cancel")) {
                response = matchTypesCommand.handleCancel(userId);
                sendMessageToCommandsChannel(response.message, message);
            } else if (data.equals("matchtypes_create")) {
                response = matchTypesCommand.handleCreateNew(userId);
                sendMessageToCommandsChannel(response.message, message);
            } else if (data.equals("matchtypes_list")) {
                response = matchTypesCommand.handleList(userId);
                sendLongMessageToCommandsChannel(response.message, message);
            } else if (data.equals("matchtypes_editselect")) {
                response = matchTypesCommand.handleEditSelection(userId);
                sendStepOrPlain(response.message, response.buttonConfig, message);
            } else if (data.startsWith("matchtypes_edit_")) {
                int matchTypeId = Integer.parseInt(data.substring("matchtypes_edit_".length()));
                response = matchTypesCommand.handleEditStart(userId, matchTypeId);
                sendMessageToCommandsChannel(response.message, message);
            } else if (data.equals("matchtypes_assignselect")) {
                response = matchTypesCommand.handleAssignSelection(userId);
                sendStepOrPlain(response.message, response.buttonConfig, message);
            } else if (data.startsWith("matchtypes_assignmt_")) {
                int matchTypeId = Integer.parseInt(data.substring("matchtypes_assignmt_".length()));
                response = matchTypesCommand.handleAssignStart(userId, matchTypeId);
                sendMessageToCommandsChannel(response.message, message);
            }
        });
    }

    /**
     * Handles /modelstats command (admin-only) - a single read-only report, no wizard.
     */
    private void handleModelStatsCommand(JsonObject message) {
        // Runs on a background thread like the other report commands - the
        // live scorecard re-extracts every board in the database, which
        // would otherwise stall the polling loop (and every other user's
        // updates) for the full duration.
        Thread modelStatsThread = new Thread(() -> {
            try {
                JsonObject from = message.getAsJsonObject("from");
                String userId = from.get("id").getAsString();

                com.calplus.ihrgstats.telegrambot.commands.CommandModelStats modelStatsCommand =
                    new com.calplus.ihrgstats.telegrambot.commands.CommandModelStats();

                com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse response =
                    modelStatsCommand.handleCommand(userId);

                sendLongMessageToCommandsChannel(response.message, message);

            } catch (Exception e) {
                String errorMsg = "Error processing /modelstats command: " + e.getMessage();
                logHelper.logError(errorMsg);
                e.printStackTrace();
                sendMessageToCommandsChannel(formatStatusMessage("🔴", "ERROR", errorMsg), message);
            }
        });
        modelStatsThread.setDaemon(true);
        modelStatsThread.start();
    }

    /**
     * Handles /predict command (admin-only) - starts the hall/player/hall/player wizard.
     */
    private void handlePredictCommand(JsonObject message) {
        try {
            JsonObject from = message.getAsJsonObject("from");
            String userId = from.get("id").getAsString();

            com.calplus.ihrgstats.telegrambot.commands.CommandPredict predictCommand =
                new com.calplus.ihrgstats.telegrambot.commands.CommandPredict();

            com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse response =
                predictCommand.handleCommand(userId);

            if (response.buttonConfig != null) {
                sendMessageWithGenericButtons(response.message, response.buttonConfig, message);
            } else {
                sendMessageToCommandsChannel(response.message, message);
            }

        } catch (Exception e) {
            String errorMsg = "Error processing /predict command: " + e.getMessage();
            logHelper.logError(errorMsg);
            e.printStackTrace();
            sendMessageToCommandsChannel(formatStatusMessage("🔴", "ERROR", errorMsg), message);
        }
    }

    /**
     * Handles predict callback queries (admin-only wizard steps)
     */
    private void handlePredictCallback(JsonObject callbackQuery, String data, String userId) {
        runCallbackRouting(callbackQuery, "predict callback", false, message -> {
            com.calplus.ihrgstats.telegrambot.commands.CommandPredict predictCommand =
                new com.calplus.ihrgstats.telegrambot.commands.CommandPredict();
            com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse response;

            if (data.equals("predict_cancel")) {
                response = predictCommand.handleCancel(userId);
                sendMessageToCommandsChannel(response.message, message);
            } else if (data.startsWith("predict_selecthall1_")) {
                int hallId = Integer.parseInt(data.substring("predict_selecthall1_".length()));
                response = predictCommand.handleFirstHallSelection(userId, hallId);
                sendStepOrPlain(response.message, response.buttonConfig, message);
            } else if (data.startsWith("predict_selectplayer1_")) {
                String player = data.substring("predict_selectplayer1_".length());
                response = predictCommand.handleFirstPlayerSelection(userId, player);
                sendStepOrPlain(response.message, response.buttonConfig, message);
            } else if (data.startsWith("predict_selecthall2_")) {
                int hallId = Integer.parseInt(data.substring("predict_selecthall2_".length()));
                response = predictCommand.handleSecondHallSelection(userId, hallId);
                sendStepOrPlain(response.message, response.buttonConfig, message);
            } else if (data.startsWith("predict_selectplayer2_")) {
                String player = data.substring("predict_selectplayer2_".length());
                response = predictCommand.handleSecondPlayerSelection(userId, player);
                sendLongMessageToCommandsChannel(response.message, message);
            }
        });
    }

    /**
     * Handles /lineup command (admin-only) - starts the opponent-hall picker.
     */
    private void handleLineupCommand(JsonObject message) {
        try {
            JsonObject from = message.getAsJsonObject("from");
            String userId = from.get("id").getAsString();

            com.calplus.ihrgstats.telegrambot.commands.CommandLineup lineupCommand =
                new com.calplus.ihrgstats.telegrambot.commands.CommandLineup();

            com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse response =
                lineupCommand.handleCommand(userId);

            if (response.buttonConfig != null) {
                sendMessageWithGenericButtons(response.message, response.buttonConfig, message);
            } else {
                sendMessageToCommandsChannel(response.message, message);
            }

        } catch (Exception e) {
            String errorMsg = "Error processing /lineup command: " + e.getMessage();
            logHelper.logError(errorMsg);
            e.printStackTrace();
            sendMessageToCommandsChannel(formatStatusMessage("🔴", "ERROR", errorMsg), message);
        }
    }

    /**
     * Handles lineup callback queries (admin-only). Cancel is instant; the
     * actual optimization is a heavier combinatorial search, so it runs on
     * a background thread (like /recalculate) so the polling loop stays free.
     */
    private void handleLineupCallback(JsonObject callbackQuery, String data, String userId) {
        try {
            com.calplus.ihrgstats.telegrambot.commands.CommandLineup lineupCommand =
                new com.calplus.ihrgstats.telegrambot.commands.CommandLineup();

            JsonObject message = callbackQuery.has("message") ? callbackQuery.getAsJsonObject("message") : null;
            if (message == null) {
                return;
            }

            JsonObject chat = message.getAsJsonObject("chat");
            String chatId = chat.get("id").getAsString();
            String messageId = message.get("message_id").getAsString();
            removeInlineKeyboard(chatId, messageId);

            if (data.equals("lineup_cancel")) {
                com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse response = lineupCommand.handleCancel(userId);
                sendMessageToCommandsChannel(response.message, message);
                return;
            }

            if (data.startsWith("lineup_selectopponent_")) {
                int opponentHallId = Integer.parseInt(data.substring("lineup_selectopponent_".length()));
                Thread lineupThread = new Thread(() -> {
                    try {
                        com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse response =
                            lineupCommand.handleOpponentHallSelection(userId, opponentHallId);
                        sendLongMessageToCommandsChannel(response.message, message);
                    } catch (Exception e) {
                        String errorMsg = "Error computing /lineup: " + e.getMessage();
                        logHelper.logError(errorMsg);
                        e.printStackTrace();
                    }
                });
                lineupThread.setDaemon(true);
                lineupThread.start();
            }

        } catch (Exception e) {
            String errorMsg = "Error processing lineup callback: " + e.getMessage();
            logHelper.logError(errorMsg);
            e.printStackTrace();
        }
    }

    /**
     * Handles help callback queries
     */
    private void handleHelpCallback(JsonObject callbackQuery, String data, String userId) {
        runCallbackRouting(callbackQuery, "help callback", false, message -> {
            com.calplus.ihrgstats.telegrambot.commands.CommandHelp helpCommand =
                new com.calplus.ihrgstats.telegrambot.commands.CommandHelp();
            com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse response;

            if (data.equals("help_cancel")) {
                response = helpCommand.handleCancel(userId);
                sendMessageToCommandsChannel(response.message, message);
            } else if (data.equals("help_back")) {
                // Both help menus carry a "🔙 Back" button with this
                // callback; it previously matched no branch here, so
                // clicking it stripped the keyboard (the shared scaffold
                // does that before routing) and then did nothing - the user
                // was stranded with no menu. Re-send the main help menu,
                // same as a fresh /help.
                response = helpCommand.handleBack(userId);
                sendMessageWithGenericButtons(response.message, response.buttonConfig, message);
            } else if (data.startsWith("help_category_")) {
                String category = data.substring("help_category_".length());
                response = helpCommand.handleCategorySelection(userId, category);
                // Category text falls back to the CHUNKED sender - help pages can exceed one message.
                if (response.buttonConfig != null) {
                    sendMessageWithGenericButtons(response.message, response.buttonConfig, message);
                } else {
                    sendLongMessageToCommandsChannel(response.message, message);
                }
            } else if (data.startsWith("help_cmd_")) {
                String command = data.substring("help_cmd_".length());
                response = helpCommand.handleCommandDetail(userId, command);
                sendLongMessageToCommandsChannel(response.message, message);
            } else if (data.startsWith("help_filetype_")) {
                String fileType = data.substring("help_filetype_".length());
                response = helpCommand.handleFileTypeSelection(userId, fileType);
                sendLongMessageToCommandsChannel(response.message, message);
            }
        });
    }

    /**
     * Handles info player callback queries. The button-removal prefix stays
     * synchronous; the actual generation runs on a background thread - see
     * {@link #handleCompareHallsCallback} for why.
     */
    private void handleInfoPlayerCallback(JsonObject callbackQuery, String data, String userId) {
        runCallbackRouting(callbackQuery, "info player callback", true, message -> {
            com.calplus.ihrgstats.telegrambot.commands.CommandInfoPlayer infoCommand =
                new com.calplus.ihrgstats.telegrambot.commands.CommandInfoPlayer();
            com.calplus.ihrgstats.telegrambot.commands.CommandInfoPlayer.InfoResponse response;

            if (data.equals("infoplayer_cancel")) {
                response = infoCommand.handleCancel(userId);
                sendMessageToCommandsChannel(response.message, message);
            } else if (data.startsWith("infoplayer_hall_")) {
                int hallId = Integer.parseInt(data.substring("infoplayer_hall_".length()));
                response = infoCommand.handleHallSelection(userId, hallId);
                sendStepOrPlain(response.message, response.buttonConfig, message);
            } else if (data.startsWith("infoplayer_player_")) {
                String player = data.substring("infoplayer_player_".length());
                response = infoCommand.handlePlayerSelection(userId, player);
                sendStepOrPlain(response.message, response.buttonConfig, message);
            } else if (data.startsWith("infoplayer_round_")) {
                String round = data.substring("infoplayer_round_".length());
                response = infoCommand.handleRoundSelection(userId, round);
                sendReportWithImage(response.message, response.imagePath, message);
            }
        });
    }

    /**
     * Handles info hall callback queries. The button-removal prefix stays
     * synchronous; the actual generation runs on a background thread - see
     * {@link #handleCompareHallsCallback} for why.
     */
    private void handleInfoHallCallback(JsonObject callbackQuery, String data, String userId) {
        runCallbackRouting(callbackQuery, "info hall callback", true, message -> {
            com.calplus.ihrgstats.telegrambot.commands.CommandInfoHall infoCommand =
                new com.calplus.ihrgstats.telegrambot.commands.CommandInfoHall();
            com.calplus.ihrgstats.telegrambot.commands.CommandInfoHall.InfoResponse response;

            if (data.equals("infohall_cancel")) {
                response = infoCommand.handleCancel(userId);
                sendMessageToCommandsChannel(response.message, message);
            } else if (data.startsWith("infohall_hall_")) {
                int hallId = Integer.parseInt(data.substring("infohall_hall_".length()));
                response = infoCommand.handleHallSelection(userId, hallId);
                sendStepOrPlain(response.message, response.buttonConfig, message);
            } else if (data.startsWith("infohall_round_")) {
                String round = data.substring("infohall_round_".length());
                response = infoCommand.handleRoundSelection(userId, round);
                sendReportWithImage(response.message, response.imagePath, message);
            }
        });
    }

    /**
     * Handles info match callback queries. The button-removal prefix stays
     * synchronous; the actual generation runs on a background thread - see
     * {@link #handleCompareHallsCallback} for why.
     */
    private void handleInfoMatchCallback(JsonObject callbackQuery, String data, String userId) {
        runCallbackRouting(callbackQuery, "info match callback", true, message -> {
            com.calplus.ihrgstats.telegrambot.commands.CommandInfoMatch infoCommand =
                new com.calplus.ihrgstats.telegrambot.commands.CommandInfoMatch();

            if (data.equals("infomatch_cancel")) {
                sendMessageToCommandsChannel(infoCommand.handleCancel(userId).message, message);
            } else if (data.startsWith("infomatch_round_")) {
                String round = data.substring("infomatch_round_".length());
                com.calplus.ihrgstats.telegrambot.commands.CommandInfoMatch.MatchResponse response =
                    infoCommand.handleRoundSelection(userId, round);
                sendReportWithImage(response.message, response.imagePath, message);
            }
        });
    }

    /**
     * Handles info match hall callback queries. The button-removal prefix
     * stays synchronous; the actual generation runs on a background thread -
     * see {@link #handleCompareHallsCallback} for why.
     */
    private void handleInfoMatchHallCallback(JsonObject callbackQuery, String data, String userId) {
        runCallbackRouting(callbackQuery, "info match hall callback", true, message -> {
            com.calplus.ihrgstats.telegrambot.commands.CommandInfoMatchHall infoCommand =
                new com.calplus.ihrgstats.telegrambot.commands.CommandInfoMatchHall();
            com.calplus.ihrgstats.telegrambot.commands.CommandInfoMatchHall.InfoResponse response;

            if (data.equals("infomatchhall_cancel")) {
                response = infoCommand.handleCancel(userId);
                sendMessageToCommandsChannel(response.message, message);
            } else if (data.startsWith("infomatchhall_hall_")) {
                int hallId = Integer.parseInt(data.substring("infomatchhall_hall_".length()));
                response = infoCommand.handleHallSelection(userId, hallId);
                sendStepOrPlain(response.message, response.buttonConfig, message);
            } else if (data.startsWith("infomatchhall_round_")) {
                String round = data.substring("infomatchhall_round_".length());
                response = infoCommand.handleRoundSelection(userId, round);
                sendReportWithImage(response.message, response.imagePath, message);
            }
        });
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
            
            if (isAllowAllChannelsProcessing() && originalMessage != null && originalMessage.has("chat")) {
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
                .timeout(HttpClientFactory.FILE_TRANSFER_TIMEOUT)
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
            
            if (isAllowAllChannelsProcessing() && originalMessage != null && originalMessage.has("chat")) {
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
                .timeout(HttpClientFactory.FILE_TRANSFER_TIMEOUT)
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

    private void sendMessageWithSettingsButtons(String message,
            com.calplus.ihrgstats.utils.TelegramCommandUtils.ButtonConfig buttonConfig,
            JsonObject originalMessage) {
        // Settings keyboards historically defaulted to ONE column, unlike
        // the shared 4-column default - hence the explicit fallback.
        int columnsPerRow = buttonConfig.columnsPerRow != null ? buttonConfig.columnsPerRow : 1;
        sendMessageWithColumnButtons(message, buttonConfig, originalMessage, columnsPerRow, "settings buttons message");
    }

    /**
     * Sends a message with export database buttons
     */
    private void sendMessageWithExportButtons(String message,
            com.calplus.ihrgstats.utils.TelegramCommandUtils.ButtonConfig buttonConfig,
            JsonObject originalMessage, String userId) {
        try {
            message = com.calplus.ihrgstats.utils.TelegramHtml.prepareForSending(message);

            JsonObject payload = new JsonObject();
            
            // Determine where to send
            if (isAllowAllChannelsProcessing() && originalMessage != null && originalMessage.has("chat")) {
                JsonObject chat = originalMessage.getAsJsonObject("chat");
                payload.addProperty("chat_id", chat.get("id").getAsString());
                
                if (originalMessage.has("message_thread_id")) {
                    // Telegram's API expects message_thread_id as a number -
                    // same fix as the upload/status/button senders.
                    try {
                        payload.addProperty("message_thread_id", originalMessage.get("message_thread_id").getAsInt());
                    } catch (NumberFormatException e) {
                        // Ignore if not a valid number
                    }
                }
            } else {
                String[] chatAndThread = getCommandsChatIdAndThread();
                if (chatAndThread == null || chatAndThread[0] == null) return;
                
                payload.addProperty("chat_id", chatAndThread[0]);
                if (chatAndThread[1] != null && !chatAndThread[1].isEmpty()) {
                    try {
                        payload.addProperty("message_thread_id", Integer.parseInt(chatAndThread[1]));
                    } catch (NumberFormatException e) {
                        // Ignore if not a valid number
                    }
                }
            }
            
            payload.addProperty("text", message);
            
            // Add parse_mode for HTML if message contains HTML tags
            if (message.contains("<b>") || message.contains("<i>") || message.contains("<code>") || message.contains("<pre>")) {
                payload.addProperty("parse_mode", "HTML");
            }
            // No parse_mode is set otherwise - after TelegramHtml.prepareForSending
            // (called at the top of every send method), any remaining "**"/"```"/"__"
            // is never legitimate markdown intent, only accidental content residue
            // (a stray unpaired sequence, or literal characters in a name/label).
            // Sending as plain text is always safe; the fragile legacy "Markdown"
            // parse mode used to be selected here on that same residue and could
            // fail outright on a single unpaired "*"/"_" (A11).
            
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

            sendMessagePayloadWithFallback(payload, "export buttons message");

        } catch (Exception e) {
            logHelper.logError("Error sending export buttons: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Sends a message with generic buttons (4-column layout)
     * This is a general-purpose method that can be used for any command with buttons
     */
    private void sendMessageWithGenericButtons(String message,
            com.calplus.ihrgstats.utils.TelegramCommandUtils.ButtonConfig buttonConfig,
            JsonObject originalMessage) {
        try {
            message = com.calplus.ihrgstats.utils.TelegramHtml.prepareForSending(message);

            JsonObject payload = new JsonObject();

            // Determine where to send
            if (isAllowAllChannelsProcessing() && originalMessage != null && originalMessage.has("chat")) {
                JsonObject chat = originalMessage.getAsJsonObject("chat");
                payload.addProperty("chat_id", chat.get("id").getAsString());

                if (originalMessage.has("message_thread_id")) {
                    // Telegram's API expects message_thread_id as a number -
                    // same fix as the upload/status/button senders.
                    try {
                        payload.addProperty("message_thread_id", originalMessage.get("message_thread_id").getAsInt());
                    } catch (NumberFormatException e) {
                        // Ignore if not a valid number
                    }
                }
            } else {
                String[] chatAndThread = getCommandsChatIdAndThread();
                if (chatAndThread == null || chatAndThread[0] == null) return;

                payload.addProperty("chat_id", chatAndThread[0]);
                if (chatAndThread[1] != null && !chatAndThread[1].isEmpty()) {
                    try {
                        payload.addProperty("message_thread_id", Integer.parseInt(chatAndThread[1]));
                    } catch (NumberFormatException e) {
                        // Ignore if not a valid number
                    }
                }
            }

            payload.addProperty("text", message);

            // Add parse_mode for HTML if message contains HTML tags
            if (message.contains("<b>") || message.contains("<i>") || message.contains("<code>") || message.contains("<pre>")) {
                payload.addProperty("parse_mode", "HTML");
            }
            // No parse_mode is set otherwise - after TelegramHtml.prepareForSending
            // (called at the top of this method), any remaining "**"/"```"/"__"/"*"
            // is never legitimate markdown intent, only accidental content residue.
            // Sending as plain text is always safe; the fragile legacy "Markdown"
            // parse mode used to be selected here on that same residue and could
            // fail outright on a single unpaired "*"/"_" (A11).

            // Create inline keyboard with configurable columns per row
            JsonObject replyMarkup = new JsonObject();
            JsonArray keyboard = new JsonArray();
            
            int columnsPerRow = buttonConfig.columnsPerRow != null ? buttonConfig.columnsPerRow : 4;
            JsonArray currentRow = new JsonArray();
            
            for (int i = 0; i < buttonConfig.labels.length; i++) {
                String label = buttonConfig.labels[i];
                String callback = buttonConfig.callbacks[i];
                
                JsonObject button = new JsonObject();
                button.addProperty("text", label);
                button.addProperty("callback_data", callback);
                
                // Check if this button should be on its own row (actions like Cancel, Back)
                boolean isActionButton = label.contains("❌") || label.contains("Cancel") || 
                                        label.contains("Back") || callback.endsWith("_cancel") || 
                                        callback.endsWith("_back");
                
                if (isActionButton) {
                    // Add current row if it has buttons
                    if (currentRow.size() > 0) {
                        keyboard.add(currentRow);
                        currentRow = new JsonArray();
                    }
                    // Add action button on its own row
                    JsonArray singleRow = new JsonArray();
                    singleRow.add(button);
                    keyboard.add(singleRow);
                } else {
                    currentRow.add(button);
                    
                    // Add row when we reach columnsPerRow
                    if (currentRow.size() >= columnsPerRow) {
                        keyboard.add(currentRow);
                        currentRow = new JsonArray();
                    }
                }
            }
            
            // Add any remaining buttons
            if (currentRow.size() > 0) {
                keyboard.add(currentRow);
            }
            
            replyMarkup.add("inline_keyboard", keyboard);
            payload.add("reply_markup", replyMarkup);

            sendMessagePayloadWithFallback(payload, "generic buttons message");

        } catch (Exception e) {
            logHelper.logError("Error sending generic buttons: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Shared implementation for every plain N-column button keyboard sender
     * (compare halls/players, rank players/halls) - these were four
     * byte-identical ~70-line copies differing only in their log-context
     * label. Buttons are chunked into rows of {@code columnsPerRow} with no
     * special-casing (unlike {@link #sendMessageWithGenericButtons}, which
     * deliberately puts Cancel/Back on their own row - that layout
     * difference is why the two senders stay separate).
     */
    private void sendMessageWithColumnButtons(String message,
            com.calplus.ihrgstats.utils.TelegramCommandUtils.ButtonConfig buttonConfig,
            JsonObject originalMessage, int columnsPerRow, String context) {
        try {
            message = com.calplus.ihrgstats.utils.TelegramHtml.prepareForSending(message);

            JsonObject payload = new JsonObject();

            // Determine where to send
            if (isAllowAllChannelsProcessing() && originalMessage != null && originalMessage.has("chat")) {
                JsonObject chat = originalMessage.getAsJsonObject("chat");
                payload.addProperty("chat_id", chat.get("id").getAsString());

                if (originalMessage.has("message_thread_id")) {
                    // Telegram's API expects message_thread_id as a number -
                    // same fix as the upload/status/button senders.
                    try {
                        payload.addProperty("message_thread_id", originalMessage.get("message_thread_id").getAsInt());
                    } catch (NumberFormatException e) {
                        // Ignore if not a valid number
                    }
                }
            } else {
                String[] chatAndThread = getCommandsChatIdAndThread();
                if (chatAndThread == null || chatAndThread[0] == null) return;

                payload.addProperty("chat_id", chatAndThread[0]);
                if (chatAndThread[1] != null && !chatAndThread[1].isEmpty()) {
                    try {
                        payload.addProperty("message_thread_id", Integer.parseInt(chatAndThread[1]));
                    } catch (NumberFormatException e) {
                        // Ignore if not a valid number
                    }
                }
            }

            payload.addProperty("text", message);

            // Add parse_mode for HTML if message contains HTML tags
            if (message.contains("<b>") || message.contains("<i>") || message.contains("<code>") || message.contains("<pre>")) {
                payload.addProperty("parse_mode", "HTML");
            }
            // No parse_mode is set otherwise - after TelegramHtml.prepareForSending
            // (called at the top of every send method), any remaining "**"/"```"/"__"
            // is never legitimate markdown intent, only accidental content residue
            // (a stray unpaired sequence, or literal characters in a name/label).
            // Sending as plain text is always safe; the fragile legacy "Markdown"
            // parse mode used to be selected here on that same residue and could
            // fail outright on a single unpaired "*"/"_" (A11).

            JsonObject replyMarkup = new JsonObject();
            JsonArray keyboard = new JsonArray();
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

            sendMessagePayloadWithFallback(payload, context);

        } catch (Exception e) {
            logHelper.logError("Error sending " + context + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void sendMessageWithCompareHallsButtons(String message,
            com.calplus.ihrgstats.utils.TelegramCommandUtils.ButtonConfig buttonConfig,
            JsonObject originalMessage) {
        sendMessageWithColumnButtons(message, buttonConfig, originalMessage, 4, "compare halls buttons message");
    }

    private void sendMessageWithComparePlayersButtons(String message,
            com.calplus.ihrgstats.utils.TelegramCommandUtils.ButtonConfig buttonConfig,
            JsonObject originalMessage) {
        sendMessageWithComparePlayersButtons(message, buttonConfig, originalMessage, 4);
    }

    private void sendMessageWithComparePlayersButtons(String message,
            com.calplus.ihrgstats.utils.TelegramCommandUtils.ButtonConfig buttonConfig,
            JsonObject originalMessage, int columnsPerRow) {
        sendMessageWithColumnButtons(message, buttonConfig, originalMessage, columnsPerRow, "compare players buttons message");
    }

    private void sendMessageWithRankPlayersButtons(String message,
            com.calplus.ihrgstats.utils.TelegramCommandUtils.ButtonConfig buttonConfig,
            JsonObject originalMessage) {
        sendMessageWithColumnButtons(message, buttonConfig, originalMessage, 4, "rank players buttons message");
    }

    private void sendMessageWithRankHallsButtons(String message,
            com.calplus.ihrgstats.utils.TelegramCommandUtils.ButtonConfig buttonConfig,
            JsonObject originalMessage) {
        sendMessageWithColumnButtons(message, buttonConfig, originalMessage, 4, "rank halls buttons message");
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
                .timeout(HttpClientFactory.FILE_TRANSFER_TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(outputStream.toByteArray()))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                System.err.println("Failed to send file to user (HTTP " + response.statusCode() + "): " + response.body());
                logHelper.logError("Failed to send database file to user DM: HTTP " + response.statusCode());
            } else {
                logHelper.logSuccess("Database file sent to user DM successfully");
            }
        } catch (Exception e) {
            System.err.println("Error sending file to user: " + e.getMessage());
            logHelper.logError("Error sending database file to user: " + e.getMessage());
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
                .timeout(HttpClientFactory.REQUEST_TIMEOUT)
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
