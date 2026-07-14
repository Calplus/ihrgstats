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
 * Headless, DB-backed end-to-end test of the round-ingestion pipeline
 * against a real (throwaway) SQLite database - no Telegram, no mocks, no
 * stubbed DAOs. There is no dependency-injection point for the DB path
 * ({@link com.calplus.ihrgstats.utils.DatabaseHelper} always resolves
 * {@code <user.dir>/database/core/default.db}), so each test redirects
 * {@code user.dir} to a fresh {@code @TempDir} and restores it afterward.
 *
 * Covers the whole-history recalculation + point-in-time snapshot design:
 * a walkover-facing player's rating must not move (no real game recorded)
 * but their RD must grow (not reset); a later upset must revise an earlier
 * round's CURRENT rating while that round's snapshot stays frozen; and a
 * round's match type must survive being reprocessed.
 */
public class RoundCsvProcessorPipelineTest {

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
        new D10_RatingTypes().seedDefaults(NOW);
        new F16_Admins().seedDefaults(NOW);
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    private static RoundCsvProcessor newProcessor() {
        RoundCsvProcessor processor = new RoundCsvProcessor();
        // Picks the semantically-correct option for whichever dialog fires:
        // "Continue and reprocess" for the reprocess-confirmation dialog,
        // "Treat as different people" in case the fuzzy name-matcher ever
        // flags two of this test's player names as similar, otherwise falls
        // back to the first (or only) option - e.g. the walkover match-type
        // selection dialog, which only ever has one candidate here.
        processor.setMultiChoiceCallback((message, options) -> {
            for (int i = 0; i < options.length; i++) {
                if (options[i].startsWith("Continue and reprocess") || options[i].startsWith("Treat as different people")) {
                    return i;
                }
            }
            return 0;
        });
        return processor;
    }

    private static Path writeRoundCsv(Path dir, String fileName, String dataRows) throws Exception {
        Path csv = dir.resolve(fileName);
        Files.writeString(csv, "name1,hall1,score1,name2,hall2,score2\n" + dataRows);
        return csv;
    }

    private static String resolvePlayerId(String name) throws Exception {
        List<B5_PlayerNames.NameRecord> candidates = new B5_PlayerNames().findCandidatesByExactName(name);
        assertFalse(candidates.isEmpty(), "Expected player '" + name + "' to have been created by the pipeline");
        return candidates.get(0).playerId;
    }

