package com.calplus.ihrgstats.upload;

import com.calplus.ihrgstats.corpus.CorpusIntegrityChecks;
import com.calplus.ihrgstats.upload.CsvVariants.Style;
import com.calplus.ihrgstats.upload.CsvVariants.Table;
import com.calplus.ihrgstats.upload.UploadTestSupport.Dialogs;
import com.calplus.ihrgstats.upload.UploadTestSupport.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.calplus.ihrgstats.upload.UploadTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Lane b1, scope item 3: sync properties of the upload pipeline, on the
 * fictional corpus (2001 rounds 1-3 unless stated) with semantic database
 * dumps as the oracle.
 * <ul>
 *   <li>same file twice == once (latest round; capped list);</li>
 *   <li>rounds out of order within a year: rejected, database untouched;</li>
 *   <li>seasons out of order: defined, but NOT the same as in order (B1-9);</li>
 *   <li>corrected re-upload == fresh ingest of the final files - holds for a
 *       score fix, fails when the wrong file introduced a name (B1-10);</li>
 *   <li>capped list replace == fresh ingest with the final list; an unknown
 *       hall in the list is accepted silently (B1-11).</li>
 * </ul>
 */
public class UploadSyncPropertiesTest {

    private String originalUserDir;

    @BeforeEach
    void saveUserDir() {
        originalUserDir = System.getProperty("user.dir");
    }

    @AfterEach
    void restoreUserDir() {
        System.setProperty("user.dir", originalUserDir);
        System.clearProperty("SETTINGS_CURRENTYEAR");
    }

    private static void ok(Outcome o, String what) {
        assertTrue(o.ok(), what + ": " + o.joined());
    }

    /** Capped list + rounds 1..n of 2001 from the corpus. */
    private static Dialogs ingest2001(Path home, int lastRound) throws Exception {
        initHome(home);
        Dialogs d = new Dialogs(CORPUS_2001_SCRIPT);
        ingestSeason(2001, lastRound, UploadTestSupport::sample, d);
        return d;
    }

    /** Writes a copy of a corpus round with one cell replaced in the first row that matches. */
    private static Path edited(Path dir, String corpusFile, String find, String replace) throws Exception {
        Files.createDirectories(dir);
        String text = Files.readString(sample(corpusFile));
        assertTrue(text.contains(find), corpusFile + " must contain " + find);
        Path out = dir.resolve(corpusFile);
        Files.writeString(out, text.replaceFirst(java.util.regex.Pattern.quote(find), java.util.regex.Matcher.quoteReplacement(replace)));
        return out;
    }

    // ------------------------------------------------------------ idempotence

    @Test
    void sameLatestRoundTwice_equalsOnce_andCancelLeavesDatabaseUntouched(@TempDir Path tmp) throws Exception {
        ingest2001(tmp.resolve("once"), 3);
        Map<String, List<String>> once = dump();

        Dialogs d = ingest2001(tmp.resolve("twice"), 3);
        Map<String, List<String>> beforeRepeat = dump();
        assertSameDatabase(once, beforeRepeat, "two identical ingests");

        d.reprocessAnswer = 1; // Cancel
        Outcome cancelled = round(sample("2001_round_3.csv"), 2001, 3, d);
        assertFalse(cancelled.ok());
        assertSameDatabase(once, dump(), "cancelled re-upload of round 3");

        d.reprocessAnswer = 0; // Continue and reprocess
        ok(round(sample("2001_round_3.csv"), 2001, 3, d), "re-upload of round 3");
        assertSameDatabase(once, dump(), "round 3 uploaded twice vs once");

        ok(capped(sample("2001_cappedlist.csv"), 2001), "capped list again");
        assertSameDatabase(once, dump(), "capped list uploaded twice vs once");
    }

    // --------------------------------------------------------- out of order

