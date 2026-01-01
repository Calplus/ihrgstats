package com.calplus.ihrgstats.utils;

import java.nio.file.Path;

/**
 * Shared utility classes for Telegram bot commands
 */
public class TelegramCommandUtils {
    
    /**
     * Selection state for tracking user interactions across commands
     */
    public static class SelectionState {
        public long timestamp;
        
        public SelectionState() {
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    /**
     * Response class containing message, image, and button configuration
     */
    public static class CommandResponse {
        public final String message;
        public final Path imagePath;
        public final ButtonConfig buttonConfig;
        
        public CommandResponse(String message, Path imagePath, ButtonConfig buttonConfig) {
            this.message = message;
            this.imagePath = imagePath;
            this.buttonConfig = buttonConfig;
        }
        
        public CommandResponse(String message, Path imagePath) {
            this(message, imagePath, null);
        }
        
        public CommandResponse(String message, ButtonConfig buttonConfig) {
            this(message, null, buttonConfig);
        }
    }
    
    /**
     * Button configuration for inline keyboards
     */
    public static class ButtonConfig {
        public final String[] labels;
        public final String[] callbacks;
        public final Integer columnsPerRow;  // null = default (4), or specific number
        
        public ButtonConfig(String[] labels, String[] callbacks) {
            this.labels = labels;
            this.callbacks = callbacks;
            this.columnsPerRow = null;  // default
        }
        
        public ButtonConfig(String[] labels, String[] callbacks, int columnsPerRow) {
            this.labels = labels;
            this.callbacks = callbacks;
            this.columnsPerRow = columnsPerRow;
        }
    }
    
    /**
     * Clean up old selection states (older than 10 minutes)
     */
    public static <T extends SelectionState> void cleanupOldStates(java.util.Map<String, T> stateMap) {
        long currentTime = System.currentTimeMillis();
        long tenMinutesAgo = currentTime - (10 * 60 * 1000);
        
        stateMap.entrySet().removeIf(entry -> entry.getValue().timestamp < tenMinutesAgo);
    }
}
