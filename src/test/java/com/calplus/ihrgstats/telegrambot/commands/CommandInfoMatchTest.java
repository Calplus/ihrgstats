package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.telegrambot.utils.RoundCsvProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for /infomatch's all-walkover matchup display: the
 * forfeiting side used to be shown with "walkoverWins - winner" points (e.g.
 * "3-2" for 5 walkover boards) instead of a full 0 - by right the losing
 * (walkover) side should not get any points at all.
 */
public class CommandInfoMatchTest {

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
        new A2_MatchTypes().createMatchType("TestType", 20.0, null, "Test match type", NOW);
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
    void allWalkoverMatchup_forfeitingSideGetsZeroPoints(@TempDir Path csvDir) throws Exception {
        // Hall 1 fields 5 separate walkover wins this round, with no real
        // opponent boards at all. By right the forfeiting side gets zero
        // points; the old "hall1Score - winner" convention gave it 2.
        Path csv = writeRoundCsv(csvDir, "r1.csv",
                "A1,1,,WALKOVER,,\n" +
                "A2,1,,WALKOVER,,\n" +
                "A3,1,,WALKOVER,,\n" +
                "A4,1,,WALKOVER,,\n" +
                "A5,1,,WALKOVER,,\n");
        assertTrue(newProcessor().processRound(csv.toString(), YEAR, 1, NOW), "Round should process");

        CommandInfoMatch infoMatch = new CommandInfoMatch();
        infoMatch.handleCommand(ADMIN_USER_ID);
        CommandInfoMatch.MatchResponse response = infoMatch.handleRoundSelection(ADMIN_USER_ID, YEAR + "_1");

        assertTrue(response.message.contains("3-0"),
                "5 walkover boards must default to a 3-0 shutout (winner=ceil(5/2), loser=0), not 3-2: " + response.message);
    }

    @Test
    void roundPicker_spansEveryYear_notJustTheCurrentOne(@TempDir Path csvDir) throws Exception {
        // A round processed in 2025, then the active year moves on to 2026.
        // The round picker (and a direct year-qualified selection) must
        // still be able to reach the 2025 round, not just 2026's.
        System.setProperty("SETTINGS_CURRENTYEAR", "2025");
        Path r2025 = writeRoundCsv(csvDir, "r2025.csv", "Persimmon Vance,Crescent,10,Quillon Ashby,Binjai,5\n");
        assertTrue(newProcessor().processRound(r2025.toString(), 2025, 1, NOW), "2025 round should process");

        System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(YEAR));
        Path r2026 = writeRoundCsv(csvDir, "r2026.csv", "Cornelius Fitzgerald,1,10,Desdemona Ashworth,2,5\n");
        assertTrue(newProcessor().processRound(r2026.toString(), YEAR, 1, NOW), "2026 round should process");

        CommandInfoMatch infoMatch = new CommandInfoMatch();
        CommandInfoMatch.MatchResponse picker = infoMatch.handleCommand(ADMIN_USER_ID);
        assertNotNull(picker.buttonConfig, "The round picker must offer buttons");
        boolean has2025Button = Arrays.asList(picker.buttonConfig.labels).stream().anyMatch(l -> l.contains("2025"));
        boolean has2026Button = Arrays.asList(picker.buttonConfig.labels).stream().anyMatch(l -> l.contains("2026"));
        assertTrue(has2025Button, "The picker must offer the 2025 round, not just the current year's");
        assertTrue(has2026Button, "The picker must still offer the current year's round too");

        CommandInfoMatch.MatchResponse response2025 = infoMatch.handleRoundSelection(ADMIN_USER_ID, "2025_1");
        assertTrue(response2025.message.contains("Crescent") && response2025.message.contains("Binjai"),
                "Selecting the 2025 round must return THAT round's match info (Crescent vs Binjai), not 2026's: " + response2025.message);
    }
}
