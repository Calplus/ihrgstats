package com.calplus.ihrgstats.databasemanager;

import com.calplus.ihrgstats.discordbot.logs.DiscordLog;
import com.calplus.ihrgstats.telegrambot.logs.TelegramLog;
import com.calplus.ihrgstats.utils.EnvironmentManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Database schema creation and management for SQLite database.
 * Handles table creation, column updates, and indexing.
 */
public class DatabaseSchema {
    private final DiscordLog discordLog;
    private final TelegramLog telegramLog;

    public DatabaseSchema() {
        // Load environment variables first
        EnvironmentManager envManager = new EnvironmentManager();
        envManager.loadIntoSystemProperties();
        
        this.discordLog = new DiscordLog();
        this.telegramLog = new TelegramLog();
    }

    /**
     * Column definition for table creation
     */
    private static class ColumnDefinition {
        String name;
        String type;

        ColumnDefinition(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    /**
     * Ensures a column exists in the specified table, adding it if necessary
     */
    private void ensureColumn(Connection conn, String tableName, String columnName, String columnType) {
        try {
            // Get all columns for the table
            DatabaseMetaData metaData = conn.getMetaData();
            ResultSet columns = metaData.getColumns(null, null, tableName, null);
            
            boolean columnExists = false;
            while (columns.next()) {
                String existingColumn = columns.getString("COLUMN_NAME");
                if (existingColumn.equalsIgnoreCase(columnName)) {
                    columnExists = true;
                    break;
                }
            }
            columns.close();

            if (!columnExists) {
                String sql = String.format("ALTER TABLE %s ADD COLUMN %s %s", tableName, columnName, columnType);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(sql);
                    String successMsg = String.format("Column '%s' added to table '%s'.", columnName, tableName);
                    System.out.println(successMsg);
                    discordLog.batchSuccess(successMsg);
                    telegramLog.batchSuccess(successMsg);
                }
            }
        } catch (SQLException e) {
            discordLog.flushBatch(); // Flush batch before error
            telegramLog.flushBatch();
            String errorMsg = String.format("Error checking/adding column %s to %s: %s", columnName, tableName, e.getMessage());
            System.err.println(errorMsg);
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
        }
    }

    /**
     * Creates or updates a table with the specified columns and indexes
     */
    private void createOrUpdateTable(Connection conn, String tableName, List<ColumnDefinition> columns, List<String> indexColumns) {
        if (columns == null || columns.isEmpty()) {
            String errorMsg = String.format("No columns specified for table %s.", tableName);
            System.err.println(errorMsg);
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
            return;
        }

        try {
            // Build CREATE TABLE SQL
            StringBuilder columnsDef = new StringBuilder();
            for (int i = 0; i < columns.size(); i++) {
                ColumnDefinition col = columns.get(i);
                columnsDef.append(col.name).append(" ").append(col.type);
                if (i < columns.size() - 1) {
                    columnsDef.append(",\n    ");
                }
            }

            String createSQL = String.format("CREATE TABLE IF NOT EXISTS %s (\n    %s\n)", tableName, columnsDef);

            try (Statement stmt = conn.createStatement()) {
                stmt.execute(createSQL);
                String successMsg = String.format("Table '%s' ensured.", tableName);
                System.out.println(successMsg);
                discordLog.batchInfo(successMsg);
                telegramLog.batchInfo(successMsg);
            }

            // Ensure all columns exist (for table updates)
            for (ColumnDefinition col : columns) {
                ensureColumn(conn, tableName, col.name, col.type);
            }

            // Create indexes on specified columns
            if (indexColumns != null && !indexColumns.isEmpty()) {
                for (String col : indexColumns) {
                    String indexName = String.format("idx_%s_%s", tableName, col);
                    String indexSQL = String.format("CREATE INDEX IF NOT EXISTS %s ON %s(%s)", indexName, tableName, col);
                    
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute(indexSQL);
                        String successMsg = String.format("Index '%s' created on '%s(%s)'.", indexName, tableName, col);
                        System.out.println(successMsg);
                        discordLog.batchInfo(successMsg);
                        telegramLog.batchInfo(successMsg);
                    }
                }
            }
        } catch (SQLException e) {
            discordLog.flushBatch(); // Flush batch before error
            telegramLog.flushBatch();
            String errorMsg = String.format("Error creating table %s: %s", tableName, e.getMessage());
            System.err.println(errorMsg);
            discordLog.logError(errorMsg);
            telegramLog.logError(errorMsg);
        }
    }

