package com.calplus.ihrgstats.telegrambot.commands;

import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.telegrambot.utils.HallStatsBuilder;
import com.calplus.ihrgstats.telegrambot.utils.RoundCsvProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for A30 (hall-vs-hall victory records mixing totals from
 * different opponents) and A33 (win-probability tie-handling and denominator)
 * - same user.dir-redirect bootstrap pattern as CommandLogicSmokeTest.
 */
public class CommandCompareHallsTest {

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

    // --- A30: per-opponent tallies, not one combined total per hall ---

    @Test
    void hallVictoryRecord_walkoverBonusDoesNotSkewRealOpponentScore(@TempDir Path csvDir) throws Exception {
        // Hall 1 fields 3 players this round: A1 beats B1 (hall 2), A2 loses
        // to B2 (hall 2), and A3 gets a walkover win (no real opponent).
        // The TRUE head-to-head against hall 2 is a 1-1 draw; the walkover
        // win is a separate bonus that has nothing to do with hall 2.
        Path csv = writeRoundCsv(csvDir, "r1.csv",
                "A1,1,10,B1,2,5\n" +
                "A2,1,5,B2,2,10\n" +
                "A3,1,,WALKOVER,,\n");
        assertTrue(newProcessor().processRound(csv.toString(), YEAR, 1, NOW), "Round should process");

        CommandCompareHalls compareHalls = new CommandCompareHalls();
        int hall1Id = new A3_Halls().getHallByName("1").id;
        int hall2Id = new A3_Halls().getHallByName("2").id;
        compareHalls.handleCommand(ADMIN_USER_ID);
        compareHalls.handleFirstHallSelection(ADMIN_USER_ID, hall1Id);
        compareHalls.handleSecondHallSelection(ADMIN_USER_ID, hall2Id);
        CommandCompareHalls.CompareResponse response = compareHalls.handleRoundSelection(ADMIN_USER_ID, "all");

        assertTrue(response.message.contains("1-1"),
                "The real head-to-head vs Hall 2 should be a 1-1 draw, not inflated by the separate walkover win: " + response.message);
        assertFalse(response.message.contains("2-1"),
                "The walkover win must not be folded into the score shown against Hall 2: " + response.message);
    }

    @Test
    void hallVictoryRecord_walkoverAgainstAKnownHall_isFoldedIntoThatHallsScore(@TempDir Path csvDir) throws Exception {
        // Unlike the unattributed-walkover case above, here the forfeiting
        // side's hall (Hall 2) IS specified in the CSV. The true result vs
        // Hall 2 is a 3-0 sweep (2 real wins + 1 walkover win) - it used to
        // be dropped into the unattributed bucket regardless, showing only "2-0".
        Path csv = writeRoundCsv(csvDir, "r1.csv",
                "A1,1,10,B1,2,5\n" +
                "A2,1,10,B2,2,5\n" +
                "A3,1,,WALKOVER,2,\n");
        assertTrue(newProcessor().processRound(csv.toString(), YEAR, 1, NOW), "Round should process");

        CommandCompareHalls compareHalls = new CommandCompareHalls();
        int hall1Id = new A3_Halls().getHallByName("1").id;
        int hall2Id = new A3_Halls().getHallByName("2").id;
        compareHalls.handleCommand(ADMIN_USER_ID);
        compareHalls.handleFirstHallSelection(ADMIN_USER_ID, hall1Id);
        compareHalls.handleSecondHallSelection(ADMIN_USER_ID, hall2Id);
        CommandCompareHalls.CompareResponse response = compareHalls.handleRoundSelection(ADMIN_USER_ID, "all");

        assertTrue(response.message.contains("3-0"),
                "The walkover win against a known Hall 2 must be folded into the real score (3-0), not dropped: " + response.message);
        assertFalse(response.message.contains("2-0"),
                "Must not show the old, incomplete 2-0 score that ignored the walkover board: " + response.message);
    }

