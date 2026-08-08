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
 * Headless, DB-backed pipeline tests for A20 (cross-year fuzzy-match pool),
 * A21/A15 (duplicate player within one round), and A18 (fuzzy-dialog -1
 * handling) - same user.dir-redirect bootstrap as RoundCsvProcessorPipelineTest.
 */
public class PlayerIdentityResolverPipelineTest {

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
        new A2_MatchTypes().createMatchType("TestType", 20.0, null, "Test match type", NOW);
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
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

    // --- A20: fuzzy matching must consider names from EVERY year, not just the current one ---

    @Test
    void fuzzyMatch_findsReturningPlayerFromAPriorYear_evenAfterOtherRowsThisYearAlreadyResolved(@TempDir Path csvDir) throws Exception {
        // 2024: "Amara Whitlock" plays and is recorded.
        Path csv2024 = writeRoundCsv(csvDir, "r2024.csv", "Amara Whitlock,1,10,Opponent A,2,5\n");
        RoundCsvProcessor processor2024 = new RoundCsvProcessor();
        processor2024.setMultiChoiceCallback((message, options) -> 0);
        assertTrue(processor2024.processRound(csv2024.toString(), 2024, 1, NOW), "2024 round should process");

        // 2025: row 1 resolves two brand-new players FIRST (so this year's
        // name pool is already non-empty), THEN row 2 has a typo'd version
        // of the RETURNING 2024 player. Before the fix, tryFuzzyMatch only
        // fell back to searching all-time names when THIS YEAR's pool was
        // still completely empty - by row 2, it wasn't, so the typo would
        // never have been compared against 2024's "Amara Whitlock" at all.
        Path csv2025 = writeRoundCsv(csvDir, "r2025.csv",
                "Someone New,1,10,Another New,2,5\n" +
                "Amara Whitloc,1,5,Third Player,3,10\n");
        RoundCsvProcessor processor2025 = new RoundCsvProcessor();
        processor2025.setMultiChoiceCallback((message, options) -> {
            // "Treat as same person" for the fuzzy-match dialog this typo should trigger.
            for (int i = 0; i < options.length; i++) {
                if (options[i].startsWith("Treat as same person")) {
                    return i;
                }
            }
            return 0;
        });
        assertTrue(processor2025.processRound(csv2025.toString(), 2025, 1, NOW), "2025 round should process");

        String originalPlayerId = resolvePlayerId("Amara Whitlock");
        String typoPlayerId = resolvePlayerId("Amara Whitloc");
        assertEquals(originalPlayerId, typoPlayerId,
                "The typo'd returning player must be fuzzy-matched to their 2024 record, not silently created as a new player");
    }

    @Test
    void fuzzyMatch_withMultipleCandidates_prefersTheMoreRecentlyActiveOne(@TempDir Path csvDir) throws Exception {
        // "Amara Whitl" (2020, long dormant) and "Amara Whitlock" (2024, recent)
        // both legitimately partial-match the 2025 typo "Amara Whitloc" (each
        // is a substring-or-superstring of it). getAllNames() has no
        // ORDER BY, so without an explicit best-candidate selection, an
        // unordered scan could just as easily surface the irrelevant,
        // long-dormant "Amara Whitl" first - the fix must prefer the more
        // recently active candidate ("Amara Whitlock").
        Path csv2020 = writeRoundCsv(csvDir, "r2020.csv", "Amara Whitl,1,10,Old Opponent,2,5\n");
        RoundCsvProcessor processor2020 = new RoundCsvProcessor();
        processor2020.setMultiChoiceCallback((message, options) -> 0);
        assertTrue(processor2020.processRound(csv2020.toString(), 2020, 1, NOW), "2020 round should process");

        // "Amara Whitlock" itself partially matches the already-registered "Cao
        // Meng" - answer "different people" here so it registers as its OWN,
        // genuinely distinct player rather than merging into "Amara Whitl".
        Path csv2024 = writeRoundCsv(csvDir, "r2024b.csv", "Amara Whitlock,1,10,Opponent A,2,5\n");
        RoundCsvProcessor processor2024 = new RoundCsvProcessor();
        processor2024.setMultiChoiceCallback((message, options) -> {
            for (int i = 0; i < options.length; i++) {
                if (options[i].startsWith("Treat as different people")) {
                    return i;
                }
            }
            return 0;
        });
        assertTrue(processor2024.processRound(csv2024.toString(), 2024, 1, NOW), "2024 round should process");

        Path csv2025 = writeRoundCsv(csvDir, "r2025b.csv",
                "Someone New,1,10,Another New,2,5\n" +
                "Amara Whitloc,1,5,Third Player,3,10\n");
        RoundCsvProcessor processor2025 = new RoundCsvProcessor();
        processor2025.setMultiChoiceCallback((message, options) -> {
            for (int i = 0; i < options.length; i++) {
                if (options[i].startsWith("Treat as same person")) {
                    return i;
                }
            }
            return 0;
        });
        assertTrue(processor2025.processRound(csv2025.toString(), 2025, 1, NOW), "2025 round should process");

        String recentPlayerId = resolvePlayerId("Amara Whitlock");
        String dormantPlayerId = resolvePlayerId("Amara Whitl");
        String typoPlayerId = resolvePlayerId("Amara Whitloc");
        assertEquals(recentPlayerId, typoPlayerId,
                "The typo must resolve to the more recently active 'Amara Whitlock', not the long-dormant 'Amara Whitl'");
        assertNotEquals(dormantPlayerId, typoPlayerId,
                "The long-dormant candidate must not win over the more recently active one");
    }

