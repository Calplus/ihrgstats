package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.DatabaseSchema;
import com.calplus.ihrgstats.databasemanager.F16_Admins;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless, DB-backed tests for {@link CommandAdmins} - same user.dir-redirect
 * bootstrap pattern as CommandLogicSmokeTest/RoundCsvProcessorPipelineTest.
 */
public class CommandAdminsTest {

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
    }

    @Test
    void handleRemoveConfirm_guardsPerPlatform_notCombinedTotal() throws Exception {
        // Regression test: the lockout guard used to check the COMBINED admin
        // count across both platforms, so a lone Telegram admin + a lone
        // Discord admin (combined total = 2) could remove themselves and
        // lock out all Telegram administration despite the guard's intent.
        F16_Admins admins = new F16_Admins();
        admins.addAdmin(F16_Admins.PLATFORM_TELEGRAM, "111111", "Sole Telegram admin", NOW);
        admins.addAdmin(F16_Admins.PLATFORM_DISCORD, "222222222222222222", "Sole Discord admin", NOW);

        int telegramAdminRowId = admins.getAllAdmins().stream()
                .filter(a -> F16_Admins.PLATFORM_TELEGRAM.equals(a.platform))
                .findFirst().orElseThrow().id;

        CommandAdmins commandAdmins = new CommandAdmins();
        CommandResponse response = commandAdmins.handleRemoveConfirm("111111", telegramAdminRowId);

        assertTrue(response.message.contains("last remaining"),
                "Removing the sole Telegram admin must be refused even though a Discord admin also exists: " + response.message);
        assertTrue(admins.isAdmin(F16_Admins.PLATFORM_TELEGRAM, "111111"),
                "The sole Telegram admin must still be present after the refused removal");
    }

    @Test
    void handleRemoveConfirm_allowsRemovalWhenAnotherAdminExistsOnTheSamePlatform() throws Exception {
        F16_Admins admins = new F16_Admins();
        admins.addAdmin(F16_Admins.PLATFORM_TELEGRAM, "111111", "Admin one", NOW);
        admins.addAdmin(F16_Admins.PLATFORM_TELEGRAM, "333333", "Admin two", NOW);

        int firstAdminRowId = admins.getAllAdmins().stream()
                .filter(a -> "111111".equals(a.platformUserId))
                .findFirst().orElseThrow().id;

        CommandAdmins commandAdmins = new CommandAdmins();
        CommandResponse response = commandAdmins.handleRemoveConfirm("333333", firstAdminRowId);

        assertTrue(response.message.contains("Removed admin"), "Removal should succeed: " + response.message);
        assertFalse(admins.isAdmin(F16_Admins.PLATFORM_TELEGRAM, "111111"));
        assertTrue(admins.isAdmin(F16_Admins.PLATFORM_TELEGRAM, "333333"));
    }

    /**
     * Regression: adding an already-registered admin used to reply with a
     * false "✅ Added" even though the insert-if-absent no-opped. It must
     * report "already an admin" and leave the table unchanged.
     */
    @Test
    void addingAnExistingAdmin_replysAlreadyAnAdmin_notAFalseAdded() throws Exception {
        F16_Admins admins = new F16_Admins();
        admins.addAdmin(F16_Admins.PLATFORM_TELEGRAM, "111111", "Acting admin", NOW);
        admins.addAdmin(F16_Admins.PLATFORM_TELEGRAM, "555555", "Already there", NOW);
        int before = admins.countAdmins();

        CommandAdmins commandAdmins = new CommandAdmins();
        commandAdmins.handleAddStart("111111");
        CommandResponse duplicate = commandAdmins.handleTextInput("111111", "TELEGRAM 555555 Duplicate Label");

        assertNotNull(duplicate, "the acting admin is mid-add-flow, so the text must be consumed by it");
        assertTrue(duplicate.message.contains("already an admin"),
                "adding an existing admin must say so: " + duplicate.message);
        assertFalse(duplicate.message.contains("✅ Added"),
                "the old false success reply must not appear: " + duplicate.message);
        assertEquals(before, admins.countAdmins(), "the duplicate add must not change the admin table");

        // A genuinely new admin through the same flow still gets the success reply.
        commandAdmins.handleAddStart("111111");
        CommandResponse added = commandAdmins.handleTextInput("111111", "TELEGRAM 777777 Genuinely New");
        assertTrue(added.message.contains("✅ Added"), added.message);
        assertTrue(admins.isAdmin(F16_Admins.PLATFORM_TELEGRAM, "777777"));
    }
}
