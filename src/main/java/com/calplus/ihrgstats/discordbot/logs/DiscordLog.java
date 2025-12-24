package com.calplus.ihrgstats.discordbot.logs;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends log messages to a Discord channel via the Discord bot.
 * Messages are queued and processed sequentially to maintain order.
 * Reacts to Discord rate limits (429) with retry_after delays.
 */
public class DiscordLog {
    private static final int DISCORD_CHARACTER_LIMIT = 2000;
    private static final long BATCH_TIMEOUT_MS = 5000; // 5 seconds
    
    private String botToken;
    private String channelId;
    private String adminUserId;
    private String discordApiUrl;
    private final BlockingQueue<QueuedMessage> messageQueue;
    private final AtomicBoolean isProcessing;
    private final HttpClient httpClient;
    private boolean discordEnabled;
    
    // Batch message handling
    private final StringBuilder batchBuffer;
    private long batchStartTime;
    private final Object batchLock;
    
    // INFO message batching - accumulate INFO logs until a terminal log type (SUCCESS/ERROR/WARNING)
    private final StringBuilder infoBatchBuffer;
    private final Object infoBatchLock;

    private static class QueuedMessage {
        String message;
        CompletableFuture<Boolean> future;

        QueuedMessage(String message, CompletableFuture<Boolean> future) {
            this.message = message;
            this.future = future;
        }
    }