    @Test
    void wholeHistoryPipeline_walkoverRdGrowth_snapshotFrozen_matchTypeSurvivesReprocess(@TempDir Path csvDir) throws Exception {
        Integer trueEloTypeId = new D10_RatingTypes().getRatingTypeId(D10_RatingTypes.TRUE_ELO);
        assertNotNull(trueEloTypeId, "TrueElo rating type must be seeded");
        new A2_MatchTypes().createMatchType("Test", 10.0, null, "Test match type", NOW);

        A1_Rounds rounds = new A1_Rounds();
        D11_PlayerRatings playerRatings = new D11_PlayerRatings();
        D15_PlayerRatingSnapshots snapshots = new D15_PlayerRatingSnapshots();

        // --- Round 1: Aurelia Nightshade beats Bartholomew Krieger decisively. ---
        Path r1 = writeRoundCsv(csvDir, "r1.csv", "Aurelia Nightshade,1,8,Bartholomew Krieger,2,2\n");
        assertTrue(newProcessor().processRound(r1.toString(), YEAR, 1, NOW), "Round 1 should process successfully");

        String playerA = resolvePlayerId("Aurelia Nightshade");
        String playerB = resolvePlayerId("Bartholomew Krieger");
        int round1Id = rounds.getRoundByYearAndOrder(YEAR, 1).id;

        D11_PlayerRatings.Rating aRound1 = playerRatings.getRating(playerA, round1Id, trueEloTypeId);
        assertNotNull(aRound1, "Aurelia Nightshade should have a round-1 rating");
        assertTrue(aRound1.ratingValue > 1000, "Aurelia Nightshade won round 1 - rating should rise above the 1000 default");

        // Baseline snapshots, captured immediately - this is what "as published
        // at the time" means, and what the rest of the test checks stays frozen
        // no matter what happens in later rounds' recalculations.
        D15_PlayerRatingSnapshots.Snapshot aRound1SnapshotBaseline = snapshots.getSnapshot(playerA, round1Id, trueEloTypeId);
        D15_PlayerRatingSnapshots.Snapshot bRound1SnapshotBaseline = snapshots.getSnapshot(playerB, round1Id, trueEloTypeId);
        assertNotNull(aRound1SnapshotBaseline, "Round 1 snapshot must exist immediately after round 1 is processed");
        assertNotNull(bRound1SnapshotBaseline, "Round 1 snapshot must exist immediately after round 1 is processed");

        // --- Round 2: Aurelia Nightshade faces a WALKOVER opponent; Bartholomew Krieger sits out entirely. ---
        Path r2 = writeRoundCsv(csvDir, "r2.csv", "Aurelia Nightshade,1,,WALKOVER,,\n");
        assertTrue(newProcessor().processRound(r2.toString(), YEAR, 2, NOW), "Round 2 (walkover) should process successfully");

        int round2Id = rounds.getRoundByYearAndOrder(YEAR, 2).id;
        D11_PlayerRatings.Rating aRound2 = playerRatings.getRating(playerA, round2Id, trueEloTypeId);
        assertNotNull(aRound2, "Aurelia Nightshade should still have a round-2 rating row (inactivity RD growth, not a missing row)");
        assertEquals(aRound1.ratingValue, aRound2.ratingValue, 1e-6,
                "A walkover win must NOT move the present player's rating - walkover games are excluded from ELO entirely");
        assertTrue(aRound2.ratingDeviation > aRound1.ratingDeviation,
                "A walkover round counts as 'no real game' for rating purposes - RD must GROW, never reset or shrink");

        // --- Round 3: Bartholomew Krieger upsets Aurelia Nightshade - reveals Bartholomew Krieger was underrated by round 1 alone. ---
        Path r3 = writeRoundCsv(csvDir, "r3.csv", "Bartholomew Krieger,2,8,Aurelia Nightshade,1,2\n");
        assertTrue(newProcessor().processRound(r3.toString(), YEAR, 3, NOW), "Round 3 should process successfully");

        // Round 1's SNAPSHOT must be UNCHANGED from its baseline - untouched by
        // round 3's later recalculation, no matter what player_ratings does.
        D15_PlayerRatingSnapshots.Snapshot aRound1SnapshotAfterR3 = snapshots.getSnapshot(playerA, round1Id, trueEloTypeId);
        D15_PlayerRatingSnapshots.Snapshot bRound1SnapshotAfterR3 = snapshots.getSnapshot(playerB, round1Id, trueEloTypeId);
        assertNotNull(aRound1SnapshotAfterR3, "Round 1 snapshot must still exist");
        assertNotNull(bRound1SnapshotAfterR3, "Round 1 snapshot must still exist");
        assertEquals(aRound1SnapshotBaseline.ratingValue, aRound1SnapshotAfterR3.ratingValue, 1e-9,
                "Round 1's snapshot must stay exactly as published at the time, even after round 3's recalculation");
        assertEquals(bRound1SnapshotBaseline.ratingValue, bRound1SnapshotAfterR3.ratingValue, 1e-9,
                "Round 1's snapshot must stay exactly as published at the time, even after round 3's recalculation");

        // But whole-history recalculation SHOULD have revised round 1's CURRENT
        // player_ratings estimate now that round 3 revealed Bartholomew Krieger
        // was underrated - the WHR-style back-propagation the snapshot table
        // exists to guard against. PlayerB's estimate should be lifted upward;
        // PlayerA's should differ from their frozen snapshot value.
        D11_PlayerRatings.Rating aRound1AfterRecalc = playerRatings.getRating(playerA, round1Id, trueEloTypeId);
        D11_PlayerRatings.Rating bRound1AfterRecalc = playerRatings.getRating(playerB, round1Id, trueEloTypeId);
        assertNotNull(aRound1AfterRecalc);
        assertNotNull(bRound1AfterRecalc);
        assertNotEquals(aRound1SnapshotBaseline.ratingValue, aRound1AfterRecalc.ratingValue, 0.01,
                "Whole-history recalculation should revise round 1's CURRENT rating after round 3's upset, "
                        + "while round 1's snapshot stays frozen at its original value");
        assertTrue(bRound1AfterRecalc.ratingValue > bRound1SnapshotBaseline.ratingValue,
                "Bartholomew Krieger's round-1 rating should be revised UPWARD once round 3 reveals they were underrated "
                        + "(originally published: " + bRound1SnapshotBaseline.ratingValue + ", after recalc: " + bRound1AfterRecalc.ratingValue + ")");

        // --- Reprocess round 2: the match type must survive without needing to be re-selected. ---
        Path r2Again = writeRoundCsv(csvDir, "r2b.csv", "Aurelia Nightshade,1,,WALKOVER,,\n");
        assertTrue(newProcessor().processRound(r2Again.toString(), YEAR, 2, NOW),
                "Reprocessing round 2 should succeed - the match type must be recovered from the round's "
                        + "existing matches (captured before they're deleted), not require re-prompting for it");
    }

