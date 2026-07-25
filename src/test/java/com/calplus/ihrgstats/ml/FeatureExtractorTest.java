package com.calplus.ihrgstats.ml;

import com.calplus.ihrgstats.databasemanager.DatabaseSchema;
import com.calplus.ihrgstats.utils.DatabaseHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DB-backed tests for {@link FeatureExtractor}: board filtering (walkover
 * out, timeout in), strictly-as-of aggregates, NULL-seat imputation, the
 * outcome-encoding guard, and - the one the whole ML layer stands on -
 * the leakage guard: changing FUTURE results must not move PAST rows.
 */
public class FeatureExtractorTest {

    private String originalUserDir;
    private final MlTestFixtures fx = new MlTestFixtures();

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        new DatabaseSchema().createDatabase("default.db");
        fx.seedCore();
        fx.createPlayers("AA-01", "AA-02", "AA-03", "BB-01", "BB-02", "BB-03");
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    /**
     * Round 1: AA-01 beats BB-01 (seats 1), AA-02 draws BB-02 (seats 2),
     *          AA-03 walkover (excluded).
     * Round 2: AA-01 (NULL seat) beats BB-02 who TIMED OUT (seat 1),
     *          BB-01 beats AA-02 (seats 2).
     * Round 3: AA-01 beats BB-01 (seats 1).
     */
    private int[] buildStandardFixture() throws Exception {
        int r1 = fx.createRound(2025, 1);
        int r2 = fx.createRound(2025, 2);
        int r3 = fx.createRound(2025, 3);
        int b1 = fx.addBoard(r1, "AA-01", fx.hallA, 1, "BB-01", fx.hallB, 1, 1.0);
        int b2 = fx.addBoard(r1, "AA-02", fx.hallA, 2, "BB-02", fx.hallB, 2, 0.5);
        fx.addWalkoverBoard(r1, "AA-03", fx.hallA, 3, fx.hallB);
        int b4 = fx.addBoard(r2, "AA-01", fx.hallA, null,
                com.calplus.ihrgstats.databasemanager.C9_MatchParticipants.PARTICIPATION_STANDARD, 1.0,
                "BB-02", fx.hallB, 1,
                com.calplus.ihrgstats.databasemanager.C9_MatchParticipants.PARTICIPATION_TIMEOUT, 0.0);
        int b5 = fx.addBoard(r2, "BB-01", fx.hallB, 2, "AA-02", fx.hallA, 2, 1.0);
        int b6 = fx.addBoard(r3, "AA-01", fx.hallA, 1, "BB-01", fx.hallB, 1, 1.0);
        return new int[]{b1, b2, b4, b5, b6};
    }

    private static FeatureExtractor.RawBoard byMatchId(List<FeatureExtractor.RawBoard> rows, int matchId) {
        return rows.stream().filter(rb -> rb.matchId == matchId).findFirst().orElseThrow();
    }

    @Test
    void filtersAndAsOfAggregates() throws Exception {
        int[] ids = buildStandardFixture();
        List<FeatureExtractor.RawBoard> rows = new FeatureExtractor().extractAll();

        // Walkover board excluded; the 5 rated boards (incl. the timeout board) present.
        assertEquals(5, rows.size());
        FeatureExtractor.RawBoard b1 = byMatchId(rows, ids[0]);
        FeatureExtractor.RawBoard b2 = byMatchId(rows, ids[1]);
        FeatureExtractor.RawBoard b4 = byMatchId(rows, ids[2]);
        FeatureExtractor.RawBoard b6 = byMatchId(rows, ids[4]);

        // Deterministic side order: A = lexicographically smaller player_id.
        assertEquals("AA-01", b1.a.playerId);
        assertEquals("BB-01", b1.b.playerId);
        assertEquals(1.0, b1.outcomeA);
        assertTrue(b2.isDraw());

        // Timeout board: flag on the timed-out side only.
        assertEquals("AA-01", b4.a.playerId);
        assertFalse(b4.aTimedOut);
        assertTrue(b4.bTimedOut);

        // Strictly as-of career counts: entering round 1 = 0; round 2 = 1; round 3 = 2.
        assertEquals(0, b1.a.careerBoards);
        assertEquals(1, b4.a.careerBoards);
        assertEquals(2, b6.a.careerBoards);
        // BB-02's timeout happened IN round 2, so entering round 2 it is still 0.
        assertEquals(0, b4.b.careerTimeouts);

        // Round-1 debutants: default rating/RD and default seat prior (no history at all).
        assertEquals(FeatureExtractor.DEFAULT_RATING, b1.a.rating);
        assertEquals(FeatureExtractor.DEFAULT_RD, b1.a.rd);
        assertEquals(FeatureExtractor.DEFAULT_RATING, b1.a.seatPrior);

        // AA-01 won round 1, so their round-2 entry rating must exceed BB-02's.
        assertTrue(b4.a.rating > b4.b.rating,
                "round-1 winner should enter round 2 with the higher forward rating");

        // Form window as-of: AA-01 enters round 3 with outcomes {1.0, 1.0}.
        assertEquals(2, b6.a.countLast5);
        assertEquals(2.0, b6.a.sumOutcomeLast5, 1e-12);

        // NULL seat imputation: b4 A-side seat is NULL -> anti[2] = 3.0 - 1.0.
        assertNull(b4.a.seat);
        FeatureExtractor.Vectors v = FeatureExtractor.assemble(b4, 6.0);
        assertEquals(FeatureExtractor.SEAT_IMPUTED - 1.0, v.anti[2], 1e-12);
        for (double x : v.sym) {
            assertTrue(Double.isFinite(x));
        }
        for (double x : v.anti) {
            assertTrue(Double.isFinite(x));
        }
    }

