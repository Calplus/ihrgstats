package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.discordbot.logs.DiscordLog;
import com.calplus.ihrgstats.telegrambot.listener.TelegramListener;
import com.calplus.ihrgstats.telegrambot.logs.TelegramLog;
import com.calplus.ihrgstats.utils.DatabaseHelper;
import com.calplus.ihrgstats.utils.EnvironmentManager;
import com.calplus.ihrgstats.utils.PropertyResolver;
import com.calplus.ihrgstats.utils.TimezoneHelper;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Command handler for /exportdatabase (admin-only) - the single merged
 * export command, replacing the old separate /exportplayers (curated
 * multi-sheet player workbook) and /exportdatabase (raw .db file) pair.
 * Offers two formats:
 * - Full export (.xlsx): a generic, one-sheet-per-table dump of every
 *   populated table, read directly via JDBC/sqlite_master metadata so it
 *   stays correct as the schema evolves without needing per-table code.
 * - Database file (.db): the raw SQLite file, unchanged - restoring this
 *   file into database/core/ is the recommended full-database recovery
 *   path (the old "re-import a player export" workflow no longer exists;
 *   this replaces it).
 */
public class CommandExportDatabase {
    private final DiscordLog discordLog;
    private final TelegramLog telegramLog;
    private final String adminUserId;
    private final Path dbPath;

    public CommandExportDatabase() {
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();

        this.discordLog = new DiscordLog();
        this.telegramLog = new TelegramLog();
        this.adminUserId = PropertyResolver.getProperty("telegram.admin.userId", "");
        this.dbPath = Paths.get(System.getProperty("user.dir"), "database", "core", "default.db");
    }

    public boolean isAdmin(String userId) {
        return !adminUserId.isEmpty() && adminUserId.equals(userId);
    }

    /**
     * Handles the initial /exportdatabase command - presents a choice
     * between the two export formats (no separate confirmation step; the
     * format buttons themselves double as the confirmation, since both
     * formats carry the same sensitivity warning).
     */
    public ExportResponse requestFormatChoice(String userId) {
        String userInfo = TelegramListener.formatUserInfo(userId);
        discordLog.logInfo(String.format("%s requested /exportdatabase command", userInfo));
        telegramLog.logInfo(String.format("%s requested /exportdatabase command", userInfo));

        if (!isAdmin(userId)) {
            String errorMsg = "❌ Access Denied: Only administrators can export the database.";
            discordLog.logWarning(String.format("Non-admin user %s attempted to use /exportdatabase", userId));
            telegramLog.logWarning(String.format("Non-admin user %s attempted to use /exportdatabase", userId));
            return new ExportResponse(errorMsg, null, false);
        }

        if (!Files.exists(dbPath)) {
            String errorMsg = "❌ Error: Database file not found at: " + dbPath;
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            return new ExportResponse(errorMsg, null, false);
        }

        String message = "📦 <b>Export Database</b>\n\n" +
                "Choose an export format:\n" +
                "• <b>Full export (.xlsx)</b> - every table as a spreadsheet, human-readable\n" +
                "• <b>Database file (.db)</b> - the raw SQLite file; restoring this into <code>database/core/</code> is the recommended full recovery path\n\n" +
                "⚠️ Both contain the complete dataset. Handle exports securely and do not share them publicly.\n\n" +
                "Do you want to proceed?";

        String[] buttonLabels = {"📊 Full export (.xlsx)", "🗄️ Database file (.db)", "❌ Cancel"};
        String[] buttonCallbacks = {"export_db_xlsx", "export_db_confirm", "export_db_cancel"};

        discordLog.logInfo(String.format("Sent export format choice to admin %s", userId));
        telegramLog.logInfo(String.format("Sent export format choice to admin %s", userId));

        return new ExportResponse(message, new ButtonConfig(buttonLabels, buttonCallbacks), false);
    }

    /** Raw .db file export - unchanged behavior from the original /exportdatabase. */
    public ExportResponse executeDbExport(String userId) {
        if (!isAdmin(userId)) {
            String errorMsg = "❌ Access Denied: Only administrators can export the database.";
            discordLog.logWarning(String.format("Non-admin user %s attempted to confirm export", userId));
            telegramLog.logWarning(String.format("Non-admin user %s attempted to confirm export", userId));
            return new ExportResponse(errorMsg, null, false);
        }

        discordLog.logInfo(String.format("Admin %s confirmed .db file export", userId));
        telegramLog.logInfo(String.format("Admin %s confirmed .db file export", userId));

        if (!Files.exists(dbPath)) {
            String errorMsg = "❌ Error: Database file not found at: " + dbPath;
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            return new ExportResponse(errorMsg, null, false);
        }

        try {
            String timestamp = TimezoneHelper.formatNow("yyyyMMdd_HHmmss");
            String exportFileName = "database_export_" + timestamp + ".db";
            Path tempDir = Files.createTempDirectory("db_export_");
            Path exportPath = tempDir.resolve(exportFileName);

            Files.copy(dbPath, exportPath, StandardCopyOption.REPLACE_EXISTING);

            String successMsg = "✅ Database file exported successfully!\n\n" +
                    "The database file has been sent to your Direct Message.\n\n" +
                    "Filename: " + exportFileName + "\n" +
                    "Timestamp: " + timestamp;

            discordLog.logSuccess(String.format("Database file exported successfully for admin %s", userId));
            telegramLog.logSuccess(String.format("Database file exported successfully for admin %s", userId));

            return new ExportResponse(successMsg, null, true, exportPath);

        } catch (IOException e) {
            String errorMsg = "❌ Error: Failed to export database file: " + e.getMessage();
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            return new ExportResponse(errorMsg, null, false);
        }
    }

