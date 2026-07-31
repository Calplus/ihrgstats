package com.calplus.ihrgstats.corpus;

import com.calplus.ihrgstats.calculations.RatingRecalculator;
import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.telegrambot.utils.CappedListProcessor;
import com.calplus.ihrgstats.telegrambot.utils.CsvLineParser;
import com.calplus.ihrgstats.telegrambot.utils.MatchScoreUtils;
import com.calplus.ihrgstats.telegrambot.utils.RoundCsvProcessor;
import com.calplus.ihrgstats.utils.DatabaseHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end validation of the committed fictional corpus in
 * {@code SAMPLE FILES/}: 4 seasons (2001-2004), 11 halls, 39 round CSVs
 * plus a capped list per year, ingested season by season through the REAL
 * pipeline (capped list first, then every round, with the interactive
 * dialogs answered exactly as an admin would - the full expected dialog
 * script is asserted in order). The result is held against the
 * {@link CorpusIntegrityChecks} battery per year, an INDEPENDENT
 * per-board recount straight from the CSV files, per-year season W/D/L,
 * the scripted identity outcomes (typos merged - including two year-long
 * variants - near-duplicates split, hall movers kept as one identity,
 * the same-name-different-person pair kept distinct), per-year capped
 * mapping (including the two entries that never play and must stay
 * unmapped staging rows), the ML burn-in boundary (no champion after the
 * single 2001 season; champion + distilled ExpElo present once later
 * seasons clear it), and whole-history recalculation determinism.
 */
public class CorpusIngestionTest {

    private static final String NOW = "2026-01-01 00:00:00.000";
    private static final double MAX_SCORE = 370.0;
    /** Season lengths: 2003 is the reduced year (5 Swiss + 4 bracket rounds). */
    private static final int[][] SEASONS = {{2001, 10}, {2002, 10}, {2003, 9}, {2004, 10}};

    /** One expected interactive dialog: matched by substring, answered by index. */
    private record ScriptedDialog(String label, String needle, int answer) {}

    /**
     * Every identity dialog the corpus fires, in file order. Fuzzy
     * name-mismatch dialogs are matched on both names so an unexpected
     * candidate can never be silently mis-answered; hall-mismatch dialogs
     * are matched on the player line. Answers: name dialogs 0=same person
     * / 1=different people; hall dialogs 1=new hall same player / 2=new
     * hall different player.
     */
    private static final List<ScriptedDialog> DIALOG_SCRIPT = List.of(
            // 2001
            new ScriptedDialog("paul-murphy", "'Paul Murphy' may match existing player 'Paul Morphy'.", 1),
            new ScriptedDialog("x-debut", "'X' may match existing player 'Kim Wexler'.", 1),
            new ScriptedDialog("bobby-fischer", "'Bobby Fischer' may match existing player 'Bob'.", 1),
            new ScriptedDialog("teddy-typo", "'Teddy Rosevelt' may match existing player 'Teddy Roosevelt'.", 0),
            // 2002
            new ScriptedDialog("joyce-move", "Player: Joyce Byers", 1),
            new ScriptedDialog("max-euwe", "'Max Euwe' may match existing player 'X'.", 1),
            new ScriptedDialog("jessie-typo", "'Jessie Pinkman' may match existing player 'Jesse Pinkman'.", 0),
            new ScriptedDialog("margarey-typo", "'Margarey Tyrell' may match existing player 'Margaery Tyrell'.", 0),
            new ScriptedDialog("max-mayfield", "'Max Mayfield' may match existing player 'X'.", 1),
            // 2003
            new ScriptedDialog("hopper-move", "Player: Jim Hopper", 1),
            new ScriptedDialog("coral-new-person", "Player: Coral Reeves", 2),
            new ScriptedDialog("zzyzx", "'Zzyzx Quibble' may match existing player 'X'.", 1),
            new ScriptedDialog("elven-typo", "'Elven' may match existing player 'Eleven'.", 0),
            new ScriptedDialog("dominique-short", "'Dominique' may match existing player 'Dominique DiPierro'.", 0),
            new ScriptedDialog("aniya-typo", "'Aniya Forger' may match existing player 'Anya Forger'.", 0),
            // 2004
            new ScriptedDialog("hermoine-typo", "'Hermoine Granger' may match existing player 'Hermione Granger'.", 0),
            new ScriptedDialog("baracuda-typo", "'Baracuda' may match existing player 'Barracuda'.", 0),
            new ScriptedDialog("petrosyan-typo", "'Tigran Petrosyan' may match existing player 'Tigran Petrosian'.", 0));

