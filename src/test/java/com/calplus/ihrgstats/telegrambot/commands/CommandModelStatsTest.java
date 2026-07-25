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
 * Headless, DB-backed tests for {@link CommandModelStats}: admin gating,
 * the "nothing trained yet" message, and the populated report (champion
 * summary, leaderboard, live scorecard) once real rounds have been
 * uploaded through the actual pipeline.
 */
public class CommandModelStatsTest {

    private static final int YEAR = 2026;
    private static final String NOW = "2026-01-01 00:00:00.000";
    private static final String ADMIN_ID = "999";
    private static final String NON_ADMIN_ID = "111";

    private String originalUserDir;
    private String originalYearProperty;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        originalYearProperty = System.getProperty("SETTINGS_CURRENTYEAR");
        System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(YEAR));

        new DatabaseSchema().createDatabase("default.db");
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
        if (originalYearProperty != null) {
            System.setProperty("SETTINGS_CURRENTYEAR", originalYearProperty);
        } else {
            System.clearProperty("SETTINGS_CURRENTYEAR");
        }
    }

    @Test
    void handleCommand_deniesNonAdmin() throws Exception {
        new F16_Admins().addAdmin(F16_Admins.PLATFORM_TELEGRAM, ADMIN_ID, "Test Admin", NOW);

        CommandModelStats modelStats = new CommandModelStats();
        CommandResponse response = modelStats.handleCommand(NON_ADMIN_ID);
        assertTrue(response.message.contains("Access Denied"));
    }

    @Test
    void handleCommand_admin_noModelsTrainedYet_saysSo() throws Exception {
        new F16_Admins().addAdmin(F16_Admins.PLATFORM_TELEGRAM, ADMIN_ID, "Test Admin", NOW);

        CommandModelStats modelStats = new CommandModelStats();
        CommandResponse response = modelStats.handleCommand(ADMIN_ID);
        assertTrue(response.message.contains("No AI models have been trained yet"));
    }

    @Test
    void handleCommand_admin_afterTraining_showsChampionLeaderboardAndScorecard() throws Exception {
        new A3_Halls().seedDefaults(NOW);
        new B4_Players().seedDefaults(NOW);
        new D10_RatingTypes().seedDefaults(NOW);
        new F16_Admins().addAdmin(F16_Admins.PLATFORM_TELEGRAM, ADMIN_ID, "Test Admin", NOW);

        Path csvDir = Files.createTempDirectory("modelstats-fixture");
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

        assertFalse(new E17_MlModels().getRecent(50).isEmpty(), "Fixture setup should have trained models");
        assertFalse(new E14_AiPredictions().getAllPredictions().isEmpty(), "Fixture setup should have logged predictions");

        CommandModelStats modelStats = new CommandModelStats();
        CommandResponse response = modelStats.handleCommand(ADMIN_ID);

        assertTrue(response.message.contains("Champion:"));
        assertTrue(response.message.contains("Leaderboard"));
        assertTrue(response.message.contains("Live scorecard"));
        assertTrue(response.message.contains("Predicted-outcome hit rate"));
        assertTrue(response.message.contains("Honest limits"));
    }
}