    /**
     * Generic one-sheet-per-table .xlsx dump of the entire database. Table
     * names are enumerated from sqlite_master (never user input), so
     * interpolating them directly into the SELECT statement is safe.
     */
    public ExportResponse executeXlsxExport(String userId) {
        if (!isAdmin(userId)) {
            String errorMsg = "❌ Access Denied: Only administrators can export the database.";
            discordLog.logWarning(String.format("Non-admin user %s attempted to confirm export", userId));
            telegramLog.logWarning(String.format("Non-admin user %s attempted to confirm export", userId));
            return new ExportResponse(errorMsg, null, false);
        }

        discordLog.logInfo(String.format("Admin %s confirmed full .xlsx export", userId));
        telegramLog.logInfo(String.format("Admin %s confirmed full .xlsx export", userId));

        try (Connection conn = DatabaseHelper.getDefaultConnection();
             XSSFWorkbook workbook = new XSSFWorkbook()) {

            int tableCount = 0;
            for (String tableName : listTables(conn)) {
                if (dumpTableToSheet(conn, workbook, tableName)) {
                    tableCount++;
                }
            }

            String timestamp = TimezoneHelper.formatNow("yyyyMMdd_HHmmss");
            String filename = String.format("database_export_%s.xlsx", timestamp);
            Path tempDir = Files.createTempDirectory("db_export_");
            Path xlsxPath = tempDir.resolve(filename);
            try (FileOutputStream fos = new FileOutputStream(xlsxPath.toFile())) {
                workbook.write(fos);
            }

            String successMsg = String.format(
                    "✅ Full database export completed: %d populated tables exported to %s.",
                    tableCount, filename);
            discordLog.logSuccess(successMsg);
            telegramLog.logSuccess(successMsg);
            return new ExportResponse(successMsg, null, true, xlsxPath);

        } catch (Exception e) {
            String errorMsg = "❌ Error: Full export failed: " + e.getMessage();
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            return new ExportResponse(errorMsg, null, false);
        }
    }

    private List<String> listTables(Connection conn) throws SQLException {
        List<String> tables = new ArrayList<>();
        String sql = "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name";
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    /**
     * Dumps one table's full contents into a new sheet named after the
     * table (column headers from ResultSetMetaData). Returns false (no
     * sheet created) if the table has no rows - "every POPULATED table".
     */
    private boolean dumpTableToSheet(Connection conn, XSSFWorkbook workbook, String tableName) throws SQLException {
        String sql = "SELECT * FROM \"" + tableName + "\"";
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();

            Sheet sheet = null;
            int rowNum = 0;
            while (rs.next()) {
                if (sheet == null) {
                    sheet = workbook.createSheet(tableName);
                    Row header = sheet.createRow(rowNum++);
                    for (int c = 1; c <= columnCount; c++) {
                        header.createCell(c - 1).setCellValue(meta.getColumnLabel(c));
                    }
                }
                Row row = sheet.createRow(rowNum++);
                for (int c = 1; c <= columnCount; c++) {
                    Object value = rs.getObject(c);
                    row.createCell(c - 1).setCellValue(value != null ? value.toString() : "");
                }
            }
            if (sheet == null) {
                return false;
            }
            for (int c = 0; c < columnCount; c++) {
                sheet.autoSizeColumn(c);
            }
            return true;
        }
    }

    public String handleCancel(String userId) {
        discordLog.logInfo(String.format("Admin %s cancelled database export", userId));
        telegramLog.logInfo(String.format("Admin %s cancelled database export", userId));
        return "ℹ️ Database export cancelled.";
    }

    /** Response object for database export operations. */
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

    /** Button configuration for inline keyboard. */
    public static class ButtonConfig {
        public final String[] labels;
        public final String[] callbacks;

        public ButtonConfig(String[] labels, String[] callbacks) {
            this.labels = labels;
            this.callbacks = callbacks;
        }
    }
}