    /**
     * Defines all tables and their structures
     */
    private void defineTableStructures(Connection conn) {
        // Table 1: Player Stats
        List<ColumnDefinition> playerStatsColumns = new ArrayList<>();
        playerStatsColumns.add(new ColumnDefinition("id", "INTEGER PRIMARY KEY AUTOINCREMENT"));
        playerStatsColumns.add(new ColumnDefinition("dateLogged", "TEXT"));

        // Player info
        playerStatsColumns.add(new ColumnDefinition("name", "TEXT"));
        playerStatsColumns.add(new ColumnDefinition("hall", "TEXT"));
        playerStatsColumns.add(new ColumnDefinition("capped", "BOOLEAN"));

        // Elo Rating
        playerStatsColumns.add(new ColumnDefinition("baseTrueElo", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("basePerfElo", "INTEGER"));

        // True Elo
        playerStatsColumns.add(new ColumnDefinition("trueEloR1", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("trueEloR2", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("trueEloR3", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("trueEloR4", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("trueEloR5", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("trueEloR6", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("trueEloT16", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("trueEloT8", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("trueEloT4", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("trueEloT2", "INTEGER"));

        // Performance Elo
        playerStatsColumns.add(new ColumnDefinition("perfEloR1", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("perfEloR2", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("perfEloR3", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("perfEloR4", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("perfEloR5", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("perfEloR6", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("perfEloT16", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("perfEloT8", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("perfEloT4", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("perfEloT2", "INTEGER"));

        // Seating Arrangement
        playerStatsColumns.add(new ColumnDefinition("seatR1", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("seatR2", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("seatR3", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("seatR4", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("seatR5", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("seatR6", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("seatT16", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("seatT8", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("seatT4", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("seatT2", "INTEGER"));

        // Opponent: Hall
        playerStatsColumns.add(new ColumnDefinition("oppHallR1", "TEXT"));
        playerStatsColumns.add(new ColumnDefinition("oppHallR2", "TEXT"));
        playerStatsColumns.add(new ColumnDefinition("oppHallR3", "TEXT"));
        playerStatsColumns.add(new ColumnDefinition("oppHallR4", "TEXT"));
        playerStatsColumns.add(new ColumnDefinition("oppHallR5", "TEXT"));
        playerStatsColumns.add(new ColumnDefinition("oppHallR6", "TEXT"));
        playerStatsColumns.add(new ColumnDefinition("oppHallT16", "TEXT"));
        playerStatsColumns.add(new ColumnDefinition("oppHallT8", "TEXT"));
        playerStatsColumns.add(new ColumnDefinition("oppHallT4", "TEXT"));
        playerStatsColumns.add(new ColumnDefinition("oppHallT2", "TEXT"));

        // Opponent: Name
        playerStatsColumns.add(new ColumnDefinition("oppNameR1", "TEXT"));
        playerStatsColumns.add(new ColumnDefinition("oppNameR2", "TEXT"));
        playerStatsColumns.add(new ColumnDefinition("oppNameR3", "TEXT"));
        playerStatsColumns.add(new ColumnDefinition("oppNameR4", "TEXT"));
        playerStatsColumns.add(new ColumnDefinition("oppNameR5", "TEXT"));
        playerStatsColumns.add(new ColumnDefinition("oppNameR6", "TEXT"));
        playerStatsColumns.add(new ColumnDefinition("oppNameT16", "TEXT"));
        playerStatsColumns.add(new ColumnDefinition("oppNameT8", "TEXT"));
        playerStatsColumns.add(new ColumnDefinition("oppNameT4", "TEXT"));
        playerStatsColumns.add(new ColumnDefinition("oppNameT2", "TEXT"));

        // Opponent: True Elo
        playerStatsColumns.add(new ColumnDefinition("oppTrueEloR1", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("oppTrueEloR2", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("oppTrueEloR3", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("oppTrueEloR4", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("oppTrueEloR5", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("oppTrueEloR6", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("oppTrueEloT16", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("oppTrueEloT8", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("oppTrueEloT4", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("oppTrueEloT2", "INTEGER"));

        // Opponent: Perf Elo
        playerStatsColumns.add(new ColumnDefinition("oppPerfEloR1", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("oppPerfEloR2", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("oppPerfEloR3", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("oppPerfEloR4", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("oppPerfEloR5", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("oppPerfEloR6", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("oppPerfEloT16", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("oppPerfEloT8", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("oppPerfEloT4", "INTEGER"));
        playerStatsColumns.add(new ColumnDefinition("oppPerfEloT2", "INTEGER"));

        List<String> playerStatsIndexes = new ArrayList<>();
        playerStatsIndexes.add("name");
        playerStatsIndexes.add("hall");

        createOrUpdateTable(conn, "A1_PlayerStats", playerStatsColumns, playerStatsIndexes);

        // Table 2: Capped Players
        List<ColumnDefinition> cappedPlayersColumns = new ArrayList<>();
        cappedPlayersColumns.add(new ColumnDefinition("id", "INTEGER PRIMARY KEY AUTOINCREMENT"));
        cappedPlayersColumns.add(new ColumnDefinition("name", "TEXT"));
        cappedPlayersColumns.add(new ColumnDefinition("prevHall", "TEXT"));
        cappedPlayersColumns.add(new ColumnDefinition("mapped", "BOOLEAN"));

        List<String> cappedPlayersIndexes = new ArrayList<>();
        cappedPlayersIndexes.add("name");
        cappedPlayersIndexes.add("mapped");

        createOrUpdateTable(conn, "A2_CappedPlayers", cappedPlayersColumns, cappedPlayersIndexes);
    }

    /**
     * Creates the database with all tables and structures
     * @param dbName The database filename
     */
    public void createDatabase(String dbName) {
        Path dbDir = Paths.get(System.getProperty("user.dir"), "database", "core");
        Path dbPath = dbDir.resolve(dbName);
        boolean dbExists = Files.exists(dbPath);

        // Log INFO: database is being created/running (initial message, send immediately)
        String infoMsgStart = String.format("Database creation started for: %s", dbName);
        System.out.println(infoMsgStart);
        discordLog.logInfo(infoMsgStart);
        telegramLog.logInfo(infoMsgStart);

        // Create database directory if it doesn't exist
        if (!dbExists) {
            try {
                Files.createDirectories(dbDir);
                Files.createFile(dbPath);
                String infoMsgBlank = String.format("Blank database file '%s' created at %s", dbName, dbPath);
                System.out.println(infoMsgBlank);
                discordLog.batchInfo(infoMsgBlank); // Batch subsequent info messages
                telegramLog.batchInfo(infoMsgBlank);
            } catch (IOException e) {
                discordLog.flushBatch(); // Flush batch before error
                telegramLog.flushBatch();
                String errMsg = String.format("Failed to create database file: %s. Error: %s. Check the console log for details.", dbPath, e.getMessage());
                System.err.println(errMsg);
                discordLog.logError(errMsg);
                telegramLog.logError(errMsg);
                throw new RuntimeException(errMsg, e);
            }
        }

        // Connect to database and create tables
        String jdbcUrl = "jdbc:sqlite:" + dbPath.toString();
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            String infoMsgOpen = String.format("Database '%s' opened", dbName);
            System.out.println(infoMsgOpen);
            discordLog.batchInfo(infoMsgOpen); // Batch this info message
            telegramLog.batchInfo(infoMsgOpen);

            defineTableStructures(conn);

            discordLog.flushBatch(); // Flush batch before final success message
            telegramLog.flushBatch();
            String successMsg = String.format("Database created successfully: %s", dbPath);
            System.out.println(successMsg);
            discordLog.logSuccess(successMsg);
            telegramLog.logSuccess(successMsg);

        } catch (SQLException e) {
            discordLog.flushBatch(); // Flush batch before error
            telegramLog.flushBatch();
            String errMsg = String.format("Database creation failed for %s: %s", dbName, e.getMessage());
            System.err.println(errMsg);
            discordLog.logError(errMsg);
            telegramLog.logError(errMsg);
            throw new RuntimeException(errMsg, e);
        }
    }

    /**
     * Main method for testing and CLI usage
     */
    public static void main(String[] args) {
        String dbFileName = "default.db";
        
        if (args.length > 0) {
            dbFileName = args[0];
        }

        if (dbFileName == null || dbFileName.isEmpty()) {
            System.err.println("Please specify a database file name.");
            System.exit(1);
        }

        DatabaseSchema schema = new DatabaseSchema();
        schema.createDatabase(dbFileName);
    }
}
