package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.discordbot.logs.DiscordLog;
import com.calplus.ihrgstats.telegrambot.logs.TelegramLog;
import com.calplus.ihrgstats.utils.EnvironmentManager;
import com.calplus.ihrgstats.utils.PropertyResolver;

import java.io.IOException;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Command handler for /exportdatabase command.
 * Exports the current database file and sends it to the requesting admin's DM.
 */
public class CommandExportDatabase {
    private final DiscordLog discordLog;
    private final TelegramLog telegramLog;
    private final String adminUserId;
    private final Path dbPath;

    public CommandExportDatabase() {
        // Load environment variables
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();
        
        this.discordLog = new DiscordLog();
        this.telegramLog = new TelegramLog();
        this.adminUserId = PropertyResolver.getProperty("telegram.admin.userId", "");
        this.dbPath = Paths.get(System.getProperty("user.dir"), "database", "core", "default.db");
    }

    /**
     * Checks if a user is an admin
     * @param userId The user ID to check
     * @return true if user is admin, false otherwise
     */
    public boolean isAdmin(String userId) {
        return !adminUserId.isEmpty() && adminUserId.equals(userId);
    }

    /**
     * Handles the initial /exportdatabase command (sends confirmation request)
     * @param userId The user ID who issued the command
     * @return ExportResponse containing the confirmation message and buttons
     */
    public ExportResponse requestConfirmation(String userId) {
        discordLog.logInfo(String.format("User %s requested /exportdatabase command", userId));
        telegramLog.logInfo(String.format("User %s requested /exportdatabase command", userId));

        // Check admin authorization
        if (!isAdmin(userId)) {
            String errorMsg = "❌ Access Denied: Only administrators can export the database.";
            discordLog.logWarning(String.format("Non-admin user %s attempted to use /exportdatabase", userId));
            telegramLog.logWarning(String.format("Non-admin user %s attempted to use /exportdatabase", userId));
            return new ExportResponse(errorMsg, null, false);
        }

        // Check if database exists
        if (!Files.exists(dbPath)) {
            String errorMsg = "❌ Error: Database file not found at: " + dbPath.toString();
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            return new ExportResponse(errorMsg, null, false);
        }

        // Build confirmation message
        String message = "⚠️ **Database Export Confirmation**\n\n" +
                        "You are about to export the entire database file.\n\n" +
                        "**Database:** `default.db`\n" +
                        "**Location:** `database/core/`\n\n" +
                        "The database file will be sent to your Direct Message.\n\n" +
                        "⚠️ **Warning:** The database contains sensitive data. " +
                        "Handle it securely and do not share it publicly.\n\n" +
                        "Do you want to proceed?";

        String[] buttonLabels = {"✅ Yes, Export Database", "❌ Cancel"};
        String[] buttonCallbacks = {"export_db_confirm", "export_db_cancel"};

        discordLog.logInfo(String.format("Sent database export confirmation to admin %s", userId));
        telegramLog.logInfo(String.format("Sent database export confirmation to admin %s", userId));

        return new ExportResponse(message, new ButtonConfig(buttonLabels, buttonCallbacks), false);
    }

    /**
     * Handles the database export after confirmation
     * @param userId The user ID who confirmed
     * @return ExportResponse containing the result and database file path if successful
     */
    public ExportResponse executeExport(String userId) {
        // Check admin authorization again
        if (!isAdmin(userId)) {
            String errorMsg = "❌ Access Denied: Only administrators can export the database.";
            discordLog.logWarning(String.format("Non-admin user %s attempted to confirm export", userId));
            telegramLog.logWarning(String.format("Non-admin user %s attempted to confirm export", userId));
            return new ExportResponse(errorMsg, null, false);
        }

        discordLog.logInfo(String.format("Admin %s confirmed database export", userId));
        telegramLog.logInfo(String.format("Admin %s confirmed database export", userId));

        // Check if database exists
        if (!Files.exists(dbPath)) {
            String errorMsg = "❌ Error: Database file not found at: " + dbPath.toString();
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            return new ExportResponse(errorMsg, null, false);
        }

        try {
            // Create a timestamped copy in temp directory
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String exportFileName = "database_export_" + timestamp + ".db";
            Path tempDir = Files.createTempDirectory("db_export_");
            Path exportPath = tempDir.resolve(exportFileName);

            // Copy database file
            Files.copy(dbPath, exportPath, StandardCopyOption.REPLACE_EXISTING);

            String successMsg = "✅ Database exported successfully!\n\n" +
                              "The database file has been sent to your Direct Message.\n\n" +
                              "Filename: " + exportFileName + "\n" +
                              "Timestamp: " + timestamp;

            discordLog.logSuccess(String.format("Database exported successfully for admin %s", userId));
            telegramLog.logSuccess(String.format("Database exported successfully for admin %s", userId));

            return new ExportResponse(successMsg, null, true, exportPath);

        } catch (IOException e) {
            String errorMsg = "❌ Error: Failed to export database: " + e.getMessage();
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            return new ExportResponse(errorMsg, null, false);
        }
    }

    /**
     * Handles the cancellation of database export
     * @param userId The user ID who cancelled
     * @return Response message
     */
    public String handleCancel(String userId) {
        discordLog.logInfo(String.format("Admin %s cancelled database export", userId));
        telegramLog.logInfo(String.format("Admin %s cancelled database export", userId));
        return "ℹ️ Database export cancelled.";
    }

    /**
     * Response object for database export operations
     */
    public static class ExportResponse {
        public final String message;
        public final ButtonConfig buttons;
        public final boolean success;
        public final Path exportedFilePath;

        public ExportResponse(String message, ButtonConfig buttons, boolean success) {
            this(message, buttons, success, null);
        }

        public ExportResponse(String message, ButtonConfig buttons, boolean success, Path exportedFilePath) {
            this.message = message;
            this.buttons = buttons;
            this.success = success;
            this.exportedFilePath = exportedFilePath;
        }
    }

    /**
     * Button configuration for inline keyboard
     */
    public static class ButtonConfig {
        public final String[] labels;
        public final String[] callbacks;

        public ButtonConfig(String[] labels, String[] callbacks) {
            this.labels = labels;
            this.callbacks = callbacks;
        }
    }
}