    // --- Fuzzy-match "same person" resolution must be held to the same
    // this-year hall-mismatch guard the exact-name path already enforces ---

    @Test
    void fuzzyMatch_sameYearHallMismatch_isRejected_insteadOfSilentlyUsingTheNewHall(@TempDir Path csvDir) throws Exception {
        // Round 1: "Player Xanthe" registers and plays in hall 1 this year.
        Path round1 = writeRoundCsv(csvDir, "r1.csv", "Player Xanthe,1,10,Opponent A,2,5\n");
        RoundCsvProcessor processor1 = new RoundCsvProcessor();
        processor1.setMultiChoiceCallback((message, options) -> 0);
        assertTrue(processor1.processRound(round1.toString(), 2026, 1, NOW), "Round 1 should process");

        // Round 2, SAME year: a near-typo of "Player Xanthe" appears under a
        // DIFFERENT hall. Answer "Treat as same person" for the fuzzy
        // dialog - before the fix, this silently resolved under hall 3 with
        // no error, even though the exact-name path would hard-error on this
        // exact scenario (a this-year hall change is a data-entry error,
        // never user-resolvable).
        Path round2 = writeRoundCsv(csvDir, "r2.csv", "Player Xanth,3,5,Opponent B,2,10\n");
        RoundCsvProcessor processor2 = new RoundCsvProcessor();
        processor2.setMultiChoiceCallback((message, options) -> {
            for (int i = 0; i < options.length; i++) {
                if (options[i].startsWith("Treat as same person")) {
                    return i;
                }
            }
            return 0;
        });
        boolean result = processor2.processRound(round2.toString(), 2026, 2, NOW);

        assertFalse(result, "A fuzzy-matched same-year hall change must be rejected, just like an exact-name match would be");
    }

    // --- A18: a fuzzy-match dialog timeout/rejection (-1) must cancel, not silently create a new player ---

    @Test
    void fuzzyMatch_negativeOneChoice_cancelsInstead_ofCreatingANewPlayer(@TempDir Path csvDir) throws Exception {
        Path round1 = writeRoundCsv(csvDir, "r1.csv", "Existing Person,1,10,Opponent A,2,5\n");
        RoundCsvProcessor processor1 = new RoundCsvProcessor();
        processor1.setMultiChoiceCallback((message, options) -> 0);
        assertTrue(processor1.processRound(round1.toString(), 2026, 1, NOW), "Round 1 should process");

        // Round 2: a near-typo of "Existing Person" should trigger the fuzzy
        // dialog. Simulate a timeout/rejected-concurrent-dialog response (-1).
        Path round2 = writeRoundCsv(csvDir, "r2.csv", "Existng Person,1,5,Opponent B,3,10\n");
        RoundCsvProcessor processor2 = new RoundCsvProcessor();
        processor2.setMultiChoiceCallback((message, options) -> -1);
        assertFalse(processor2.processRound(round2.toString(), 2026, 2, NOW),
                "A -1 (timeout/rejected) response to the fuzzy-match dialog must cancel processing");

        assertTrue(new B5_PlayerNames().findCandidatesByExactName("Existng Person").isEmpty(),
                "A cancelled fuzzy-match dialog must NOT silently create a new player record");
    }

