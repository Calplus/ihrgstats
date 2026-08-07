package com.calplus.ihrgstats.utils;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Platform-neutral core shared by TelegramLog and DiscordLog (previously
 * two structural twins maintained in parallel): the ordered send queue,
 * the batch buffer, the INFO-accumulation buffer (INFO logs are held back
 * and prepended to the next SUCCESS/WARNING/ERROR so they arrive as one
 * message), char-limit splitting, and the shutdown flush hook.
 *
 * Subclasses provide everything wire-specific: config loading, line
 * formatting (incl. any platform escaping), the actual HTTP send with its
 * rate-limit parsing, the queue-retry strategy, and the admin mention
 * format for errors.
 */
public abstract class ChannelLog {
    protected static final long BATCH_TIMEOUT_MS = 5000; // 5 seconds

    protected final int characterLimit;
    protected final HttpClient httpClient;
    protected final BlockingQueue<QueuedMessage> messageQueue;
    protected final AtomicBoolean isProcessing;
    protected final boolean enabled;

    // Batch message handling
    private final StringBuilder batchBuffer;
    private long batchStartTime;
    private final Object batchLock;

    // INFO message batching - accumulate INFO logs until a terminal log type (SUCCESS/ERROR/WARNING)
    private final StringBuilder infoBatchBuffer;
    private final Object infoBatchLock;

    protected static class QueuedMessage {
        public final String message;
        public final CompletableFuture<Boolean> future;

        public QueuedMessage(String message, CompletableFuture<Boolean> future) {
            this.message = message;
            this.future = future;
        }
    }

    protected ChannelLog(int characterLimit) {
        this.characterLimit = characterLimit;
        this.messageQueue = new LinkedBlockingQueue<>();
        this.isProcessing = new AtomicBoolean(false);
        this.httpClient = HttpClientFactory.newClient();
        // loadConfig() is the subclass's - it assigns the subclass's own
        // token/channel fields (declared without initializers, so nothing
        // re-runs after this constructor to overwrite them).
        this.enabled = loadConfig();
        this.batchBuffer = new StringBuilder();
        this.batchStartTime = 0;
        this.batchLock = new Object();
        this.infoBatchBuffer = new StringBuilder();
        this.infoBatchLock = new Object();

        // Add shutdown hook to ensure all messages are sent before exit
        if (enabled) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                // Send (not discard) any pending INFO messages - INFO logs
                // accumulated right up to shutdown (with no later
                // SUCCESS/WARNING/ERROR to combine with) would otherwise be
                // silently lost at exactly the moment they'd be most useful
                // for diagnosing why the app was shutting down.
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

    /** Loads platform config; returns whether remote logging is enabled. Runs during construction. */
    protected abstract boolean loadConfig();

    /** Formats one log line with emote, timestamp, filename and type - subclasses apply any platform escaping. */
    protected abstract String formatMessage(String emote, String type, String message, String filename);

    /**
     * Sends one message over the wire.
     * @return Retry delay in milliseconds (0 if successful, -1 if failed, >0 if rate limited)
     */
    protected abstract long sendMessage(String message);

    /**
     * Drains the queue per the platform's retry strategy (the two platforms
     * deliberately differ: Telegram re-queues a rate-limited message and
     * sleeps; Discord retries the same message inline).
     */
    protected abstract void processQueue();

    /** Decorates a formatted ERROR line with the platform's admin mention, when configured. */
    protected abstract String decorateError(String formattedMessage);

    /**
     * Gets the caller's filename from the stack trace, skipping the log
     * classes themselves (so a log call routed through LogHelper is
     * attributed to LogHelper.java, matching historical behavior).
     */
    protected String getCallerFilename() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();

        for (StackTraceElement element : stackTrace) {
            String className = element.getClassName();
            String fileName = element.getFileName();

            if (fileName != null &&
                !className.equals("java.lang.Thread") &&
                !className.equals(ChannelLog.class.getName()) &&
                !className.equals(getClass().getName()) &&
                !className.startsWith("java.") &&
                !className.startsWith("sun.")) {
                return fileName;
            }
        }

        return "CLI";
    }

    /** Formats timestamp with milliseconds. */
    protected String getTimestamp() {
        return TimezoneHelper.formatNow("yyyy-MM-dd HH:mm:ss.SSS");
    }

    /** Waits for all queued messages to be sent. */
    public void flush() {
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
     * Adds a message to the queue and processes it.
     * @return CompletableFuture that resolves to true if successful, false otherwise
     */
    protected CompletableFuture<Boolean> queueMessage(String message) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        if (!enabled) {
            future.complete(false);
            return future;
        }

        messageQueue.add(new QueuedMessage(message, future));
        processQueue();
        return future;
    }

