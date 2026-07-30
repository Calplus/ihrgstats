package com.calplus.ihrgstats.utils;

import com.calplus.ihrgstats.discordbot.logs.DiscordLog;
import com.calplus.ihrgstats.telegrambot.logs.TelegramLog;

/**
 * Helper class that wraps both Discord and Telegram logging,
 * providing a unified interface for logging operations.
 */
public class LogHelper {
    private final DiscordLog discordLog;
    private final TelegramLog telegramLog;
    
    public LogHelper() {
        this(new DiscordLog(), new TelegramLog());
    }

    /**
     * Wraps existing log instances instead of creating fresh ones - for
     * callers (e.g. TelegramListener) that also need direct access to one
     * side for deliberately asymmetric logging (a Telegram send failure is
     * logged to Discord only, etc.) while still using this helper for the
     * both-sides case.
     */
    public LogHelper(DiscordLog discordLog, TelegramLog telegramLog) {
        this.discordLog = discordLog;
        this.telegramLog = telegramLog;
    }
    
    /**
     * Log an informational message to both Discord and Telegram
     */
    public void logInfo(String message) {
        discordLog.logInfo(message);
        telegramLog.logInfo(message);
    }
    
    /**
     * Log a warning message to both Discord and Telegram
     */
    public void logWarning(String message) {
        discordLog.logWarning(message);
        telegramLog.logWarning(message);
    }
    
    /**
     * Log an error message to both Discord and Telegram
     */
    public void logError(String message) {
        discordLog.logError(message);
        telegramLog.logError(message);
    }
    
    /**
     * Log a success message to both Discord and Telegram
     */
    public void logSuccess(String message) {
        discordLog.logSuccess(message);
        telegramLog.logSuccess(message);
    }
    
    /**
     * Batch an info message to both Discord and Telegram
     */
    public void batchInfo(String message) {
        discordLog.batchInfo(message);
        telegramLog.batchInfo(message);
    }
    
    /**
     * Flush batched messages for both Discord and Telegram
     */
    public void flushBatch() {
        discordLog.flushBatch();
        telegramLog.flushBatch();
    }

    /**
     * Final flush of any queued messages on both platforms (shutdown path)
     */
    public void flush() {
        discordLog.flush();
        telegramLog.flush();
    }
    
    /**
     * Get the Discord log instance for direct access if needed
     */
    public DiscordLog getDiscordLog() {
        return discordLog;
    }
    
    /**
     * Get the Telegram log instance for direct access if needed
     */
    public TelegramLog getTelegramLog() {
        return telegramLog;
    }
}