    // --- A21 / A15: the same real player cannot appear more than once in a single round ---

    @Test
    void duplicatePlayerAcrossTwoRows_inSameRound_isRejected(@TempDir Path csvDir) throws Exception {
        // "Same Guy" appears as player1 in both rows of the same round.
        // The two opponents deliberately do NOT resemble each other (unlike
        // "Opponent A"/"Opponent B", which are Levenshtein distance 1 apart
        // and would otherwise fuzzy-match each other and trigger the
        // this-year hall-mismatch guard first, masking the duplicate-player
        // check this test actually exercises).
        Path csv = writeRoundCsv(csvDir, "r1.csv",
                "Same Guy,1,10,Opponent Alpha,2,5\n" +
                "Same Guy,1,3,Third Player,3,10\n");
        RoundCsvProcessor processor = new RoundCsvProcessor();
        processor.setMultiChoiceCallback((message, options) -> 0);

        assertFalse(processor.processRound(csv.toString(), 2026, 1, NOW),
                "A player appearing twice in the same round CSV must be rejected, not silently corrupt the round");

        // No partial write should have happened - the check runs before any
        // match/participant row is created for this round.
        A1_Rounds.Round round = new A1_Rounds().getRoundByYearAndOrder(2026, 1);
        if (round != null) {
            assertTrue(new C9_MatchParticipants().getParticipantsForRound(round.id).isEmpty(),
                    "Rejecting the duplicate must leave no partially-written match data behind");
        }
    }

    @Test
    void samePlayerBothSidesOfOneRow_isRejected(@TempDir Path csvDir) throws Exception {
        // The exact same name/hall on both sides of a single row - a
        // same-cell typo that would otherwise resolve to the identical
        // player_id and violate the (match_id, player_id) primary key.
        Path csv = writeRoundCsv(csvDir, "r1.csv", "Same Guy,1,10,Same Guy,1,5\n");
        RoundCsvProcessor processor = new RoundCsvProcessor();
        processor.setMultiChoiceCallback((message, options) -> 0);

        assertFalse(processor.processRound(csv.toString(), 2026, 1, NOW),
                "A row where both sides resolve to the same player must be rejected");
    }

    @Test
    void duplicatePlayer_doesNotFalselyTriggerOnWalkoverSentinel(@TempDir Path csvDir) throws Exception {
        // Multiple WALKOVER rows in the same round are legitimate (several
        // different players each facing a walkover) - the WALKOVER sentinel
        // itself must be exempt from the duplicate-player check.
        Path csv = writeRoundCsv(csvDir, "r1.csv",
                "Player One,1,,WALKOVER,,\n" +
                "Player Two,2,,WALKOVER,,\n");
        RoundCsvProcessor processor = new RoundCsvProcessor();
        processor.setMultiChoiceCallback((message, options) -> 0);

        assertTrue(processor.processRound(csv.toString(), 2026, 1, NOW),
                "Two different players each facing their own WALKOVER in the same round must not be flagged as duplicates");
    }

    // --- Containment minimum length: a <3-char name must no longer partial-match
    // every future debut containing its letters, while a 3-char name still must ---

    @Test
    void containment_underThreeChars_firesNoDialog_forADebutContainingTheShortName(@TempDir Path csvDir) throws Exception {
        // Regression: a 2-letter registered name ("Ng") used to trigger the
        // same-person dialog for every future debut containing those letters
        // ("Nightingale ..."), permanently adding dialog noise to ingestion.
        Path round1 = writeRoundCsv(csvDir, "r1.csv", "Ng,1,10,Opponent Quill,2,5\n");
        RoundCsvProcessor processor1 = new RoundCsvProcessor();
        processor1.setMultiChoiceCallback((message, options) -> fail("no dialog expected in round 1: " + message));
        assertTrue(processor1.processRound(round1.toString(), 2026, 1, NOW), "Round 1 should process");

        Path round2 = writeRoundCsv(csvDir, "r2.csv", "Nightingale Florence,1,8,Opponent Quill,2,2\n");
        RoundCsvProcessor processor2 = new RoundCsvProcessor();
        processor2.setMultiChoiceCallback((message, options) ->
                fail("the 2-char containment must NOT fire a dialog for the containing debut: " + message));
        assertTrue(processor2.processRound(round2.toString(), 2026, 2, NOW), "Round 2 should process without any dialog");

        assertNotEquals(resolvePlayerId("Ng"), resolvePlayerId("Nightingale Florence"),
                "the debut must have been created as its own player, not merged");
    }

