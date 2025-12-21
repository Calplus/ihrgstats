package com.calplus.ihrgstats;

import com.calplus.ihrgstats.discordbot.logs.DiscordLog;
import com.calplus.ihrgstats.telegrambot.listener.TelegramListener;
import com.calplus.ihrgstats.telegrambot.logs.TelegramLog;
import com.calplus.ihrgstats.utils.EnvironmentManager;

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
}