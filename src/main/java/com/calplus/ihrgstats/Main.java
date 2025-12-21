package com.calplus.ihrgstats;

import com.calplus.ihrgstats.databasemanager.DatabaseSchema;
import com.calplus.ihrgstats.discordbot.logs.DiscordLog;
import com.calplus.ihrgstats.telegrambot.listener.TelegramListener;
import com.calplus.ihrgstats.telegrambot.logs.TelegramLog;
import com.calplus.ihrgstats.utils.EnvironmentManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Main {
    public static void main(String[] args) {
        // Initialize logging
        DiscordLog discordLog = new DiscordLog();
        TelegramLog telegramLog = new TelegramLog();
        
        // Load environment variables from .env.properties file
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();
        
        System.out.println("==========================================");
        System.out.println("   IHRG Stats Application Started");
        System.out.println("==========================================");
        
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
     * Checks if the database exists, creates it if not
     */
    private static void initializeDatabase(DiscordLog discordLog, TelegramLog telegramLog) {
        Path dbPath = Paths.get(System.getProperty("user.dir"), "database", "core", "default.db");
        
        if (Files.exists(dbPath)) {
            discordLog.batchInfo("Database already exists at: " + dbPath);
            telegramLog.batchInfo("Database already exists at: " + dbPath);
            System.out.println("Database found: " + dbPath);
        } else {
            discordLog.batchInfo("Database not found. Creating new database...");
            telegramLog.batchInfo("Database not found. Creating new database...");
            System.out.println("Database not found. Creating new database at: " + dbPath);
            
            discordLog.flushBatch();
            telegramLog.flushBatch();
            
            try {
                DatabaseSchema schema = new DatabaseSchema();
                schema.createDatabase("default.db");
                
                discordLog.logSuccess("Database created successfully");
                telegramLog.logSuccess("Database created successfully");
                System.out.println("Database created successfully");
            } catch (Exception e) {
                String errorMsg = "Failed to create database: " + e.getMessage();
                discordLog.logError(errorMsg);
                telegramLog.logError(errorMsg);
                System.err.println(errorMsg);
                e.printStackTrace();
                System.exit(1);
            }
        }
    }
}