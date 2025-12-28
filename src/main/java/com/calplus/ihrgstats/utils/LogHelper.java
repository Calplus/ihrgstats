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
        this.discordLog = new DiscordLog();
        this.telegramLog = new TelegramLog();
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