    @Test
    void containment_atThreeChars_stillFiresTheDialog(@TempDir Path csvDir) throws Exception {
        Path round1 = writeRoundCsv(csvDir, "r1.csv", "Fis,1,10,Opponent Quill,2,5\n");
        RoundCsvProcessor processor1 = new RoundCsvProcessor();
        processor1.setMultiChoiceCallback((message, options) -> 0);
        assertTrue(processor1.processRound(round1.toString(), 2026, 1, NOW), "Round 1 should process");

        List<String> dialogs = new java.util.ArrayList<>();
        Path round2 = writeRoundCsv(csvDir, "r2.csv", "Fisher Jones,1,8,Opponent Quill,2,2\n");
        RoundCsvProcessor processor2 = new RoundCsvProcessor();
        processor2.setMultiChoiceCallback((message, options) -> {
            dialogs.add(message);
            for (int i = 0; i < options.length; i++) {
                if (options[i].startsWith("Treat as different people")) {
                    return i;
                }
            }
            return 0;
        });
        assertTrue(processor2.processRound(round2.toString(), 2026, 2, NOW), "Round 2 should process");

        assertTrue(dialogs.stream().anyMatch(m -> m.contains("may match existing player 'Fis'")),
                "a 3-char containment must still fire the same-person dialog, got: " + dialogs);
        assertNotEquals(resolvePlayerId("Fis"), resolvePlayerId("Fisher Jones"),
                "answering 'different people' must keep the two players distinct");
    }

    // --- Fuzzy-confirmed returning players get the SAME prior-year hall-mismatch
    // dialog the exact-name path offers (both resolutions) ---

    @Test
    void fuzzyReturningPlayerWithPriorYearHallChange_getsHallDialog_keepOldHall(@TempDir Path csvDir) throws Exception {
        // 2025: "Rosalind Beck" plays in hall 1.
        Path r2025 = writeRoundCsv(csvDir, "r2025.csv", "Rosalind Beck,1,10,Opp Alpha,2,5\n");
        RoundCsvProcessor processor2025 = new RoundCsvProcessor();
        processor2025.setMultiChoiceCallback((message, options) -> 0);
        assertTrue(processor2025.processRound(r2025.toString(), 2025, 1, NOW), "2025 round should process");

        // 2026: the typo'd returning player appears under a DIFFERENT hall.
        // Before the fix, answering "same person" silently adopted hall 3
        // with no dialog - while the identical situation via an exact name
        // match prompted "Keep old hall / Use new hall".
        List<String> dialogs = new java.util.ArrayList<>();
        Path r2026 = writeRoundCsv(csvDir, "r2026.csv", "Rosalind Bck,3,7,Opp Alpha,2,3\n");
        RoundCsvProcessor processor2026 = new RoundCsvProcessor();
        processor2026.setMultiChoiceCallback((message, options) -> {
            dialogs.add(message);
            if (message.contains("Hall Mismatch Resolution")) {
                return 0; // keep old hall
            }
            for (int i = 0; i < options.length; i++) {
                if (options[i].startsWith("Treat as same person")) {
                    return i;
                }
            }
            return 0;
        });
        assertTrue(processor2026.processRound(r2026.toString(), 2026, 1, NOW), "2026 round should process");

        assertTrue(dialogs.stream().anyMatch(m -> m.contains("may match existing player 'Rosalind Beck'")),
                "the fuzzy same-person dialog must fire first: " + dialogs);
        assertTrue(dialogs.stream().anyMatch(m -> m.contains("Hall Mismatch Resolution")),
                "the prior-year hall-mismatch dialog must ALSO fire for the fuzzy-confirmed returning player: " + dialogs);

        assertEquals(resolvePlayerId("Rosalind Beck"), resolvePlayerId("Rosalind Bck"),
                "the typo must resolve to the returning player");
        assertEquals(new A3_Halls().getHallByName("1").id,
                new B6_PlayerYearStatus().getStatus(resolvePlayerId("Rosalind Beck"), 2026).hallId,
                "answering 'keep old hall' must register the 2026 season under hall 1, not the CSV row's hall 3");
    }

