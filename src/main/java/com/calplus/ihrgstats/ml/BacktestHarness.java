package com.calplus.ihrgstats.ml;

import java.util.*;

/**
 * Walk-forward backtest - the measurable definition of "more accurate
 * than Glicko" and the gate every model family must face.
 *
 * Protocol: boards are grouped by global round sequence. After a burn-in
 * prefix, each remaining round is predicted by a model trained ONLY on
 * the rounds strictly before it (features are already as-of by
 * construction - see {@link FeatureExtractor}). Refitting per round is
 * cheap at this data size and guarantees standardization stats and draw
 * base rates are as leak-free as the features themselves.
 *
 * Metrics per candidate: 3-class Brier score, log-loss, expected-score
 * MSE, argmax accuracy, a 10-bucket calibration table, and a per-round
 * paired comparison against the baseline (sign-test counts).
 *
 * Also runnable standalone against the real database (read-only):
 *   java -cp target/ihrgstats-2.0.0.jar com.calplus.ihrgstats.ml.BacktestHarness
 */
public class BacktestHarness {

    /** Refits a fresh predictor on a training prefix - one per candidate config. */
    public interface ModelFactory {
        String name();

        MatchupPredictor fit(List<FeatureExtractor.RawBoard> train);
    }

    /** Aggregated walk-forward result for one candidate. */
    public static class Result {
        public final String name;
        public final String family;
        public final int predictedBoards;
        public final int predictedRounds;
        public final double brier;         // mean 3-class Brier (lower = better)
        public final double logLoss;       // mean 3-class log-loss
        public final double expectedScoreMse;
        public final double accuracy;      // argmax class hit rate
        public final double[][] calibration; // 10 x {meanPredictedScore, meanActualScore, count}
        public final Map<Integer, Double> perRoundBrier; // roundSeq -> mean Brier that round
        /** vs baseline (filled by runAll): rounds where this candidate's Brier was strictly lower / higher. */
        public int roundsBetterThanBaseline;
        public int roundsWorseThanBaseline;

        Result(String name, String family, int predictedBoards, int predictedRounds,
               double brier, double logLoss, double expectedScoreMse, double accuracy,
               double[][] calibration, Map<Integer, Double> perRoundBrier) {
            this.name = name;
            this.family = family;
            this.predictedBoards = predictedBoards;
            this.predictedRounds = predictedRounds;
            this.brier = brier;
            this.logLoss = logLoss;
            this.expectedScoreMse = expectedScoreMse;
            this.accuracy = accuracy;
            this.calibration = calibration;
            this.perRoundBrier = perRoundBrier;
        }
    }

    /**
     * Burn-in: rounds never predicted, only trained on. When history spans
     * more than one year, this is max(10, rounds in the first stored year)
     * - the whole first season is reserved so nothing gets "predicted"
     * before any Elo history exists to predict from. With only a SINGLE
     * year on record (a club's first season, or simply a fresh database),
     * that same rule degenerates: "first year's round count" always
     * equals the running total, so burn-in would forever chase the total
     * upward and no round would EVER be predicted, no matter how much
     * data accumulated. In that case burn-in is just the fixed 10-round
     * floor instead, so a first-ever season can still train once it has
     * enough rounds.
     */
    public static int defaultBurnIn(List<FeatureExtractor.RawBoard> all) {
        if (all.isEmpty()) {
            return 10;
        }
        int firstYear = all.get(0).year;
        Set<Integer> years = new HashSet<>();
        Set<Integer> firstYearRounds = new HashSet<>();
        for (FeatureExtractor.RawBoard rb : all) {
            years.add(rb.year);
            if (rb.year == firstYear) {
                firstYearRounds.add(rb.roundSeq);
            }
        }
        if (years.size() <= 1) {
            return 10;
        }
        return Math.max(10, firstYearRounds.size());
    }

    /**
     * Runs every candidate through the identical walk-forward protocol.
     * The FIRST factory is treated as the baseline: every other result
     * gets its per-round sign-test counts filled in against it.
     */
    public static List<Result> runAll(List<FeatureExtractor.RawBoard> all,
                                      List<ModelFactory> factories, int burnInRounds) {
        List<Result> results = new ArrayList<>();
        for (ModelFactory factory : factories) {
            results.add(run(all, factory, burnInRounds));
        }
        if (results.size() > 1) {
            Result baseline = results.get(0);
            for (int i = 1; i < results.size(); i++) {
                Result r = results.get(i);
                for (Map.Entry<Integer, Double> e : r.perRoundBrier.entrySet()) {
                    Double base = baseline.perRoundBrier.get(e.getKey());
                    if (base == null) {
                        continue;
                    }
                    if (e.getValue() < base) {
                        r.roundsBetterThanBaseline++;
                    } else if (e.getValue() > base) {
                        r.roundsWorseThanBaseline++;
                    }
                }
            }
        }
        return results;
    }