    /**
     * Regression test for A3: cancelling a LATER dialog during a reprocess
     * (after already saying "yes" to the top-level reprocess warning) must
     * leave the round's previously-stored data completely untouched. The
     * bug: deleteFutureRounds/deleteMatchesForRound/deleteRatingsForRound/
     * deleteSnapshotsForRound used to run BEFORE the match-type and
     * player-identity-resolution dialogs, so a cancellation at either of
     * those left the round emptied with nothing re-written, even though
     * "Cancel" was presented as safe.
     */
    @Test
    void reprocessCancelledAtALaterDialog_leavesPreviouslyStoredDataUntouched(@TempDir Path csvDir) throws Exception {
        Integer trueEloTypeId = new D10_RatingTypes().getRatingTypeId(D10_RatingTypes.TRUE_ELO);
        A1_Rounds rounds = new A1_Rounds();
        C8_Matches matches = new C8_Matches();
        D11_PlayerRatings playerRatings = new D11_PlayerRatings();

        // --- Round 1: processed successfully the first time. ---
        Path r1 = writeRoundCsv(csvDir, "r1.csv", "Aurelia Nightshade,1,8,Bartholomew Krieger,2,2\n");
        assertTrue(newProcessor().processRound(r1.toString(), YEAR, 1, NOW), "Round 1 should process successfully");

        String playerA = resolvePlayerId("Aurelia Nightshade");
        int round1Id = rounds.getRoundByYearAndOrder(YEAR, 1).id;
        D11_PlayerRatings.Rating aRound1Baseline = playerRatings.getRating(playerA, round1Id, trueEloTypeId);
        assertNotNull(aRound1Baseline, "Aurelia Nightshade should have a round-1 rating");
        int matchCountBaseline = matches.getMatchesForRound(round1Id).size();
        assertEquals(1, matchCountBaseline);

        // --- Reprocess round 1: say "yes" to the top-level warning, but a
        // fuzzy-name-match dialog fires deeper in (an intentional near-typo
        // of an existing name) and the user cancels THAT one instead. ---
        RoundCsvProcessor processor = new RoundCsvProcessor();
        processor.setMultiChoiceCallback((message, options) -> {
            if (message.contains("Round Already Processed")) {
                for (int i = 0; i < options.length; i++) {
                    if (options[i].startsWith("Continue and reprocess")) return i;
                }
            }
            if (message.contains("Name Mismatch Detected")) {
                for (int i = 0; i < options.length; i++) {
                    if (options[i].startsWith("Cancel")) return i;
                }
            }
            return options.length - 1;
        });
        Path r1Retry = writeRoundCsv(csvDir, "r1_retry.csv", "Aurelia Nightshde,1,9,Bartholomew Krieger,2,1\n");
        boolean result = processor.processRound(r1Retry.toString(), YEAR, 1, NOW);

        assertFalse(result, "Cancelling the fuzzy-name-match dialog must fail the reprocess");

        D11_PlayerRatings.Rating aRound1AfterCancel = playerRatings.getRating(playerA, round1Id, trueEloTypeId);
        assertNotNull(aRound1AfterCancel, "Round 1's rating must still exist after the cancelled reprocess");
        assertEquals(aRound1Baseline.ratingValue, aRound1AfterCancel.ratingValue, 1e-9,
                "Round 1's rating must be byte-for-byte unchanged - the cancelled reprocess must not have deleted anything");
        assertEquals(matchCountBaseline, matches.getMatchesForRound(round1Id).size(),
                "Round 1's matches must still be present - the delete must not have run before the cancellation");
    }

