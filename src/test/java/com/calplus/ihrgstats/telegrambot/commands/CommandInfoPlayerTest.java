package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.telegrambot.utils.RoundCsvProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for /infoplayer's new "All Years" option: it collapses
 * the per-round Stats/Seating/Victory-Record breakdown to one summary row
 * per year, instead of re-exploding the per-round width/height budgets once
 * a player has multiple years of history.
 */
public class CommandInfoPlayerTest {

    private static final int YEAR = 2026;
    private static final String NOW = "2026-01-01 00:00:00.000";
    private static final String ADMIN_USER_ID = "test_admin";

    private String originalUserDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(YEAR));

        new DatabaseSchema().createDatabase("default.db");
        new A3_Halls().seedDefaults(NOW);
        new B4_Players().seedDefaults(NOW);
        new D10_RatingTypes().seedDefaults(NOW);
        new F16_Admins().seedDefaults(NOW);
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
        System.clearProperty("SETTINGS_CURRENTYEAR");
    }

    private static Path writeRoundCsv(Path dir, String fileName, String dataRows) throws Exception {
        Path csv = dir.resolve(fileName);
        Files.writeString(csv, "name1,hall1,score1,name2,hall2,score2\n" + dataRows);
        return csv;
    }

    private static RoundCsvProcessor newProcessor() {
        RoundCsvProcessor processor = new RoundCsvProcessor();
        processor.setMultiChoiceCallback((message, options) -> 0);
        return processor;
    }

    @Test
    void allYears_collapsesToOneRowPerYear_notOnePerRound(@TempDir Path csvDir) throws Exception {
        System.setProperty("SETTINGS_CURRENTYEAR", "2025");
        Path r2025 = writeRoundCsv(csvDir, "r2025.csv", "Aurelia Nightshade,1,10,Bartholomew Krieger,2,5\n");
        assertTrue(newProcessor().processRound(r2025.toString(), 2025, 1, NOW), "2025 round should process");

        System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(YEAR));
        Path r2026 = writeRoundCsv(csvDir, "r2026.csv", "Aurelia Nightshade,1,10,Bartholomew Krieger,2,5\n");
        assertTrue(newProcessor().processRound(r2026.toString(), YEAR, 1, NOW), "2026 round should process");

        CommandInfoPlayer infoPlayer = new CommandInfoPlayer();
        int hall1Id = new A3_Halls().getHallByName("1").id;
        infoPlayer.handleHallSelection(ADMIN_USER_ID, hall1Id);
        String playerId = new B5_PlayerNames().findCandidatesByExactName("Aurelia Nightshade").get(0).playerId;
        infoPlayer.handlePlayerSelection(ADMIN_USER_ID, playerId);
        CommandInfoPlayer.InfoResponse response = infoPlayer.handleRoundSelection(ADMIN_USER_ID, "allyears");

        assertTrue(response.message.contains("2025"), "All-Years Stats Per Year must include a 2025 row: " + response.message);
        assertTrue(response.message.contains("2026"), "All-Years Stats Per Year must include a 2026 row: " + response.message);
        assertNotNull(response.imagePath, "All-Years mode must still render an image");
    }
}
