package com.calplus.ihrgstats;

import com.calplus.ihrgstats.databasemanager.A3_Halls;
import com.calplus.ihrgstats.databasemanager.B4_Players;
import com.calplus.ihrgstats.databasemanager.D10_RatingTypes;
import com.calplus.ihrgstats.databasemanager.DatabaseSchema;
import com.calplus.ihrgstats.discordbot.logs.DiscordLog;
import com.calplus.ihrgstats.telegrambot.listener.TelegramListener;
import com.calplus.ihrgstats.telegrambot.logs.TelegramLog;
import com.calplus.ihrgstats.utils.EnvironmentManager;
import com.calplus.ihrgstats.utils.TimezoneHelper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class Main {
    // Store the launch time for the application
    public static final ZonedDateTime LAUNCH_TIME = ZonedDateTime.now(ZoneId.of("Asia/Singapore"));
    public static void main(String[] args) {
        // Initialize logging
        DiscordLog discordLog = new DiscordLog();
        TelegramLog telegramLog = new TelegramLog();
        
        // Load environment variables from .env.properties file
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();
        
        System.out.println("====================================");
        System.out.println("   IHRG Stats Application Started");
        System.out.println("====================================");
        
        discordLog.logInfo("IHRG Stats Application Started");
        telegramLog.logInfo("IHRG Stats Application Started");
        
        discordLog.batchInfo("Environment variables loaded successfully");
        telegramLog.batchInfo("Environment variables loaded successfully");
        
        // Check and initialize database if needed
        initializeDatabase(discordLog, telegramLog);
        
        // Start Telegram file listener
        discordLog.batchInfo("Initializing Telegram file listener...");
        telegramLog.batchInfo("Initializing Telegram file listener...");
        
        discordLog.flushBatch();
        telegramLog.flushBatch();
        
        try {
            TelegramListener listener = new TelegramListener();
            listener.start();
            
            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n==========================================");
                System.out.println("   IHRG Stats Application Shutting Down");
                System.out.println("==========================================");
                
                discordLog.logInfo("IHRG Stats Application shutting down...");
                telegramLog.logInfo("IHRG Stats Application shutting down...");
                
                listener.stop();
                
                discordLog.flushBatch();
                telegramLog.flushBatch();
                discordLog.flush();
                telegramLog.flush();
            }));
            
            System.out.println("\nApplication is running. Press Ctrl+C to stop.");
            System.out.println("==========================================\n");
            
            // Keep application running
            Thread.currentThread().join();
            
        } catch (Exception e) {
            String errorMsg = "Fatal error in main application: " + e.getMessage();
            System.err.println(errorMsg);
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /**
     * Ensures the database and its full schema exist. Schema creation always
     * runs (every statement is CREATE ... IF NOT EXISTS, so this is
     * idempotent) - this is what lets tables added in later versions, such
     * as player_ratings_snapshot, appear in a database created by an older
     * version.
     */
    private static void initializeDatabase(DiscordLog discordLog, TelegramLog telegramLog) {
        Path dbPath = Paths.get(System.getProperty("user.dir"), "database", "core", "default.db");
        boolean dbExisted = Files.exists(dbPath);

        if (dbExisted) {
            discordLog.batchInfo("Database already exists at: " + dbPath + " - ensuring schema is up to date");
            telegramLog.batchInfo("Database already exists at: " + dbPath + " - ensuring schema is up to date");
            System.out.println("Database found: " + dbPath + " (ensuring schema is up to date)");
        } else {
            discordLog.batchInfo("Database not found. Creating new database...");
            telegramLog.batchInfo("Database not found. Creating new database...");
            System.out.println("Database not found. Creating new database at: " + dbPath);

            discordLog.flushBatch();
            telegramLog.flushBatch();
        }

        try {
            DatabaseSchema schema = new DatabaseSchema();
            schema.createDatabase("default.db");

            if (!dbExisted) {
                discordLog.logSuccess("Database created successfully");
                telegramLog.logSuccess("Database created successfully");
                System.out.println("Database created successfully");
            }
        } catch (Exception e) {
            String errorMsg = "Failed to " + (dbExisted ? "update database schema" : "create database") + ": " + e.getMessage();
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            System.err.println(errorMsg);
            e.printStackTrace();
            System.exit(1);
        }

        seedReferenceData(discordLog, telegramLog);
    }

    /**
     * Seeds reference/lookup data (halls, the WLKOVR sentinel player,
     * rating types) needed before any round can be processed. Each
     * seedDefaults() call is idempotent, so this runs on every startup
     * regardless of whether the database file already existed.
     */
    private static void seedReferenceData(DiscordLog discordLog, TelegramLog telegramLog) {
        String now = TimezoneHelper.formatNow("yyyy-MM-dd HH:mm:ss.SSS");
        try {
            new A3_Halls().seedDefaults(now);
            new B4_Players().seedDefaults(now);
            new D10_RatingTypes().seedDefaults(now);

            discordLog.batchInfo("Reference data seeded (halls, WLKOVR sentinel, rating types)");
            telegramLog.batchInfo("Reference data seeded (halls, WLKOVR sentinel, rating types)");
            System.out.println("Reference data seeded (halls, WLKOVR sentinel, rating types)");
        } catch (Exception e) {
            String errorMsg = "Failed to seed reference data: " + e.getMessage();
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            System.err.println(errorMsg);
            e.printStackTrace();
            System.exit(1);
        }
    }
}