    @Test
    void roundOutOfOrder_isRejected_andDatabaseUntouched(@TempDir Path tmp) throws Exception {
        Dialogs d = ingest2001(tmp.resolve("h"), 1);
        Map<String, List<String>> before = dump();
        Outcome skip = round(sample("2001_round_3.csv"), 2001, 3, d);
        assertFalse(skip.ok());
        assertEquals("\uD83D\uDD34 Cannot process round 3 for 2001 - rounds must be processed in order (last processed: 1).",
                skip.messages().get(0));
        assertSameDatabase(before, dump(), "rejected out-of-order round");
        Outcome firstOfEmptyYear = round(sample("2002_round_2.csv"), 2002, 2, d);
        assertFalse(firstOfEmptyYear.ok());
        assertTrue(firstOfEmptyYear.joined().contains("(last processed: 0)"), firstOfEmptyYear.joined());
        assertSameDatabase(before, dump(), "rejected round 2 of an empty year");
    }

    /**
     * B1-9: a season uploaded AFTER a later season skips the hall-change
     * question (the resolver only looks at EARLIER years), so two different
     * people who share a name are silently merged. In order, the 2003 upload
     * asks about "Coral Reeves" (hall 8 in 2002, HallC in 2003) and the admin
     * keeps them apart; uploading 2003 round 1 first and 2002 round 1 second
     * merges them into one player with no question - the database differs
     * from the in-order one. Backfilling old seasons into the fresh v3.0
     * database in any order but oldest-first hits this.
     */
    @Test
    void seasonsOutOfOrder_mergeSameNamePeopleWithoutAsking_knownDefect_B1_9(@TempDir Path tmp) throws Exception {
        List<Script> script = List.of(
                new Script("Player: Coral Reeves", 2),   // different player
                new Script("Player: Jim Hopper", 1));    // same player, new hall
        // in order: 2002 R1 then 2003 R1
        initHome(tmp.resolve("forward"));
        Dialogs fwd = new Dialogs(script);
        fwd.fallbackAnswer = 1; // any fuzzy name dialog: different people
        ok(round(sample("2002_round_1.csv"), 2002, 1, fwd), "2002 R1");
        ok(round(sample("2003_round_1.csv"), 2003, 1, fwd), "2003 R1");
        List<String[]> fwdCoral = query("SELECT DISTINCT player_id FROM player_names WHERE name = 'Coral Reeves'");
        int fwdPlayers = playerCount();
        assertEquals(2, fwdCoral.size(), "in order, the admin is asked and the two Coral Reeves stay apart");
        assertTrue(fwd.seen.stream().anyMatch(m -> m.contains("Player: Coral Reeves")), "in order the hall dialog fires");

        // reversed: 2003 R1 then 2002 R1
        initHome(tmp.resolve("reverse"));
        Dialogs rev = new Dialogs(script);
        rev.fallbackAnswer = 1;
        ok(round(sample("2003_round_1.csv"), 2003, 1, rev), "2003 R1 first");
        ok(round(sample("2002_round_1.csv"), 2002, 1, rev), "2002 R1 second");
        List<String[]> revCoral = query("SELECT DISTINCT player_id FROM player_names WHERE name = 'Coral Reeves'");
        assertTrue(rev.seen.stream().noneMatch(m -> m.contains("Hall Mismatch")), "reversed: no hall dialog at all: " + rev.seen);
        assertEquals(1, revCoral.size(), "today the two people are merged silently");
        String merged = revCoral.get(0)[0];
        List<String[]> halls = query("SELECT s.year, h.hall_name FROM player_year_status s JOIN halls h ON h.id = s.hall_id WHERE s.player_id = '"
                + merged + "' ORDER BY s.year");
        assertEquals(2, halls.size());
        assertEquals("8", halls.get(0)[1]);
        assertEquals("HallC", halls.get(1)[1]);
        assertEquals(fwdPlayers - 1, playerCount(), "one player fewer than the in-order database");
        System.out.println("[b1] seasons reversed: Coral Reeves -> one player " + merged + " (2002 hall 8, 2003 HallC); in order: "
                + fwdCoral.size() + " players; dialogs in order " + fwd.seen.size() + ", reversed " + rev.seen.size());
    }

    // ---------------------------------------------------- corrected re-upload

