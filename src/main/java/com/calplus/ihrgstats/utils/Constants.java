package com.calplus.ihrgstats.utils;

import java.util.Arrays;
import java.util.List;

/**
 * Application-wide constants to eliminate duplication across classes.
 */
public final class Constants {
    
    // Prevent instantiation
    private Constants() {
        throw new UnsupportedOperationException("Constants class cannot be instantiated");
    }
    
    /**
     * Standard round sequence used throughout the application.
     * Includes regular rounds (1-6) and tournament rounds (T16, T8, T4, T2).
     */
    public static final List<String> ROUND_SEQUENCE = Arrays.asList("1", "2", "3", "4", "5", "6", "t16", "t8", "t4", "t2");
    
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
