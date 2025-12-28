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
     * Gets the default database path.
     * @return Path to the default database file
     */
    public static Path getDefaultDatabasePath() {
        return Paths.get(System.getProperty("user.dir"), "database", "core", "default.db");
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
    
    /**
     * Gets a database connection for the given database path.
     * @param dbPath Path to the database file
     * @return Active database connection
     * @throws SQLException if connection fails
     */
    public static Connection getConnection(String dbPath) throws SQLException {
        String jdbcUrl = createJdbcUrl(dbPath);
        return DriverManager.getConnection(jdbcUrl);
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
