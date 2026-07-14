package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.A3_Halls;
import com.calplus.ihrgstats.databasemanager.DatabaseSchema;
import com.calplus.ihrgstats.databasemanager.F16_Admins;
import com.calplus.ihrgstats.utils.DatabaseHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for M6: the raw .db export used to be a plain
 * {@code Files.copy()} of the live database file, which can capture a torn,
 * internally-inconsistent snapshot (and misses an in-flight -wal/-journal
 * file) if another connection is mid-write. {@code VACUUM INTO} instead
 * produces a consistent snapshot even under concurrent writers - this test
 * proves that concretely with a real background writer, rather than leaving
 * it as "verify manually".
 */
public class CommandExportDatabaseTest {

    private static final String NOW = "2026-01-01 00:00:00.000";
    private static final String ADMIN_USER_ID = "test_admin";

    private String originalUserDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        System.setProperty("TELEGRAM_ADMIN_USERID", ADMIN_USER_ID);

        new DatabaseSchema().createDatabase("default.db");
        new A3_Halls().seedDefaults(NOW);
        new F16_Admins().seedDefaults(NOW);
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
        System.clearProperty("TELEGRAM_ADMIN_USERID");
    }

    @Test
    void exportedDatabase_passesIntegrityCheck_evenWhileAnotherConnectionIsWriting() throws Exception {
        AtomicBoolean keepWriting = new AtomicBoolean(true);
        Thread writer = new Thread(() -> {
            int i = 0;
            while (keepWriting.get()) {
                try (Connection conn = DatabaseHelper.getDefaultConnection();
                     PreparedStatement ps = conn.prepareStatement(
                             "INSERT INTO halls (hall_code, hall_name, next_player_seq, created_dttm, updated_dttm) VALUES (?, ?, 1, ?, ?)")) {
                    ps.setString(1, "STRESS" + i);
                    ps.setString(2, "Stress Hall " + i);
                    ps.setString(3, NOW);
                    ps.setString(4, NOW);
                    ps.executeUpdate();
                    i++;
                } catch (SQLException e) {
                    // A rare lock-contention error here is fine - the goal is
                    // to keep real concurrent write activity flowing during
                    // the export, not to guarantee every insert lands.
                }
            }
        });
        writer.setDaemon(true);
        writer.start();

        CommandExportDatabase.ExportResponse response;
        try {
            response = new CommandExportDatabase().executeDbExport(ADMIN_USER_ID);
        } finally {
            keepWriting.set(false);
            writer.join(5000);
        }

        assertTrue(response.success, "export must succeed even with a concurrent writer: " + response.message);
        assertNotNull(response.exportedFilePath);
        assertTrue(Files.exists(response.exportedFilePath));

        String url = "jdbc:sqlite:" + response.exportedFilePath.toAbsolutePath();
        try (Connection exportedConn = DriverManager.getConnection(url);
             Statement stmt = exportedConn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA integrity_check")) {
            assertTrue(rs.next());
            assertEquals("ok", rs.getString(1),
                    "exported database must pass SQLite's own integrity check, not just exist as a file");
        }
    }
}