    /**
     * Queues {@code content}, splitting it into multiple queued messages
     * first if it exceeds the platform's character limit. The accumulated
     * INFO batch (unbounded - see {@link #logInfo}) gets prepended to every
     * terminal (SUCCESS/ERROR/WARNING) message, so the combined text can
     * exceed the limit even when neither piece alone would - an over-limit
     * single send silently dropped the whole message.
     * @return CompletableFuture that resolves to true only if every chunk sent successfully
     */
    protected CompletableFuture<Boolean> queueMessageSplit(String content) {
        List<String> chunks = splitForLimit(content, characterLimit);
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
     */
    public static List<String> splitForLimit(String content, int limit) {
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

    /** Sends a plain log message. */
    public CompletableFuture<Boolean> log(String message) {
        String filename = getCallerFilename();
        String formattedMessage = formatMessage("📝", "LOG", message, filename);
        System.out.println(formattedMessage);
        return queueMessage(formattedMessage);
    }

    /** Sends an error log message, decorated with the platform's admin mention when configured. */
    public CompletableFuture<Boolean> logError(String message) {
        String filename = getCallerFilename();
        String formattedMessage = decorateError(formatMessage("🔴", "ERROR", message, filename));
        System.err.println(formattedMessage);

        // Combine (not discard) any accumulated INFO messages leading up to
        // this error - that context matters most for debugging.
        return queueMessageSplit(combineInfoBatchWithMessage(formattedMessage));
    }

    /** Sends a success log message, with any accumulated INFO messages prepended. */
    public CompletableFuture<Boolean> logSuccess(String message) {
        String filename = getCallerFilename();
        String formattedMessage = formatMessage("🟢", "SUCCESS", message, filename);
        System.out.println(formattedMessage);
        return queueMessageSplit(combineInfoBatchWithMessage(formattedMessage));
    }

    /** Sends a warning log message, with any accumulated INFO messages prepended. */
    public CompletableFuture<Boolean> logWarning(String message) {
        String filename = getCallerFilename();
        String formattedMessage = formatMessage("🟡", "WARNING", message, filename);
        System.out.println(formattedMessage);
        return queueMessageSplit(combineInfoBatchWithMessage(formattedMessage));
    }

    /**
     * Adds an info log message to the INFO batch buffer. INFO messages are
     * accumulated and sent together with the next SUCCESS/ERROR/WARNING message.
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

    /** Adds a log message to the batch buffer. */
    public void batchLog(String message) {
        addToBatch(formatAndEcho("📝", "LOG", message));
    }

    /** Adds an info log message to the batch buffer. */
    public void batchInfo(String message) {
        addToBatch(formatAndEcho("🔵", "INFO", message));
    }

    /** Adds a success log message to the batch buffer. */
    public void batchSuccess(String message) {
        addToBatch(formatAndEcho("🟢", "SUCCESS", message));
    }

    /** Adds a warning log message to the batch buffer. */
    public void batchWarning(String message) {
        addToBatch(formatAndEcho("🟡", "WARNING", message));
    }

    private String formatAndEcho(String emote, String type, String message) {
        String formattedMessage = formatMessage(emote, type, message, getCallerFilename());
        System.out.println(formattedMessage);
        return formattedMessage;
    }

    /** Adds a message to the batch buffer, flushing on timeout or near the character limit. */
    private void addToBatch(String formattedMessage) {
        synchronized (batchLock) {
            if (batchBuffer.length() == 0) {
                batchStartTime = System.currentTimeMillis();
            }

            boolean shouldFlush = false;

            if (batchBuffer.length() > 0) {
                batchBuffer.append("\n");
            }

            if (System.currentTimeMillis() - batchStartTime >= BATCH_TIMEOUT_MS) {
                shouldFlush = true;
            }

            batchBuffer.append(formattedMessage);

            if (batchBuffer.length() >= characterLimit * 0.9) { // 90% threshold
                shouldFlush = true;
            }

            if (shouldFlush) {
                flushBatchInternal();
            }
        }
    }

    /** Sends all batched messages immediately. */
    public CompletableFuture<Boolean> flushBatch() {
        synchronized (batchLock) {
            return flushBatchInternal();
        }
    }

    /** Internal method to flush batch (must be called within synchronized block). */
    private CompletableFuture<Boolean> flushBatchInternal() {
        if (batchBuffer.length() == 0) {
            return CompletableFuture.completedFuture(true);
        }

        String batchContent = batchBuffer.toString();
        batchBuffer.setLength(0);
        batchStartTime = 0;

        return queueMessageSplit(batchContent);
    }

    /**
     * Combines accumulated INFO messages with a terminal message
     * (SUCCESS/ERROR/WARNING) and clears the INFO buffer.
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
}