    /**
     * B1-10 (a): a wrong score in round 2, fixed by re-uploading round 2 and
     * then round 3. Boards, ratings, names and statuses equal the fresh
     * ingest, but the round-2 SNAPSHOT gains rows for the players who debut
     * only in round 3: their year-status rows (created by the first round-3
     * upload) survive the deletion of round 3, and the per-round rating step
     * (RoundCsvProcessor:429-436) carries every status row of a playing hall,
     * with no "already appeared this year" gate (the recalc has one,
     * RatingRecalculator:157-160). Should be identical.
     */
    @Test
    void correctedScoreReupload_snapshotGainsLaterDebutants_knownDefect_B1_10(@TempDir Path tmp) throws Exception {
        ingest2001(tmp.resolve("fresh"), 3);
        Map<String, List<String>> fresh = dump();

        Path wrong = edited(tmp.resolve("wrong"), "2001_round_2.csv", "Walter White,1,203.75", "Walter White,1,103.75");
        Dialogs d = ingest2001(tmp.resolve("fixed"), 1);
        ok(round(wrong, 2001, 2, d), "wrong round 2");
        ok(round(sample("2001_round_3.csv"), 2001, 3, d), "round 3 on top of the wrong round 2");
        assertFalse(differingTables(fresh, dump()).isEmpty(), "the wrong score must be visible before the fix");
        d.reprocessAnswer = 0;
        ok(round(sample("2001_round_2.csv"), 2001, 2, d), "corrected round 2");
        assertEquals(2, new com.calplus.ihrgstats.databasemanager.A1_Rounds().getRoundsForYear(2001).size(),
                "reprocessing round 2 deletes round 3, as the dialog warns");
        ok(round(sample("2001_round_3.csv"), 2001, 3, d), "round 3 again");
        Map<String, List<String>> fixed = dump();
        assertEquals(List.of("player_ratings_snapshot"), differingTables(fresh, fixed), "today only the snapshot table differs");
        List<String> extra = new ArrayList<>(fixed.get("player_ratings_snapshot"));
        extra.removeAll(fresh.get("player_ratings_snapshot"));
        List<String> missing = new ArrayList<>(fresh.get("player_ratings_snapshot"));
        missing.removeAll(fixed.get("player_ratings_snapshot"));
        assertTrue(missing.isEmpty(), "nothing is lost: " + missing);
        assertEquals(4, extra.size(), "four round-3 debutants gain a round-2 snapshot: " + extra);
        for (String row : extra) {
            String pid = row.substring(0, row.indexOf('|'));
            assertTrue(row.contains("|2001:2|"), row);
            String firstRound = query("SELECT MIN(r.round_order) FROM match_participants p JOIN matches m ON m.id = p.match_id "
                    + "JOIN rounds r ON r.id = m.round_id WHERE p.player_id = '" + pid + "' AND r.year = 2001").get(0)[0];
            assertEquals("3", firstRound, pid + " first plays in round 3, yet has a round-2 snapshot");
        }
        System.out.println("[b1] corrected-score re-upload: extra round-2 snapshot rows " + extra);
    }

