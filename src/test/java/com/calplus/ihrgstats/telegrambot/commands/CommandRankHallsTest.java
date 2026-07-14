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
 * Regression test for /rankhalls's new "All Years" option - the current-year
 * roster must stay scoped to settings.currentYear, but All-Years must
 * include halls whose only activity was in a past year.
 */
public class CommandRankHallsTest {

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
    void allYears_includesHallsActiveOnlyInAPastYear(@TempDir Path csvDir) throws Exception {
        // Binjai/Crescent only ever played in 2025; Saraca/Tamarind play in
        // the current year 2026. "All Rounds" (current year) must only see
        // Saraca/Tamarind; "All Years" must see all four (named halls used
        // to avoid any ambiguity with numeric rank/count columns).
        System.setProperty("SETTINGS_CURRENTYEAR", "2025");
        Path r2025 = writeRoundCsv(csvDir, "r2025.csv", "Persimmon Vance,Binjai,10,Quillon Ashby,Crescent,5\n");
        assertTrue(newProcessor().processRound(r2025.toString(), 2025, 1, NOW), "2025 round should process");

        System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(YEAR));
        Path r2026 = writeRoundCsv(csvDir, "r2026.csv", "Cornelius Fitzgerald,Saraca,10,Desdemona Ashworth,Tamarind,5\n");
        assertTrue(newProcessor().processRound(r2026.toString(), YEAR, 1, NOW), "2026 round should process");

        CommandRankHalls rankHalls = new CommandRankHalls();
        rankHalls.handleCommand(ADMIN_USER_ID);

        CommandRankHalls.RankResponse currentYearOnly = rankHalls.handleRoundSelection(ADMIN_USER_ID, "all");
        assertTrue(currentYearOnly.message.contains("Saraca"),
                "2026's Saraca hall must appear in the current-year view: " + currentYearOnly.message);
        assertFalse(currentYearOnly.message.contains("Binjai"),
                "2025-only Binjai hall must NOT appear in the current-year view: " + currentYearOnly.message);

        CommandRankHalls.RankResponse allYears = rankHalls.handleRoundSelection(ADMIN_USER_ID, "allyears");
        assertTrue(allYears.message.contains("Binjai"),
                "All-Years view must include halls whose only activity was in 2025: " + allYears.message);
        assertTrue(allYears.message.contains("Crescent"),
                "All-Years view must include Crescent too: " + allYears.message);
    }
}
