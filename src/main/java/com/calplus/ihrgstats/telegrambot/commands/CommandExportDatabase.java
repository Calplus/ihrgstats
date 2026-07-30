package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.telegrambot.listener.TelegramListener;
import com.calplus.ihrgstats.utils.DatabaseHelper;
import com.calplus.ihrgstats.utils.EnvironmentManager;
import com.calplus.ihrgstats.utils.LogHelper;
import com.calplus.ihrgstats.utils.OutputPaths;
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
import java.util.UUID;

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
    private final LogHelper logHelper;
    private final com.calplus.ihrgstats.databasemanager.F16_Admins admins = new com.calplus.ihrgstats.databasemanager.F16_Admins();
    private final Path dbPath;

    public CommandExportDatabase() {
        EnvironmentManager.ensureSystemPropertiesLoaded();

        this.logHelper = new LogHelper();
        this.dbPath = DatabaseHelper.getDefaultDatabasePath();
    }

    /** Fails closed (denies) on a database error rather than risking a false "admin". */
    public boolean isAdmin(String userId) {
        try {
            return admins.isAdmin(com.calplus.ihrgstats.databasemanager.F16_Admins.PLATFORM_TELEGRAM, userId);
        } catch (java.sql.SQLException e) {
            logHelper.logError("Database error checking admin status: " + e.getMessage());
            return false;
        }
    }

    /**
     * Handles the initial /exportdatabase command - presents a choice
     * between the two export formats (no separate confirmation step; the
     * format buttons themselves double as the confirmation, since both
     * formats carry the same sensitivity warning).
     */
    public ExportResponse requestFormatChoice(String userId) {
        String userInfo = TelegramListener.formatUserInfo(userId);
        logHelper.logInfo(String.format("%s requested /exportdatabase command", userInfo));

        if (!isAdmin(userId)) {
            String errorMsg = "❌ Access Denied: Only administrators can export the database.";
            logHelper.logWarning(String.format("Non-admin user %s attempted to use /exportdatabase", userId));
            return new ExportResponse(errorMsg, null, false);
        }

        if (!Files.exists(dbPath)) {
            String errorMsg = "❌ Error: Database file not found at: " + dbPath;
            logHelper.logError(errorMsg);
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

        logHelper.logInfo(String.format("Sent export format choice to admin %s", userId));

        return new ExportResponse(message, new ButtonConfig(buttonLabels, buttonCallbacks), false);
    }

    /** Raw .db file export - unchanged behavior from the original /exportdatabase. */
    public ExportResponse executeDbExport(String userId) {
        if (!isAdmin(userId)) {
            String errorMsg = "❌ Access Denied: Only administrators can export the database.";
            logHelper.logWarning(String.format("Non-admin user %s attempted to confirm export", userId));
            return new ExportResponse(errorMsg, null, false);
        }

        logHelper.logInfo(String.format("Admin %s confirmed .db file export", userId));

        if (!Files.exists(dbPath)) {
            String errorMsg = "❌ Error: Database file not found at: " + dbPath;
            logHelper.logError(errorMsg);
            return new ExportResponse(errorMsg, null, false);
        }

        try {
            String timestamp = TimezoneHelper.formatNow("yyyyMMdd_HHmmss");
            // A short random suffix guarantees uniqueness even within the
            // same second - the old per-export Files.createTempDirectory()
            // gave every export its own randomly-named directory, so two
            // exports completing in the same second could never collide;
            // moving to one shared output directory removed that accidental
            // protection, so the filename itself must provide it now.
            String exportFileName = "database_export_" + timestamp + "_" + shortUniqueSuffix() + ".db";
            // Shared, dedicated output directory (not a fresh OS temp dir
            // per export) - nothing was ever cleaning those up either.
            Path exportPath = OutputPaths.getOutputDirectory().resolve(exportFileName);

            // VACUUM INTO (not Files.copy) produces a consistent, defragmented
            // snapshot even while another connection is mid-write (e.g. a
            // round upload or /recalculate running concurrently) - a plain
            // file copy of a live SQLite database can capture a torn,
            // internally-inconsistent state and doesn't pick up an in-flight
            // -journal/-wal file, which is disqualifying for a file explicitly
            // advertised as the recommended disaster-recovery path. The
            // destination must not already exist - the timestamp + unique
            // suffix in exportFileName already guarantees that. VACUUM INTO
            // takes its destination as a string literal, not a bind
            // parameter, so single-quotes in the path are escaped by hand.
            String escapedPath = exportPath.toString().replace("'", "''");
            try (Connection conn = DatabaseHelper.getDefaultConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("VACUUM INTO '" + escapedPath + "'");
            }

            String successMsg = "✅ Database file exported successfully!\n\n" +
                    "The database file has been sent to your Direct Message.\n\n" +
                    "Filename: " + exportFileName + "\n" +
                    "Timestamp: " + timestamp;

            logHelper.logSuccess(String.format("Database file exported successfully for admin %s", userId));

            return new ExportResponse(successMsg, null, true, exportPath);

        } catch (IOException | SQLException e) {
            String errorMsg = "❌ Error: Failed to export database file: " + e.getMessage();
            logHelper.logError(errorMsg);
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
            logHelper.logWarning(String.format("Non-admin user %s attempted to confirm export", userId));
            return new ExportResponse(errorMsg, null, false);
        }

        logHelper.logInfo(String.format("Admin %s confirmed full .xlsx export", userId));

        try (Connection conn = DatabaseHelper.getDefaultConnection();
             XSSFWorkbook workbook = new XSSFWorkbook()) {

            int tableCount = 0;
            for (String tableName : listTables(conn)) {
                if (dumpTableToSheet(conn, workbook, tableName)) {
                    tableCount++;
                }
            }

            String timestamp = TimezoneHelper.formatNow("yyyyMMdd_HHmmss");
            // See the matching comment in executeDbExport for why the short
            // unique suffix is needed now that exports share one directory.
            String filename = String.format("database_export_%s_%s.xlsx", timestamp, shortUniqueSuffix());
            // Shared, dedicated output directory (not a fresh OS temp dir
            // per export) - nothing was ever cleaning those up either.
            Path xlsxPath = OutputPaths.getOutputDirectory().resolve(filename);
            try (FileOutputStream fos = new FileOutputStream(xlsxPath.toFile())) {
                workbook.write(fos);
            }

            String successMsg = String.format(
                    "✅ Full database export completed: %d populated tables exported to %s.",
                    tableCount, filename);
            logHelper.logSuccess(successMsg);
            return new ExportResponse(successMsg, null, true, xlsxPath);

        } catch (Exception e) {
            String errorMsg = "❌ Error: Full export failed: " + e.getMessage();
            logHelper.logError(errorMsg);
            return new ExportResponse(errorMsg, null, false);
        }
    }

    /** A short, filename-safe unique token (8 hex chars) - see the comments at both call sites. */
    private static String shortUniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
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
        logHelper.logInfo(String.format("Admin %s cancelled database export", userId));
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
