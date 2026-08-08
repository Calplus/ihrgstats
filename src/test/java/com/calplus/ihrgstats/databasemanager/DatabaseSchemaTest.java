package com.calplus.ihrgstats.databasemanager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the fail-fast schema fix: a failed CREATE TABLE used
 * to be logged and SKIPPED, leaving a half-built schema that surfaced as
 * confusing failures at first use. It must now throw at startup. The
 * failure is provoked with a real, connectable-but-readonly database file:
 * SQLite opens it read-only, so the connection succeeds and the very first
 * DDL statement fails - exactly the mid-creation failure the old code
 * swallowed.
 */
public class DatabaseSchemaTest {

    private String originalUserDir;
    private Path dbFile;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        Path coreDir = tempDir.resolve("database").resolve("core");
        Files.createDirectories(coreDir);
        dbFile = coreDir.resolve("default.db");
        Files.createFile(dbFile); // zero bytes = a valid, empty SQLite database
        Files.setAttribute(dbFile, "dos:readonly", true);
    }

    @AfterEach
    void tearDown() throws Exception {
        // Clear the attribute so @TempDir cleanup can delete the file.
        if (dbFile != null && Files.exists(dbFile)) {
            Files.setAttribute(dbFile, "dos:readonly", false);
        }
        System.setProperty("user.dir", originalUserDir);
    }

    @Test
    void aSchemaCreationFailure_throwsAtStartup_insteadOfLeavingAHalfBuiltSchema() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> new DatabaseSchema().createDatabase("default.db"),
                "a CREATE failure on a readonly database must abort startup, not be logged and skipped");
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("Error creating"),
                "the failure must surface from the schema-creation step itself (create table/index), got: " + ex.getMessage());
    }
}
