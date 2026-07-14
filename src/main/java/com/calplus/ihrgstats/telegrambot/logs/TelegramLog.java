package com.calplus.ihrgstats.telegrambot.logs;

import com.calplus.ihrgstats.utils.TelegramHtml;
import com.calplus.ihrgstats.utils.TimezoneHelper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends log messages to a Telegram chat via the Telegram Bot API.
 * Messages are queued and processed sequentially to maintain order.
 * Reacts to Telegram rate limits (429) with retry_after delays.
 */
public class TelegramLog {
    private static final int TELEGRAM_CHARACTER_LIMIT = 4096;
    private static final long BATCH_TIMEOUT_MS = 5000; // 5 seconds
    
    private String botToken;
    private String chatId;
    private String chatIdLog; // Specific channel/topic within the group
    private String adminUserId;
    private String telegramApiUrl;
    private final BlockingQueue<QueuedMessage> messageQueue;
    private final AtomicBoolean isProcessing;
    private final HttpClient httpClient;
    private boolean telegramEnabled;
    
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

    public TelegramLog() {
        this.messageQueue = new LinkedBlockingQueue<>();
        this.isProcessing = new AtomicBoolean(false);
        this.httpClient = HttpClient.newHttpClient();
        this.telegramEnabled = loadConfig();
        this.batchBuffer = new StringBuilder();
        this.batchStartTime = 0;
        this.batchLock = new Object();
        this.infoBatchBuffer = new StringBuilder();
        this.infoBatchLock = new Object();
        
        // Add shutdown hook to ensure all messages are sent before exit
        if (telegramEnabled) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                // Send (not discard) any pending INFO messages - despite its
                // comment, flushInfoBatch() only ever cleared the buffer and
                // never sent anything, so INFO logs accumulated right up to
                // shutdown (with no later SUCCESS/WARNING/ERROR to combine
                // with) were silently lost at exactly the moment they'd be
                // most useful for diagnosing why the app was shutting down.
                String pendingInfo;
                synchronized (infoBatchLock) {
                    pendingInfo = infoBatchBuffer.length() > 0 ? infoBatchBuffer.toString() : null;
                    infoBatchBuffer.setLength(0);
                }
                if (pendingInfo != null) {
                    queueMessage(pendingInfo);
                }
                flushBatch(); // Send any pending batch messages
                flush();
            }));
        }
    }

    /**
     * Loads the Telegram bot token and chat ID from application.properties
     * @return true if Telegram logging is enabled, false otherwise
     */
    private boolean loadConfig() {
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
     * Gets the caller's filename from the stack trace
     * @return The filename of the caller
     */
    private String getCallerFilename() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();

        // Skip internal calls and find the first external caller
        for (StackTraceElement element : stackTrace) {
            String className = element.getClassName();
            String fileName = element.getFileName();

            // Skip Thread, TelegramLog (check full class path), and internal classes
            if (fileName != null && 
                !className.equals("java.lang.Thread") &&
                !className.endsWith(".TelegramLog") &&
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
        return TimezoneHelper.formatNow("yyyy-MM-dd HH:mm:ss.SSS");
    }

    /**
     * Formats a log message with emote, timestamp, filename, type, and message
     * @param emote The emote to use
     * @param type The log type (INFO, SUCCESS, ERROR, etc.)
     * @param message The message to log
     * @param filename The calling file
     * @return Formatted message
     */
    String formatMessage(String emote, String type, String message, String filename) {
        String timestamp = getTimestamp();
        // message/filename are escaped here, before entering the batch/info
        // buffers - every send in this class uses parse_mode=HTML, but
        // exception text and file paths are arbitrary content that can
        // contain "&"/"<"/">" (e.g. a SQL error, a stack trace fragment). An
        // unescaped one breaks Telegram's HTML parser and the whole message
        // silently fails to send (no fallback retry exists in this class).
        // Escaping here (not at send time) also means every length check
        // downstream (batch char-limit splitting) already measures the real
        // wire-format text, so there's no separate raw-vs-escaped mismatch.
        return String.format("%s [%s] [%s] %s: %s", emote, timestamp, TelegramHtml.escape(filename), type, TelegramHtml.escape(message));
    }

    /**
     * Sends a message to the Telegram chat
     * @param message The message to send
     * @return Retry delay in milliseconds (0 if successful, -1 if failed, >0 if rate limited)
     */
    private long sendMessage(String message) {
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
     * Processes the message queue sequentially with reactive rate limiting
     */
    private void processQueue() {
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
        
        // If Telegram is disabled, complete immediately with false
        if (!telegramEnabled) {
            future.complete(false);
            return future;
        }
        
        messageQueue.add(new QueuedMessage(message, future));
        processQueue();
        return future;
    }

    /**
     * Queues {@code content}, splitting it into multiple queued messages
     * first if it exceeds Telegram's character limit. The accumulated INFO
     * batch (unbounded - see {@link #logInfo}) gets prepended to every
     * terminal (SUCCESS/ERROR/WARNING) message via
     * {@link #combineInfoBatchWithMessage}, so the combined text can exceed
     * the limit even when neither piece alone would - sending it through
     * {@link #queueMessage} directly got a 400 from Telegram and silently
     * dropped the whole message (including the terminal log it was meant to
     * decorate). Reuses the same char-limit/newline-preferring split
     * {@link #flushBatchInternal} already applies to the separate batch buffer.
     * @return CompletableFuture that resolves to true only if every chunk sent successfully
     */
    private CompletableFuture<Boolean> queueMessageSplit(String content) {
        List<String> chunks = splitForLimit(content, TELEGRAM_CHARACTER_LIMIT);
        CompletableFuture<Boolean> result = CompletableFuture.completedFuture(true);
        for (String chunk : chunks) {
            CompletableFuture<Boolean> chunkResult = queueMessage(chunk);
            result = result.thenCombine(chunkResult, (a, b) -> a && b);
        }
        return result;
    }

    /**
     * Splits {@code content} into chunks each within {@code limit} characters,
     * preferring to break at the last newline before the limit when one
     * exists (so a chunk doesn't cut a line in half). Returns a single
     * one-element list unchanged if {@code content} is already within limit.
     * Package-private for testing.
     */
    static List<String> splitForLimit(String content, int limit) {
        List<String> chunks = new ArrayList<>();
        if (content.length() <= limit) {
            chunks.add(content);
            return chunks;
        }
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(start + limit, content.length());
            if (end < content.length()) {
                int lastNewline = content.lastIndexOf('\n', end);
                if (lastNewline > start) {
                    end = lastNewline;
                }
            }
            chunks.add(content.substring(start, end));
            start = end;
            if (start < content.length() && content.charAt(start) == '\n') {
                start++; // Skip the newline we broke at
            }
        }
        return chunks;
    }

    /**
     * Sends a log message with timestamp to the Telegram chat
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
     * Sends an error log message to the Telegram chat with admin user mention
     * @param message The error message to send
     * @return CompletableFuture that resolves to true if successful, false otherwise
     */
    public CompletableFuture<Boolean> logError(String message) {
        String filename = getCallerFilename();
        String formattedMessage = formatMessage("🔴", "ERROR", message, filename);
        
        // Add admin user mention if configured (Telegram uses @username or tg://user?id=<userid>)
        if (adminUserId != null && !adminUserId.isEmpty()) {
            // For Telegram, if adminUserId is numeric, create a mention link
            // If it's a username, use @username format
            if (adminUserId.matches("\\d+")) {
                formattedMessage = String.format("<a href=\"tg://user?id=%s\">Admin</a> %s", adminUserId, formattedMessage);
            } else {
                formattedMessage = "@" + adminUserId + " " + formattedMessage;
            }
        }
        
        System.err.println(formattedMessage);

        // Combine (not discard) any accumulated INFO messages leading up to
        // this error - previously flushInfoBatch() threw this context away
        // right when it mattered most for debugging, while SUCCESS/WARNING
        // both correctly prepend it via combineInfoBatchWithMessage below.
        String combinedMessage = combineInfoBatchWithMessage(formattedMessage);

        return queueMessageSplit(combinedMessage);
    }

    /**
     * Sends a success log message to the Telegram chat
     * @param message The success message to send
     * @return CompletableFuture that resolves to true if successful, false otherwise
     */
    public CompletableFuture<Boolean> logSuccess(String message) {
        String filename = getCallerFilename();
        String formattedMessage = formatMessage("🟢", "SUCCESS", message, filename);
        System.out.println(formattedMessage);
        
        // Combine accumulated INFO messages with success message
        String combinedMessage = combineInfoBatchWithMessage(formattedMessage);
        
        return queueMessageSplit(combinedMessage);
    }

    /**
     * Sends a warning log message to the Telegram chat
     * @param message The warning message to send
     * @return CompletableFuture that resolves to true if successful, false otherwise
     */
    public CompletableFuture<Boolean> logWarning(String message) {
        String filename = getCallerFilename();
        String formattedMessage = formatMessage("🟡", "WARNING", message, filename);
        System.out.println(formattedMessage);
        
        // Combine accumulated INFO messages with warning message
        String combinedMessage = combineInfoBatchWithMessage(formattedMessage);
        
        return queueMessageSplit(combinedMessage);
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
            if (batchBuffer.length() >= TELEGRAM_CHARACTER_LIMIT * 0.9) { // 90% threshold
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

        // Split message if it exceeds Telegram's character limit - shares
        // the same split helper queueMessageSplit uses for combined
        // INFO+terminal messages, so this logic only lives in one place.
        return queueMessageSplit(batchContent);
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
