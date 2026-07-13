package com.calplus.ihrgstats.databasemanager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless, DB-backed tests for {@link F16_Admins} - the multi-admin,
 * multi-platform registry replacing the old single telegram.admin.userId
 * property comparison. Same user.dir-redirect bootstrap pattern as
 * RoundCsvProcessorPipelineTest/CommandLogicSmokeTest.
 */
public class F16_AdminsTest {

    private static final String NOW = "2026-01-01 00:00:00.000";

    private String originalUserDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        new DatabaseSchema().createDatabase("default.db");
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
        System.clearProperty("TELEGRAM_ADMIN_USERID");
        System.clearProperty("DISCORD_ADMIN_USERID");
    }

    @Test
    void seedDefaults_seedsAdminFromTelegramProperty() throws Exception {
        System.setProperty("TELEGRAM_ADMIN_USERID", "111111");
        F16_Admins admins = new F16_Admins();
        admins.seedDefaults(NOW);

        assertTrue(admins.isAdmin(F16_Admins.PLATFORM_TELEGRAM, "111111"));
        assertFalse(admins.isAdmin(F16_Admins.PLATFORM_TELEGRAM, "someone_else"));
    }

    @Test
    void seedDefaults_seedsDiscordAdminSeparately_platformsDontCrossMatch() throws Exception {
        System.setProperty("TELEGRAM_ADMIN_USERID", "222222");
        System.setProperty("DISCORD_ADMIN_USERID", "222222"); // same ID string, different platform
        F16_Admins admins = new F16_Admins();
        admins.seedDefaults(NOW);

        assertTrue(admins.isAdmin(F16_Admins.PLATFORM_TELEGRAM, "222222"));
        assertTrue(admins.isAdmin(F16_Admins.PLATFORM_DISCORD, "222222"));
        assertEquals(2, admins.countAdmins(), "Same ID on two platforms should be two distinct admin rows");
    }

    @Test
    void seedDefaults_withNoConfiguredAdmin_seedsNothing() throws Exception {
        F16_Admins admins = new F16_Admins();
        admins.seedDefaults(NOW);

        assertEquals(0, admins.countAdmins());
    }

    @Test
    void seedDefaults_isIdempotent() throws Exception {
        System.setProperty("TELEGRAM_ADMIN_USERID", "333333");
        F16_Admins admins = new F16_Admins();
        admins.seedDefaults(NOW);
        admins.seedDefaults(NOW);
        admins.seedDefaults(NOW);

        assertEquals(1, admins.countAdmins(), "Re-seeding the same configured admin must not create duplicates");
    }

    @Test
    void seedDefaults_doesNotResurrectAnAdminRemovedViaAdminsCommand() throws Exception {
        // Regression test: seedDefaults used to insert-if-absent per configured
        // ID on every call (i.e. every app restart), so explicitly removing an
        // admin via /admins while the property was still configured got
        // silently undone on the next restart. It must now only bootstrap
        // while the table is genuinely empty.
        System.setProperty("TELEGRAM_ADMIN_USERID", "555555");
        F16_Admins admins = new F16_Admins();
        admins.seedDefaults(NOW); // first boot: bootstraps from the property
        assertTrue(admins.isAdmin(F16_Admins.PLATFORM_TELEGRAM, "555555"));

        admins.addAdmin(F16_Admins.PLATFORM_TELEGRAM, "666666", "second admin", NOW);
        admins.removeAdmin(F16_Admins.PLATFORM_TELEGRAM, "555555"); // explicit removal, property left configured

        admins.seedDefaults(NOW); // simulates a second app restart

        assertFalse(admins.isAdmin(F16_Admins.PLATFORM_TELEGRAM, "555555"),
                "An admin explicitly removed via /admins must not be silently re-added just because the property is still configured");
        assertTrue(admins.isAdmin(F16_Admins.PLATFORM_TELEGRAM, "666666"), "The other admin should be untouched");
    }

    @Test
    void isAdminSafe_reflectsTheTableJustLikeIsAdmin() throws Exception {
        F16_Admins admins = new F16_Admins();
        admins.addAdmin(F16_Admins.PLATFORM_TELEGRAM, "777777", null, NOW);

        assertTrue(admins.isAdminSafe(F16_Admins.PLATFORM_TELEGRAM, "777777"));
        assertFalse(admins.isAdminSafe(F16_Admins.PLATFORM_TELEGRAM, "not_this_one"));
        assertFalse(admins.isAdminSafe(F16_Admins.PLATFORM_DISCORD, "777777"), "Platforms must not cross-match");
    }

    @Test
    void addAdmin_andRemoveAdmin_roundTrip() throws Exception {
        F16_Admins admins = new F16_Admins();
        admins.addAdmin(F16_Admins.PLATFORM_DISCORD, "444444444444444444", "Alex", NOW);

        assertTrue(admins.isAdmin(F16_Admins.PLATFORM_DISCORD, "444444444444444444"));
        List<F16_Admins.Admin> all = admins.getAllAdmins();
        assertEquals(1, all.size());
        assertEquals("Alex", all.get(0).displayName);

        admins.removeAdmin(F16_Admins.PLATFORM_DISCORD, "444444444444444444");
        assertFalse(admins.isAdmin(F16_Admins.PLATFORM_DISCORD, "444444444444444444"));
        assertEquals(0, admins.countAdmins());
    }

    @Test
    void isAdmin_returnsFalseWhenTableIsEmpty() throws Exception {
        F16_Admins admins = new F16_Admins();
        assertFalse(admins.isAdmin(F16_Admins.PLATFORM_TELEGRAM, "anyone"));
    }
}
