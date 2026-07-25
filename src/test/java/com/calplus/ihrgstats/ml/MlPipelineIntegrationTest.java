package com.calplus.ihrgstats.ml;

import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.telegrambot.utils.RoundCsvProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end proof that the AI/ML layer is actually wired into the live
 * upload pipeline (not just unit-testable in isolation): uploading enough
 * rounds through the real {@link RoundCsvProcessor} must, on its own,
 * populate {@code ml_models}, log pre-round predictions to
 * {@code ai_predictions} using an already-trained champion, and refresh
 * {@code player_rolling_cache} - with zero direct calls to any ml.* class.
 *
 * 12 rounds are uploaded (single year): {@link ModelTrainer#MIN_BOARDS_TO_TRAIN}
 * (20) is cleared by round 4 (5 boards/round), but the walk-forward burn-in
 * floor - 10 rounds, see {@link BacktestHarness#defaultBurnIn} - is the
 * real gate here, so training first produces walk-forward evidence once
 * round 11 exists. Using round count (not just board count) as the
 * trigger mirrors a real first-ever season, which is exactly the
 * single-year burn-in fix this test exists to guard.
 */
public class MlPipelineIntegrationTest {

    private static final int YEAR = 2026;
    private static final String NOW = "2026-01-01 00:00:00.000";
    private static final int TOTAL_ROUNDS = 12;

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

    /** 5 boards: Hall-1 player i vs Hall-2 player i, alternating who wins to avoid an all-draws/all-one-sided degenerate round. */
    private static String roundCsv(int roundOrder) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            boolean p1Wins = (roundOrder + i) % 2 == 0;
            double s1 = p1Wins ? 8 : 2;
            double s2 = p1Wins ? 2 : 8;
            sb.append(String.format("P%d,1,%.0f,Q%d,2,%.0f%n", i, s1, i, s2));
        }
        return sb.toString();
    }

    private static Path writeRoundCsv(Path dir, int roundOrder) throws Exception {
        Path csv = dir.resolve("round_" + roundOrder + ".csv");
        Files.writeString(csv, "name1,hall1,score1,name2,hall2,score2\n" + roundCsv(roundOrder));
        return csv;
    }

    @Test
    void enoughUploadsTrainModelsLogPredictionsAndRefreshTheRollingCache(@TempDir Path csvDir) throws Exception {
        E17_MlModels mlModels = new E17_MlModels();
        E14_AiPredictions aiPredictions = new E14_AiPredictions();
        E13_PlayerRollingCache rollingCache = new E13_PlayerRollingCache();
        A1_Rounds rounds = new A1_Rounds();
        C8_Matches matches = new C8_Matches();

        for (int order = 1; order <= TOTAL_ROUNDS; order++) {
            Path csv = writeRoundCsv(csvDir, order);
            assertTrue(newProcessor().processRound(csv.toString(), YEAR, order, NOW),
                    "Round " + order + " should process successfully");
        }

        List<E17_MlModels.MlModel> finalModels = mlModels.getRecent(50);
        assertFalse(finalModels.isEmpty(),
                "Uploading enough rounds must trigger automatic training with zero direct ML calls");
        assertEquals(1, finalModels.stream().filter(m -> m.isChampion).count(), "Exactly one champion must be crowned");

        // The champion existing after round 11's training must have been used
        // to log real pre-round predictions for round 12's boards.
        int lastRoundId = rounds.getRoundByYearAndOrder(YEAR, TOTAL_ROUNDS).id;
        List<C8_Matches.Match> lastRoundMatches = matches.getMatchesForRound(lastRoundId);
        assertEquals(5, lastRoundMatches.size());
        for (C8_Matches.Match m : lastRoundMatches) {
            E14_AiPredictions.Prediction prediction = aiPredictions.getPrediction(m.id);
            assertNotNull(prediction, "The last round's boards should have a logged pre-round prediction");
            assertNotNull(prediction.modelVersion);
            assertTrue(prediction.predictedWinProbability >= 0.0 && prediction.predictedWinProbability <= 1.0);
        }

        E13_PlayerRollingCache.RollingCache p1Cache = rollingCache.getCache(resolvePlayerId("P1"));
        assertNotNull(p1Cache, "player_rolling_cache should have a row for an active player after upload-triggered refresh");
    }

    @Test
    void tooFewRoundsNeverPopulatesMlModels(@TempDir Path csvDir) throws Exception {
        Path r1 = writeRoundCsv(csvDir, 1);
        assertTrue(newProcessor().processRound(r1.toString(), YEAR, 1, NOW), "Round 1 should process successfully");

        assertTrue(new E17_MlModels().getRecent(50).isEmpty(),
                "A single round is far below the walk-forward burn-in floor - ml_models must stay empty, not crash or half-populate");
        // The pipeline itself must still have fully succeeded despite no champion existing yet.
        assertNotNull(new A1_Rounds().getRoundByYearAndOrder(YEAR, 1));
    }

    private static String resolvePlayerId(String name) throws Exception {
        List<B5_PlayerNames.NameRecord> candidates = new B5_PlayerNames().findCandidatesByExactName(name);
        assertFalse(candidates.isEmpty(), "Expected player '" + name + "' to have been created by the pipeline");
        return candidates.get(0).playerId;
    }
}
