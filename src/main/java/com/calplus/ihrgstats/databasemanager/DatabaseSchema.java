package com.calplus.ihrgstats.databasemanager;

import com.calplus.ihrgstats.utils.DatabaseHelper;
import com.calplus.ihrgstats.utils.EnvironmentManager;
import com.calplus.ihrgstats.utils.LogHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;

/**
 * Database schema creation and management for SQLite database.
 * Creates the normalized IHRGStats 2.0 relational schema (rounds, players,
 * match_participants, player_ratings, etc.), replacing the legacy
 * A1_PlayerStats/A2_CappedPlayers wide-table design.
 */
public class DatabaseSchema {
    private final LogHelper logHelper;

    public DatabaseSchema() {
        // Load environment variables first
        EnvironmentManager.ensureSystemPropertiesLoaded();

        this.logHelper = new LogHelper();
    }

    /**
     * Executes a single CREATE TABLE IF NOT EXISTS statement and logs the result.
     */
    private void createTable(Connection conn, String tableName, String createSQL) {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(createSQL);
            String successMsg = String.format("Table '%s' ensured.", tableName);
            System.out.println(successMsg);
            logHelper.batchInfo(successMsg);
        } catch (SQLException e) {
            logHelper.flushBatch();
            String errorMsg = String.format("Error creating table %s: %s", tableName, e.getMessage());
            System.err.println(errorMsg);
            logHelper.logError(errorMsg);
            // Fail fast: continuing here leaves a half-built schema that
            // surfaces as confusing failures at first use. createDatabase
            // already throws on connection-level failures - partial-schema
            // failures must behave the same way.
            throw new RuntimeException(errorMsg, e);
        }
    }

    /**
     * Executes a single CREATE INDEX IF NOT EXISTS statement and logs the result.
     */
    private void createIndex(Connection conn, String indexName, String tableName, String columns) {
        String indexSQL = String.format("CREATE INDEX IF NOT EXISTS %s ON %s(%s)", indexName, tableName, columns);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(indexSQL);
            String successMsg = String.format("Index '%s' created on '%s(%s)'.", indexName, tableName, columns);
            System.out.println(successMsg);
            logHelper.batchInfo(successMsg);
        } catch (SQLException e) {
            logHelper.flushBatch();
            String errorMsg = String.format("Error creating index %s on %s: %s", indexName, tableName, e.getMessage());
            System.err.println(errorMsg);
            logHelper.logError(errorMsg);
            // Fail fast - same rationale as createTable.
            throw new RuntimeException(errorMsg, e);
        }
    }

    /**
     * Defines all tables and their structures for the IHRGStats 2.0 normalized
     * relational schema (replaces the legacy A1_PlayerStats/A2_CappedPlayers
     * wide-table design).
     *
     * NOTE: Seed data (halls, the WLKOVR sentinel player, rating_types lookup
     * rows) is intentionally NOT created here - it is the responsibility of a
     * separate seed/bootstrap step invoked after table creation.
     */
    private void defineTableStructures(Connection conn) {
        // Enforce foreign key constraints for this connection (SQLite disables
        // FK enforcement by default per-connection).
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON");
        } catch (SQLException e) {
            String errorMsg = "Failed to enable foreign key enforcement: " + e.getMessage();
            System.err.println(errorMsg);
            logHelper.logError(errorMsg);
        }

        // ====================================================================
        // DOMAIN 1: STRUCTURAL CONFIGURATION
        // ====================================================================

        createTable(conn, "rounds",
            "CREATE TABLE IF NOT EXISTS rounds (\n" +
            "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
            "    year INTEGER NOT NULL,\n" +
            "    round_order INTEGER NOT NULL,\n" +
            "    round_label TEXT NOT NULL,\n" +
            "    round_datetime TEXT,\n" +
            "    created_dttm TEXT NOT NULL,\n" +
            "    updated_dttm TEXT NOT NULL,\n" +
            "    UNIQUE (year, round_order)\n" +
            ")");

        createTable(conn, "match_types",
            "CREATE TABLE IF NOT EXISTS match_types (\n" +
            "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
            "    type_name TEXT NOT NULL,\n" +
            "    max_score REAL NOT NULL,\n" +
            "    time_limit_minutes INTEGER,\n" +
            "    description TEXT,\n" +
            "    created_dttm TEXT NOT NULL,\n" +
            "    updated_dttm TEXT NOT NULL\n" +
            ")");

        createTable(conn, "halls",
            "CREATE TABLE IF NOT EXISTS halls (\n" +
            "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
            "    hall_code TEXT NOT NULL UNIQUE,\n" +
            "    hall_name TEXT NOT NULL UNIQUE,\n" +
            "    next_player_seq INTEGER NOT NULL,\n" +
            "    created_dttm TEXT NOT NULL,\n" +
            "    updated_dttm TEXT NOT NULL\n" +
            ")");

        // ====================================================================
        // DOMAIN 2: PLAYER IDENTITY & PER-YEAR STATUS
        // ====================================================================

        createTable(conn, "players",
            "CREATE TABLE IF NOT EXISTS players (\n" +
            "    player_id TEXT PRIMARY KEY,\n" +
            "    created_dttm TEXT NOT NULL,\n" +
            "    updated_dttm TEXT NOT NULL\n" +
            ")");

        createTable(conn, "player_names",
            "CREATE TABLE IF NOT EXISTS player_names (\n" +
            "    player_id TEXT NOT NULL,\n" +
            "    name TEXT NOT NULL,\n" +
            "    first_seen_year INTEGER NOT NULL,\n" +
            "    last_seen_year INTEGER NOT NULL,\n" +
            "    created_dttm TEXT NOT NULL,\n" +
            "    updated_dttm TEXT NOT NULL,\n" +
            "    PRIMARY KEY (player_id, name),\n" +
            "    FOREIGN KEY (player_id) REFERENCES players(player_id) ON DELETE CASCADE\n" +
            ")");

        createTable(conn, "player_year_status",
            "CREATE TABLE IF NOT EXISTS player_year_status (\n" +
            "    player_id TEXT NOT NULL,\n" +
            "    year INTEGER NOT NULL,\n" +
            "    hall_id INTEGER NOT NULL,\n" +
            "    capped BOOLEAN NOT NULL,\n" +
            "    active BOOLEAN NOT NULL,\n" +
            "    created_dttm TEXT NOT NULL,\n" +
            "    updated_dttm TEXT NOT NULL,\n" +
            "    PRIMARY KEY (player_id, year),\n" +
            "    FOREIGN KEY (player_id) REFERENCES players(player_id) ON DELETE CASCADE,\n" +
            "    FOREIGN KEY (hall_id) REFERENCES halls(id)\n" +
            ")");

        createTable(conn, "capped_imports",
            "CREATE TABLE IF NOT EXISTS capped_imports (\n" +
            "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
            "    year INTEGER NOT NULL,\n" +
            "    name TEXT NOT NULL,\n" +
            "    prev_hall TEXT NOT NULL,\n" +
            "    player_id TEXT,\n" +
            "    mapped BOOLEAN NOT NULL,\n" +
            "    created_dttm TEXT NOT NULL,\n" +
            "    updated_dttm TEXT NOT NULL,\n" +
            "    FOREIGN KEY (player_id) REFERENCES players(player_id)\n" +
            ")");

        // ====================================================================
        // DOMAIN 3: MATCH RECORDS
        // ====================================================================

        createTable(conn, "matches",
            "CREATE TABLE IF NOT EXISTS matches (\n" +
            "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
            "    round_id INTEGER NOT NULL,\n" +
            "    match_type_id INTEGER,\n" +
            "    table_number INTEGER,\n" +
            "    match_timestamp TEXT,\n" +
            "    created_dttm TEXT NOT NULL,\n" +
            "    updated_dttm TEXT NOT NULL,\n" +
            "    FOREIGN KEY (round_id) REFERENCES rounds(id) ON DELETE CASCADE,\n" +
            "    FOREIGN KEY (match_type_id) REFERENCES match_types(id)\n" +
            ")");

        createTable(conn, "match_participants",
            "CREATE TABLE IF NOT EXISTS match_participants (\n" +
            "    match_id INTEGER NOT NULL,\n" +
            "    player_id TEXT NOT NULL,\n" +
            "    hall_id INTEGER NOT NULL,\n" +
            "    hall_seat_number INTEGER,\n" +
            "    participation_type TEXT NOT NULL,\n" +
            "    score REAL NOT NULL,\n" +
            "    outcome REAL NOT NULL,\n" +
            "    created_dttm TEXT NOT NULL,\n" +
            "    updated_dttm TEXT NOT NULL,\n" +
            "    PRIMARY KEY (match_id, player_id),\n" +
            "    FOREIGN KEY (match_id) REFERENCES matches(id) ON DELETE CASCADE,\n" +
            "    FOREIGN KEY (player_id) REFERENCES players(player_id),\n" +
            "    FOREIGN KEY (hall_id) REFERENCES halls(id)\n" +
            ")");

        // ====================================================================
        // DOMAIN 4: RATING ENGINE
        // ====================================================================

        createTable(conn, "rating_types",
            "CREATE TABLE IF NOT EXISTS rating_types (\n" +
            "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
            "    rating_name TEXT NOT NULL UNIQUE,\n" +
            "    created_dttm TEXT NOT NULL,\n" +
            "    updated_dttm TEXT NOT NULL\n" +
            ")");

        createTable(conn, "player_ratings",
            "CREATE TABLE IF NOT EXISTS player_ratings (\n" +
            "    player_id TEXT NOT NULL,\n" +
            "    round_id INTEGER NOT NULL,\n" +
            "    rating_type_id INTEGER NOT NULL,\n" +
            "    rating_value REAL NOT NULL,\n" +
            "    rating_deviation REAL NOT NULL,\n" +
            "    volatility REAL NOT NULL,\n" +
            "    created_dttm TEXT NOT NULL,\n" +
            "    updated_dttm TEXT NOT NULL,\n" +
            "    PRIMARY KEY (player_id, round_id, rating_type_id),\n" +
            "    FOREIGN KEY (player_id) REFERENCES players(player_id) ON DELETE CASCADE,\n" +
            "    FOREIGN KEY (round_id) REFERENCES rounds(id) ON DELETE CASCADE,\n" +
            "    FOREIGN KEY (rating_type_id) REFERENCES rating_types(id)\n" +
            ")");

        // Point-in-time record: the rating each player had for a round AS
        // COMPUTED WHEN THAT ROUND WAS ORIGINALLY PROCESSED. Unlike
        // player_ratings (which the whole-history recalculation rewrites as
        // later results arrive), snapshot rows are immutable - replaced only
        // when their own round is re-uploaded/reprocessed. "Rankings as of
        // round N" queries read this table.
        createTable(conn, "player_ratings_snapshot",
            "CREATE TABLE IF NOT EXISTS player_ratings_snapshot (\n" +
            "    player_id TEXT NOT NULL,\n" +
            "    round_id INTEGER NOT NULL,\n" +
            "    rating_type_id INTEGER NOT NULL,\n" +
            "    rating_value REAL NOT NULL,\n" +
            "    rating_deviation REAL NOT NULL,\n" +
            "    volatility REAL NOT NULL,\n" +
            "    created_dttm TEXT NOT NULL,\n" +
            "    updated_dttm TEXT NOT NULL,\n" +
            "    PRIMARY KEY (player_id, round_id, rating_type_id),\n" +
            "    FOREIGN KEY (player_id) REFERENCES players(player_id) ON DELETE CASCADE,\n" +
            "    FOREIGN KEY (round_id) REFERENCES rounds(id) ON DELETE CASCADE,\n" +
            "    FOREIGN KEY (rating_type_id) REFERENCES rating_types(id)\n" +
            ")");

        // ====================================================================
        // DOMAIN 5: AI HYBRID FEATURES
        // ====================================================================

        createTable(conn, "player_profiles",
            "CREATE TABLE IF NOT EXISTS player_profiles (\n" +
            "    player_id TEXT PRIMARY KEY,\n" +
            "    playstyle_vector TEXT NOT NULL,\n" +
            "    last_calculated_year INTEGER NOT NULL,\n" +
            "    created_dttm TEXT NOT NULL,\n" +
            "    updated_dttm TEXT NOT NULL,\n" +
            "    FOREIGN KEY (player_id) REFERENCES players(player_id) ON DELETE CASCADE\n" +
            ")");

        createTable(conn, "player_rolling_cache",
            "CREATE TABLE IF NOT EXISTS player_rolling_cache (\n" +
            "    player_id TEXT PRIMARY KEY,\n" +
            "    current_streak INTEGER NOT NULL,\n" +
            "    avg_margin_last_5_matches REAL NOT NULL,\n" +
            "    matches_played_today INTEGER NOT NULL,\n" +
            "    created_dttm TEXT NOT NULL,\n" +
            "    updated_dttm TEXT NOT NULL,\n" +
            "    FOREIGN KEY (player_id) REFERENCES players(player_id) ON DELETE CASCADE\n" +
            ")");

        createTable(conn, "ai_predictions",
            "CREATE TABLE IF NOT EXISTS ai_predictions (\n" +
            "    match_id INTEGER PRIMARY KEY,\n" +
            "    predicted_winner_player_id TEXT,\n" +
            "    predicted_win_probability REAL NOT NULL,\n" +
            "    model_version TEXT NOT NULL,\n" +
            "    created_dttm TEXT NOT NULL,\n" +
            "    FOREIGN KEY (match_id) REFERENCES matches(id) ON DELETE CASCADE,\n" +
            "    FOREIGN KEY (predicted_winner_player_id) REFERENCES players(player_id)\n" +
            ")");

        // Trained ML model registry (Segment A of the AI/ML plan). One row per
        // training run: serialized parameters + walk-forward backtest metrics.
        // Exactly one row should carry is_champion = 1 at any time - the model
        // the prediction/lineup features serve from. The Glicko baseline is
        // itself persisted as a run (family GLICKO_BASELINE) so "the champion
        // is still plain Glicko" is a recorded, honest outcome.
        createTable(conn, "ml_models",
            "CREATE TABLE IF NOT EXISTS ml_models (\n" +
            "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
            "    model_version TEXT NOT NULL UNIQUE,\n" +
            "    family TEXT NOT NULL,\n" +
            "    params_json TEXT NOT NULL,\n" +
            "    metrics_json TEXT NOT NULL,\n" +
            "    trained_boards INTEGER NOT NULL,\n" +
            "    is_champion BOOLEAN NOT NULL,\n" +
            "    created_dttm TEXT NOT NULL,\n" +
            "    updated_dttm TEXT NOT NULL\n" +
            ")");

        // ====================================================================
        // DOMAIN 6: ACCESS CONTROL
        // ====================================================================

        // Multi-admin, multi-platform admin registry - replaces the old
        // single telegram.admin.userId property comparison. platform_user_id
        // is always the platform's stable numeric ID (Telegram user ID /
        // Discord snowflake), never a mutable @username - display_name is a
        // friendly label only, never used for the actual admin check.
        createTable(conn, "admins",
            "CREATE TABLE IF NOT EXISTS admins (\n" +
            "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
            "    platform TEXT NOT NULL,\n" +
            "    platform_user_id TEXT NOT NULL,\n" +
            "    display_name TEXT,\n" +
            "    created_dttm TEXT NOT NULL,\n" +
            "    updated_dttm TEXT NOT NULL,\n" +
            "    UNIQUE (platform, platform_user_id)\n" +
            ")");

        // ====================================================================
        // STRATEGIC INDEXES
        // ====================================================================

        createIndex(conn, "idx_rounds_year", "rounds", "year");
        createIndex(conn, "idx_matches_round_id", "matches", "round_id");
        createIndex(conn, "idx_matches_match_type_id", "matches", "match_type_id");
        createIndex(conn, "idx_matches_match_timestamp", "matches", "match_timestamp");
        createIndex(conn, "idx_match_participants_player_id", "match_participants", "player_id");
        createIndex(conn, "idx_match_participants_player_outcome", "match_participants", "player_id, outcome");
        createIndex(conn, "idx_match_participants_hall_id", "match_participants", "hall_id");
        createIndex(conn, "idx_player_names_name", "player_names", "name");
        createIndex(conn, "idx_player_year_status_year", "player_year_status", "year");
        createIndex(conn, "idx_player_year_status_hall_id", "player_year_status", "hall_id");
        createIndex(conn, "idx_player_year_status_compound", "player_year_status", "year, hall_id");
        createIndex(conn, "idx_capped_imports_year_mapped", "capped_imports", "year, mapped");
        createIndex(conn, "idx_player_ratings_round_type", "player_ratings", "round_id, rating_type_id");
        createIndex(conn, "idx_player_ratings_value_search", "player_ratings", "rating_type_id, rating_value");
        createIndex(conn, "idx_player_ratings_snapshot_round_type", "player_ratings_snapshot", "round_id, rating_type_id");
        createIndex(conn, "idx_player_profiles_year", "player_profiles", "last_calculated_year");
        createIndex(conn, "idx_ai_predictions_winner_id", "ai_predictions", "predicted_winner_player_id");
        createIndex(conn, "idx_ml_models_champion", "ml_models", "is_champion");
    }

    /**
     * Creates the database with all tables and structures
     * @param dbName The database filename
     */
    public void createDatabase(String dbName) {
        Path dbPath = DatabaseHelper.getDatabasePath(dbName);
        Path dbDir = dbPath.getParent();
        boolean dbExists = Files.exists(dbPath);

        // Log INFO: database is being created/running (initial message, send immediately)
        String infoMsgStart = String.format("Database creation started for: %s", dbName);
        System.out.println(infoMsgStart);
        logHelper.logInfo(infoMsgStart);

        // Create database directory if it doesn't exist
        if (!dbExists) {
            try {
                Files.createDirectories(dbDir);
                Files.createFile(dbPath);
                String infoMsgBlank = String.format("Blank database file '%s' created at %s", dbName, dbPath);
                System.out.println(infoMsgBlank);
                logHelper.batchInfo(infoMsgBlank);
            } catch (IOException e) {
                logHelper.flushBatch();
                String errMsg = String.format("Failed to create database file: %s. Error: %s. Check the console log for details.", dbPath, e.getMessage());
                System.err.println(errMsg);
                logHelper.logError(errMsg);
                throw new RuntimeException(errMsg, e);
            }
        }

        // Connect to database and create tables
        try (Connection conn = DatabaseHelper.getConnection(dbPath.toString())) {
            String infoMsgOpen = String.format("Database '%s' opened", dbName);
            System.out.println(infoMsgOpen);
            logHelper.batchInfo(infoMsgOpen);

            defineTableStructures(conn);

            logHelper.flushBatch();
            String successMsg = String.format("Database created successfully: %s", dbPath);
            System.out.println(successMsg);
            logHelper.logSuccess(successMsg);

        } catch (SQLException e) {
            logHelper.flushBatch();
            String errMsg = String.format("Database creation failed for %s: %s", dbName, e.getMessage());
            System.err.println(errMsg);
            logHelper.logError(errMsg);
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
