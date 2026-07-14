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
 * Regression test for the "LR" column: /help describes it as "Last Round
 * where the player actually competed", but it used to show the round of the
 * player's latest RATING row - which advances for every round the player's
 * hall played, even a round the player personally sat out (a real Glicko-2
 * RD-growth requirement, not evidence of having played).
 */
public class CommandRankPlayersTest {

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
    void lrColumn_showsThePlayersActualLastPlayedRound_notTheirHallsMostRecentRound(@TempDir Path csvDir) throws Exception {
        // Round 1: Aurelia Nightshade (hall 1) actually plays.
        Path r1 = writeRoundCsv(csvDir, "r1.csv", "Aurelia Nightshade,1,10,Bartholomew Krieger,2,5\n");
        assertTrue(newProcessor().processRound(r1.toString(), YEAR, 1, NOW), "Round 1 should process");

        // Round 2: a DIFFERENT hall-1 player plays, so hall 1 "played" this
        // round and Aurelia Nightshade (who personally sat out) is carried
        // forward with an RD-growth-only rating row for round 2 - not
        // evidence she played it. Names deliberately don't resemble each
        // other or the round-1 names (no accidental fuzzy-match collision).
        Path r2 = writeRoundCsv(csvDir, "r2.csv", "Cornelius Fitzgerald,1,10,Desdemona Ashworth,2,5\n");
        assertTrue(newProcessor().processRound(r2.toString(), YEAR, 2, NOW), "Round 2 should process");

        CommandRankPlayers rankPlayers = new CommandRankPlayers();
        rankPlayers.handleCommand(ADMIN_USER_ID);
        CommandRankPlayers.RankResponse response = rankPlayers.handleRoundSelection(ADMIN_USER_ID, "all");

        // Extract Aurelia Nightshade's row from the rendered table and check its LR cell.
        String[] lines = response.message.split("\n");
        String targetRow = null;
        for (String line : lines) {
            if (line.contains("Aurelia")) {
                targetRow = line;
                break;
            }
        }
        assertNotNull(targetRow, "Aurelia Nightshade should appear in the rankings table: " + response.message);
        assertTrue(targetRow.contains("R1"),
                "Aurelia Nightshade's LR column must show R1 (her actual last-played round), not R2 (her hall's most recent round): " + targetRow);
        assertFalse(targetRow.contains("R2"),
                "Aurelia Nightshade never played round 2 - it must not appear as her LR: " + targetRow);
    }

    @Test
    void allYears_includesPlayersFromEveryYear_notJustTheCurrentOne(@TempDir Path csvDir) throws Exception {
        // A player active only in 2025, then the active year moves on to 2026
        // with a different player. "All Rounds" (current year) must only see
        // the 2026 player; "All Years" must see both.
        System.setProperty("SETTINGS_CURRENTYEAR", "2025");
        Path r2025 = writeRoundCsv(csvDir, "r2025.csv", "Persimmon Vance,1,10,Quillon Ashby,2,5\n");
        assertTrue(newProcessor().processRound(r2025.toString(), 2025, 1, NOW), "2025 round should process");

        System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(YEAR));
        Path r2026 = writeRoundCsv(csvDir, "r2026.csv", "Cornelius Fitzgerald,1,10,Desdemona Ashworth,2,5\n");
        assertTrue(newProcessor().processRound(r2026.toString(), YEAR, 1, NOW), "2026 round should process");

        CommandRankPlayers rankPlayers = new CommandRankPlayers();
        rankPlayers.handleCommand(ADMIN_USER_ID);

        CommandRankPlayers.RankResponse currentYearOnly = rankPlayers.handleRoundSelection(ADMIN_USER_ID, "all");
        assertTrue(currentYearOnly.message.contains("Cornelius"), "2026's player must appear in the current-year view");
        assertFalse(currentYearOnly.message.contains("Persimmon"), "2025's player must NOT appear in the current-year view");

        CommandRankPlayers.RankResponse allYears = rankPlayers.handleRoundSelection(ADMIN_USER_ID, "allyears");
        assertTrue(allYears.message.contains("Cornelius"), "All-Years view must include the 2026 player");
        assertTrue(allYears.message.contains("Persimmon"), "All-Years view must ALSO include the 2025-only player");
    }
}