    /** Walk-forward evaluation of one candidate. */
    public static Result run(List<FeatureExtractor.RawBoard> all, ModelFactory factory, int burnInRounds) {
        // Group by round sequence, preserving chronological order.
        TreeMap<Integer, List<FeatureExtractor.RawBoard>> bySeq = new TreeMap<>();
        for (FeatureExtractor.RawBoard rb : all) {
            bySeq.computeIfAbsent(rb.roundSeq, k -> new ArrayList<>()).add(rb);
        }
        List<Integer> seqs = new ArrayList<>(bySeq.keySet());

        double sumBrier = 0.0;
        double sumLogLoss = 0.0;
        double sumEsMse = 0.0;
        int hits = 0;
        int nPredicted = 0;
        int nRounds = 0;
        double[][] calib = new double[10][3];
        Map<Integer, Double> perRoundBrier = new LinkedHashMap<>();

        List<FeatureExtractor.RawBoard> train = new ArrayList<>();
        for (int idx = 0; idx < seqs.size(); idx++) {
            int seq = seqs.get(idx);
            List<FeatureExtractor.RawBoard> roundBoards = bySeq.get(seq);
            if (idx >= burnInRounds && !train.isEmpty()) {
                MatchupPredictor model = factory.fit(train);
                double roundBrier = 0.0;
                for (FeatureExtractor.RawBoard rb : roundBoards) {
                    MatchupPredictor.Probs probs = model.predict(rb);
                    double yWin = rb.outcomeA == 1.0 ? 1.0 : 0.0;
                    double yDraw = rb.isDraw() ? 1.0 : 0.0;
                    double yLoss = rb.outcomeA == 0.0 ? 1.0 : 0.0;

                    double brier = sq(probs.pWin - yWin) + sq(probs.pDraw - yDraw) + sq(probs.pLoss - yLoss);
                    double pActual = yWin > 0 ? probs.pWin : (yDraw > 0 ? probs.pDraw : probs.pLoss);
                    double logLoss = -Math.log(Math.max(pActual, 1e-12));
                    double es = probs.expectedScore();

                    sumBrier += brier;
                    roundBrier += brier;
                    sumLogLoss += logLoss;
                    sumEsMse += sq(es - rb.outcomeA);
                    double pMax = Math.max(probs.pWin, Math.max(probs.pDraw, probs.pLoss));
                    boolean hit = (pMax == probs.pWin && yWin > 0)
                            || (pMax == probs.pDraw && yDraw > 0)
                            || (pMax == probs.pLoss && yLoss > 0);
                    if (hit) {
                        hits++;
                    }
                    int bucket = Math.min(9, (int) Math.floor(es * 10.0));
                    calib[bucket][0] += es;
                    calib[bucket][1] += rb.outcomeA;
                    calib[bucket][2] += 1;
                    nPredicted++;
                }
                perRoundBrier.put(seq, roundBrier / roundBoards.size());
                nRounds++;
            }
            train.addAll(roundBoards);
        }

        for (double[] bucket : calib) {
            if (bucket[2] > 0) {
                bucket[0] /= bucket[2];
                bucket[1] /= bucket[2];
            }
        }
        String family = factory.fit(List.of()).family(); // empty fit is cheap and yields the family tag
        return new Result(factory.name(), family, nPredicted, nRounds,
                nPredicted > 0 ? sumBrier / nPredicted : Double.NaN,
                nPredicted > 0 ? sumLogLoss / nPredicted : Double.NaN,
                nPredicted > 0 ? sumEsMse / nPredicted : Double.NaN,
                nPredicted > 0 ? (double) hits / nPredicted : Double.NaN,
                calib, perRoundBrier);
    }

    private static double sq(double x) {
        return x * x;
    }

    // ========================================================================
    // Candidate roster (shared with ModelTrainer so backtest == what ships)
    // ========================================================================

    /** The Segment-A candidate roster: baseline first, then the logistic grid. */
    public static List<ModelFactory> segmentACandidates() {
        List<ModelFactory> factories = new ArrayList<>();
        factories.add(new ModelFactory() {
            @Override
            public String name() {
                return "glicko-baseline";
            }

            @Override
            public MatchupPredictor fit(List<FeatureExtractor.RawBoard> train) {
                return GlickoBaseline.fit(train);
            }
        });
        for (double n0 : new double[]{3, 6, 12}) {
            for (double lambda : new double[]{0.3, 1.0, 3.0}) {
                final double fn0 = n0;
                final double fLambda = lambda;
                factories.add(new ModelFactory() {
                    @Override
                    public String name() {
                        return String.format(Locale.ROOT, "logistic n0=%.0f lambda=%.1f", fn0, fLambda);
                    }

                    @Override
                    public MatchupPredictor fit(List<FeatureExtractor.RawBoard> train) {
                        return LogisticModel.fit(train, fn0, fLambda);
                    }
                });
            }
        }
        return factories;
    }

