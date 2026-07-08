package com.calplus.ihrgstats.utils;

/**
 * Application-wide constants to eliminate duplication across classes.
 */
public final class Constants {
    
    // Prevent instantiation
    private Constants() {
        throw new UnsupportedOperationException("Constants class cannot be instantiated");
    }
    
    /**
     * Base ELO rating for new players.
     */
    public static final int BASE_ELO = 1000;
    
    /**
     * Database-related constants
     */
    public static final class Database {
        private Database() {}
        
        public static final String DEFAULT_DB_NAME = "default.db";
        public static final String DB_DIR_PATH = "database/core";
    }
    
    /**
     * Player validation constants
     */
    public static final class Validation {
        private Validation() {}
        
        public static final int MAX_PLAYERS_PER_HALL = 5;
    }
}
