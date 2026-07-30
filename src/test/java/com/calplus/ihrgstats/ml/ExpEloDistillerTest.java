package com.calplus.ihrgstats.ml;

import com.calplus.ihrgstats.calculations.RatingRecalculator;
import com.calplus.ihrgstats.databasemanager.A1_Rounds;
import com.calplus.ihrgstats.databasemanager.B6_PlayerYearStatus;
import com.calplus.ihrgstats.databasemanager.D10_RatingTypes;
import com.calplus.ihrgstats.databasemanager.D11_PlayerRatings;
import com.calplus.ihrgstats.databasemanager.DatabaseSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ExpEloDistiller} tests: the least-squares projection exactly
 * reproduces the champion's predicted expected score, ExpElo rows appear
 * for exactly the TrueElo rated set (spectators included), a non-playing
 * player's ExpElo carries forward unchanged, distillation is a deterministic
 * no-op without a champion, and rerunning it is idempotent.
 */
public class ExpEloDistillerTest {

    private String originalUserDir;
    private final MlTestFixtures fx = new MlTestFixtures();
    private final D10_RatingTypes ratingTypes = new D10_RatingTypes();
    private final D11_PlayerRatings playerRatings = new D11_PlayerRatings();
    private final A1_Rounds rounds = new A1_Rounds();

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        new DatabaseSchema().createDatabase("default.db");
        fx.seedCore();
        ratingTypes.seedDefaults(MlTestFixtures.NOW);
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    /** Deterministic (no draws) stub: exact P(a beats b) from a hand-specified table, defaulting to a coin flip for unlisted pairs. */
    private static class StubPredictor implements MatchupPredictor {
        private final Map<String, Double> table = new HashMap<>();

        void set(String a, String b, double pAWins) {
            table.put(a + "|" + b, pAWins);
            table.put(b + "|" + a, 1.0 - pAWins);
        }

        @Override
        public Probs predict(FeatureExtractor.RawBoard board) {
            Double p = table.get(board.a.playerId + "|" + board.b.playerId);
            if (p == null) {
                return new Probs(0.5, 0.0, 0.5);
            }
            return new Probs(p, 0.0, 1.0 - p);
        }

        @Override
        public String family() {
            return "STUB";
        }
    }

    private static double expectedScoreFromElo(double eloA, double eloB) {
        return 1.0 / (1.0 + Math.exp(-(eloA - eloB) / 173.7178));
    }

    @Test
    void distillationReproducesTheChampionsExpectedScoreExactly() throws Exception {
        fx.createPlayers("AA-01", "BB-01");
        int roundId = fx.createRound(2025, 1);
        fx.addBoard(roundId, "AA-01", fx.hallA, 1, "BB-01", fx.hallB, 1, 1.0);

        new RatingRecalculator().recalculateAll(MlTestFixtures.NOW);

        StubPredictor stub = new StubPredictor();
        stub.set("AA-01", "BB-01", 0.73); // FeatureExtractor orders A = lexicographically smaller id, so AA-01 is "a"
        new ExpEloDistiller().distillAndWrite(stub, MlTestFixtures.NOW);

        Integer expEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.EXP_ELO);
        D11_PlayerRatings.Rating a = playerRatings.getRating("AA-01", roundId, expEloTypeId);
        D11_PlayerRatings.Rating b = playerRatings.getRating("BB-01", roundId, expEloTypeId);
        assertNotNull(a);
        assertNotNull(b);

