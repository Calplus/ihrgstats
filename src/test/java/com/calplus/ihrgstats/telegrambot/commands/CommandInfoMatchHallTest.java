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
 * Regression test for /infomatchhall's opponent/score tallying: it used to
 * pick the FIRST non-walkover opponent hall found and sum ALL boards'
 * outcomes into one combined score, regardless of which opponent hall each
 * board was actually against - the same bug already fixed in
 * CommandInfoHall/CommandCompareHalls's calculateHallVictoryRecords (see
 * CommandCompareHallsTest's equivalent multi-opponent test), ported here.
 */
public class CommandInfoMatchHallTest {

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
    void multiOpponentRound_reportsPrimaryOpponentOnly_notACombinedScoreAcrossBoth(@TempDir Path csvDir) throws Exception {
        // Hall 1 fields 3 players: 2 boards vs hall 2 (both won), 1 board vs
        // hall 3 (lost). Hall 2 is the primary opponent (2 boards vs 1) -
        // the record must show hall 1's real 2-0 sweep of hall 2, not a
        // fabricated score built from mismatched totals against two halls.
        Path csv = writeRoundCsv(csvDir, "r1.csv",
                "A1,1,10,B1,2,5\n" +
                "A2,1,10,B2,2,5\n" +
                "A3,1,5,C1,3,10\n");
        assertTrue(newProcessor().processRound(csv.toString(), YEAR, 1, NOW), "Round should process");

        CommandInfoMatchHall infoMatchHall = new CommandInfoMatchHall();
        int hall1Id = new A3_Halls().getHallByName("1").id;
        infoMatchHall.handleCommand(ADMIN_USER_ID);
        infoMatchHall.handleHallSelection(ADMIN_USER_ID, hall1Id);
        CommandInfoMatchHall.InfoResponse response = infoMatchHall.handleRoundSelection(ADMIN_USER_ID, YEAR + "_1");

        assertTrue(response.message.contains("2-0"),
                "Hall 1 swept its actual (majority) opponent Hall 2 2-0: " + response.message);
        assertFalse(response.message.contains("Hall 3") && response.message.contains("2-1"),
                "Hall 1 lost its single board against Hall 3 (0-1) - it must never be shown as a 2-1 win over Hall 3: " + response.message);
    }

    @Test
    void walkoverAgainstAKnownHall_isFoldedIntoThatHallsScore_notDroppedEntirely(@TempDir Path csvDir) throws Exception {
        // Hall 1 fields 2 real boards vs Hall 2 (both won) PLUS one walkover
        // board where the forfeiting side's hall (Hall 2) is specified in the
        // CSV - the true result is a 3-0 sweep, but the walkover used to be
        // dropped into an unattributed bucket entirely, showing only "2-0".
        Path csv = writeRoundCsv(csvDir, "r1.csv",
                "A1,1,10,B1,2,5\n" +
                "A2,1,10,B2,2,5\n" +
                "A3,1,,WALKOVER,2,\n");
        assertTrue(newProcessor().processRound(csv.toString(), YEAR, 1, NOW), "Round should process");

        CommandInfoMatchHall infoMatchHall = new CommandInfoMatchHall();
        int hall1Id = new A3_Halls().getHallByName("1").id;
        infoMatchHall.handleCommand(ADMIN_USER_ID);
        infoMatchHall.handleHallSelection(ADMIN_USER_ID, hall1Id);
        CommandInfoMatchHall.InfoResponse response = infoMatchHall.handleRoundSelection(ADMIN_USER_ID, YEAR + "_1");

        assertTrue(response.message.contains("3-0"),
                "The walkover win against a known Hall 2 must be folded into the real score (3-0), not dropped: " + response.message);
        assertFalse(response.message.contains("2-0"),
                "Must not show the old, incomplete 2-0 score that ignored the walkover board: " + response.message);
    }

    @Test
    void allWalkoverRound_withNoOpponentHallSpecified_forfeitingSideGetsZeroPoints(@TempDir Path csvDir) throws Exception {
        // 5 separate walkover boards, none naming an opponent hall - the only
        // signal is the board count. By right the forfeiting side gets zero
        // points; the old "walkoverCount - winner" convention gave it 2.
        Path csv = writeRoundCsv(csvDir, "r1.csv",
                "A1,1,,WALKOVER,,\n" +
                "A2,1,,WALKOVER,,\n" +
                "A3,1,,WALKOVER,,\n" +
                "A4,1,,WALKOVER,,\n" +
                "A5,1,,WALKOVER,,\n");
        assertTrue(newProcessor().processRound(csv.toString(), YEAR, 1, NOW), "Round should process");

        CommandInfoMatchHall infoMatchHall = new CommandInfoMatchHall();
        int hall1Id = new A3_Halls().getHallByName("1").id;
        infoMatchHall.handleCommand(ADMIN_USER_ID);
        infoMatchHall.handleHallSelection(ADMIN_USER_ID, hall1Id);
        CommandInfoMatchHall.InfoResponse response = infoMatchHall.handleRoundSelection(ADMIN_USER_ID, YEAR + "_1");

        assertTrue(response.message.contains("3-0"),
                "5 unattributed walkovers must default to a 3-0 shutout (winner=ceil(5/2), loser=0), not 3-2: " + response.message);
    }

    @Test
    void roundPicker_spansEveryYear_notJustTheCurrentOne(@TempDir Path csvDir) throws Exception {
        // A round processed in 2025, then the active year moves on to 2026.
        // The round picker (and a direct year-qualified selection) must
        // still be able to reach the 2025 round for the same hall.
        System.setProperty("SETTINGS_CURRENTYEAR", "2025");
        Path r2025 = writeRoundCsv(csvDir, "r2025.csv", "A1,1,10,B1,2,3\n");
        assertTrue(newProcessor().processRound(r2025.toString(), 2025, 1, NOW), "2025 round should process");

        System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(YEAR));
        Path r2026 = writeRoundCsv(csvDir, "r2026.csv", "C1,1,10,D1,2,7\n");
        assertTrue(newProcessor().processRound(r2026.toString(), YEAR, 1, NOW), "2026 round should process");

        CommandInfoMatchHall infoMatchHall = new CommandInfoMatchHall();
        int hall1Id = new A3_Halls().getHallByName("1").id;
        infoMatchHall.handleCommand(ADMIN_USER_ID);
        CommandInfoMatchHall.InfoResponse picker = infoMatchHall.handleHallSelection(ADMIN_USER_ID, hall1Id);
        assertNotNull(picker.buttonConfig, "The round picker must offer buttons");
        boolean has2025Button = java.util.Arrays.asList(picker.buttonConfig.labels).stream().anyMatch(l -> l.contains("2025"));
        assertTrue(has2025Button, "The picker must offer the 2025 round, not just the current year's");

        // handleHallSelection consumed the pending selection state above, so
        // re-select the hall before picking a round, matching real usage.
        infoMatchHall.handleHallSelection(ADMIN_USER_ID, hall1Id);
        CommandInfoMatchHall.InfoResponse response2025 = infoMatchHall.handleRoundSelection(ADMIN_USER_ID, "2025_1");
        assertTrue(response2025.message.contains("10-3"),
                "Selecting the 2025 round must return THAT round's match detail (10-3), not 2026's (10-7): " + response2025.message);
    }
}
