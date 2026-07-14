package com.calplus.ihrgstats.databasemanager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Headless, DB-backed tests for {@link A1_Rounds#getAllRounds()} - the new
 * cross-year query backing the "All Years" round pickers/aggregations.
 * roundOrder resets every year, so ordering must be the (year, roundOrder)
 * tuple, not roundOrder alone.
 */
public class A1_RoundsTest {

    private static final String NOW = "2026-01-01 00:00:00.000";

    private String originalUserDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        new DatabaseSchema().createDatabase("default.db");
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    @Test
    void getAllRounds_ordersByYearThenRoundOrder_notRoundOrderAlone() throws Exception {
        A1_Rounds rounds = new A1_Rounds();
        // Deliberately created out of chronological order, and with 2026's
        // round numbers overlapping 2025's, to catch an ordering bug that
        // sorts by round_order alone (which would interleave the two years).
        rounds.getOrCreateRound(2026, 2, NOW);
        rounds.getOrCreateRound(2025, 1, NOW);
        rounds.getOrCreateRound(2026, 1, NOW);
        rounds.getOrCreateRound(2025, 2, NOW);

        List<A1_Rounds.Round> all = rounds.getAllRounds();

        assertEquals(4, all.size());
        assertEquals(2025, all.get(0).year);
        assertEquals(1, all.get(0).roundOrder);
        assertEquals(2025, all.get(1).year);
        assertEquals(2, all.get(1).roundOrder);
        assertEquals(2026, all.get(2).year);
        assertEquals(1, all.get(2).roundOrder);
        assertEquals(2026, all.get(3).year);
        assertEquals(2, all.get(3).roundOrder);
    }
}