    /**
     * B1-10: when the WRONG file contained a name that is not in the final
     * files (a typo far from any known name, so no dialog), the player it
     * created survives the correction: player row, name, and an ACTIVE
     * player_year_status in that hall - and because every active hall member
     * is carried through each round's rating, the phantom gets rating rows
     * for rounds it never played. The database is not the fresh one.
     */
    @Test
    void correctedNameReupload_leavesPhantomActivePlayer_knownDefect_B1_10(@TempDir Path tmp) throws Exception {
        ingest2001(tmp.resolve("fresh"), 3);
        Map<String, List<String>> fresh = dump();
        int freshPlayers = playerCount();

        Path wrong = edited(tmp.resolve("wrong"), "2001_round_2.csv", "Walter White,1,203.75", "Qwilliam Zantorr,1,203.75");
        Dialogs d = ingest2001(tmp.resolve("fixed"), 1);
        ok(round(wrong, 2001, 2, d), "wrong round 2 (typo'd name, no dialog)");
        d.reprocessAnswer = 0;
        ok(round(sample("2001_round_2.csv"), 2001, 2, d), "corrected round 2");
        ok(round(sample("2001_round_3.csv"), 2001, 3, d), "round 3");

        Map<String, List<String>> fixed = dump();
        List<String> differ = differingTables(fresh, fixed);
        assertFalse(differ.isEmpty(), "today the corrected database differs from the fresh one");
        assertEquals(freshPlayers + 1, playerCount(), "one phantom player survives");
        String phantom = query("SELECT player_id FROM player_names WHERE name = 'Qwilliam Zantorr'").get(0)[0];
        assertEquals("1", query("SELECT active FROM player_year_status WHERE player_id = '" + phantom + "' AND year = 2001").get(0)[0]
                .replace("true", "1"), "the phantom is ACTIVE in 2001");
        int phantomBoards = Integer.parseInt(query("SELECT COUNT(*) FROM match_participants WHERE player_id = '" + phantom + "'").get(0)[0]);
        int phantomRatings = Integer.parseInt(query("SELECT COUNT(*) FROM player_ratings WHERE player_id = '" + phantom + "'").get(0)[0]);
        int phantomSnapshots = Integer.parseInt(query("SELECT COUNT(*) FROM player_ratings_snapshot WHERE player_id = '" + phantom + "'").get(0)[0]);
        int hallMembers = Integer.parseInt(query("SELECT COUNT(*) FROM player_year_status s JOIN halls h ON h.id = s.hall_id "
                + "WHERE s.player_id = '" + phantom + "' AND s.year = 2001 AND h.hall_name = '1'").get(0)[0]);
        assertEquals(0, phantomBoards, "the phantom has no boards after the correction");
        assertEquals(0, phantomRatings, "the whole-history recalc does not rate it (first-appearance gate)");
        assertTrue(phantomSnapshots > 0, "...but the per-round step snapshots it as an active hall member");
        assertEquals(1, hallMembers, "it is listed as a hall-1 member for 2001 (the source of the player pickers, /lineup roster, /predict)");
        String integrity;
        try {
            CorpusIntegrityChecks.runAll(2001);
            integrity = "integrity battery passes";
        } catch (AssertionError e) {
            integrity = "integrity battery FAILS: " + e.getMessage();
        }
        System.out.println("[b1] corrected-name re-upload: differing tables " + differ + "; phantom " + phantom
                + " boards=" + phantomBoards + " ratingRows=" + phantomRatings + " snapshotRows=" + phantomSnapshots + "; " + integrity);
    }

    /**
     * B1-12: "Cancel" in an identity dialog is presented as safe, but every
     * row resolved BEFORE the cancelled dialog has already written its new
     * player, name and year-status rows (PlayerIdentityResolver:183-218 runs
     * inside the resolution loop, before the round's write block). Round 3
     * of 2001 is cancelled at its 'Teddy Rosevelt' dialog (row 17): the
     * database is no longer the one from before the upload.
     */
    @Test
    void cancelledIdentityDialog_leavesPartialIdentityRows_knownDefect_B1_12(@TempDir Path tmp) throws Exception {
        ingest2001(tmp.resolve("h"), 2);
        Map<String, List<String>> before = dump();
        int playersBefore = playerCount();
        Dialogs cancel = new Dialogs(List.of(new Script("'Teddy Rosevelt' may match", 2)));
        Outcome o = round(sample("2001_round_3.csv"), 2001, 3, cancel);
        assertFalse(o.ok());
        assertTrue(o.joined().contains("Round processing cancelled during player identity resolution."), o.joined());
        assertEquals(2, new com.calplus.ihrgstats.databasemanager.A1_Rounds().getLatestRoundOrder(2001), "no round row is created");
        List<String> differ = differingTables(before, dump());
        int residue = playerCount() - playersBefore;
        assertTrue(residue > 0, "today the cancelled upload leaves new players behind");
        assertTrue(differ.containsAll(List.of("players", "player_names", "player_year_status")), differ.toString());
        assertFalse(differ.contains("matches+participants"), "no board is written");
        System.out.println("[b1] cancelled round 3: " + residue + " player(s) left behind; differing tables " + differ);

        // Same mechanism for a plain validation failure: a typo'd hall in the
        // LAST row of round 3 is rejected ("Unknown hall") only after every
        // earlier row has been resolved and its debutants written.
        ingest2001(tmp.resolve("h2"), 2);
        Map<String, List<String>> before2 = dump();
        int players2 = playerCount();
        String text = Files.readString(sample("2001_round_3.csv")).stripTrailing();
        int lastLine = text.lastIndexOf('\n') + 1;
        String last = text.substring(lastLine);
        String[] cells = last.split(",", -1);
        assertEquals(6, cells.length, last);
        cells[4] = cells[4].isEmpty() ? cells[4] : "Hall Nine";
        if (cells[4].isEmpty()) cells[1] = "Hall Nine";
        Path typo = tmp.resolve("typo").resolve("2001_round_3.csv");
        Files.createDirectories(typo.getParent());
        Files.writeString(typo, text.substring(0, lastLine) + String.join(",", cells) + "\r\n");
        Outcome rejected = round(typo, 2001, 3, new Dialogs(CORPUS_2001_SCRIPT));
        assertFalse(rejected.ok());
        assertTrue(rejected.joined().contains("Unknown hall: 'Hall Nine'"), rejected.joined());
        int residue2 = playerCount() - players2;
        assertTrue(residue2 > 0, "a rejected round leaves its earlier rows' debutants behind");
        System.out.println("[b1] round 3 rejected for an unknown hall in its last row: " + residue2
                + " player(s) left behind; differing tables " + differingTables(before2, dump()));
    }

