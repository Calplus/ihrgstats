package com.calplus.ihrgstats.ml;

import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.telegrambot.utils.CappedListProcessor;
import com.calplus.ihrgstats.telegrambot.utils.RoundCsvProcessor;
import com.calplus.ihrgstats.utils.DatabaseHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-3 lane a4: ML invariants on the committed fictional sample corpus
 * ({@code SAMPLE FILES/}, read only - nothing is written there; the database
 * lives in a JUnit temp directory). The corpus is ingested through the real
 * upload pipeline (capped list, then every round; each round retrains), with
 * the identity dialogs answered generically ("different people" / "different
 * player", never merging) - the identity graph is therefore not the scripted
 * one CorpusIngestionTest asserts, which these invariants do not depend on.
 *
 * Invariants checked on every persisted model and on a freshly fitted Glicko
 * baseline, over every extracted board and its mirror:
 * probabilities finite and in [0, 1], summing to 1; exact mirror symmetry
 * (pWin(A,B) = pLoss(B,A), pDraw equal) so that expected scores satisfy
 * E(A beats B) + E(B beats A) = 1; the same champion from two further
 * trainings on the same data (and the same as the ingest's own last
 * training); every stored rating, deviation and volatility finite.
 */
public class MlCorpusInvariantsProbeTest {

    private static final String NOW = "2026-01-01 00:00:00.000";
    private static final double MAX_SCORE = 370.0;
    private static final int[][] SEASONS = {{2001, 10}, {2002, 10}, {2003, 9}, {2004, 10}};
    private static final double EPS = 1e-9;

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
        new A2_MatchTypes().createMatchType("Corpus", MAX_SCORE, null, "Corpus probe match type", NOW);
        insertHall("HA", "HallA");
        insertHall("HB", "HallB");
        insertHall("HC", "HallC");
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
        System.clearProperty("SETTINGS_CURRENTYEAR");
    }

    private static void insertHall(String code, String name) throws SQLException {
        String sql = "INSERT INTO halls (hall_code, hall_name, next_player_seq, created_dttm, updated_dttm) VALUES (?, ?, 1, ?, ?)";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            ps.setString(2, name);
            ps.setString(3, NOW);
            ps.setString(4, NOW);
            ps.executeUpdate();
        }
    }

    @Test
    void corpus_mlInvariantsHold() throws Exception {
        // ---- Ingest the whole fictional corpus through the real pipeline ----
        List<String> unexpected = new ArrayList<>();
        int[] dialogCounts = new int[3];
        RoundCsvProcessor processor = new RoundCsvProcessor();
        processor.setMultiChoiceCallback((message, options) -> {
            if (message.startsWith("⚠️ This round contains a WALKOVER")) {
                dialogCounts[0]++;
                return 0;
            }
            if (message.contains("Name Mismatch Detected")) {
                dialogCounts[1]++;
                return 1; // treat as different people - never merges
            }
            if (message.contains("Hall Mismatch Resolution")) {
                dialogCounts[2]++;
                return 2; // new hall, different player - never merges
            }
            unexpected.add(message);
            return -1;
        });
        CappedListProcessor capped = new CappedListProcessor();
        int roundsIngested = 0;
        for (int[] season : SEASONS) {
            int year = season[0];
            System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(year));
            assertTrue(capped.processCappedList(sampleDir.resolve(year + "_cappedlist.csv").toString(), year, NOW));
            for (int round = 1; round <= season[1]; round++) {
                Path csv = sampleDir.resolve(year + "_round_" + round + ".csv");
                assertTrue(processor.processRound(csv.toString(), year, round, NOW),
                        year + " round " + round + " must ingest (unexpected dialogs: " + unexpected + ")");
                roundsIngested++;
            }
        }
        assertEquals(39, roundsIngested);
        assertTrue(unexpected.isEmpty(), "unexpected dialogs: " + unexpected);
        System.out.printf("[a4-ML] ingested %d rounds; dialogs walkover=%d name=%d hall=%d%n",
                roundsIngested, dialogCounts[0], dialogCounts[1], dialogCounts[2]);

        E17_MlModels mlModels = new E17_MlModels();
        E17_MlModels.MlModel ingestChampion = mlModels.getChampion();
        assertNotNull(ingestChampion, "the full corpus must clear the ML burn-in");

        // ---- Finite ratings everywhere ----
        int[] ratingRows = new int[2];
        try (Connection conn = DatabaseHelper.getDefaultConnection(); Statement st = conn.createStatement()) {
            String[] tables = {"player_ratings", "player_ratings_snapshot"};
            for (int t = 0; t < tables.length; t++) {
                try (ResultSet rs = st.executeQuery("SELECT player_id, round_id, rating_type_id, rating_value, rating_deviation, volatility FROM " + tables[t])) {
                    while (rs.next()) {
                        ratingRows[t]++;
                        for (int c = 4; c <= 6; c++) {
                            Object v = rs.getObject(c);
                            assertTrue(v instanceof Number && Double.isFinite(((Number) v).doubleValue()),
                                    tables[t] + " non-finite column " + c + " for " + rs.getString(1) + "/" + rs.getInt(2) + "/" + rs.getInt(3) + ": " + v);
                        }
                        assertTrue(rs.getDouble(5) > 0, tables[t] + " RD must be positive: " + rs.getDouble(5));
                    }
                }
            }
        }
        assertTrue(ratingRows[0] > 0 && ratingRows[1] > 0);

        // ---- Extraction determinism ----
        List<FeatureExtractor.RawBoard> boards1 = new FeatureExtractor().extractAll();
        List<FeatureExtractor.RawBoard> boards2 = new FeatureExtractor().extractAll();
        assertFalse(boards1.isEmpty());
        assertEquals(canonical(boards1), canonical(boards2), "two extractions of the same database must be identical");

        // ---- Probability invariants: every persisted model + fresh baseline ----
        List<MatchupPredictor> predictors = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        Set<String> families = new LinkedHashSet<>();
        for (E17_MlModels.MlModel m : mlModels.getRecent(100_000)) {
            predictors.add(ModelCodec.decode(m.family, m.paramsJson));
            labels.add(m.modelVersion);
            families.add(m.family);
        }
        predictors.add(GlickoBaseline.fit(boards1));
        labels.add("fresh-glicko-baseline");
        long checks = 0;
        double worstSum = 0, worstMirror = 0;
        for (int i = 0; i < predictors.size(); i++) {
            MatchupPredictor model = predictors.get(i);
            for (FeatureExtractor.RawBoard rb : boards1) {
                MatchupPredictor.Probs ab = model.predict(rb);
                MatchupPredictor.Probs ba = model.predict(FeatureExtractor.swapped(rb));
                assertValid(ab, labels.get(i), rb);
                assertValid(ba, labels.get(i), rb);
                worstSum = Math.max(worstSum, Math.abs(ab.pWin + ab.pDraw + ab.pLoss - 1.0));
                double mirror = Math.max(Math.abs(ab.pWin - ba.pLoss),
                        Math.max(Math.abs(ab.pLoss - ba.pWin), Math.abs(ab.pDraw - ba.pDraw)));
                worstMirror = Math.max(worstMirror, mirror);
                assertEquals(1.0, ab.expectedScore() + ba.expectedScore(), EPS,
                        labels.get(i) + ": E(A beats B) + E(B beats A) must be 1 on match " + rb.matchId);
                assertTrue(mirror <= EPS, labels.get(i) + ": mirror asymmetry " + mirror + " on match " + rb.matchId);
                checks++;
            }
        }
        System.out.printf("[a4-ML] predictors=%d families=%s boards=%d board-checks=%d worstSumError=%.3e worstMirrorError=%.3e%n",
                predictors.size(), families, boards1.size(), checks, worstSum, worstMirror);

        // ---- Champion determinism: two more trainings on the same data ----
        ModelTrainer.TrainOutcome o1 = new ModelTrainer().retrainAndSelect(boards1, NOW);
        ModelTrainer.TrainOutcome o2 = new ModelTrainer().retrainAndSelect(new FeatureExtractor().extractAll(), NOW);
        assertTrue(o1.trained && o2.trained);
        assertEquals(o1.championVersion, o2.championVersion, "same data -> same champion version");
        assertEquals(o1.championFamily, o2.championFamily);
        assertEquals(o1.runsPersisted, o2.runsPersisted);
        assertEquals(ingestChampion.modelVersion, o1.championVersion,
                "retraining on unchanged data must reproduce the ingest's own last champion");
        E17_MlModels.MlModel c1 = mlModels.getByVersion(o1.championVersion);
        assertEquals(ingestChampion.paramsJson, c1.paramsJson, "champion parameters must be byte-identical");
        System.out.printf("[a4-ML] champion=%s family=%s runs=%d ratingRows=%d snapshotRows=%d%n",
                o1.championVersion, o1.championFamily, o1.runsPersisted, ratingRows[0], ratingRows[1]);

        // ---- Hypothetical boards (the /predict and /lineup path) ----
        PredictionService service = new PredictionService();
        MatchupPredictor champion = service.loadChampion();
        PredictionService.LatestState state = service.latestState(2004);
        List<String> ids = new ArrayList<>(state.sides.keySet());
        ids.sort(null);
        ids.add("never-played-probe-id");
        int hypo = 0;
        for (int a = 0; a < ids.size(); a += 3) {
            for (int b = 1; b < ids.size(); b += 5) {
                if (ids.get(a).equals(ids.get(b))) continue;
                FeatureExtractor.RawBoard rb = service.buildHypotheticalBoard(ids.get(a), ids.get(b), 2004);
                MatchupPredictor.Probs ab = champion.predict(rb);
                MatchupPredictor.Probs ba = champion.predict(FeatureExtractor.swapped(rb));
                assertValid(ab, "champion-hypothetical", rb);
                assertEquals(1.0, ab.expectedScore() + ba.expectedScore(), EPS);
                hypo++;
            }
        }
        assertTrue(hypo > 0);
        System.out.printf("[a4-ML] hypothetical pairs checked=%d (incl. a never-played id)%n", hypo);
    }

    private static void assertValid(MatchupPredictor.Probs p, String label, FeatureExtractor.RawBoard rb) {
        for (double v : new double[]{p.pWin, p.pDraw, p.pLoss}) {
            assertTrue(Double.isFinite(v) && v >= 0.0 && v <= 1.0,
                    label + ": probability out of [0,1] or non-finite on match " + rb.matchId + ": " + v);
        }
        assertEquals(1.0, p.pWin + p.pDraw + p.pLoss, EPS, label + ": probabilities must sum to 1");
    }

    /** Field-by-field rendering of every board (all public final fields of RawBoard and both Sides). */
    private static String canonical(List<FeatureExtractor.RawBoard> boards) throws IllegalAccessException {
        StringBuilder sb = new StringBuilder();
        for (FeatureExtractor.RawBoard rb : boards) {
            appendFields(sb, rb);
            appendFields(sb, rb.a);
            appendFields(sb, rb.b);
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void appendFields(StringBuilder sb, Object o) throws IllegalAccessException {
        for (Field f : o.getClass().getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            Object v = f.get(o);
            if (v instanceof FeatureExtractor.Side) continue;
            sb.append(f.getName()).append('=').append(v).append(';');
        }
    }
}