    /**
     * Which identity dialogs fire in which round ("year:round"), in CSV row
     * order. Every round that contains any WALKOVER row additionally fires
     * the match-type dialog first (asserted from an independent CSV scan).
     */
    private static final Map<String, List<String>> IDENTITY_DIALOGS_AT = Map.ofEntries(
            Map.entry("2001:1", List.of("paul-murphy", "x-debut")),
            Map.entry("2001:2", List.of("bobby-fischer")),
            Map.entry("2001:3", List.of("teddy-typo")),
            Map.entry("2002:1", List.of("joyce-move", "max-euwe")),
            Map.entry("2002:3", List.of("jessie-typo")),
            Map.entry("2002:5", List.of("margarey-typo")),
            Map.entry("2002:6", List.of("max-mayfield")),
            Map.entry("2003:1", List.of("hopper-move", "coral-new-person", "zzyzx")),
            Map.entry("2003:4", List.of("elven-typo")),
            Map.entry("2003:5", List.of("dominique-short")),
            Map.entry("2003:6", List.of("aniya-typo")),
            Map.entry("2004:1", List.of("hermoine-typo")),
            Map.entry("2004:2", List.of("baracuda-typo")),
            Map.entry("2004:3", List.of("petrosyan-typo")));

    /** Capped-list entries that never play their capped year and must stay unmapped. */
    private static final Map<Integer, String> UNMAPPED_CAPPED = Map.of(2001, "Dave Burger", 2003, "Paul Murphy");