    @Test
    void cancelledFirstTimeUpload_leavesNoRoundRowBehind(@TempDir Path csvDir) throws Exception {
        // Regression test for A24: a round row used to be created via
        // getOrCreateRound() BEFORE any cancellable dialog ran. Deliberately
        // create NO match type here, so a walkover round's match-type dialog
        // fails immediately ("must be assigned" - resolveMatchTypeInteractively
        // returns null when no match types exist at all) - simulating a
        // first-time upload that gets cancelled/fails before any data would
        // ever be written.
        Path r1 = writeRoundCsv(csvDir, "r1.csv", "Someone,1,,WALKOVER,,\n");
        boolean result = newProcessor().processRound(r1.toString(), YEAR, 1, NOW);

        assertFalse(result, "Round should fail - no match type exists to score the walkover");
        assertNull(new A1_Rounds().getRoundByYearAndOrder(YEAR, 1),
                "A cancelled/failed FIRST-TIME upload must not leave a permanent empty round row behind");
    }

    @Test
    void unknownHallName_failsCleanly_insteadOfCrashing(@TempDir Path csvDir) throws Exception {
        // Regression test for A25: requireHall() throws IllegalArgumentException
        // for a hall name that isn't seeded - previously uncaught by
        // processRound's catch clauses, it propagated as an unhandled crash
        // instead of the same clean "processing failed" notification every
        // other expected validation failure gets.
        Path r1 = writeRoundCsv(csvDir, "r1.csv", "Someone,NoSuchHall99,10,Someone Else,2,5\n");
        boolean result = newProcessor().processRound(r1.toString(), YEAR, 1, NOW);

        assertFalse(result, "An unknown hall name must fail processing cleanly, not throw an uncaught exception");
    }

    @Test
    void headlessInvocation_withMultipleMatchTypes_cancelsInsteadOfSilentlyPickingOne(@TempDir Path csvDir) throws Exception {
        // Regression test for A14: requestMultiChoice's no-callback fallback
        // returns options.length - 1, a convention that means "Cancel" for
        // every OTHER multi-choice dialog in this app (which all append an
        // explicit Cancel entry) - but resolveMatchTypeInteractively's
        // options used to be built from real match types ONLY, so the same
        // fallback silently selected the LAST REAL match type instead of
        // cancelling. A trailing "Cancel" option now makes this fallback
        // behave consistently with every other dialog.
        new A2_MatchTypes().createMatchType("TypeA", 10.0, null, "First type", NOW);
        new A2_MatchTypes().createMatchType("TypeB", 20.0, null, "Second type", NOW);

        Path r1 = writeRoundCsv(csvDir, "r1.csv", "Someone,1,,WALKOVER,,\n");
        RoundCsvProcessor processor = new RoundCsvProcessor(); // no callback registered - headless
        boolean result = processor.processRound(r1.toString(), YEAR, 1, NOW);

        assertFalse(result, "A headless invocation with no callback must cancel the walkover match-type dialog, not silently pick a match type");
        assertNull(new A1_Rounds().getRoundByYearAndOrder(YEAR, 1),
                "A cancelled first-time upload must not leave a round row behind (A24)");
    }

