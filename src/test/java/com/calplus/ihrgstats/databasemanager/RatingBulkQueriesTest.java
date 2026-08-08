package com.calplus.ihrgstats.databasemanager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct DAO-edge tests for the bulk "latest row per player up to a round"
 * queries backing {@code RankingQueryHelper.getLatestRatingsUpToRound}'s
 * flat-query rewrite: last-write-wins per player, the round_order &lt;=
 * boundary, and the year/rating-type filters. These were previously covered
 * only indirectly (the corpus battery proves end-to-end equivalence; this
 * pins the DAO contract itself).
 */
public class RatingBulkQueriesTest {

    private static final String NOW = "2026-01-01 00:00:00.000";

    private String originalUserDir;

    private int trueEloTypeId;
    private int expEloTypeId;
    private int round2025r1;
    private int round2025r2;
    private int round2026r1;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        new DatabaseSchema().createDatabase("default.db");
        new D10_RatingTypes().seedDefaults(NOW);

        D10_RatingTypes ratingTypes = new D10_RatingTypes();
        trueEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.TRUE_ELO);
        expEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.EXP_ELO);

        B4_Players players = new B4_Players();
        players.createPlayer("p1", NOW);
        players.createPlayer("p2", NOW);
        players.createPlayer("p3", NOW);

        A1_Rounds rounds = new A1_Rounds();
        round2025r1 = rounds.getOrCreateRound(2025, 1, NOW).id;
        round2025r2 = rounds.getOrCreateRound(2025, 2, NOW).id;
        round2026r1 = rounds.getOrCreateRound(2026, 1, NOW).id;
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    @Test
    void latestRatingsBulk_lastWriteWinsPerPlayer_withBoundaryYearAndTypeFilters() throws Exception {
        D11_PlayerRatings ratings = new D11_PlayerRatings();
        ratings.insertRating("p1", round2025r1, trueEloTypeId, 1000.0, 200.0, 0.06, NOW);
        ratings.insertRating("p1", round2025r2, trueEloTypeId, 1100.0, 180.0, 0.06, NOW);
        ratings.insertRating("p2", round2025r1, trueEloTypeId, 900.0, 220.0, 0.06, NOW);
        ratings.insertRating("p3", round2026r1, trueEloTypeId, 1200.0, 150.0, 0.06, NOW); // other year
        ratings.insertRating("p1", round2025r2, expEloTypeId, 1500.0, 100.0, 0.06, NOW);  // other type

        Map<String, D11_PlayerRatings.Rating> upToR2 = ratings.getLatestRatingsUpToRoundBulk(2025, 2, trueEloTypeId);
        assertEquals(2, upToR2.size(), "only 2025 TrueElo players may appear");
        assertEquals(1100.0, upToR2.get("p1").ratingValue, 1e-9,
                "p1 has rows in rounds 1 and 2 - the LATER round's row must win");
        assertEquals(900.0, upToR2.get("p2").ratingValue, 1e-9,
                "p2's only row (round 1) is still their latest at round 2");
        assertFalse(upToR2.containsKey("p3"), "a 2026 row must never leak into the 2025 query");

        Map<String, D11_PlayerRatings.Rating> upToR1 = ratings.getLatestRatingsUpToRoundBulk(2025, 1, trueEloTypeId);
        assertEquals(1000.0, upToR1.get("p1").ratingValue, 1e-9,
                "with the limit at round 1, p1's round-2 row is beyond the boundary and must be excluded");

        Map<String, D11_PlayerRatings.Rating> expElo = ratings.getLatestRatingsUpToRoundBulk(2025, 2, expEloTypeId);
        assertEquals(1, expElo.size(), "the ExpElo query must see only ExpElo rows");
        assertEquals(1500.0, expElo.get("p1").ratingValue, 1e-9);
    }

    @Test
    void latestSnapshotsBulk_lastWriteWinsPerPlayer_withBoundaryYearAndTypeFilters() throws Exception {
        D15_PlayerRatingSnapshots snapshots = new D15_PlayerRatingSnapshots();
        snapshots.insertSnapshot("p1", round2025r1, trueEloTypeId, 1010.0, 200.0, 0.06, NOW);
        snapshots.insertSnapshot("p1", round2025r2, trueEloTypeId, 1110.0, 180.0, 0.06, NOW);
        snapshots.insertSnapshot("p2", round2025r1, trueEloTypeId, 910.0, 220.0, 0.06, NOW);
        snapshots.insertSnapshot("p3", round2026r1, trueEloTypeId, 1210.0, 150.0, 0.06, NOW); // other year
        snapshots.insertSnapshot("p1", round2025r2, expEloTypeId, 1510.0, 100.0, 0.06, NOW);  // other type

        Map<String, D15_PlayerRatingSnapshots.Snapshot> upToR2 =
                snapshots.getLatestSnapshotsUpToRoundBulk(2025, 2, trueEloTypeId);
        assertEquals(2, upToR2.size());
        assertEquals(1110.0, upToR2.get("p1").ratingValue, 1e-9, "the later round's snapshot must win");
        assertEquals(910.0, upToR2.get("p2").ratingValue, 1e-9);
        assertFalse(upToR2.containsKey("p3"), "a 2026 snapshot must never leak into the 2025 query");

        Map<String, D15_PlayerRatingSnapshots.Snapshot> upToR1 =
                snapshots.getLatestSnapshotsUpToRoundBulk(2025, 1, trueEloTypeId);
        assertEquals(1010.0, upToR1.get("p1").ratingValue, 1e-9, "the round_order boundary must be inclusive-only");

        Map<String, D15_PlayerRatingSnapshots.Snapshot> expElo =
                snapshots.getLatestSnapshotsUpToRoundBulk(2025, 2, expEloTypeId);
        assertEquals(1, expElo.size(), "the ExpElo query must see only ExpElo snapshots");
        assertEquals(1510.0, expElo.get("p1").ratingValue, 1e-9);
    }
}
