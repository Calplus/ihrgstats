package com.calplus.ihrgstats.ml;

import com.calplus.ihrgstats.databasemanager.A1_Rounds;
import com.calplus.ihrgstats.databasemanager.D10_RatingTypes;
import com.calplus.ihrgstats.databasemanager.D11_PlayerRatings;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Distills the currently-crowned champion model's win probabilities into a
 * single scalar rating - "ExpElo", the visible product of the whole AI/ML
 * layer, displayed alongside TrueElo rather than replacing it.
 *
 * Algorithm (a hand-rolled Bradley-Terry least-squares projection, run as a
 * full whole-history pass every time - same "full refit from scratch"
 * philosophy as {@link com.calplus.ihrgstats.calculations.RatingRecalculator},
 * so ExpElo always reflects the CURRENT champion rather than whichever
 * model was champion when a given round first happened):
 *
 * Rounds are walked chronologically, maintaining a running ExpElo value per
 * player (starting at {@link FeatureExtractor#DEFAULT_RATING}, same as
 * TrueElo's debut default). For every board actually played in a round, the
 * champion's predicted expected score for side A ({@code pWin + 0.5*pDraw})
 * is inverted through the same Glicko-2 rating-diff scale every other
 * predictor in this package already uses ({@code GlickoBaseline}'s
 * {@code 173.7178} constant - deliberately NOT re-applying its RD-based
 * {@code combinedG} widening here, since the champion model's own
 * covariates - including RD and the seat prior - already inform its
 * probability, and re-widening would double-count that uncertainty) into a
 * target rating difference. The MINIMAL perturbation of the two players'
 * current ExpElo that reproduces that exact target difference is the
 * textbook least-squares solution to an under-determined system: split the
 * gap evenly, {@code +gap/2} to A and {@code -gap/2} to B. A player who
 * didn't play that round simply carries their ExpElo forward unchanged -
 * "no game, no update," the same convention Glicko-2 itself uses for
 * anything besides deviation growth.
 *
 * A row is written for EVERY player in that round's TrueElo rated set - not
 * just the ones who played - so ExpElo rows always exist for exactly the
 * TrueElo rated set (spectators included, matching
 * {@link com.calplus.ihrgstats.calculations.RatingRecalculator}'s own rated-
 * set semantics exactly, since that's where the set comes from). ExpElo's
 * own rating deviation / volatility columns are not separately modelled -
 * they simply inherit that round's TrueElo values, a documented
 * simplification (ExpElo distills a point estimate, not a full Glicko-2
 * process of its own).
 *
 * Requires {@code player_ratings} to already hold the current TrueElo rated
 * set for every round (i.e. call AFTER
 * {@link com.calplus.ihrgstats.calculations.RatingRecalculator#recalculateAll}),
 * and a trained champion model (a no-op, not an error, when there is none
 * yet - matching every other "not enough data" outcome in this package).
 */
public class ExpEloDistiller {

    /** Same Glicko-2 internal-to-classic scale constant {@link GlickoBaseline} uses. */
    private static final double GLICKO2_SCALE = 173.7178;
    /** Clamp bound so an extreme (near-0/1) model probability never produces an infinite logit. */
    private static final double SCORE_CLAMP_EPS = 1e-6;

    private final A1_Rounds rounds = new A1_Rounds();
    private final D10_RatingTypes ratingTypes = new D10_RatingTypes();
    private final D11_PlayerRatings playerRatings = new D11_PlayerRatings();

    /** Summary of one distillation run. */
    public static class DistillResult {
        public final int roundsProcessed;
        public final int rowsWritten;

        DistillResult(int roundsProcessed, int rowsWritten) {
            this.roundsProcessed = roundsProcessed;
            this.rowsWritten = rowsWritten;
        }
    }

    /**
     * Runs the full whole-history distillation and rewrites every ExpElo
     * row. Safe to call with no champion yet or an empty database (returns
     * a zero result in either case).
     */
    public DistillResult distillAndWrite(MatchupPredictor champion, String nowTimestamp) throws SQLException {
        if (champion == null) {
            return new DistillResult(0, 0);
        }

        Integer trueEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.TRUE_ELO);
        Integer expEloTypeId = ratingTypes.getRatingTypeId(D10_RatingTypes.EXP_ELO);
        if (trueEloTypeId == null || expEloTypeId == null) {
            throw new SQLException("Rating types must be seeded before distillation.");
        }

        List<FeatureExtractor.RawBoard> allBoards = new FeatureExtractor().extractAll();
        Map<Integer, List<FeatureExtractor.RawBoard>> boardsByRound = new LinkedHashMap<>();
        for (FeatureExtractor.RawBoard rb : allBoards) {
            boardsByRound.computeIfAbsent(rb.roundId, k -> new ArrayList<>()).add(rb);
        }

        List<A1_Rounds.Round> allRounds = new ArrayList<>();
        for (int year : rounds.getAllYears()) {
            allRounds.addAll(rounds.getRoundsForYear(year));
        }

        Map<String, Double> currentExpElo = new HashMap<>();
        int roundsProcessed = 0;
        int rowsWritten = 0;

        for (A1_Rounds.Round round : allRounds) {
            List<D11_PlayerRatings.Rating> trueEloRows = playerRatings.getRatingsForRound(round.id, trueEloTypeId);
            if (trueEloRows.isEmpty()) {
                continue;
            }
            roundsProcessed++;

            for (FeatureExtractor.RawBoard rb : boardsByRound.getOrDefault(round.id, List.of())) {
                applyProjection(champion, rb, currentExpElo);
            }

            playerRatings.deleteRatingsForRoundAndType(round.id, expEloTypeId);
            for (D11_PlayerRatings.Rating trueRow : trueEloRows) {
                double value = currentExpElo.computeIfAbsent(trueRow.playerId, k -> FeatureExtractor.DEFAULT_RATING);
                playerRatings.insertRating(trueRow.playerId, round.id, expEloTypeId,
                        value, trueRow.ratingDeviation, trueRow.volatility, nowTimestamp);
                rowsWritten++;
            }
        }

        return new DistillResult(roundsProcessed, rowsWritten);
    }

    /**
     * The least-squares projection step for one played board: shifts both
     * players' running ExpElo by the minimal (equal-and-opposite) amount
     * that makes their new diff reproduce the champion's predicted expected
     * score exactly, via the standard Glicko-2 rating-diff-to-probability
     * formula.
     */
    private static void applyProjection(MatchupPredictor champion, FeatureExtractor.RawBoard rb,
                                        Map<String, Double> currentExpElo) {
        MatchupPredictor.Probs p = champion.predict(rb);
        double eModel = clamp(p.expectedScore());
        double targetDiff = GLICKO2_SCALE * Math.log(eModel / (1.0 - eModel));

        double enterA = currentExpElo.getOrDefault(rb.a.playerId, FeatureExtractor.DEFAULT_RATING);
        double enterB = currentExpElo.getOrDefault(rb.b.playerId, FeatureExtractor.DEFAULT_RATING);
        double shift = (targetDiff - (enterA - enterB)) / 2.0;

        currentExpElo.put(rb.a.playerId, enterA + shift);
        currentExpElo.put(rb.b.playerId, enterB - shift);
    }

    private static double clamp(double expectedScore) {
        return Math.max(SCORE_CLAMP_EPS, Math.min(1.0 - SCORE_CLAMP_EPS, expectedScore));
    }
}