    // --------------------------------------------------------- capped list

    /** Replacing the capped list mid-season equals a fresh ingest that had the final list from the start. */
    @Test
    void cappedListReplacedMidSeason_equalsFreshIngestWithFinalList(@TempDir Path tmp) throws Exception {
        // Final list B = corpus list without "Kim Wexler", plus "Walter White" (who plays from round 1).
        Path dir = tmp.resolve("lists");
        Files.createDirectories(dir);
        String a = Files.readString(sample("2001_cappedlist.csv"));
        assertTrue(a.contains("Kim Wexler,1"));
        Path listB = dir.resolve("2001_cappedlist.csv");
        Files.writeString(listB, a.replace("Kim Wexler,1", "Walter White,1"));

        initHome(tmp.resolve("fresh"));
        Dialogs d1 = new Dialogs(CORPUS_2001_SCRIPT);
        ok(capped(listB, 2001), "list B first");
        for (int r = 1; r <= 3; r++) ok(round(sample("2001_round_" + r + ".csv"), 2001, r, d1), "round " + r);
        Map<String, List<String>> fresh = dump();

        Dialogs d2 = ingest2001(tmp.resolve("replaced"), 3); // with list A
        Outcome replace = capped(listB, 2001);
        ok(replace, "list B replaces A");
        Map<String, List<String>> replaced = dump();
        // Capped flags and staging rows must match; ratings never depend on the capped flag.
        for (String table : new String[]{"player_year_status", "capped_imports", "player_ratings", "matches+participants"}) {
            assertEquals(fresh.get(table), replaced.get(table), table + ": replace vs fresh-with-final-list");
        }
        assertSameDatabase(fresh, replaced, "capped list replaced mid-season vs fresh with final list");
        System.out.println("[b1] capped replace message: " + replace.joined());
    }

    /**
     * B1-11: the capped list's hall column is never checked - a hall that
     * does not exist is stored and the upload reports success.
     */
    @Test
    void cappedListUnknownHall_acceptedSilently_knownDefect_B1_11(@TempDir Path tmp) throws Exception {
        initHome(tmp.resolve("h"));
        Path list = tmp.resolve("2001_cappedlist.csv");
        Files.writeString(list, "name,hall\r\nCorwin Baxendale,Hall Ninety-Nine\r\nDara O'Fennelly,99\r\n");
        Outcome o = capped(list, 2001);
        assertTrue(o.ok(), "today an unknown hall is accepted: " + o.joined());
        assertTrue(o.joined().contains("processed successfully"), o.joined());
        assertEquals("2", query("SELECT COUNT(*) FROM capped_imports WHERE prev_hall IN ('Hall Ninety-Nine', '99')").get(0)[0],
                "both rows are stored with their non-existent hall");
        assertEquals("0", query("SELECT COUNT(*) FROM halls WHERE hall_name IN ('Hall Ninety-Nine', '99')").get(0)[0]);
    }
}