    @Test
    void antiFeaturesAreExactlyAntisymmetric() throws Exception {
        buildStandardFixture();
        for (FeatureExtractor.RawBoard rb : new FeatureExtractor().extractAll()) {
            FeatureExtractor.Vectors v = FeatureExtractor.assemble(rb, 6.0);
            FeatureExtractor.Vectors vs = FeatureExtractor.assemble(FeatureExtractor.swapped(rb), 6.0);
            for (int j = 0; j < FeatureExtractor.ANTI_DIM; j++) {
                assertEquals(-v.anti[j], vs.anti[j], 1e-12, "anti[" + j + "] must negate on swap");
            }
            for (int j = 0; j < FeatureExtractor.SYM_DIM; j++) {
                assertEquals(v.sym[j], vs.sym[j], 1e-12, "sym[" + j + "] must be swap-invariant");
            }
        }
    }

    /** The core leakage guard: rewriting round-3 results must leave rounds 1-2 rows byte-identical. */
    @Test
    void futureResultsDoNotChangePastRows() throws Exception {
        int[] ids = buildStandardFixture();
        List<FeatureExtractor.RawBoard> before = new FeatureExtractor().extractAll();
        String pastBefore = fingerprintRounds(before, 1);

        // Flip the round-3 board's result entirely (winner becomes loser).
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE match_participants SET outcome = 1.0 - outcome WHERE match_id = ?")) {
            ps.setInt(1, ids[4]);
            ps.executeUpdate();
        }

        List<FeatureExtractor.RawBoard> after = new FeatureExtractor().extractAll();
        String pastAfter = fingerprintRounds(after, 1);
        assertEquals(pastBefore, pastAfter,
                "rounds 1-2 features moved when only round 3 results changed - lookahead leak!");

        // Sanity: the round-3 row itself DID change.
        assertNotEquals(byMatchId(before, ids[4]).outcomeA, byMatchId(after, ids[4]).outcomeA);
    }

    @Test
    void invalidOutcomeEncodingIsRejected() throws Exception {
        int[] ids = buildStandardFixture();
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE match_participants SET outcome = 0.3 WHERE match_id = ? AND player_id = 'AA-01'")) {
            ps.setInt(1, ids[4]);
            ps.executeUpdate();
        }
        FeatureExtractor extractor = new FeatureExtractor();
        IllegalStateException ex = assertThrows(IllegalStateException.class, extractor::extractAll);
        assertTrue(ex.getMessage().contains("0.3"));
    }

    /** Full-field fingerprint of every row up to and including maxRoundSeq. */
    private static String fingerprintRounds(List<FeatureExtractor.RawBoard> rows, int maxRoundSeq) {
        return rows.stream()
                .filter(rb -> rb.roundSeq <= maxRoundSeq)
                .map(FeatureExtractorTest::fingerprint)
                .collect(Collectors.joining("\n"));
    }

    private static String fingerprint(FeatureExtractor.RawBoard rb) {
        FeatureExtractor.Vectors v = FeatureExtractor.assemble(rb, 6.0);
        StringBuilder sb = new StringBuilder();
        sb.append(rb.matchId).append('|').append(rb.roundSeq).append('|').append(rb.outcomeA)
          .append('|').append(rb.aTimedOut).append('|').append(rb.bTimedOut);
        for (FeatureExtractor.Side s : new FeatureExtractor.Side[]{rb.a, rb.b}) {
            sb.append(String.format(Locale.ROOT, "|%s;%d;%s;%.10f;%.10f;%d;%d;%.10f;%d;%.10f",
                    s.playerId, s.hallId, s.seat, s.rating, s.rd, s.careerBoards, s.careerTimeouts,
                    s.sumOutcomeLast5, s.countLast5, s.seatPrior));
        }
        for (double x : v.sym) {
            sb.append(String.format(Locale.ROOT, "|%.12f", x));
        }
        for (double x : v.anti) {
            sb.append(String.format(Locale.ROOT, "|%.12f", x));
        }
        return sb.toString();
    }
}
