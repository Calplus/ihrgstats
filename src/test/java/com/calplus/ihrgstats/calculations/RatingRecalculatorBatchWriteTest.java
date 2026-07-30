package com.calplus.ihrgstats.calculations;

import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.telegrambot.utils.RoundCsvProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the single-connection batched rewrite the whole-history
 * recalculation now uses: rerunning must be row-for-row deterministic at
 * the DATABASE level, and each round's stored rows must exactly mirror the
 * recalculated rated set (stale rows for dropped players are deleted) -
 * the same contract the old one-connection-per-statement path had.
 */
public class RatingRecalculatorBatchWriteTest {

    private static final int YEAR = 2026;
    private static final String NOW = "2026-01-01 00:00:00.000";

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
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
        System.clearProperty("SETTINGS_CURRENTYEAR");
    }

    private static void ingest(Path csvDir, String fileName, int round, String rows) throws Exception {
        Path csv = csvDir.resolve(fileName);
        Files.writeString(csv, "name1,hall1,score1,name2,hall2,score2\n" + rows);
        RoundCsvProcessor processor = new RoundCsvProcessor();
        processor.setMultiChoiceCallback((message, options) -> 0);
        assertTrue(processor.processRound(csv.toString(), YEAR, round, NOW), "round " + round + " must ingest");
    }

    private static Map<String, String> allTrueEloRows(int trueEloTypeId) throws Exception {
        Map<String, String> rows = new HashMap<>();
        A1_Rounds rounds = new A1_Rounds();
        D11_PlayerRatings ratings = new D11_PlayerRatings();
        for (A1_Rounds.Round round : rounds.getRoundsForYear(YEAR)) {
            for (D11_PlayerRatings.Rating r : ratings.getRatingsForRound(round.id, trueEloTypeId)) {
                rows.put(r.playerId + "|" + r.roundId,
                        String.format("%.12f|%.12f|%.12f", r.ratingValue, r.ratingDeviation, r.volatility));
            }
        }
        return rows;
    }

    @Test
    void rerunningRecalc_isRowForRowDeterministic_andDeletesRowsOutsideTheRatedSet(@TempDir Path csvDir) throws Exception {
        ingest(csvDir, "r1.csv", 1, "Aurelia Nightshade,1,8,Bartholomew Krieger,2,2\n"
                + "Cassandra Vale,1,5,Dmitri Volkov,2,5\n");
        ingest(csvDir, "r2.csv", 2, "Aurelia Nightshade,1,3,Dmitri Volkov,2,7\n"
                + "Cassandra Vale,1,9,Bartholomew Krieger,2,1\n");

        int trueEloTypeId = new D10_RatingTypes().getRatingTypeId(D10_RatingTypes.TRUE_ELO);
        Map<String, String> firstRun = allTrueEloRows(trueEloTypeId);
        assertFalse(firstRun.isEmpty(), "Ingestion must have produced rating rows");

        RatingRecalculator.RecalcResult rerun = new RatingRecalculator().recalculateAll(NOW);
        assertEquals(firstRun, allTrueEloRows(trueEloTypeId),
                "Rerunning the whole-history recalculation must reproduce every stored row exactly");
        assertEquals(firstRun.size(), rerun.ratingRowsWritten,
                "The batched write must report exactly the stored row count");

        // A stale row for a player outside the rated set must be swept away
        // by the per-round delete, exactly as the old write path did.
        new B4_Players().createPlayer("zz_ghost", NOW);
        int round1Id = new A1_Rounds().getRoundByYearAndOrder(YEAR, 1).id;
        new D11_PlayerRatings().insertRating("zz_ghost", round1Id, trueEloTypeId, 1234.0, 100.0, 0.06, NOW);

        new RatingRecalculator().recalculateAll(NOW);
        assertEquals(firstRun, allTrueEloRows(trueEloTypeId),
                "A stale row for a player outside the rated set must be deleted by the rewrite");
    }
}
