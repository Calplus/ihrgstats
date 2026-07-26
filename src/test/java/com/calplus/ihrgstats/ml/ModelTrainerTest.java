package com.calplus.ihrgstats.ml;

import com.calplus.ihrgstats.databasemanager.DatabaseSchema;
import com.calplus.ihrgstats.databasemanager.E12_PlayerProfiles;
import com.calplus.ihrgstats.databasemanager.E17_MlModels;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end trainer tests on a real (temp) database: the full
 * extract -> walk-forward -> persist -> crown cycle, its determinism,
 * and the not-enough-data skip path.
 */
public class ModelTrainerTest {

    private String originalUserDir;
    private final MlTestFixtures fx = new MlTestFixtures();
    private final E17_MlModels mlModels = new E17_MlModels();

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

    /** 8 rounds x 3 boards = 24 rated boards with deterministic mixed outcomes. */
    private void buildTrainableHistory() throws Exception {
        String[] hallAPlayers = {"AA-01", "AA-02", "AA-03"};
        String[] hallBPlayers = {"BB-01", "BB-02", "BB-03"};
        for (int order = 1; order <= 8; order++) {
            int roundId = fx.createRound(2025, order);
            for (int b = 0; b < 3; b++) {
                // Deterministic outcome pattern with wins both ways and draws.
                int k = order * 3 + b;
                double outcome = k % 5 == 0 ? 0.5 : (k % 2 == 0 ? 1.0 : 0.0);
                fx.addBoard(roundId, hallAPlayers[b], fx.hallA, b + 1,
                        hallBPlayers[(b + order) % 3], fx.hallB, b + 1, outcome);
            }
        }
    }

    @Test
    void fullCyclePersistsRunsAndCrownsExactlyOneChampion() throws Exception {
        buildTrainableHistory();
        ModelTrainer trainer = new ModelTrainer(2, 1); // small fixture: burn-in 2, eligibility floor 1

        ModelTrainer.TrainOutcome outcome = trainer.retrainAndSelect(MlTestFixtures.NOW);
        assertTrue(outcome.trained);
        assertEquals(16, outcome.runsPersisted); // baseline + 3x3 logistic grid + 2x2 gbm grid + 2 gbm_emb grid
        assertNotNull(outcome.championVersion);
        assertFalse(outcome.note.isEmpty());

        List<E17_MlModels.MlModel> rows = mlModels.getRecent(50);
        assertEquals(16, rows.size());
        assertEquals(1, rows.stream().filter(m -> m.isChampion).count());

        // Champion must be loadable and serve valid, symmetric probabilities.
        E17_MlModels.MlModel champion = mlModels.getChampion();
        assertEquals(outcome.championVersion, champion.modelVersion);
        MatchupPredictor predictor = ModelCodec.decode(champion.family, champion.paramsJson);
        FeatureExtractor.RawBoard probe = new FeatureExtractor().extractAll().get(0);
        MatchupPredictor.Probs p = predictor.predict(probe);
        assertEquals(1.0, p.pWin + p.pDraw + p.pLoss, 1e-9);
        assertTrue(p.pWin >= 0 && p.pDraw >= 0 && p.pLoss >= 0);

        // Every run's metrics carry the baseline comparison.
        for (E17_MlModels.MlModel row : rows) {
            assertTrue(row.metricsJson.contains("brierDeltaVsBaseline"));
            assertEquals(24, row.trainedBoards);
        }

        // The GBM_EMB grid is always fit as part of the full-history persist loop, so the
        // reserved player_profiles.playstyle_vector slot must be populated too - the first
        // thing that has ever written to that table.
        E12_PlayerProfiles.Profile profile = new E12_PlayerProfiles().getProfile("AA-01");
        assertNotNull(profile, "training must export embeddings into player_profiles");
        assertTrue(profile.playstyleVector.startsWith("["), "playstyle_vector should be a JSON array: " + profile.playstyleVector);
        assertEquals(2025, profile.lastCalculatedYear);
    }

    @Test
    void retrainingIsDeterministic() throws Exception {
        buildTrainableHistory();
        ModelTrainer trainer = new ModelTrainer(2, 1);
        ModelTrainer.TrainOutcome first = trainer.retrainAndSelect(MlTestFixtures.NOW);
        ModelTrainer.TrainOutcome second = trainer.retrainAndSelect(MlTestFixtures.NOW);

        assertEquals(first.championVersion, second.championVersion);
        // Upsert semantics: rerun updates the same 16 rows, no duplicates.
        assertEquals(16, mlModels.getRecent(50).size());
        assertEquals(1, mlModels.getRecent(50).stream().filter(m -> m.isChampion).count());
    }

    /** >=20 boards crammed into fewer rounds than the burn-in: zero walk-forward evidence, must skip. */
    @Test
    void skipsWhenNoRoundsBeyondBurnIn() throws Exception {
        fx.createPlayers("AA-04", "AA-05", "AA-06", "BB-04", "BB-05", "BB-06");
        String[] hallAPlayers = {"AA-01", "AA-02", "AA-03", "AA-04", "AA-05", "AA-06"};
        String[] hallBPlayers = {"BB-01", "BB-02", "BB-03", "BB-04", "BB-05", "BB-06"};
        for (int order = 1; order <= 4; order++) { // 4 rounds < default 10-round burn-in
            int roundId = fx.createRound(2025, order);
            for (int b = 0; b < 6; b++) {          // x6 boards = 24 total (>= MIN_BOARDS_TO_TRAIN)
                fx.addBoard(roundId, hallAPlayers[b], fx.hallA, (b % 5) + 1,
                        hallBPlayers[(b + order) % 6], fx.hallB, (b % 5) + 1, (order + b) % 2 == 0 ? 1.0 : 0.0);
            }
        }

        ModelTrainer.TrainOutcome outcome = new ModelTrainer().retrainAndSelect(MlTestFixtures.NOW);
        assertFalse(outcome.trained);
        assertNull(outcome.championVersion);
        assertTrue(outcome.note.contains("burn-in"));
        assertTrue(mlModels.getRecent(10).isEmpty(), "no NaN-metrics rows may be persisted");
    }

    @Test
    void skipsTrainingWhenDataIsTooThin() throws Exception {
        int roundId = fx.createRound(2025, 1);
        fx.addBoard(roundId, "AA-01", fx.hallA, 1, "BB-01", fx.hallB, 1, 1.0);

        ModelTrainer.TrainOutcome outcome = new ModelTrainer().retrainAndSelect(MlTestFixtures.NOW);
        assertFalse(outcome.trained);
        assertNull(outcome.championVersion);
        assertTrue(outcome.note.contains("skipped"));
        assertTrue(mlModels.getRecent(10).isEmpty(), "no rows should be written on a skip");
    }
}
