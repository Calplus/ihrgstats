package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.telegrambot.utils.RoundCsvProcessor;
import com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end, DB-backed tests for {@link CommandLineup}: admin gating,
 * the required-settings guards, and - once a real two-hall history has
 * been uploaded through the live pipeline (enough to train a model and
 * give both halls seating history) - that the full report actually
 * renders every section the plan calls for.
 */
public class CommandLineupTest {

    private static final int YEAR = 2026;
    private static final String NOW = "2026-01-01 00:00:00.000";
    private static final String ADMIN_ID = "999";
    private static final String NON_ADMIN_ID = "111";

    private String originalUserDir;
    private String originalYearProperty;
    private String originalHomeHallProperty;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        originalYearProperty = System.getProperty("SETTINGS_CURRENTYEAR");
        originalHomeHallProperty = System.getProperty("SETTINGS_HOMEHALL");
        System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(YEAR));

        new DatabaseSchema().createDatabase("default.db");
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
        restore("SETTINGS_CURRENTYEAR", originalYearProperty);
        restore("SETTINGS_HOMEHALL", originalHomeHallProperty);
    }

    private static void restore(String key, String value) {
        if (value != null) {
            System.setProperty(key, value);
        } else {
            System.clearProperty(key);
        }
    }

    @Test
    void handleCommand_deniesNonAdmin() throws Exception {
        new F16_Admins().addAdmin(F16_Admins.PLATFORM_TELEGRAM, ADMIN_ID, "Test Admin", NOW);
        CommandLineup lineup = new CommandLineup();
        assertTrue(lineup.handleCommand(NON_ADMIN_ID).message.contains("Access Denied"));
    }

    @Test
    void handleOpponentHallSelection_deniesNonAdmin() throws Exception {
        new F16_Admins().addAdmin(F16_Admins.PLATFORM_TELEGRAM, ADMIN_ID, "Test Admin", NOW);
        CommandLineup lineup = new CommandLineup();
        assertTrue(lineup.handleOpponentHallSelection(NON_ADMIN_ID, 1).message.contains("Access Denied"));
    }

    @Test
    void handleOpponentHallSelection_withoutHomeHallConfigured_reportsClearError() throws Exception {
        new A3_Halls().seedDefaults(NOW);
        new F16_Admins().addAdmin(F16_Admins.PLATFORM_TELEGRAM, ADMIN_ID, "Test Admin", NOW);
        System.clearProperty("SETTINGS_HOMEHALL");

        int opponentHallId = new A3_Halls().getHallByName("2").id;
        CommandResponse response = new CommandLineup().handleOpponentHallSelection(ADMIN_ID, opponentHallId);
        assertTrue(response.message.contains("home hall"), response.message);
    }

    @Test
    void handleOpponentHallSelection_sameAsHomeHall_isRejected() throws Exception {
        new A3_Halls().seedDefaults(NOW);
        new F16_Admins().addAdmin(F16_Admins.PLATFORM_TELEGRAM, ADMIN_ID, "Test Admin", NOW);
        System.setProperty("SETTINGS_HOMEHALL", "1");

        int homeHallId = new A3_Halls().getHallByName("1").id;
        CommandResponse response = new CommandLineup().handleOpponentHallSelection(ADMIN_ID, homeHallId);
        assertTrue(response.message.contains("different"), response.message);
    }

    /** Full pipeline: 12 rounds of hall "1" vs hall "2", enough to train a model and build both halls' seating history. */
    @Test
    void fullReport_rendersEverySectionOnceHistoryAndAModelExist() throws Exception {
        new A3_Halls().seedDefaults(NOW);
        new B4_Players().seedDefaults(NOW);
        new D10_RatingTypes().seedDefaults(NOW);
        new F16_Admins().addAdmin(F16_Admins.PLATFORM_TELEGRAM, ADMIN_ID, "Test Admin", NOW);
        System.setProperty("SETTINGS_HOMEHALL", "1");

        Path csvDir = Files.createTempDirectory("lineup-fixture");
        RoundCsvProcessor processor = new RoundCsvProcessor();
        processor.setMultiChoiceCallback((message, options) -> 0);
        for (int order = 1; order <= 12; order++) {
            StringBuilder body = new StringBuilder("name1,hall1,score1,name2,hall2,score2\n");
            for (int i = 1; i <= 5; i++) {
                boolean p1Wins = (order + i) % 2 == 0;
                body.append(String.format("P%d,1,%d,Q%d,2,%d%n", i, p1Wins ? 8 : 2, i, p1Wins ? 2 : 8));
            }
            Path csv = csvDir.resolve("round_" + order + ".csv");
            Files.writeString(csv, body.toString());
            assertTrue(processor.processRound(csv.toString(), YEAR, order, NOW), "Round " + order + " should process successfully");
        }
        assertFalse(new E17_MlModels().getRecent(10).isEmpty(), "Fixture setup should have trained a model");

        int opponentHallId = new A3_Halls().getHallByName("2").id;
        CommandResponse response = new CommandLineup().handleOpponentHallSelection(ADMIN_ID, opponentHallId);

        String msg = response.message;
        assertTrue(msg.contains("Lineup vs"), msg);
        assertTrue(msg.contains("Predictor:"), msg);
        assertTrue(msg.contains("Opponent captain profile"), msg);
        assertTrue(msg.contains("Best response"), msg);
        assertTrue(msg.contains("Maximin"), msg);
        assertTrue(msg.contains("Strategy archetypes"), msg);
        assertTrue(msg.contains("Strength order"), msg);
        assertTrue(msg.contains("Single sacrifice"), msg);
        assertTrue(msg.contains("Double sacrifice"), msg);
        assertTrue(msg.contains("Free optimum"), msg);
        assertTrue(msg.contains("Per-board pairing"), msg);
        assertTrue(msg.contains("Reliability"), msg);
        assertTrue(msg.contains("Why this lineup"), msg);
    }
}
