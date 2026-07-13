package com.calplus.ihrgstats.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for A13: every connection now sets a busy_timeout, so a
 * connection that finds the database transiently locked by another
 * concurrent connection (this app opens one connection per statement/DAO
 * call, not a pool) waits rather than failing immediately with
 * SQLITE_BUSY. Also re-confirms the pre-existing foreign_keys=ON pragma
 * (unchanged by this fix) is still applied.
 */
public class DatabaseHelperTest {

    @Test
    void getConnection_setsABusyTimeout(@TempDir Path tempDir) throws Exception {
        String dbPath = tempDir.resolve("test.db").toString();
        try (Connection conn = DatabaseHelper.getConnection(dbPath);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA busy_timeout")) {
            assertTrue(rs.next());
            assertEquals(5000, rs.getInt(1), "busy_timeout should be set to a non-zero, multi-second window");
        }
    }

    @Test
    void getConnection_stillEnablesForeignKeyEnforcement(@TempDir Path tempDir) throws Exception {
        String dbPath = tempDir.resolve("test2.db").toString();
        try (Connection conn = DatabaseHelper.getConnection(dbPath);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA foreign_keys")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "foreign_keys enforcement must still be ON (pre-existing behavior, unrelated to A13)");
        }
    }

    // --- A28: getDatabasePath is now the single authoritative source both
    // DatabaseSchema.createDatabase and Main's startup logging derive from.

    @Test
    void getDatabasePath_resolvesUnderDatabaseCoreDirectory() {
        String originalUserDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", "/some/root");
            Path path = DatabaseHelper.getDatabasePath("myfile.db");
            assertEquals(Path.of("/some/root", "database", "core", "myfile.db"), path);
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    @Test
    void getDefaultDatabasePath_matchesGetDatabasePathWithDefaultDbName() {
        assertEquals(DatabaseHelper.getDatabasePath("default.db"), DatabaseHelper.getDefaultDatabasePath());
    }
}