    public DiscordLog() {
        this.messageQueue = new LinkedBlockingQueue<>();
        this.isProcessing = new AtomicBoolean(false);
        this.httpClient = HttpClient.newHttpClient();
        this.discordEnabled = loadConfig();
        this.batchBuffer = new StringBuilder();
        this.batchStartTime = 0;
        this.batchLock = new Object();
        this.infoBatchBuffer = new StringBuilder();
        this.infoBatchLock = new Object();
        
        // Add shutdown hook to ensure all messages are sent before exit
        if (discordEnabled) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                flushInfoBatch(); // Send any pending INFO messages
                flushBatch(); // Send any pending batch messages
                flush();
            }));
        }
    }

    /**
     * Loads the Discord bot token and channel ID from application.properties
     * @return true if Discord logging is enabled, false otherwise
     */
    private boolean loadConfig() {
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

    /**
     * Gets the caller's filename from the stack trace
     * @return The filename of the caller
     */
    private String getCallerFilename() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();

        // Skip internal calls and find the first external caller
        for (StackTraceElement element : stackTrace) {
            String className = element.getClassName();
            String fileName = element.getFileName();

            // Skip Thread, DiscordLog (check full class path), and internal classes
            if (fileName != null && 
                !className.equals("java.lang.Thread") &&
                !className.endsWith(".DiscordLog") &&
                !className.startsWith("java.") &&
                !className.startsWith("sun.")) {
                return fileName;
            }
        }

        return "CLI";
    }

    /**
     * Formats timestamp with milliseconds
     * @return Formatted timestamp
     */
    private String getTimestamp() {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        return now.format(formatter);
    }

    /**
     * Formats a log message with emote, timestamp, filename, type, and message
     * @param emote The emote to use
     * @param type The log type (INFO, SUCCESS, ERROR, etc.)
     * @param message The message to log
     * @param filename The calling file
     * @return Formatted message
     */
    private String formatMessage(String emote, String type, String message, String filename) {
        String timestamp = getTimestamp();
        return String.format("%s [%s] [%s] %s: %s", emote, timestamp, filename, type, message);
    }

    /**
     * Sends a message to the Discord channel
     * @param message The message to send
     * @return Retry delay in milliseconds (0 if successful, -1 if failed, >0 if rate limited)
     */
    private long sendMessage(String message) {
        try {
            String payload = String.format("{\"content\":\"%s\"}", 
                message.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t"));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(this.discordApiUrl))
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
     * Processes the message queue sequentially with reactive rate limiting
     */
    private void processQueue() {
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
                            
                            while (result > 0) {
                                // Rate limited - wait for retry_after duration
                                try {
                                    Thread.sleep(result);
                                } catch (InterruptedException e) {
                                    interrupted = true;
                                    // Continue after interrupt
                                }
                                // Retry sending the message
                                result = sendMessage(queuedMsg.message);
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
     * Waits for all queued messages to be sent
     */
    public void flush() {
        // Wait for queue to be empty and processing to finish
        while (!messageQueue.isEmpty() || isProcessing.get()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Adds a message to the queue and processes it
     * @param message The message to queue
     * @return CompletableFuture that resolves to true if successful, false otherwise
     */
    private CompletableFuture<Boolean> queueMessage(String message) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        // If Discord is disabled, complete immediately with false
        if (!discordEnabled) {
            future.complete(false);
            return future;
        }
        
        messageQueue.add(new QueuedMessage(message, future));
        processQueue();
        return future;
    }

    /**
     * Sends a log message with timestamp to the Discord channel
     * @param message The log message to send
     * @return CompletableFuture that resolves to true if successful, false otherwise
     */
    public CompletableFuture<Boolean> log(String message) {
        String filename = getCallerFilename();
        String formattedMessage = formatMessage("📝", "LOG", message, filename);
        System.out.println(formattedMessage);
        return queueMessage(formattedMessage);
    }

    /**
     * Sends an error log message to the Discord channel with admin user ping
     * @param message The error message to send
     * @return CompletableFuture that resolves to true if successful, false otherwise
     */
    public CompletableFuture<Boolean> logError(String message) {
        String filename = getCallerFilename();
        String formattedMessage = formatMessage("🔴", "ERROR", message, filename);
        
        // Add admin user ping if configured
        if (adminUserId != null && !adminUserId.isEmpty()) {
            formattedMessage = "<@" + adminUserId + "> " + formattedMessage;
        }
        
        System.err.println(formattedMessage);
        
        // Flush any accumulated INFO messages before sending error
        flushInfoBatch();
        
        return queueMessage(formattedMessage);
    }

    /**
     * Sends a success log message to the Discord channel
     * @param message The success message to send
     * @return CompletableFuture that resolves to true if successful, false otherwise
     */
    public CompletableFuture<Boolean> logSuccess(String message) {
        String filename = getCallerFilename();
        String formattedMessage = formatMessage("🟢", "SUCCESS", message, filename);
        System.out.println(formattedMessage);
        
        // Combine accumulated INFO messages with success message
        String combinedMessage = combineInfoBatchWithMessage(formattedMessage);
        
        return queueMessage(combinedMessage);
    }

    /**
     * Sends a warning log message to the Discord channel
     * @param message The warning message to send
     * @return CompletableFuture that resolves to true if successful, false otherwise
     */
    public CompletableFuture<Boolean> logWarning(String message) {
        String filename = getCallerFilename();
        String formattedMessage = formatMessage("🟡", "WARNING", message, filename);
        System.out.println(formattedMessage);
        
        // Combine accumulated INFO messages with warning message
        String combinedMessage = combineInfoBatchWithMessage(formattedMessage);
        
        return queueMessage(combinedMessage);
    }

    /**
     * Adds an info log message to the INFO batch buffer
     * INFO messages are accumulated and sent together with the next SUCCESS/ERROR/WARNING message
     * @param message The info message to add
     * @return CompletableFuture that resolves to true (immediately)
     */
    public CompletableFuture<Boolean> logInfo(String message) {
        String filename = getCallerFilename();
        String formattedMessage = formatMessage("🔵", "INFO", message, filename);
        System.out.println(formattedMessage);
        
        synchronized (infoBatchLock) {
            if (infoBatchBuffer.length() > 0) {
                infoBatchBuffer.append("\n");
            }
            infoBatchBuffer.append(formattedMessage);
        }
        
        return CompletableFuture.completedFuture(true);
    }

    /**
     * Flushes any accumulated INFO messages without sending them
     * (Used internally when error occurs to clear the buffer)
     */
    private void flushInfoBatch() {
        synchronized (infoBatchLock) {
            infoBatchBuffer.setLength(0);
        }
    }
    
    /**
     * Combines accumulated INFO messages with a terminal message (SUCCESS/ERROR/WARNING)
     * and clears the INFO buffer
     * @param terminalMessage The terminal message to append
     * @return Combined message with all INFO logs followed by the terminal message
     */
    private String combineInfoBatchWithMessage(String terminalMessage) {
        synchronized (infoBatchLock) {
            if (infoBatchBuffer.length() == 0) {
                return terminalMessage;
            }
            
            String combined = infoBatchBuffer.toString() + "\n" + terminalMessage;
            infoBatchBuffer.setLength(0);
            return combined;
        }
    }
    
    /**
     * Adds a log message to the batch buffer
     * @param message The log message to add to batch
     */
    public void batchLog(String message) {
        String filename = getCallerFilename();
        String formattedMessage = formatMessage("📝", "LOG", message, filename);
        System.out.println(formattedMessage);
        addToBatch(formattedMessage);
    }

    /**
     * Adds an info log message to the batch buffer
     * @param message The info message to add to batch
     */
    public void batchInfo(String message) {
        String filename = getCallerFilename();
        String formattedMessage = formatMessage("🔵", "INFO", message, filename);
        System.out.println(formattedMessage);
        addToBatch(formattedMessage);
    }

    /**
     * Adds a success log message to the batch buffer
     * @param message The success message to add to batch
     */
    public void batchSuccess(String message) {
        String filename = getCallerFilename();
        String formattedMessage = formatMessage("🟢", "SUCCESS", message, filename);
        System.out.println(formattedMessage);
        addToBatch(formattedMessage);
    }

    /**
     * Adds a warning log message to the batch buffer
     * @param message The warning message to add to batch
     */
    public void batchWarning(String message) {
        String filename = getCallerFilename();
        String formattedMessage = formatMessage("🟡", "WARNING", message, filename);
        System.out.println(formattedMessage);
        addToBatch(formattedMessage);
    }

    /**
     * Adds a message to the batch buffer with timeout check
     * @param formattedMessage The formatted message to add
     */
    private void addToBatch(String formattedMessage) {
        synchronized (batchLock) {
            if (batchBuffer.length() == 0) {
                batchStartTime = System.currentTimeMillis();
            }
            
            // Check if adding this message would exceed the limit or timeout
            boolean shouldFlush = false;
            
            if (batchBuffer.length() > 0) {
                batchBuffer.append("\n");
            }
            
            // Check timeout
            if (System.currentTimeMillis() - batchStartTime >= BATCH_TIMEOUT_MS) {
                shouldFlush = true;
            }
            
            batchBuffer.append(formattedMessage);
            
            // Check if we need to flush due to character limit
            if (batchBuffer.length() >= DISCORD_CHARACTER_LIMIT * 0.9) { // 90% threshold
                shouldFlush = true;
            }
            
            if (shouldFlush) {
                flushBatchInternal();
            }
        }
    }

    /**
     * Sends all batched messages immediately
     * @return CompletableFuture that resolves to true if successful, false otherwise
     */
    public CompletableFuture<Boolean> flushBatch() {
        synchronized (batchLock) {
            return flushBatchInternal();
        }
    }

    /**
     * Internal method to flush batch (must be called within synchronized block)
     */
    private CompletableFuture<Boolean> flushBatchInternal() {
        if (batchBuffer.length() == 0) {
            return CompletableFuture.completedFuture(true);
        }
        
        String batchContent = batchBuffer.toString();
        batchBuffer.setLength(0);
        batchStartTime = 0;
        
        // Split message if it exceeds Discord's character limit
        if (batchContent.length() <= DISCORD_CHARACTER_LIMIT) {
            return queueMessage(batchContent);
        } else {
            // Split into multiple messages
            CompletableFuture<Boolean> result = CompletableFuture.completedFuture(true);
            int start = 0;
            while (start < batchContent.length()) {
                int end = Math.min(start + DISCORD_CHARACTER_LIMIT, batchContent.length());
                
                // Try to break at newline if possible
                if (end < batchContent.length()) {
                    int lastNewline = batchContent.lastIndexOf('\n', end);
                    if (lastNewline > start) {
                        end = lastNewline;
                    }
                }
                
                String chunk = batchContent.substring(start, end);
                CompletableFuture<Boolean> chunkResult = queueMessage(chunk);
                result = result.thenCombine(chunkResult, (a, b) -> a && b);
                start = end;
                if (start < batchContent.length() && batchContent.charAt(start) == '\n') {
                    start++; // Skip the newline we broke at
                }
            }
            return result;
        }
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