    /** GBM configs only (no baseline row - combine with {@link #segmentACandidates} via {@link #allCandidates}). */
    public static List<ModelFactory> gbmCandidates() {
        List<ModelFactory> factories = new ArrayList<>();
        for (double n0 : new double[]{6, 12}) {
            for (double lambda : new double[]{1.0, 3.0}) {
                final double fn0 = n0;
                final double fLambda = lambda;
                factories.add(new ModelFactory() {
                    @Override
                    public String name() {
                        return String.format(Locale.ROOT, "gbm n0=%.0f lambda=%.1f", fn0, fLambda);
                    }

                    @Override
                    public MatchupPredictor fit(List<FeatureExtractor.RawBoard> train) {
                        return GbmModel.fit(train, fn0, fLambda);
                    }
                });
            }
        }
        return factories;
    }

    /** Full candidate roster used by the trainer and the real-DB report: baseline + logistic grid + GBM grid. */
    public static List<ModelFactory> allCandidates() {
        List<ModelFactory> all = new ArrayList<>(segmentACandidates());
        all.addAll(gbmCandidates());
        return all;
    }

    // ========================================================================
    // Read-only report against the real database (audit checkpoints)
    // ========================================================================

    public static String buildReport(List<FeatureExtractor.RawBoard> all) {
        StringBuilder sb = new StringBuilder();
        Set<Integer> years = new TreeSet<>();
        Set<Integer> roundSeqs = new TreeSet<>();
        int draws = 0;
        int timeouts = 0;
        int nullSeats = 0;
        for (FeatureExtractor.RawBoard rb : all) {
            years.add(rb.year);
            roundSeqs.add(rb.roundSeq);
            if (rb.isDraw()) {
                draws++;
            }
            if (rb.a.seat == null) {
                nullSeats++;
            }
            if (rb.b.seat == null) {
                nullSeats++;
            }
        }
        for (FeatureExtractor.RawBoard rb : all) {
            if (rb.aTimedOut || rb.bTimedOut) {
                timeouts++;
            }
        }

        sb.append("=== IHRGStats ML walk-forward report (read-only) ===\n");
        sb.append(String.format(Locale.ROOT, "Data: %d rated boards, %d rounds, years %s%n",
                all.size(), roundSeqs.size(), years));
        sb.append(String.format(Locale.ROOT, "Draws: %d (%.1f%%) | timeout boards: %d | NULL seats: %d of %d sides%n",
                draws, all.isEmpty() ? 0.0 : 100.0 * draws / all.size(), timeouts, nullSeats, all.size() * 2));
        int burnIn = defaultBurnIn(all);
        sb.append(String.format(Locale.ROOT, "Burn-in: first %d rounds (never predicted)%n%n", burnIn));

        List<Result> results = runAll(all, allCandidates(), burnIn);
        Result baseline = results.get(0);
        sb.append(String.format(Locale.ROOT, "%-28s %9s %9s %9s %7s %12s%n",
                "candidate", "Brier", "logLoss", "ES-MSE", "acc", "rounds +/-"));
        for (Result r : results) {
            String sign = r == baseline ? "(baseline)"
                    : String.format(Locale.ROOT, "%d/%d", r.roundsBetterThanBaseline, r.roundsWorseThanBaseline);
            sb.append(String.format(Locale.ROOT, "%-28s %9.5f %9.5f %9.5f %6.1f%% %12s%n",
                    r.name, r.brier, r.logLoss, r.expectedScoreMse, r.accuracy * 100.0, sign));
        }
        sb.append(String.format(Locale.ROOT, "%nPredicted boards per candidate: %d across %d rounds%n",
                baseline.predictedBoards, baseline.predictedRounds));

        Result best = results.get(0);
        for (Result r : results) {
            if (!Double.isNaN(r.brier) && r.brier < best.brier) {
                best = r;
            }
        }
        sb.append(String.format(Locale.ROOT, "Best by Brier: %s (delta vs baseline: %+.5f)%n",
                best.name, best.brier - baseline.brier));

        sb.append("\nCalibration (best candidate) - bucket: predicted vs actual mean score (n):\n");
        for (int i = 0; i < 10; i++) {
            double[] b = best.calibration[i];
            if (b[2] > 0) {
                sb.append(String.format(Locale.ROOT, "  %.1f-%.1f: %.3f vs %.3f (%d)%n",
                        i / 10.0, (i + 1) / 10.0, b[0], b[1], (long) b[2]));
            }
        }
        return sb.toString();
    }

    /** Read-only console report against the app's default database. */
    public static void main(String[] args) throws Exception {
        List<FeatureExtractor.RawBoard> all = new FeatureExtractor().extractAll();
        if (all.isEmpty()) {
            System.out.println("No rated boards found in the database - nothing to backtest.");
            return;
        }
        System.out.println(buildReport(all));
    }
}
