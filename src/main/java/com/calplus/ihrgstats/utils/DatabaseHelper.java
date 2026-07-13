package com.calplus.ihrgstats.utils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Centralized database connection management.
 * Eliminates repeated JDBC URL construction and connection handling across the codebase.
 */
public final class DatabaseHelper {
    
    // Prevent instantiation
    private DatabaseHelper() {
        throw new UnsupportedOperationException("DatabaseHelper class cannot be instantiated");
    }
    
    /**
     * Gets the path for a named database file under the app's standard
     * database/core directory - the single authoritative source for this
     * layout, so callers (DatabaseSchema.createDatabase, Main's own startup
     * logging) never need their own independent copy of this construction
     * that could silently drift out of sync (A28).
     * @param dbName The database filename (e.g. "default.db")
     * @return Path to that database file
     */
    public static Path getDatabasePath(String dbName) {
        return Paths.get(System.getProperty("user.dir"), "database", "core", dbName);
    }

    /**
     * Gets the default database path.
     * @return Path to the default database file
     */
    public static Path getDefaultDatabasePath() {
        return getDatabasePath("default.db");
    }
    
    /**
     * Gets the database path as a string.
     * @return String path to the default database
     */
    public static String getDefaultDatabasePathString() {
        return getDefaultDatabasePath().toString();
    }
    
    /**
     * Creates a JDBC URL for the given database path.
     * @param dbPath Path to the database file
     * @return JDBC URL string
     */
    public static String createJdbcUrl(String dbPath) {
        return "jdbc:sqlite:" + dbPath;
    }
    
    /**
     * Creates a JDBC URL for the default database.
     * @return JDBC URL string for default database
     */
    public static String createDefaultJdbcUrl() {
        return createJdbcUrl(getDefaultDatabasePathString());
    }
    
    // How long a connection waits for a lock held by another connection to
    // clear before failing with SQLITE_BUSY ("database is locked"). This app
    // opens a fresh connection per statement/DAO call rather than pooling
    // (A13 - a larger connection-pooling/transaction refactor is tracked
    // separately, not done here), so under concurrent access - e.g. the
    // whole-history recalculation's thousands of single-statement
    // connections racing an in-progress round upload - a connection can
    // legitimately find the database transiently locked by another one.
    // Without a busy timeout, SQLite fails such a connection immediately;
    // this gives it a reasonable window to retry internally instead.
    private static final int BUSY_TIMEOUT_MS = 5000;

    /**
     * Gets a database connection for the given database path.
     * Foreign key constraint enforcement is enabled on every connection
     * (SQLite disables it by default, per-connection) so that ON DELETE
     * CASCADE relationships across the schema actually fire at runtime.
     * A busy timeout is also set on every connection (see BUSY_TIMEOUT_MS)
     * so a transient lock from another concurrent connection doesn't fail
     * this one outright.
     * @param dbPath Path to the database file
     * @return Active database connection
     * @throws SQLException if connection fails
     */
    public static Connection getConnection(String dbPath) throws SQLException {
        String jdbcUrl = createJdbcUrl(dbPath);
        Connection conn = DriverManager.getConnection(jdbcUrl);
        try (var stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON");
            stmt.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS);
        }
        return conn;
    }
    
    /**
     * Gets a database connection for the default database.
     * @return Active database connection
     * @throws SQLException if connection fails
     */
    public static Connection getDefaultConnection() throws SQLException {
        return getConnection(getDefaultDatabasePathString());
    }
    
    /**
     * Safely closes a database connection without throwing exceptions.
     * @param conn Connection to close (can be null)
     */
    public static void closeQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                // Ignore - connection is closing anyway
            }
        }
    }
}
