package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.A2_MatchTypes;
import com.calplus.ihrgstats.databasemanager.DatabaseSchema;
import com.calplus.ihrgstats.databasemanager.F16_Admins;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless, DB-backed tests for the /matchtypes create wizard - the
 * step-by-step text-input flow (name, max score, time limit, description),
 * its per-step validation, and the fail-closed admin gate.
 */
public class CommandMatchTypesTest {

    private static final String NOW = "2026-01-01 00:00:00.000";
    private static final String ADMIN = "111111";
    private static final String STRANGER = "999999";

    private String originalUserDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        new DatabaseSchema().createDatabase("default.db");
        new F16_Admins().addAdmin(F16_Admins.PLATFORM_TELEGRAM, ADMIN, "Test admin", NOW);
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    @Test
    void createWizard_walksAllSteps_validatesEachInput_andPersistsTheMatchType() throws Exception {
        CommandMatchTypes command = new CommandMatchTypes();

        CommandResponse start = command.handleCreateNew(ADMIN);
        assertTrue(start.message.contains("type name"), "First step must ask for the type name: " + start.message);
        assertTrue(command.isAwaitingTextInput(ADMIN), "Wizard must now be awaiting text input");

        CommandResponse afterName = command.handleTextInput(ADMIN, "Weiqi Standard");
        assertTrue(afterName.message.contains("max score"), "Second step must ask for the max score: " + afterName.message);

        CommandResponse badScore = command.handleTextInput(ADMIN, "not-a-number");
        assertTrue(badScore.message.contains("❌"), "Non-numeric max score must be rejected: " + badScore.message);
        CommandResponse negativeScore = command.handleTextInput(ADMIN, "-5");
        assertTrue(negativeScore.message.contains("❌"), "Negative max score must be rejected: " + negativeScore.message);

        CommandResponse afterScore = command.handleTextInput(ADMIN, "368.5");
        assertTrue(afterScore.message.contains("time limit"), "Third step must ask for the time limit: " + afterScore.message);

        CommandResponse badMinutes = command.handleTextInput(ADMIN, "0");
        assertTrue(badMinutes.message.contains("❌"), "Zero time limit must be rejected: " + badMinutes.message);

        CommandResponse afterTime = command.handleTextInput(ADMIN, "none");
        assertTrue(afterTime.message.contains("description"), "Fourth step must ask for the description: " + afterTime.message);

        CommandResponse done = command.handleTextInput(ADMIN, "none");
        assertTrue(done.message.contains("✅ Created match type"), "Completion must confirm creation: " + done.message);
        assertFalse(command.isAwaitingTextInput(ADMIN), "Wizard state must be cleared after completion");

        List<A2_MatchTypes.MatchType> all = new A2_MatchTypes().getAllMatchTypes();
        assertEquals(1, all.size());
        assertEquals("Weiqi Standard", all.get(0).typeName);
        assertEquals(368.5, all.get(0).maxScore, 1e-9);
        assertNull(all.get(0).timeLimitMinutes, "\"none\" must persist as no time limit");
    }

    @Test
    void nonAdmin_isDeniedEverywhere_andTextInputWithoutAWizardIsIgnored() throws Exception {
        CommandMatchTypes command = new CommandMatchTypes();

        assertTrue(command.handleCreateNew(STRANGER).message.contains("Access Denied"));
        assertTrue(command.handleEditSelection(STRANGER).message.contains("Access Denied"));
        assertTrue(command.handleAssignSelection(STRANGER).message.contains("Access Denied"));

        assertNull(command.handleTextInput(STRANGER, "random chatter"),
                "Text from a user with no active wizard must be ignored, not answered");
        assertEquals(0, new A2_MatchTypes().getAllMatchTypes().size(), "Nothing may be created by a denied user");
    }

    @Test
    void cancel_discardsAHalfFinishedWizard() throws Exception {
        CommandMatchTypes command = new CommandMatchTypes();
        command.handleCreateNew(ADMIN);
        command.handleTextInput(ADMIN, "Halfway Type");

        CommandResponse cancelled = command.handleCancel(ADMIN);
        assertTrue(cancelled.message.contains("cancelled"), cancelled.message);
        assertFalse(command.isAwaitingTextInput(ADMIN));
        assertEquals(0, new A2_MatchTypes().getAllMatchTypes().size(), "A cancelled wizard must persist nothing");
    }
}