        double reproduced = expectedScoreFromElo(a.ratingValue, b.ratingValue);
        assertEquals(0.73, reproduced, 1e-9,
                "the resulting ExpElo pair must reproduce the champion's predicted expected score exactly through the standard rating-diff formula");
    }

    @Test
    void expEloRowsAppearForExactlyTheTrueEloRatedSet() throws Exception {
        fx.createPlayers("AA-01", "AA-02", "BB-01", "BB-02");
        int round1 = fx.createRound(2025, 1);
        fx.addBoard(round1, "AA-01", fx.hallA, 1, "BB-01", fx.hallB, 1, 1.0);
        fx.addBoard(round1, "AA-02", fx.hallA, 2, "BB-02", fx.hallB, 2, 0.0);

        // Round 2: AA-02 sits out (spectator) while their hall still plays -
        // requires an active player_year_status row to be picked up by
        // RatingRecalculator's rated-set expansion.
        new B6_PlayerYearStatus().upsertStatus("AA-01", 2025, fx.hallA, false, true, MlTestFixtures.NOW);
        new B6_PlayerYearStatus().upsertStatus("AA-02", 2025, fx.hallA, false, true, MlTestFixtures.NOW);
        new B6_PlayerYearStatus().upsertStatus("BB-01", 2025, fx.hallB, false, true, MlTestFixtures.NOW);
        new B6_PlayerYearStatus().upsertStatus("BB-02", 2025, fx.hallB, false, true, MlTestFixtures.NOW);
        int round2 = fx.createRound(2025, 2);
        fx.addBoard(round2, "AA-01", fx.hallA, 1, "BB-01", fx.hallB, 1, 1.0);

        new RatingRecalculator().recalculateAll(MlTestFixtures.NOW);

        StubPredictor stub = new StubPredictor();
        new ExpEloDistiller().distillAndWrite(stub, MlTestFixtures.NOW);

        Integer trueEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.TRUE_ELO);
        Integer expEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.EXP_ELO);
        A1_Rounds.Round r2 = rounds.getRoundByYearAndOrder(2025, 2);

        Set<String> trueEloRatedSet = playerRatings.getRatingsForRound(r2.id, trueEloTypeId).stream()
                .map(r -> r.playerId).collect(Collectors.toSet());
        Set<String> expEloRatedSet = playerRatings.getRatingsForRound(r2.id, expEloTypeId).stream()
                .map(r -> r.playerId).collect(Collectors.toSet());

        assertTrue(trueEloRatedSet.contains("AA-02"), "fixture sanity: AA-02 must be a spectator in TrueElo's round-2 rated set");
        assertEquals(trueEloRatedSet, expEloRatedSet, "ExpElo rows must appear for exactly the TrueElo rated set, spectators included");
    }

    @Test
    void nonPlayingPlayerCarriesExpEloForwardUnchanged() throws Exception {
        fx.createPlayers("AA-01", "AA-02", "BB-01", "BB-02");
        int round1 = fx.createRound(2025, 1);
        fx.addBoard(round1, "AA-01", fx.hallA, 1, "BB-01", fx.hallB, 1, 1.0);
        fx.addBoard(round1, "AA-02", fx.hallA, 2, "BB-02", fx.hallB, 2, 0.5);

        new B6_PlayerYearStatus().upsertStatus("AA-01", 2025, fx.hallA, false, true, MlTestFixtures.NOW);
        new B6_PlayerYearStatus().upsertStatus("AA-02", 2025, fx.hallA, false, true, MlTestFixtures.NOW);
        new B6_PlayerYearStatus().upsertStatus("BB-01", 2025, fx.hallB, false, true, MlTestFixtures.NOW);
        new B6_PlayerYearStatus().upsertStatus("BB-02", 2025, fx.hallB, false, true, MlTestFixtures.NOW);
        int round2 = fx.createRound(2025, 2);
        fx.addBoard(round2, "AA-01", fx.hallA, 1, "BB-01", fx.hallB, 1, 1.0);

        new RatingRecalculator().recalculateAll(MlTestFixtures.NOW);

        StubPredictor stub = new StubPredictor();
        stub.set("AA-01", "BB-01", 0.9);
        new ExpEloDistiller().distillAndWrite(stub, MlTestFixtures.NOW);

        Integer expEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.EXP_ELO);
        A1_Rounds.Round r1 = rounds.getRoundByYearAndOrder(2025, 1);
        A1_Rounds.Round r2 = rounds.getRoundByYearAndOrder(2025, 2);

        double spectatorRound1 = playerRatings.getRating("AA-02", r1.id, expEloTypeId).ratingValue;
        double spectatorRound2 = playerRatings.getRating("AA-02", r2.id, expEloTypeId).ratingValue;
        assertEquals(spectatorRound1, spectatorRound2, 0.0, "a player who didn't play round 2 must carry their ExpElo forward unchanged");
    }

    @Test
    void noChampionYieldsNoOpAndWritesNoRows() throws Exception {
        fx.createPlayers("AA-01", "BB-01");
        int roundId = fx.createRound(2025, 1);
        fx.addBoard(roundId, "AA-01", fx.hallA, 1, "BB-01", fx.hallB, 1, 1.0);
        new RatingRecalculator().recalculateAll(MlTestFixtures.NOW);

        ExpEloDistiller.DistillResult result = new ExpEloDistiller().distillAndWrite(null, MlTestFixtures.NOW);
        assertEquals(0, result.roundsProcessed);
        assertEquals(0, result.rowsWritten);

        Integer expEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.EXP_ELO);
        assertTrue(playerRatings.getRatingsForRound(roundId, expEloTypeId).isEmpty());
    }

    @Test
    void rerunningDistillationIsIdempotentGivenUnchangedInputs() throws Exception {
        fx.createPlayers("AA-01", "AA-02", "BB-01", "BB-02");
        int round1 = fx.createRound(2025, 1);
        fx.addBoard(round1, "AA-01", fx.hallA, 1, "BB-01", fx.hallB, 1, 1.0);
        int round2 = fx.createRound(2025, 2);
        fx.addBoard(round2, "AA-02", fx.hallA, 2, "BB-02", fx.hallB, 2, 0.0);
        new RatingRecalculator().recalculateAll(MlTestFixtures.NOW);

        StubPredictor stub = new StubPredictor();
        stub.set("AA-01", "BB-01", 0.61);
        stub.set("AA-02", "BB-02", 0.4);

        ExpEloDistiller.DistillResult first = new ExpEloDistiller().distillAndWrite(stub, MlTestFixtures.NOW);
        Integer expEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.EXP_ELO);
        double firstValue = playerRatings.getRating("AA-01", round1, expEloTypeId).ratingValue;

        ExpEloDistiller.DistillResult second = new ExpEloDistiller().distillAndWrite(stub, MlTestFixtures.NOW);
        double secondValue = playerRatings.getRating("AA-01", round1, expEloTypeId).ratingValue;

        assertEquals(first.roundsProcessed, second.roundsProcessed);
        assertEquals(first.rowsWritten, second.rowsWritten);
        assertEquals(firstValue, secondValue, 0.0);
    }
}
