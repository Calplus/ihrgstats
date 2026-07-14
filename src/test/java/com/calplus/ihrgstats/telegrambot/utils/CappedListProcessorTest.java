package com.calplus.ihrgstats.telegrambot.utils;

import com.calplus.ihrgstats.databasemanager.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless, DB-backed tests for {@link CappedListProcessor} - same
 * user.dir-redirect bootstrap pattern as the other pipeline tests.
 */
public class CappedListProcessorTest {

    private static final int YEAR = 2026;
    private static final String NOW = "2026-01-01 00:00:00.000";

    private String originalUserDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());

        new DatabaseSchema().createDatabase("default.db");
        new A3_Halls().seedDefaults(NOW);
        new B4_Players().seedDefaults(NOW);
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    private static Path writeCsv(Path dir, String fileName, String dataRows) throws Exception {
        Path csv = dir.resolve(fileName);
        Files.writeString(csv, "name,hall\n" + dataRows);
        return csv;
    }

    @Test
    void processCappedList_handlesQuotedCommaContainingName(@TempDir Path csvDir) throws Exception {
        // Regression test for A4: the parser used to be a naive line.split(",", -1),
        // which breaks a quoted, comma-containing name into 3 fields instead of 2.
        // Real AY24 data contains exactly this shape of name.
        Path csv = writeCsv(csvDir, "cappedlist.csv", "\"Nightingale, Florence\",4\n");
        CappedListProcessor processor = new CappedListProcessor();

        assertTrue(processor.processCappedList(csv.toString(), YEAR, NOW),
                "A quoted, comma-containing name must not be rejected as a malformed row");

        List<B7_CappedImports.ImportRow> imports = new B7_CappedImports().getImportsForYear(YEAR);
        assertEquals(1, imports.size());
        assertEquals("Nightingale, Florence", imports.get(0).name,
                "The comma inside the quoted name must be preserved, not treated as a field separator");
    }

    @Test
    void processCappedList_reuploadWithoutAName_unCapsThatPlayer(@TempDir Path csvDir) throws Exception {
        // Regression test for A5: cappedlist upload is "full replace" for the
        // capped_imports staging table, but the old code only ever SET
        // capped=true, so a player removed from a corrected list stayed
        // capped forever. Set up an existing player_year_status row directly
        // (as if they'd already appeared in a round this year).
        B4_Players players = new B4_Players();
        B5_PlayerNames playerNames = new B5_PlayerNames();
        B6_PlayerYearStatus playerYearStatus = new B6_PlayerYearStatus();
        A3_Halls halls = new A3_Halls();

        String playerId = players.generateNewPlayerId(halls.getHallByName("4").hallCode, NOW);
        playerNames.addOrUpdateName(playerId, "Test Player", YEAR, NOW);
        playerYearStatus.upsertStatus(playerId, YEAR, halls.getHallByName("4").id, false, true, NOW);

        CappedListProcessor processor = new CappedListProcessor();

        // First upload: caps the player.
        Path csv1 = writeCsv(csvDir, "cappedlist1.csv", "Test Player,4\n");
        assertTrue(processor.processCappedList(csv1.toString(), YEAR, NOW));
        assertTrue(playerYearStatus.getStatus(playerId, YEAR).capped, "Player should be capped after the first upload");

        // Second upload: a corrected list that no longer includes this player.
        Path csv2 = writeCsv(csvDir, "cappedlist2.csv", "Someone Else,5\n");
        assertTrue(processor.processCappedList(csv2.toString(), YEAR, NOW));
        assertFalse(playerYearStatus.getStatus(playerId, YEAR).capped,
                "Player removed from the corrected list must be un-capped, not left capped forever");
    }

    @Test
    void processCappedList_twoSameNamedPlayers_capsOnlyTheHallMatchingOne(@TempDir Path csvDir) throws Exception {
        // Regression test: two DISTINCT players sharing an exact name (a real
        // case per B5_PlayerNames' own javadoc), each already active this
        // year in a different hall. Before the fix, the capped-list match
        // always took the first candidate found (an arbitrary/most-recently-
        // active pick), capping the wrong player whenever the entry's own
        // hall column could have disambiguated them correctly.
        B4_Players players = new B4_Players();
        B5_PlayerNames playerNames = new B5_PlayerNames();
        B6_PlayerYearStatus playerYearStatus = new B6_PlayerYearStatus();
        A3_Halls halls = new A3_Halls();

        String playerIdHall4 = players.generateNewPlayerId(halls.getHallByName("4").hallCode, NOW);
        playerNames.addOrUpdateName(playerIdHall4, "Same Name", YEAR, NOW);
        playerYearStatus.upsertStatus(playerIdHall4, YEAR, halls.getHallByName("4").id, false, true, NOW);

        String playerIdHall5 = players.generateNewPlayerId(halls.getHallByName("5").hallCode, NOW);
        playerNames.addOrUpdateName(playerIdHall5, "Same Name", YEAR, NOW);
        playerYearStatus.upsertStatus(playerIdHall5, YEAR, halls.getHallByName("5").id, false, true, NOW);

        CappedListProcessor processor = new CappedListProcessor();
        Path csv = writeCsv(csvDir, "cappedlist.csv", "Same Name,5\n");
        assertTrue(processor.processCappedList(csv.toString(), YEAR, NOW));

        assertTrue(playerYearStatus.getStatus(playerIdHall5, YEAR).capped,
                "The hall-5 player, matching the capped list's stated hall, should be capped");
        assertFalse(playerYearStatus.getStatus(playerIdHall4, YEAR).capped,
                "The hall-4 player must NOT be capped just because they share a name with the hall-5 player");
    }
}