    private String originalUserDir;
    private Path sampleDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        originalUserDir = System.getProperty("user.dir");
        sampleDir = Paths.get(originalUserDir, "SAMPLE FILES");
        System.setProperty("user.dir", tempDir.toString());
        new DatabaseSchema().createDatabase("default.db");
        new A3_Halls().seedDefaults(NOW);
        new B4_Players().seedDefaults(NOW);
        new D10_RatingTypes().seedDefaults(NOW);
        new F16_Admins().seedDefaults(NOW);
        new A2_MatchTypes().createMatchType("Corpus", MAX_SCORE, null, "Corpus battery match type", NOW);
        // The committed corpus deliberately uses FICTIONAL hall names
        // (HallA/HallB/HallC) so no real hall name ever appears in the repo.
        // They are not part of the production seed, so the corpus database
        // gets them added up front - ingestion hard-fails on unknown halls.
        insertHall("HA", "HallA");
        insertHall("HB", "HallB");
        insertHall("HC", "HallC");
    }

    private static void insertHall(String code, String name) throws SQLException {
        String sql = "INSERT INTO halls (hall_code, hall_name, next_player_seq, created_dttm, updated_dttm) "
                + "VALUES (?, ?, 1, ?, ?)";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            ps.setString(2, name);
            ps.setString(3, NOW);
            ps.setString(4, NOW);
            ps.executeUpdate();
        }
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
        System.clearProperty("SETTINGS_CURRENTYEAR");
    }

    @Test
    void fullFourYearCorpus_ingestsCleanly_passesEveryBattery_andRecalcIsDeterministic() throws Exception {
        List<String> dialogs = new ArrayList<>();
        List<String> expectedDialogs = new ArrayList<>();
        Map<String, List<String[]>> rowsByYearRound = new LinkedHashMap<>();

        RoundCsvProcessor processor = new RoundCsvProcessor();
        processor.setMultiChoiceCallback((message, options) -> {
            if (message.startsWith("⚠️ This round contains a WALKOVER")) {
                dialogs.add("matchtype");
                return 0; // the single "Corpus" match type
            }
            for (ScriptedDialog scripted : DIALOG_SCRIPT) {
                if (message.contains(scripted.needle())) {
                    dialogs.add(scripted.label());
                    return scripted.answer();
                }
            }
            dialogs.add("UNEXPECTED: " + message);
            return -1; // cancels the round -> the ingest assertion fails loudly
        });

        CappedListProcessor cappedProcessor = new CappedListProcessor();
        E17_MlModels mlModels = new E17_MlModels();
        for (int[] season : SEASONS) {
            int year = season[0];
            int roundCount = season[1];
            System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(year));

            // Capped list first - the season-start upload order; nobody has
            // played this year yet, so every entry lands as an unmapped
            // staging row, claimed by identity resolution on first appearance.
            assertTrue(cappedProcessor.processCappedList(
                            sampleDir.resolve(year + "_cappedlist.csv").toString(), year, NOW),
                    year + "_cappedlist.csv must process cleanly");

            for (int round = 1; round <= roundCount; round++) {
                Path csv = sampleDir.resolve(year + "_round_" + round + ".csv");
                List<String[]> rows = readDataRows(csv);
                rowsByYearRound.put(year + ":" + round, rows);
                // Independent scan: any WALKOVER row makes the processor ask
                // for the match type (once, before the identity dialogs).
                if (rows.stream().anyMatch(r -> r[0].equalsIgnoreCase("WALKOVER") || r[3].equalsIgnoreCase("WALKOVER"))) {
                    expectedDialogs.add("matchtype");
                }
                expectedDialogs.addAll(IDENTITY_DIALOGS_AT.getOrDefault(year + ":" + round, List.of()));

                assertTrue(processor.processRound(csv.toString(), year, round, NOW),
                        year + " round " + round + " must ingest cleanly (dialog log: " + dialogs + ")");
            }

            CorpusIntegrityChecks.runAll(year);
            assertCappedListOutcomes(year);

            if (year == 2001) {
                // ML burn-in boundary: a single stored season (10 rounds)
                // leaves nothing beyond the burn-in - no champion, no ExpElo.
                assertNull(mlModels.getChampion(),
                        "the single 2001 season must not clear the ML burn-in");
            }
        }
        assertEquals(expectedDialogs, dialogs, "the corpus must fire exactly the scripted dialogs, in order");

        // --- Identity outcomes -------------------------------------------------
        A3_Halls halls = new A3_Halls();
        B6_PlayerYearStatus statuses = new B6_PlayerYearStatus();

        // Typos and short forms resolve to the same player (incl. the two
        // year-long variants and the partial-name short form).
        assertSamePlayer("Teddy Roosevelt", "Teddy Rosevelt");
        assertSamePlayer("Jesse Pinkman", "Jessie Pinkman");
        assertSamePlayer("Margaery Tyrell", "Margarey Tyrell");
        assertSamePlayer("Eleven", "Elven");
        assertSamePlayer("Anya Forger", "Aniya Forger");
        assertSamePlayer("Dominique DiPierro", "Dominique");
        assertSamePlayer("Hermione Granger", "Hermoine Granger");
        assertSamePlayer("Barracuda", "Baracuda");
        assertSamePlayer("Tigran Petrosian", "Tigran Petrosyan");

        // Near-duplicates stay distinct people.
        assertNotEquals(uniquePlayerIdOf("Paul Morphy"), uniquePlayerIdOf("Paul Murphy"));
        assertNotEquals(uniquePlayerIdOf("Bob"), uniquePlayerIdOf("Bobby Fischer"));
        String xId = uniquePlayerIdOf("X");
        for (String name : new String[]{"Kim Wexler", "Max Mayfield", "Max Euwe", "Zzyzx Quibble"}) {
            assertNotEquals(xId, uniquePlayerIdOf(name), "'X' must stay distinct from " + name);
        }

        // Hall movers stay ONE identity with the right hall per year.
        String joyceId = uniquePlayerIdOf("Joyce Byers");
        assertEquals(halls.getHallByName("5").id, statuses.getStatus(joyceId, 2001).hallId);
        assertEquals(halls.getHallByName("2").id, statuses.getStatus(joyceId, 2002).hallId);
        assertEquals(halls.getHallByName("2").id, statuses.getStatus(joyceId, 2003).hallId);
        assertNull(statuses.getStatus(joyceId, 2004), "Joyce Byers leaves after 2003");
        String hopperId = uniquePlayerIdOf("Jim Hopper");
        assertEquals(halls.getHallByName("5").id, statuses.getStatus(hopperId, 2001).hallId);
        assertEquals(halls.getHallByName("5").id, statuses.getStatus(hopperId, 2002).hallId);
        assertEquals(halls.getHallByName("HallA").id, statuses.getStatus(hopperId, 2003).hallId);
        assertEquals(halls.getHallByName("HallA").id, statuses.getStatus(hopperId, 2004).hallId);

        // Same name, two different people: the hall-8 Coral Reeves (2001-02)
        // and the HallC Coral Reeves (2003-04) - disjoint year coverage.
        List<B5_PlayerNames.NameRecord> corals = new B5_PlayerNames().findCandidatesByExactName("Coral Reeves");
        assertEquals(2, corals.size(), "exactly two distinct players must share the name Coral Reeves");
        assertNotEquals(corals.get(0).playerId, corals.get(1).playerId);
        assertEquals(halls.getHallByName("8").id, statuses.getStatus(playerIdOf("Coral Reeves", 2001), 2001).hallId);
        assertEquals(halls.getHallByName("HallC").id, statuses.getStatus(playerIdOf("Coral Reeves", 2003), 2003).hallId);
        assertNotEquals(playerIdOf("Coral Reeves", 2001), playerIdOf("Coral Reeves", 2003));

        // Roster ghosts and sit-outs: players the CSVs never show must not
        // exist (or have no status row for the missing year).
        assertTrue(new B5_PlayerNames().findCandidatesByExactName("Huell Babineaux").isEmpty(),
                "a rostered-but-never-fielded player must never reach the database");
        assertTrue(new B5_PlayerNames().findCandidatesByExactName("Dave Burger").isEmpty(),
                "a capped entry that never plays must never become a player");
        assertNull(statuses.getStatus(uniquePlayerIdOf("Paul Murphy"), 2003),
                "Paul Murphy never plays 2003 despite being on its capped list");
        assertNull(statuses.getStatus(uniquePlayerIdOf("Draco Malfoy"), 2004),
                "Draco Malfoy's 2004 slump benches him all year");
        assertNull(statuses.getStatus(uniquePlayerIdOf("Tigran Petrosian"), 2003), "Petrosian sits out 2003");
        assertNull(statuses.getStatus(uniquePlayerIdOf("Finn the Human"), 2002),
                "Finn the Human is the capped-overflow sit-out of 2002");

        // --- Marquee outlier boards (readable spot checks; the full recount
        // below covers every board of all 39 files) -----------------------------
        A1_Rounds rounds = new A1_Rounds();
        C9_MatchParticipants participants = new C9_MatchParticipants();
        double walkoverDefault = MatchScoreUtils.computeWalkoverDefaultScore(MAX_SCORE);

        // TIMEOUT with the winner's score left blank (2002 R1, side 1 timed
        // out): stored as a rated 0-0 win for the other side.
        int r1of2002 = rounds.getRoundByYearAndOrder(2002, 1).id;
        C9_MatchParticipants.Participant mike = participants.getParticipantForPlayerAndRound(playerIdOf("Mike Ehrmantraut", 2002), r1of2002);
        assertEquals(C9_MatchParticipants.PARTICIPATION_TIMEOUT, mike.participationType);
        assertEquals(0.0, mike.score);
        assertEquals(0.0, mike.outcome);
        C9_MatchParticipants.Participant joyce2002 = participants.getParticipantForPlayerAndRound(playerIdOf("Joyce Byers", 2002), r1of2002);
        assertEquals(C9_MatchParticipants.PARTICIPATION_STANDARD, joyce2002.participationType);
        assertEquals(0.0, joyce2002.score, "a blank winner score on a TIMEOUT board is stored as 0");
        assertEquals(1.0, joyce2002.outcome, "the 0-0 TIMEOUT board is still a decided, rated win");

        // WALKOVER as name1 (2003 R3): the real player wins on the name2 side.
        int r3of2003 = rounds.getRoundByYearAndOrder(2003, 3).id;
        C9_MatchParticipants.Participant elliot = participants.getParticipantForPlayerAndRound(playerIdOf("Elliot Alderson", 2003), r3of2003);
        assertEquals(C9_MatchParticipants.PARTICIPATION_STANDARD, elliot.participationType);
        assertEquals(walkoverDefault, elliot.score);
        assertEquals(1.0, elliot.outcome);
        assertEquals(B4_Players.WALKOVER_PLAYER_ID,
                participants.getOpponentParticipant(elliot.matchId, elliot.playerId).playerId);

        // Individual walkover with the opposing hall stated (2004 R1): the
        // short-handed side's hall lands on the sentinel participant.
        int r1of2004 = rounds.getRoundByYearAndOrder(2004, 1).id;
        C9_MatchParticipants.Participant alberic = participants.getParticipantForPlayerAndRound(playerIdOf("Alberic O'Kelly de Galway", 2004), r1of2004);
        assertEquals(walkoverDefault, alberic.score);
        assertEquals(1.0, alberic.outcome);
        C9_MatchParticipants.Participant albericOpp = participants.getOpponentParticipant(alberic.matchId, alberic.playerId);
        assertEquals(B4_Players.WALKOVER_PLAYER_ID, albericOpp.playerId);
        assertEquals(halls.getHallByName("HallA").id, albericOpp.hallId,
                "the stated opposing hall must be attributed to the walkover side");

        // Board sweepout 370-0 (2001 R9).
        int r9of2001 = rounds.getRoundByYearAndOrder(2001, 9).id;
        C9_MatchParticipants.Participant daenerys = participants.getParticipantForPlayerAndRound(playerIdOf("Daenerys Targaryen", 2001), r9of2001);
        assertEquals(MAX_SCORE, daenerys.score, "the board sweepout must store the max score");
        assertEquals(1.0, daenerys.outcome);
        assertEquals(0.0, participants.getOpponentParticipant(daenerys.matchId, daenerys.playerId).score);

        // The one 0-0 STANDARD draw (2002 R3) - distinct from a 0-0 timeout win.
        int r3of2002 = rounds.getRoundByYearAndOrder(2002, 3).id;
        boolean foundZeroDraw = false;
        for (C9_MatchParticipants.Participant p : participants.getParticipantsForRound(r3of2002)) {
            if (p.outcome == 0.5) {
                assertEquals(0.0, p.score, "2002 R3's draw is the 0-0 standard draw");
                assertEquals(C9_MatchParticipants.PARTICIPATION_STANDARD, p.participationType);
                foundZeroDraw = true;
            }
        }
        assertTrue(foundZeroDraw, "the 0-0 standard draw must exist in 2002 R3");

        // --- Independent recount: every board of every round of every year -----
        // Expectations are rebuilt straight from the CSV files with an
        // independent implementation of the scoring vocabulary, then compared
        // as per-round multisets of participant signatures.
        int timeoutSides = 0;
        int walkoverSides = 0;
        C8_Matches matches = new C8_Matches();
        Map<Integer, Map<String, int[]>> expectedWdlByYear = new HashMap<>();
        for (int[] season : SEASONS) {
            int year = season[0];
            for (int round = 1; round <= season[1]; round++) {
                A1_Rounds.Round dbRound = rounds.getRoundByYearAndOrder(year, round);
                assertNotNull(dbRound, year + " round " + round + " missing from the database");

                List<String> expectedMatches = new ArrayList<>();
                Map<String, int[]> expectedWdl = expectedWdlByYear.computeIfAbsent(year, k -> new HashMap<>());
                for (String[] row : rowsByYearRound.get(year + ":" + round)) {
                    ExpectedSide[] sides = expectedSides(row, year);
                    expectedMatches.add(matchSignature(
                            signature(sides[0].playerId(), sides[0].type, sides[0].score, sides[0].outcome),
                            signature(sides[1].playerId(), sides[1].type, sides[1].score, sides[1].outcome)));
                    for (ExpectedSide side : sides) {
                        if (C9_MatchParticipants.PARTICIPATION_TIMEOUT.equals(side.type)) timeoutSides++;
                        if (C9_MatchParticipants.PARTICIPATION_WALKOVER.equals(side.type)) walkoverSides++;
                        if (!B4_Players.WALKOVER_PLAYER_ID.equals(side.playerId())) {
                            int[] wdl = expectedWdl.computeIfAbsent(side.playerId(), k -> new int[3]);
                            if (side.outcome == 1.0) wdl[0]++;
                            else if (side.outcome == 0.5) wdl[1]++;
                            else wdl[2]++;
                        }
                    }
                }

                List<String> actualMatches = new ArrayList<>();
                for (C8_Matches.Match match : matches.getMatchesForRound(dbRound.id)) {
                    List<C9_MatchParticipants.Participant> pair = participants.getParticipantsForMatch(match.id);
                    assertEquals(2, pair.size());
                    actualMatches.add(matchSignature(
                            signature(pair.get(0).playerId, pair.get(0).participationType, pair.get(0).score, pair.get(0).outcome),
                            signature(pair.get(1).playerId, pair.get(1).participationType, pair.get(1).score, pair.get(1).outcome)));
                }

                Collections.sort(expectedMatches);
                Collections.sort(actualMatches);
                assertEquals(expectedMatches, actualMatches,
                        year + " round " + round + ": stored boards differ from the CSV recount");
            }
        }
        assertEquals(13, timeoutSides, "the corpus must contain exactly 13 TIMEOUT boards");
        assertEquals(162, walkoverSides, "the corpus must contain exactly 162 walkover sides");

        // Season W/D/L per player per year must match the CSVs.
        for (int[] season : SEASONS) {
            int year = season[0];
            Map<String, int[]> actualWdl = new HashMap<>();
            for (A1_Rounds.Round round : rounds.getRoundsForYear(year)) {
                for (C9_MatchParticipants.Participant p : participants.getParticipantsForRound(round.id)) {
                    if (B4_Players.WALKOVER_PLAYER_ID.equals(p.playerId)) continue;
                    int[] wdl = actualWdl.computeIfAbsent(p.playerId, k -> new int[3]);
                    if (p.outcome == 1.0) wdl[0]++;
                    else if (p.outcome == 0.5) wdl[1]++;
                    else wdl[2]++;
                }
            }
            Map<String, int[]> expectedWdl = expectedWdlByYear.get(year);
            assertEquals(expectedWdl.keySet(), actualWdl.keySet(),
                    year + ": set of players with boards differs from the CSVs");
            for (Map.Entry<String, int[]> entry : expectedWdl.entrySet()) {
                assertArrayEquals(entry.getValue(), actualWdl.get(entry.getKey()),
                        year + ": season W/D/L differs from the CSV recount for player " + entry.getKey());
            }
        }

        // --- ML: the 4-year corpus clears the burn-in during year 2, so the
        // finished database must hold a champion and distilled ExpElo rows
        // (their per-round parity with TrueElo is asserted by the battery). --
        assertNotNull(mlModels.getChampion(), "the finished 4-year corpus must have trained a champion");
        D10_RatingTypes ratingTypes = new D10_RatingTypes();
        int expEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.EXP_ELO);
        int finalRoundId = rounds.getRoundByYearAndOrder(2004, 10).id;
        assertFalse(new D11_PlayerRatings().getRatingsForRound(finalRoundId, expEloTypeId).isEmpty(),
                "ExpElo must be distilled for the final round once a champion exists");

        // --- Whole-history recalculation determinism ---------------------------
        int trueEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.TRUE_ELO);
        Map<String, String> trueEloBefore = allRatingRows(trueEloTypeId);
        Map<String, String> expEloBefore = allRatingRows(expEloTypeId);
        assertFalse(trueEloBefore.isEmpty());
        assertFalse(expEloBefore.isEmpty());
        RatingRecalculator.RecalcResult rerun = new RatingRecalculator().recalculateAll(NOW);
        assertEquals(trueEloBefore, allRatingRows(trueEloTypeId),
                "rerunning the whole-history recalculation must reproduce every TrueElo row exactly");
        assertEquals(trueEloBefore.size(), rerun.ratingRowsWritten);
        assertEquals(expEloBefore, allRatingRows(expEloTypeId),
                "the recalculation must not touch the distilled ExpElo rows");

        for (int[] season : SEASONS) {
            CorpusIntegrityChecks.runAll(season[0]);
        }
    }

    @Test
    void sameYearCrossHallExactDuplicateName_isRejectedWithoutDataLoss(@TempDir Path csvDir) throws Exception {
        final int year = 2001;
        RoundCsvProcessor processor = new RoundCsvProcessor();
        processor.setMultiChoiceCallback((message, options) -> fail("no dialog expected: " + message));

        Path round1 = csvDir.resolve("r1.csv");
        Files.writeString(round1, "name1,hall1,score1,name2,hall2,score2\nJohn Smith,1,10,Alice Wong,2,5\n");
        assertTrue(processor.processRound(round1.toString(), year, 1, NOW));

        // The exact same name under a different hall in the SAME year is a
        // deliberate hard error (data-entry mistake, not user-resolvable).
        Path round2 = csvDir.resolve("r2.csv");
        Files.writeString(round2, "name1,hall1,score1,name2,hall2,score2\nJohn Smith,3,8,Bob Lee,4,2\n");
        assertFalse(processor.processRound(round2.toString(), year, 2, NOW),
                "a same-year cross-hall exact duplicate must be rejected");

        // The failed upload must not have destroyed or half-written anything.
        List<A1_Rounds.Round> rounds = new A1_Rounds().getRoundsForYear(year);
        assertEquals(1, rounds.size(), "the rejected round must not leave a round row behind");
        assertTrue(new B5_PlayerNames().findCandidatesByExactName("Bob Lee").isEmpty(),
                "no player may be created by a rejected round");
        B6_PlayerYearStatus.Status johnSmith = new B6_PlayerYearStatus().getStatus(uniquePlayerIdOf("John Smith"), year);
        assertEquals(new A3_Halls().getHallByName("1").id, johnSmith.hallId,
                "the existing player's hall must be untouched");
        CorpusIntegrityChecks.runAll(year);
    }

    // --- capped-list assertions ------------------------------------------------

    /**
     * Every entry of the year's capped list must be a single staging row;
     * entries whose player appears that year are claimed (mapped, capped
     * flag set on exactly that player's year status), the scripted
     * never-plays entries stay unmapped. Also pins the negative cases:
     * variant-year and overflow players are NOT capped.
     */
    private void assertCappedListOutcomes(int year) throws Exception {
        B7_CappedImports cappedImports = new B7_CappedImports();
        B6_PlayerYearStatus statuses = new B6_PlayerYearStatus();
        String unmappedName = UNMAPPED_CAPPED.get(year);
        int mapped = 0;
        for (String[] row : readDataRows(sampleDir.resolve(year + "_cappedlist.csv"))) {
            String name = row[0];
            List<B7_CappedImports.ImportRow> imports = cappedImports.findByYearAndName(year, name);
            assertEquals(1, imports.size(), year + ": expected exactly one capped import row for " + name);
            if (name.equals(unmappedName)) {
                assertFalse(imports.get(0).mapped, year + ": " + name + " never plays and must stay unmapped");
                continue;
            }
            mapped++;
            assertTrue(imports.get(0).mapped, year + ": capped entry must be claimed on first appearance: " + name);
            String playerId = playerIdOf(name, year);
            assertEquals(playerId, imports.get(0).playerId, year + ": capped row claimed by the wrong player: " + name);
            assertTrue(statuses.getStatus(playerId, year).capped, year + ": player must be flagged capped: " + name);
        }
        assertTrue(mapped > 0, year + ": capped list must not be empty");

        // Negative pins per year, straight from the design script.
        if (year == 2001) {
            assertFalse(statuses.getStatus(playerIdOf("Arya Stark", 2001), 2001).capped,
                    "Arya Stark is deliberately NOT on the 2001 list (hall-3 cap of 3)");
        }
        if (year == 2002) {
            assertFalse(statuses.getStatus(playerIdOf("X", 2002), 2002).capped,
                    "the single-letter player is never capped");
        }
        if (year == 2004) {
            assertFalse(statuses.getStatus(playerIdOf("Hermoine Granger", 2004), 2004).capped,
                    "the year-long variant year is deliberately a non-capped year");
        }
        assertFalse(statuses.getStatus(playerIdOf("Walter White", year), year).capped,
                "Walter White is never capped in any year");
    }

    // --- helpers ---------------------------------------------------------------

    /** Resolves a name that belongs to exactly ONE player across the whole corpus. */
    private static String uniquePlayerIdOf(String name) throws Exception {
        List<B5_PlayerNames.NameRecord> candidates = new B5_PlayerNames().findCandidatesByExactName(name);
        assertEquals(1, candidates.size(), "expected exactly one player for name '" + name + "'");
        return candidates.get(0).playerId;
    }

    /**
     * Resolves a CSV name within a year: of all players who ever used the
     * name, exactly one has a status row for that year (this is what keeps
     * the two Coral Reeves apart).
     */
    private static String playerIdOf(String name, int year) throws Exception {
        B6_PlayerYearStatus statuses = new B6_PlayerYearStatus();
        List<String> active = new ArrayList<>();
        for (B5_PlayerNames.NameRecord candidate : new B5_PlayerNames().findCandidatesByExactName(name)) {
            if (statuses.getStatus(candidate.playerId, year) != null && !active.contains(candidate.playerId)) {
                active.add(candidate.playerId);
            }
        }
        assertEquals(1, active.size(), "expected exactly one player named '" + name + "' active in " + year);
        return active.get(0);
    }

    private static void assertSamePlayer(String canonical, String variant) throws Exception {
        assertEquals(uniquePlayerIdOf(canonical), uniquePlayerIdOf(variant),
                "'" + variant + "' must resolve to the same player as '" + canonical + "'");
    }

    private static List<String[]> readDataRows(Path csv) throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(csv.toFile()))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                if (header) {
                    header = false;
                    continue;
                }
                String[] parts = CsvLineParser.parseLine(line);
                for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
                rows.add(parts);
            }
        }
        return rows;
    }

    /** One side of an expected board, built independently from a CSV row. */
    private static final class ExpectedSide {
        final String name;
        final int year;
        final String type;
        final double score;
        final double outcome;

        ExpectedSide(String name, int year, String type, double score, double outcome) {
            this.name = name;
            this.year = year;
            this.type = type;
            this.score = score;
            this.outcome = outcome;
        }

        String playerId() {
            try {
                return name.equalsIgnoreCase("WALKOVER") ? B4_Players.WALKOVER_PLAYER_ID : playerIdOf(name, year);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Independent implementation of the scoring vocabulary: a WALKOVER side
     * loses 0 vs the computed default; a TIMEOUT side scores 0 and loses,
     * the other side keeping its recorded score (blank -> 0); otherwise the
     * higher score wins and equal scores draw.
     */
    private static ExpectedSide[] expectedSides(String[] row, int year) {
        String name1 = row[0], score1 = row[2], name2 = row[3], score2 = row[5];
        boolean p1Walkover = name1.equalsIgnoreCase("WALKOVER");
        boolean p2Walkover = name2.equalsIgnoreCase("WALKOVER");
        String walkover = C9_MatchParticipants.PARTICIPATION_WALKOVER;
        String timeout = C9_MatchParticipants.PARTICIPATION_TIMEOUT;
        String standard = C9_MatchParticipants.PARTICIPATION_STANDARD;

        if (p1Walkover || p2Walkover) {
            double defaultScore = MatchScoreUtils.computeWalkoverDefaultScore(MAX_SCORE);
            if (p1Walkover) {
                return new ExpectedSide[]{
                        new ExpectedSide(name1, year, walkover, 0.0, 0.0),
                        new ExpectedSide(name2, year, standard, defaultScore, 1.0)};
            }
            return new ExpectedSide[]{
                    new ExpectedSide(name1, year, standard, defaultScore, 1.0),
                    new ExpectedSide(name2, year, walkover, 0.0, 0.0)};
        }
        if (score1.equalsIgnoreCase("TIMEOUT")) {
            double winnerScore = score2.isEmpty() ? 0.0 : Double.parseDouble(score2);
            return new ExpectedSide[]{
                    new ExpectedSide(name1, year, timeout, 0.0, 0.0),
                    new ExpectedSide(name2, year, standard, winnerScore, 1.0)};
        }
        if (score2.equalsIgnoreCase("TIMEOUT")) {
            double winnerScore = score1.isEmpty() ? 0.0 : Double.parseDouble(score1);
            return new ExpectedSide[]{
                    new ExpectedSide(name1, year, standard, winnerScore, 1.0),
                    new ExpectedSide(name2, year, timeout, 0.0, 0.0)};
        }
        double s1 = Double.parseDouble(score1);
        double s2 = Double.parseDouble(score2);
        double o1 = s1 > s2 ? 1.0 : (s1 < s2 ? 0.0 : 0.5);
        return new ExpectedSide[]{
                new ExpectedSide(name1, year, standard, s1, o1),
                new ExpectedSide(name2, year, standard, s2, 1.0 - o1)};
    }

    private static String signature(String playerId, String type, double score, double outcome) {
        return String.format(Locale.ROOT, "%s|%s|%.4f|%.1f", playerId, type, score, outcome);
    }

    /** Order-independent signature for a two-sided board. */
    private static String matchSignature(String sideA, String sideB) {
        return sideA.compareTo(sideB) <= 0 ? sideA + "||" + sideB : sideB + "||" + sideA;
    }

    /** All rating rows of one type across the whole 4-year corpus. */
    private static Map<String, String> allRatingRows(int ratingTypeId) throws Exception {
        Map<String, String> rows = new HashMap<>();
        A1_Rounds rounds = new A1_Rounds();
        D11_PlayerRatings ratings = new D11_PlayerRatings();
        for (int[] season : SEASONS) {
            for (A1_Rounds.Round round : rounds.getRoundsForYear(season[0])) {
                for (D11_PlayerRatings.Rating r : ratings.getRatingsForRound(round.id, ratingTypeId)) {
                    rows.put(r.playerId + "|" + r.roundId,
                            String.format(Locale.ROOT, "%.12f|%.12f|%.12f", r.ratingValue, r.ratingDeviation, r.volatility));
                }
            }
        }
        return rows;
    }
}
