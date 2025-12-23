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
    private String publicChatIdStatus;
    private String publicChatIdCommands;
    private String adminUserId;
    private boolean allowNonAdminUploads;
    
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
    private static final String FILE_PROCESSING_CONFIRMATION_KEY = "file_processing";
    
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
            this.publicChatIdStatus = PropertyResolver.getProperty("telegram.devChatId.status", "");
            this.publicChatIdCommands = PropertyResolver.getProperty("telegram.publicChatId.commands", "");
            this.adminUserId = PropertyResolver.getProperty("telegram.admin.userId", "");
            this.allowNonAdminUploads = Boolean.parseBoolean(PropertyResolver.getProperty("settings.allowNonAdminUploads", "true"));
            
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

        if (publicChatId.isEmpty()) {
            String errorMsg = "Telegram publicChatId not configured. Cannot start listener.";
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            return;
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
                
                if (!update.has("message")) continue;
                JsonObject message = update.getAsJsonObject("message");
                
                // Check if it's from the right chat
                JsonObject chat = message.getAsJsonObject("chat");
                String chatId = chat.get("id").getAsString();
            
            // Check chat match
            boolean isPublicChat = chatId.equals(publicChatId);
            boolean hasThreadId = false;
            
            // If we're waiting for a confirmation, accept ANY message from the correct chat
            boolean waitingForConfirmation = pendingConfirmations.containsKey(FILE_PROCESSING_CONFIRMATION_KEY);
            
            if (waitingForConfirmation) {
                // Accept any message from the correct chat while waiting for confirmation
                hasThreadId = true;
                String msgThreadId = message.has("message_thread_id") ? message.get("message_thread_id").getAsString() : "none";
                System.out.println("[CONFIRMATION MODE] Accepting message from chat " + chatId + " with thread ID: " + msgThreadId);
            } else if (!publicChatIdFileupload.isEmpty()) {
                // Normal mode - check message_thread_id if specified
                if (message.has("message_thread_id")) {
                    String threadId = message.get("message_thread_id").getAsString();
                    if (threadId.equals(publicChatIdFileupload)) {
                        hasThreadId = true;
                    }
                }
            } else {
                hasThreadId = true; // No thread requirement
            }
            
            if (!isPublicChat || !hasThreadId) {
                if (waitingForConfirmation) {
                    System.out.println("[CONFIRMATION MODE] Rejecting message - isPublicChat: " + isPublicChat + ", hasThreadId: " + hasThreadId);
                }
                continue; // Not from target chat/thread
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
                processFile(fileId, fileName, userId);
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
    private void processFile(String fileId, String fileName, String userId) {
        fileName = fileName.toLowerCase();
        
        // Download file
        TelegramFileDownloader downloader = new TelegramFileDownloader(botToken);
        Path downloadedFile = downloader.downloadToTemp(fileId, fileName);
        
        if (downloadedFile == null) {
            String errorMsg = "Failed to download file from Telegram";
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            // Send error to upload chat
            sendMessageToUploadChat(formatStatusMessage("🔴", "ERROR", errorMsg));
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
                    sendMessageToUploadChat(msg);
                });
                
                boolean success = processor.processCappedList(downloadedFile.toString());
                
                if (!success) {
                    String errorMsg = "Failed to process cappedlist.csv";
                    discordLog.logError(errorMsg);
                    telegramLog.logError(errorMsg);
                    // Send error to upload chat
                    sendMessageToUploadChat(formatStatusMessage("🔴", "ERROR", errorMsg));
                }
                
            } else if (fileName.matches("round_[1-6t1628]+(\\d+)?\\.csv")) {
                // Extract round number
                String roundName = extractRoundName(fileName);
                
                if (roundName == null) {
                    String errorMsg = "Invalid round filename format: " + fileName;
                    discordLog.logError(errorMsg);
                    telegramLog.logError(errorMsg);
                    // Send error to upload chat
                    sendMessageToUploadChat(formatStatusMessage("🔴", "ERROR", errorMsg));
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
                
                // Set up callback to send success message to upload chat
                processor.setUploadChatCallback((msg) -> {
                    sendMessageToUploadChat(msg);
                });
                
                boolean success = processor.processRound(downloadedFile.toString(), roundName);
                
                if (!success) {
                    String errorMsg = String.format("Failed to process round_%s.csv", roundName);
                    discordLog.logError(errorMsg);
                    telegramLog.logError(errorMsg);
                    // Send error to upload chat
                    sendMessageToUploadChat(formatStatusMessage("🔴", "ERROR", errorMsg));
                }
                
            } else if (fileName.matches("playerexport_\\d{8}_\\d{6}\\.csv")) {
                // Process player import from export file
                discordLog.logInfo("Processing player import file: " + fileName);
                telegramLog.logInfo("Processing player import file: " + fileName);
                
                A1_PlayerStats processor = new A1_PlayerStats();
                
                // Set up callback to send success message to upload chat
                processor.setUploadChatCallback((msg) -> {
                    sendMessageToUploadChat(msg);
                });
                
                boolean success = processor.importPlayerExport(downloadedFile.toString());
                
                if (!success) {
                    String errorMsg = "Failed to import player export file: " + fileName;
                    discordLog.logError(errorMsg);
                    telegramLog.logError(errorMsg);
                    // Send error to upload chat
                    sendMessageToUploadChat(formatStatusMessage("🔴", "ERROR", errorMsg));
                }
                
            } else {
                String errorMsg = String.format("Unknown file type: %s. Accepted files: cappedlist.csv, round_[n].csv, playerExport_[datetime].csv", fileName);
                discordLog.logError(errorMsg);
                telegramLog.logError(errorMsg);
                // Send error to upload chat
                sendMessageToUploadChat(formatStatusMessage("🔴", "ERROR", errorMsg));
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
        // Check if message is in commands channel/thread
        boolean isCommandsChannel = false;
        if (publicChatIdCommands != null && !publicChatIdCommands.isEmpty()) {
            if (message.has("message_thread_id")) {
                String threadId = message.get("message_thread_id").getAsString();
                if (threadId.equals(publicChatIdCommands)) {
                    isCommandsChannel = true;
                }
            }
        } else {
            // If no specific commands channel, allow commands in any chat
            isCommandsChannel = true;
        }

        if (!isCommandsChannel) {
            System.out.println("Command received but not in commands channel: " + command);
            return;
        }

        // Parse command
        if (command.equalsIgnoreCase("/exportplayers")) {
            handleExportPlayersCommand(message);
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
            com.calplus.ihrgstats.telegrambot.commands.CommandExportLatestPlayerData exporter = 
                new com.calplus.ihrgstats.telegrambot.commands.CommandExportLatestPlayerData();
            
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
            
            // Build multipart request body
            java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
            java.io.PrintWriter writer = new java.io.PrintWriter(new java.io.OutputStreamWriter(outputStream, java.nio.charset.StandardCharsets.UTF_8), true);
            
            // Add chat_id
            writer.append("--" + boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"chat_id\"").append("\r\n");
            writer.append("\r\n");
            writer.append(publicChatId).append("\r\n");
            
            // Add message_thread_id if specified
            if (publicChatIdCommands != null && !publicChatIdCommands.isEmpty()) {
                writer.append("--" + boundary).append("\r\n");
                writer.append("Content-Disposition: form-data; name=\"message_thread_id\"").append("\r\n");
                writer.append("\r\n");
                writer.append(publicChatIdCommands).append("\r\n");
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
     */
    private void sendMessageToCommandsChannel(String message, JsonObject originalMessage) {
        try {
            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
            
            JsonObject payload = new JsonObject();
            payload.addProperty("chat_id", publicChatId);
            payload.addProperty("text", message);
            
            // Add thread ID if specified
            if (publicChatIdCommands != null && !publicChatIdCommands.isEmpty()) {
                payload.addProperty("message_thread_id", publicChatIdCommands);
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
        if (devChatId == null || devChatId.isEmpty()) {
            System.out.println("Dev chat ID not configured, heartbeat disabled");
            return;
        }
        
        if (publicChatIdStatus == null || publicChatIdStatus.isEmpty()) {
            System.out.println("Status thread ID not configured, heartbeat disabled");
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
     */
    private void sendMessageToStatusChat(String message) {
        try {
            if (devChatId == null || devChatId.isEmpty()) {
                System.err.println("Cannot send status message: devChatId is empty or null");
                return;
            }
            
            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
            
            JsonObject payload = new JsonObject();
            payload.addProperty("chat_id", devChatId);  // Use devChatId, not publicChatId!
            payload.addProperty("text", message);
            
            // Add status thread ID if specified (convert to integer)
            if (publicChatIdStatus != null && !publicChatIdStatus.isEmpty()) {
                try {
                    int threadId = Integer.parseInt(publicChatIdStatus);
                    payload.addProperty("message_thread_id", threadId);  // Pass as integer, not string
                } catch (NumberFormatException e) {
                    System.err.println("Invalid thread ID format: " + publicChatIdStatus + " - sending without thread ID");
                }
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
                System.out.println("Status heartbeat sent successfully to chat " + devChatId + " thread " + publicChatIdStatus);
            }
        } catch (Exception e) {
            System.err.println("Error sending status message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Sends a message to the upload chat/thread
     */
    private void sendMessageToUploadChat(String message) {
        try {
            if (publicChatId == null || publicChatId.isEmpty()) {
                System.err.println("Cannot send message: publicChatId is empty or null");
                discordLog.logWarning("Cannot send message to Telegram: publicChatId not configured");
                return;
            }
            
            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
            
            JsonObject payload = new JsonObject();
            payload.addProperty("chat_id", publicChatId); // Use main chat ID, not thread ID
            payload.addProperty("text", message);
            
            // Add thread ID if specified
            if (publicChatIdFileupload != null && !publicChatIdFileupload.isEmpty()) {
                payload.addProperty("message_thread_id", publicChatIdFileupload);
                System.out.println("Sending to chat " + publicChatId + " with thread ID " + publicChatIdFileupload);
            } else {
                System.out.println("Sending to chat " + publicChatId + " without thread ID");
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