    @Test
    void hallVictoryRecord_multiOpponentRound_reportsPrimaryOpponentOnly(@TempDir Path csvDir) throws Exception {
        // Hall 1 fields 3 players: 2 boards vs hall 2 (both won), 1 board vs
        // hall 3 (lost). Hall 2 is the primary opponent (2 boards vs 1) - the
        // record must show hall 1's real 2-0 sweep of hall 2, not a fabricated
        // "win" against hall 3 built from mismatched totals.
        Path csv = writeRoundCsv(csvDir, "r1.csv",
                "A1,1,10,B1,2,5\n" +
                "A2,1,10,B2,2,5\n" +
                "A3,1,5,C1,3,10\n");
        assertTrue(newProcessor().processRound(csv.toString(), YEAR, 1, NOW), "Round should process");

        CommandCompareHalls compareHalls = new CommandCompareHalls();
        int hall1Id = new A3_Halls().getHallByName("1").id;
        int hall2Id = new A3_Halls().getHallByName("2").id;
        compareHalls.handleCommand(ADMIN_USER_ID);
        compareHalls.handleFirstHallSelection(ADMIN_USER_ID, hall1Id);
        compareHalls.handleSecondHallSelection(ADMIN_USER_ID, hall2Id);
        CommandCompareHalls.CompareResponse response = compareHalls.handleRoundSelection(ADMIN_USER_ID, "all");

        assertTrue(response.message.contains("2-0"),
                "Hall 1 swept its actual (majority) opponent Hall 2 2-0: " + response.message);
        assertFalse(response.message.contains("Hall 3") && response.message.contains("2-1"),
                "Hall 1 lost its single board against Hall 3 (0-1) - it must never be shown as a 2-1 win over Hall 3: " + response.message);
    }

    // --- A30: identical fix in CommandInfoHall (same bug, same code shape) ---

    @Test
    void infoHall_walkoverBonusDoesNotSkewRealOpponentScore(@TempDir Path csvDir) throws Exception {
        Path csv = writeRoundCsv(csvDir, "r1.csv",
                "A1,1,10,B1,2,5\n" +
                "A2,1,5,B2,2,10\n" +
                "A3,1,,WALKOVER,,\n");
        assertTrue(newProcessor().processRound(csv.toString(), YEAR, 1, NOW), "Round should process");

        CommandInfoHall infoHall = new CommandInfoHall();
        int hall1Id = new A3_Halls().getHallByName("1").id;
        infoHall.handleHallSelection(ADMIN_USER_ID, hall1Id);
        CommandInfoHall.InfoResponse response = infoHall.handleRoundSelection(ADMIN_USER_ID, "all");

        assertTrue(response.message.contains("1-1"),
                "Same A30 fix applies to /infohall - real head-to-head vs Hall 2 is a 1-1 draw: " + response.message);
        assertFalse(response.message.contains("2-1"), "Walkover win must not skew the vs-Hall-2 score: " + response.message);
    }

    @Test
    void infoHall_walkoverAgainstAKnownHall_isFoldedIntoThatHallsScore(@TempDir Path csvDir) throws Exception {
        Path csv = writeRoundCsv(csvDir, "r1.csv",
                "A1,1,10,B1,2,5\n" +
                "A2,1,10,B2,2,5\n" +
                "A3,1,,WALKOVER,2,\n");
        assertTrue(newProcessor().processRound(csv.toString(), YEAR, 1, NOW), "Round should process");

        CommandInfoHall infoHall = new CommandInfoHall();
        int hall1Id = new A3_Halls().getHallByName("1").id;
        infoHall.handleHallSelection(ADMIN_USER_ID, hall1Id);
        CommandInfoHall.InfoResponse response = infoHall.handleRoundSelection(ADMIN_USER_ID, "all");

        assertTrue(response.message.contains("3-0"),
                "Same fix applies to /infohall - walkover vs a known Hall 2 must be folded in as 3-0: " + response.message);
        assertFalse(response.message.contains("2-0"), "Must not show the old, incomplete 2-0 score: " + response.message);
    }