    @Test
    void roundZero_isRejected_insteadOfWipingTheWholeYear(@TempDir Path csvDir) throws Exception {
        // Regression test: round_order is never 0 or negative for a real
        // round (A1_Rounds numbers rounds starting at 1). Without an
        // explicit guard, round_order=0 satisfies "isReprocess = 0 <=
        // latestRoundOrder" for ANY year that already has round 1+
        // processed, even though round 0 never existed - existingRound
        // stays null, a bogus round 0 gets created, and
        // deleteFutureRounds(year, 0) deletes EVERY already-processed round
        // of the year (round_order > 0 matches all of them).
        Path r1 = writeRoundCsv(csvDir, "r1.csv", "Aurelia Nightshade,1,8,Bartholomew Krieger,2,2\n");
        assertTrue(newProcessor().processRound(r1.toString(), YEAR, 1, NOW), "Round 1 should process successfully");

        Path r2 = writeRoundCsv(csvDir, "r2.csv", "Aurelia Nightshade,1,7,Bartholomew Krieger,2,3\n");
        assertTrue(newProcessor().processRound(r2.toString(), YEAR, 2, NOW), "Round 2 should process successfully");

        Path r0 = writeRoundCsv(csvDir, "r0.csv", "Someone,1,10,Someone Else,2,5\n");
        boolean result = newProcessor().processRound(r0.toString(), YEAR, 0, NOW);

        assertFalse(result, "Round 0 must be rejected outright, not treated as an already-processed round to reprocess");
        assertEquals(2, new A1_Rounds().getRoundsForYear(YEAR).size(),
                "Rounds 1 and 2 must both still exist - a rejected round_0.csv must not delete any real round");
        assertNull(new A1_Rounds().getRoundByYearAndOrder(YEAR, 0),
                "No round 0 row should have been created");
    }

    @Test
    void walkoverBoard_winnerGetsAFullOnePointWin_forfeiterGetsZero(@TempDir Path csvDir) throws Exception {
        // Pins down individual-board walkover scoring: the real, present
        // player must be recorded with outcome=1.0 (a full win, identical to
        // beating a real opponent), and the WLKOVR sentinel on the other side
        // must be outcome=0.0 - regardless of the match type's board count.
        new A2_MatchTypes().createMatchType("Test", 10.0, null, "Test match type", NOW);

        Path r1 = writeRoundCsv(csvDir, "r1.csv", "Aurelia Nightshade,1,,WALKOVER,,\n");
        assertTrue(newProcessor().processRound(r1.toString(), YEAR, 1, NOW), "Walkover round should process successfully");

        String playerA = resolvePlayerId("Aurelia Nightshade");
        int round1Id = new A1_Rounds().getRoundByYearAndOrder(YEAR, 1).id;

        C9_MatchParticipants participants = new C9_MatchParticipants();
        List<C9_MatchParticipants.Participant> rows = participants.getParticipantsForRound(round1Id);
        assertEquals(2, rows.size(), "A walkover board still produces exactly 2 participant rows (winner + sentinel)");

        C9_MatchParticipants.Participant winner = rows.stream()
                .filter(p -> p.playerId.equals(playerA)).findFirst().orElseThrow();
        C9_MatchParticipants.Participant forfeiter = rows.stream()
                .filter(p -> p.playerId.equals(B4_Players.WALKOVER_PLAYER_ID)).findFirst().orElseThrow();

        assertEquals(1.0, winner.outcome, 1e-9, "A walkover win must count as a full 1.0-point win for the present player");
        assertEquals(0.0, forfeiter.outcome, 1e-9, "The forfeiting (WLKOVR sentinel) side must be a full 0.0-point loss");
    }
}
