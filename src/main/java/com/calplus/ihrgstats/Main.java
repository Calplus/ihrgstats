package com.calplus.ihrgstats;

import com.calplus.ihrgstats.databasemanager.A3_Halls;
import com.calplus.ihrgstats.databasemanager.B4_Players;
import com.calplus.ihrgstats.databasemanager.D10_RatingTypes;
import com.calplus.ihrgstats.databasemanager.DatabaseSchema;
import com.calplus.ihrgstats.databasemanager.F16_Admins;
import com.calplus.ihrgstats.telegrambot.listener.TelegramListener;
import com.calplus.ihrgstats.utils.EnvironmentManager;
import com.calplus.ihrgstats.utils.LogHelper;
import com.calplus.ihrgstats.utils.TimezoneHelper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class Main {
    // Store the launch time for the application. Self-contained: loads env
    // vars (if not already loaded) before reading the configured timezone,
    // so this is correct regardless of which class's main() actually started
    // the app - TelegramListener.java also has its own "for testing" main()
    // that never goes through THIS class's main() body, so a version that
    // depended on this class's main() having already run first (as a plain,
    // non-final field assigned inside main()) could stay permanently null
    // when started that other way, NPE-ing the first /about call. This still
    // honors settings.timezone like everything else in the app, instead of a
    // hardcoded zone (A28) - just without the null-safety regression.
    public static final ZonedDateTime LAUNCH_TIME = computeLaunchTime();

    private static ZonedDateTime computeLaunchTime() {
        EnvironmentManager.ensureSystemPropertiesLoaded();
        return TimezoneHelper.now();
    }

    public static void main(String[] args) {
        // Initialize logging
        LogHelper logHelper = new LogHelper();

        // Load environment variables from .env.properties file
        EnvironmentManager.ensureSystemPropertiesLoaded();


        System.out.println("====================================");
        System.out.println("   IHRG Stats Application Started");
        System.out.println("====================================");
        
        logHelper.logInfo("IHRG Stats Application Started");
        
        logHelper.batchInfo("Environment variables loaded successfully");
        
        // Check and initialize database if needed
        initializeDatabase(logHelper);
        
        // Start Telegram file listener
        logHelper.batchInfo("Initializing Telegram file listener...");
        
        logHelper.flushBatch();
        
        try {
            TelegramListener listener = new TelegramListener();
            listener.start();
            
            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n==========================================");
                System.out.println("   IHRG Stats Application Shutting Down");
                System.out.println("==========================================");
                
                logHelper.logInfo("IHRG Stats Application shutting down...");
                
                listener.stop();
                
                logHelper.flushBatch();
                logHelper.flush();
            }));
            
            System.out.println("\nApplication is running. Press Ctrl+C to stop.");
            System.out.println("==========================================\n");
            
            // Keep application running
            Thread.currentThread().join();
            
        } catch (Exception e) {
            String errorMsg = "Fatal error in main application: " + e.getMessage();
            System.err.println(errorMsg);
            logHelper.logError(errorMsg);
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
    private static void initializeDatabase(LogHelper logHelper) {
        Path dbPath = com.calplus.ihrgstats.utils.DatabaseHelper.getDefaultDatabasePath();
        boolean dbExisted = Files.exists(dbPath);

        if (dbExisted) {
            logHelper.batchInfo("Database already exists at: " + dbPath + " - ensuring schema is up to date");
            System.out.println("Database found: " + dbPath + " (ensuring schema is up to date)");
        } else {
            logHelper.batchInfo("Database not found. Creating new database...");
            System.out.println("Database not found. Creating new database at: " + dbPath);

            logHelper.flushBatch();
        }

        try {
            DatabaseSchema schema = new DatabaseSchema();
            schema.createDatabase("default.db");

            if (!dbExisted) {
                logHelper.logSuccess("Database created successfully");
                System.out.println("Database created successfully");
            }
        } catch (Exception e) {
            String errorMsg = "Failed to " + (dbExisted ? "update database schema" : "create database") + ": " + e.getMessage();
            logHelper.logError(errorMsg);
            System.err.println(errorMsg);
            e.printStackTrace();
            System.exit(1);
        }

        seedReferenceData(logHelper);
    }

    /**
     * Seeds reference/lookup data (halls, the WLKOVR sentinel player,
     * rating types, and the initial admin(s) from
     * telegram.admin.userId/discord.admin.userId) needed before any round
     * can be processed. Each seedDefaults() call is idempotent, so this runs
     * on every startup regardless of whether the database file already
     * existed.
     */
    private static void seedReferenceData(LogHelper logHelper) {
        String now = TimezoneHelper.formatNow("yyyy-MM-dd HH:mm:ss.SSS");
        try {
            new A3_Halls().seedDefaults(now);
            new B4_Players().seedDefaults(now);
            new D10_RatingTypes().seedDefaults(now);
            new F16_Admins().seedDefaults(now);

            logHelper.batchInfo("Reference data seeded (halls, WLKOVR sentinel, rating types, admins)");
            System.out.println("Reference data seeded (halls, WLKOVR sentinel, rating types, admins)");
        } catch (Exception e) {
            String errorMsg = "Failed to seed reference data: " + e.getMessage();
            logHelper.logError(errorMsg);
            System.err.println(errorMsg);
            e.printStackTrace();
            System.exit(1);
        }
    }
}