    @Test
    void infoHall_allYears_collapsesToOneRowPerYear_notOnePerRound(@TempDir Path csvDir) throws Exception {
        // Hall 1 plays a round in 2025, then again in 2026. All-Years mode
        // must show BOTH years as summary rows, not a per-round breakdown.
        System.setProperty("SETTINGS_CURRENTYEAR", "2025");
        Path r2025 = writeRoundCsv(csvDir, "r2025.csv", "A1,1,10,B1,2,5\n");
        assertTrue(newProcessor().processRound(r2025.toString(), 2025, 1, NOW), "2025 round should process");

        System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(YEAR));
        Path r2026 = writeRoundCsv(csvDir, "r2026.csv", "A1,1,10,B1,2,5\n");
        assertTrue(newProcessor().processRound(r2026.toString(), YEAR, 1, NOW), "2026 round should process");

        CommandInfoHall infoHall = new CommandInfoHall();
        int hall1Id = new A3_Halls().getHallByName("1").id;
        infoHall.handleHallSelection(ADMIN_USER_ID, hall1Id);
        CommandInfoHall.InfoResponse response = infoHall.handleRoundSelection(ADMIN_USER_ID, "allyears");

        assertTrue(response.message.contains("2025"), "All-Years Hall Elo must include a 2025 row: " + response.message);
        assertTrue(response.message.contains("2026"), "All-Years Hall Elo must include a 2026 row: " + response.message);
        assertNotNull(response.imagePath, "All-Years mode must still render an image");
    }

    @Test
    void compareHalls_allYears_collapsesToOneRowPerYear(@TempDir Path csvDir) throws Exception {
        System.setProperty("SETTINGS_CURRENTYEAR", "2025");
        Path r2025 = writeRoundCsv(csvDir, "r2025.csv", "A1,1,10,B1,2,5\n");
        assertTrue(newProcessor().processRound(r2025.toString(), 2025, 1, NOW), "2025 round should process");

        System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(YEAR));
        Path r2026 = writeRoundCsv(csvDir, "r2026.csv", "A1,1,10,B1,2,5\n");
        assertTrue(newProcessor().processRound(r2026.toString(), YEAR, 1, NOW), "2026 round should process");

        CommandCompareHalls compareHalls = new CommandCompareHalls();
        int hall1Id = new A3_Halls().getHallByName("1").id;
        int hall2Id = new A3_Halls().getHallByName("2").id;
        compareHalls.handleCommand(ADMIN_USER_ID);
        compareHalls.handleFirstHallSelection(ADMIN_USER_ID, hall1Id);
        compareHalls.handleSecondHallSelection(ADMIN_USER_ID, hall2Id);
        CommandCompareHalls.CompareResponse response = compareHalls.handleRoundSelection(ADMIN_USER_ID, "allyears");

        assertTrue(response.message.contains("2025"), "All-Years comparison must include a 2025 row: " + response.message);
        assertTrue(response.message.contains("2026"), "All-Years comparison must include a 2026 row: " + response.message);
        assertNotNull(response.imagePath, "All-Years mode must still render an image");
    }

    // --- A33: win-probability tie-handling and denominator ---

    private static HallStatsBuilder.PlayerData playerWithElo(int elo) {
        HallStatsBuilder.PlayerData p = new HallStatsBuilder.PlayerData();
        p.elo = elo;
        return p;
    }

    private static HallStatsBuilder.HallData hallOf(int... elos) {
        HallStatsBuilder.HallData h = new HallStatsBuilder.HallData();
        for (int elo : elos) {
            h.players.add(playerWithElo(elo));
        }
        return h;
    }

    @Test
    void winProbability_teamSizeMismatch_usesComparedBoardsAsDenominator() {
        // Hall 1 fields a single, much stronger player; hall 2 fields 3
        // weaker players. Only 1 board is ever actually compared
        // (min(1,3)=1), and hall 1 wins it in every possible pairing - hall 1
        // should be the clear, ~100% favorite. The old code divided the
        // win threshold by team2.size()=3 instead of the 1 board actually
        // compared, requiring an unreachable >1.5 wins from a single board.
        CommandCompareHalls compareHalls = new CommandCompareHalls();
        HallStatsBuilder.HallData hall1 = hallOf(1000);
        HallStatsBuilder.HallData hall2 = hallOf(900, 900, 900);

        double winProbability = compareHalls.calculateWinningProbability(hall1, hall2);

        assertEquals(100.0, winProbability, 0.001,
                "Hall 1's only player outrates every one of hall 2's players - it should win with certainty, not 0%");
    }

    @Test
    void winProbability_tiesCountAsHalfCredit_notFullLoss() {
        // Hall 1 = [1000, 900] vs Hall 2 = [1000, 800]. Across the 2 possible
        // pairings: one has a tie (1000 vs 1000) plus a clear win (900 vs
        // 800) = 1.5/2 boards, a win for hall 1; the other has a clear win
        // (1000 vs 800) plus a clear loss (900 vs 1000) = 1.0/2 boards, an
        // exact tie, not a win. Correctly counting the tie as 0.5 credit
        // gives hall 1 a real, non-zero win chance (1 of 2 permutations).
        // The old code gave a tied board ZERO credit, so neither permutation
        // ever cleared the win threshold, undercounting hall 1 as a flat 0%.
        CommandCompareHalls compareHalls = new CommandCompareHalls();
        HallStatsBuilder.HallData hall1 = hallOf(1000, 900);
        HallStatsBuilder.HallData hall2 = hallOf(1000, 800);

        double winProbability = compareHalls.calculateWinningProbability(hall1, hall2);

        assertEquals(50.0, winProbability, 0.001,
                "Counting a tied board as half-credit should give hall 1 a real win chance, not the old code's flat 0%");
    }

    // --- "Last Round" label must reflect the true latest round across BOTH halls ---

    @Test
    void latestRoundLabel_picksTheHallThatPlayedLonger_regardlessOfArgumentOrder() {
        // Hall 1 stopped at round 3; hall 2 played on to round 5. The label
        // must reflect round 5 - the true latest round overall - not just
        // whichever hall happened to be passed as the first argument.
        HallStatsBuilder.HallData hall1 = hallOf(1000);
        hall1.lastRoundOrder = 3;
        hall1.lastRoundLabel = "Round 3";

        HallStatsBuilder.HallData hall2 = hallOf(1000);
        hall2.lastRoundOrder = 5;
        hall2.lastRoundLabel = "Round 5";

        assertEquals("Round 5", CommandCompareHalls.latestRoundLabelAcrossBothHalls(hall1, hall2),
                "Hall 2 played later - its round must be shown, not hall 1's earlier one");
        assertEquals("Round 5", CommandCompareHalls.latestRoundLabelAcrossBothHalls(hall2, hall1),
                "The result must not depend on which hall is passed first");
    }

    @Test
    void winProbability_hall2SideIsComputedIndependently_notAsOneHundredMinusHall1() {
        // Every board is an exact tie (identical elos) - NO permutation
        // gives either hall a strict win, so both halls' OWN win
        // probability must independently be 0%. The old code instead
        // rendered hall2's displayed percentage as "100 - hall1's 0%" =
        // 100%, falsely showing hall2 as a certain winner in a dead-even
        // tie scenario, because it silently folded every drawn permutation
        // into hall2's side instead of computing hall2's chance on its own.
        CommandCompareHalls compareHalls = new CommandCompareHalls();
        HallStatsBuilder.HallData hall1 = hallOf(1000, 1000);
        HallStatsBuilder.HallData hall2 = hallOf(1000, 1000);

        double hall1WinProbability = compareHalls.calculateWinningProbability(hall1, hall2);
        double hall2WinProbability = compareHalls.calculateWinningProbability(hall2, hall1);

        assertEquals(0.0, hall1WinProbability, 0.001, "An all-tied roster gives hall 1 no strict wins");
        assertEquals(0.0, hall2WinProbability, 0.001,
                "Hall 2's own win chance must independently be 0% too - not 100% from '100 - hall1's 0%'");
    }

    @Test
    void latestRoundLabel_fallsBackToTheOtherHall_whenOneHasNoData() {
        HallStatsBuilder.HallData hallWithData = hallOf(1000);
        hallWithData.lastRoundOrder = 4;
        hallWithData.lastRoundLabel = "Round 4";

        HallStatsBuilder.HallData hallWithoutData = hallOf(); // no rounds played - lastRoundOrder stays null

        assertEquals("Round 4", CommandCompareHalls.latestRoundLabelAcrossBothHalls(hallWithData, hallWithoutData));
        assertEquals("Round 4", CommandCompareHalls.latestRoundLabelAcrossBothHalls(hallWithoutData, hallWithData));
    }
}