    @Test
    void fuzzyReturningPlayerWithPriorYearHallChange_getsHallDialog_useNewHall(@TempDir Path csvDir) throws Exception {
        Path r2025 = writeRoundCsv(csvDir, "r2025.csv", "Percival Wren,1,10,Opp Alpha,2,5\n");
        RoundCsvProcessor processor2025 = new RoundCsvProcessor();
        processor2025.setMultiChoiceCallback((message, options) -> 0);
        assertTrue(processor2025.processRound(r2025.toString(), 2025, 1, NOW), "2025 round should process");

        List<String> dialogs = new java.util.ArrayList<>();
        Path r2026 = writeRoundCsv(csvDir, "r2026.csv", "Percival Wrem,3,7,Opp Alpha,2,3\n");
        RoundCsvProcessor processor2026 = new RoundCsvProcessor();
        processor2026.setMultiChoiceCallback((message, options) -> {
            dialogs.add(message);
            if (message.contains("Hall Mismatch Resolution")) {
                return 1; // use new hall, same player who changed halls
            }
            for (int i = 0; i < options.length; i++) {
                if (options[i].startsWith("Treat as same person")) {
                    return i;
                }
            }
            return 0;
        });
        assertTrue(processor2026.processRound(r2026.toString(), 2026, 1, NOW), "2026 round should process");

        assertTrue(dialogs.stream().anyMatch(m -> m.contains("Hall Mismatch Resolution")),
                "the prior-year hall-mismatch dialog must fire: " + dialogs);
        assertEquals(resolvePlayerId("Percival Wren"), resolvePlayerId("Percival Wrem"),
                "the typo must resolve to the returning player");
        assertEquals(new A3_Halls().getHallByName("3").id,
                new B6_PlayerYearStatus().getStatus(resolvePlayerId("Percival Wren"), 2026).hallId,
                "answering 'use new hall' must register the 2026 season under the CSV row's hall 3");
    }

    // --- First appearance claims exactly ONE unmapped same-name capped row ---

    @Test
    void firstAppearance_claimsExactlyOneUnmappedSameNameCappedRow(@TempDir Path csvDir) throws Exception {
        // Regression: a year's capped list can legitimately carry two rows of
        // the same name (two distinct same-named people). The first player to
        // appear used to claim EVERY unmapped same-name row, so the second
        // person could never be flagged capped.
        Path cappedCsv = csvDir.resolve("cappedlist.csv");
        Files.writeString(cappedCsv, "name,hall\nSame Name,4\nSame Name,5\n");
        assertTrue(new CappedListProcessor().processCappedList(cappedCsv.toString(), 2026, NOW),
                "the capped list with a duplicated name must process");

        Path round1 = writeRoundCsv(csvDir, "r1.csv", "Same Name,4,10,Other Person,2,5\n");
        RoundCsvProcessor processor = new RoundCsvProcessor();
        processor.setMultiChoiceCallback((message, options) -> 0);
        assertTrue(processor.processRound(round1.toString(), 2026, 1, NOW), "Round 1 should process");

        String playerId = resolvePlayerId("Same Name");
        assertTrue(new B6_PlayerYearStatus().getStatus(playerId, 2026).capped,
                "the appearing player must be flagged capped");

        List<B7_CappedImports.ImportRow> rows = new B7_CappedImports().findByYearAndName(2026, "Same Name");
        assertEquals(2, rows.size(), "both staging rows must still exist");
        long mappedCount = rows.stream().filter(r -> r.mapped).count();
        assertEquals(1, mappedCount,
                "exactly ONE of the two same-name rows may be claimed - the other must stay claimable by a second same-named person");
        assertEquals(playerId, rows.stream().filter(r -> r.mapped).findFirst().orElseThrow().playerId,
                "the claimed row must belong to the player who appeared");
    }
}
