package com.calplus.ihrgstats.ml;

import com.calplus.ihrgstats.databasemanager.E17_MlModels;
import com.calplus.ihrgstats.ml.embed.EmbeddingNet;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-built XGBoost-style gradient-boosted matchup model - the "actually
 * ML" tier above {@link LogisticModel}, using the exact same two-stage
 * (draw / win) structure so it plugs into the same walk-forward harness
 * and champion-selection rule.
 *
 * Exact antisymmetry with trees: axis-aligned regression trees don't
 * automatically satisfy f(-x) = -f(x) the way a no-intercept linear model
 * does (an arbitrary split threshold has no reason to sit at 0). Instead
 * of hoping symmetric training data produces an approximately-symmetric
 * tree, the win stage is trained as a PLAIN ensemble on a training set
 * augmented with every board's mirror image (swapped sides, negated
 * antisymmetric features, flipped label), and served through the
 * algebraic wrapper {@code f(x) = 0.5 * (raw(x) - raw(-x))}. This is
 * exactly antisymmetric for ANY function raw(), regardless of what the
 * trees actually learned - the same guarantee the logistic model gets
 * from its linear no-intercept form, just proven differently. The
 * mirrored augmentation also roughly doubles the effective sample size,
 * which matters at this data scale.
 *
 * The draw stage needs no such treatment: sym[] features are already
 * swap-invariant by construction (see {@link FeatureExtractor#assemble}),
 * so a plain ensemble on them is trivially swap-invariant too.
 *
 * Missing seats: trained with {@code assemble(..., allowMissingSeat=true)},
 * so the seat-diff feature is NaN (not mean-imputed) whenever either side's
 * seat is unrecorded, letting {@link GbmTree}'s learned missing-direction
 * handle it rather than always assuming "board 3".
 *
 * Trees are scale-invariant (threshold splits), so unlike the logistic
 * model no standardization is needed.
 */
public class GbmModel implements MatchupPredictor {

    public static class Params {
        public double n0;
        public double lambda;
        public double learningRate;
        public int maxDepth;
        public double drawBaseScore; // logit(overall draw rate) - constant offset, draw stage only
        public List<GbmTree.Node> drawTrees;
        public List<GbmTree.Node> winTrees;
        /** Null = plain GBM (family {@code GBM}); present = GBM_EMB, win-stage anti[] is augmented with embedding interaction features. */
        public EmbeddingNet.Params embedding;
    }

    private static final int MAX_TREES = 200;
    private static final double MIN_CHILD_WEIGHT = 4.0;
    private static final double MIN_GAIN = 1e-4;
    /** Consecutive non-improving iterations before early stopping kicks in. */
    private static final int PATIENCE = 12;
    /** Minimum distinct training rounds required to carve out an internal validation slice. */
    private static final int MIN_ROUNDS_FOR_VALIDATION = 6;
    private static final double VALIDATION_FRACTION = 0.2;
    /** Tree count used when the training set is too small to validate against. */
    private static final int FALLBACK_TREE_COUNT = 40;

    private final Params params;

    private GbmModel(Params params) {
        this.params = params;
    }

    public Params getParams() {
        return params;
    }

    // ========================================================================
    // Fitting
    // ========================================================================

    public static GbmModel fit(List<FeatureExtractor.RawBoard> train, double n0, double lambda) {
        return fitInternal(train, n0, lambda, null);
    }

    /**
     * Same as {@link #fit}, but first trains an {@link EmbeddingNet} on
     * {@code train} and augments every win-stage anti[] vector with its
     * antisymmetric interaction features - the GBM_EMB family. Kept as a
     * genuinely separate model class (not a post-hoc feature bolt-on) so
     * the walk-forward harness can score it head-to-head against plain
     * GBM and the trainer can gate on it strictly beating plain GBM, not
     * just the Glicko baseline (see {@code ModelTrainer.pickChampion}).
     */
    public static GbmModel fitWithEmbeddings(List<FeatureExtractor.RawBoard> train, double n0, double lambda, int embedDim) {
        EmbeddingNet.Params embedding = EmbeddingNet.fit(train, embedDim);
        return fitInternal(train, n0, lambda, embedding);
    }

    private static GbmModel fitInternal(List<FeatureExtractor.RawBoard> train, double n0, double lambda,
                                        EmbeddingNet.Params embedding) {
        Params p = new Params();
        p.n0 = n0;
        p.lambda = lambda;
        p.learningRate = 0.1;
        p.maxDepth = 3;
        p.embedding = embedding;

        if (train.isEmpty()) {
            p.drawBaseScore = 0.0;
            p.drawTrees = List.of();
            p.winTrees = List.of();
            return new GbmModel(p);
        }

        long draws = train.stream().filter(FeatureExtractor.RawBoard::isDraw).count();
        double drawRate = Math.max(1e-4, Math.min(1 - 1e-4, (double) draws / train.size()));
        p.drawBaseScore = Math.log(drawRate / (1 - drawRate));

        // Chronological fit/validation split by ROUND (not board) to avoid round-level leakage.
        List<Integer> roundSeqs = train.stream().map(rb -> rb.roundSeq).distinct().sorted().toList();
        boolean canValidate = roundSeqs.size() >= MIN_ROUNDS_FOR_VALIDATION;
        int splitSeq = canValidate
                ? roundSeqs.get((int) Math.max(1, roundSeqs.size() * (1 - VALIDATION_FRACTION)))
                : Integer.MAX_VALUE;

        List<FeatureExtractor.RawBoard> fitSet = new ArrayList<>();
        List<FeatureExtractor.RawBoard> valSet = new ArrayList<>();
        for (FeatureExtractor.RawBoard rb : train) {
            (rb.roundSeq < splitSeq ? fitSet : valSet).add(rb);
        }
        if (!canValidate || valSet.isEmpty()) {
            fitSet = train;
            valSet = List.of();
        }

        p.drawTrees = fitDrawStage(fitSet, valSet, p);
        p.winTrees = fitWinStage(fitSet, valSet, p);
        return new GbmModel(p);
    }

    private static List<GbmTree.Node> fitDrawStage(List<FeatureExtractor.RawBoard> fitSet,
                                                    List<FeatureExtractor.RawBoard> valSet, Params p) {
        double[][] fitX = symMatrix(fitSet, p.n0);
        double[] fitY = new double[fitSet.size()];
        for (int i = 0; i < fitSet.size(); i++) {
            fitY[i] = fitSet.get(i).isDraw() ? 1.0 : 0.0;
        }
        double[][] valX = symMatrix(valSet, p.n0);
        double[] valY = new double[valSet.size()];
        for (int i = 0; i < valSet.size(); i++) {
            valY[i] = valSet.get(i).isDraw() ? 1.0 : 0.0;
        }
        return boost(fitX, fitY, p.drawBaseScore, valX, valY, p);
    }

    /** Mirror-augmented plain boosting; symmetry comes from the serving-time wrapper, not this ensemble alone. */
    private static List<GbmTree.Node> fitWinStage(List<FeatureExtractor.RawBoard> fitSet,
                                                   List<FeatureExtractor.RawBoard> valSet, Params p) {
        List<FeatureExtractor.RawBoard> decisiveFit = fitSet.stream().filter(rb -> !rb.isDraw()).toList();
        List<FeatureExtractor.RawBoard> decisiveVal = valSet.stream().filter(rb -> !rb.isDraw()).toList();

        double[][] fitX = mirroredAntiMatrix(decisiveFit, p.n0, p.embedding);
        double[] fitY = new double[decisiveFit.size() * 2];
        for (int i = 0; i < decisiveFit.size(); i++) {
            fitY[2 * i] = decisiveFit.get(i).outcomeA;
            fitY[2 * i + 1] = 1.0 - decisiveFit.get(i).outcomeA;
        }
        // Validation uses the antisymmetrized prediction directly (see predictWinLogit), not the raw mirrored set.
        double[][] valX = antiMatrix(decisiveVal, p.n0, p.embedding);
        double[] valY = new double[decisiveVal.size()];
        for (int i = 0; i < decisiveVal.size(); i++) {
            valY[i] = decisiveVal.get(i).outcomeA;
        }
        return boostAntisymmetric(fitX, fitY, valX, valY, p);
    }

    private static double[][] symMatrix(List<FeatureExtractor.RawBoard> boards, double n0) {
        double[][] x = new double[boards.size()][];
        for (int i = 0; i < boards.size(); i++) {
            x[i] = FeatureExtractor.assemble(boards.get(i), n0, true).sym;
        }
        return x;
    }

    private static double[][] antiMatrix(List<FeatureExtractor.RawBoard> boards, double n0, EmbeddingNet.Params embedding) {
        double[][] x = new double[boards.size()][];
        for (int i = 0; i < boards.size(); i++) {
            x[i] = augmentedAnti(boards.get(i), n0, embedding);
        }
        return x;
    }

    /** Each board contributes its own row AND its negated-feature mirror row, doubling the fit set. */
    private static double[][] mirroredAntiMatrix(List<FeatureExtractor.RawBoard> boards, double n0, EmbeddingNet.Params embedding) {
        double[][] x = new double[boards.size() * 2][];
        for (int i = 0; i < boards.size(); i++) {
            double[] anti = augmentedAnti(boards.get(i), n0, embedding);
            x[2 * i] = anti;
            x[2 * i + 1] = negate(anti);
        }
        return x;
    }

    /**
     * The plain anti[] vector, optionally extended with the trained
     * embedding net's antisymmetric interaction features (see
     * {@link EmbeddingNet#interactionFeatures}). Every appended dimension
     * is itself exactly antisymmetric under an A/B swap, so {@link #negate}
     * on the WHOLE augmented row is still exactly equal to what this method
     * would return for the swapped board - the mirroring trick and the
     * serving-time wrapper both stay exact with embeddings in the mix.
     */
    private static double[] augmentedAnti(FeatureExtractor.RawBoard rb, double n0, EmbeddingNet.Params embedding) {
        double[] anti = FeatureExtractor.assemble(rb, n0, true).anti;
        if (embedding == null) {
            return anti;
        }
        double[] extra = EmbeddingNet.interactionFeatures(embedding, rb.a.playerId, rb.a.hallId, rb.b.playerId, rb.b.hallId);
        double[] out = new double[anti.length + extra.length];
        System.arraycopy(anti, 0, out, 0, anti.length);
        System.arraycopy(extra, 0, out, anti.length, extra.length);
        return out;
    }

    /** Standard second-order logistic boosting with a constant base score and validation-based early stopping. */
    private static List<GbmTree.Node> boost(double[][] fitX, double[] fitY, double baseScore,
                                            double[][] valX, double[] valY, Params p) {
        int n = fitX.length;
        if (n == 0) {
            return List.of();
        }
        List<Integer> allIdx = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            allIdx.add(i);
        }
        double[] fitPred = new double[n];
        java.util.Arrays.fill(fitPred, baseScore);
        double[] valPred = new double[valX.length];
        java.util.Arrays.fill(valPred, baseScore);

        List<GbmTree.Node> trees = new ArrayList<>();
        int bestIteration = 0;
        double bestValLoss = valX.length > 0 ? Double.POSITIVE_INFINITY : Double.NaN;
        int sinceImprovement = 0;
        int cap = valX.length > 0 ? MAX_TREES : FALLBACK_TREE_COUNT;

        for (int t = 0; t < cap; t++) {
            double[] grad = new double[n];
            double[] hess = new double[n];
            for (int i = 0; i < n; i++) {
                double pi = sigmoid(fitPred[i]);
                grad[i] = pi - fitY[i];
                hess[i] = Math.max(pi * (1 - pi), 1e-6);
            }
            GbmTree.Node tree = GbmTree.buildTree(allIdx, fitX, grad, hess, p.maxDepth, p.lambda, MIN_CHILD_WEIGHT, MIN_GAIN);
            trees.add(tree);
            for (int i = 0; i < n; i++) {
                fitPred[i] += p.learningRate * GbmTree.predict(tree, fitX[i]);
            }
            if (valX.length == 0) {
                continue;
            }
            for (int i = 0; i < valX.length; i++) {
                valPred[i] += p.learningRate * GbmTree.predict(tree, valX[i]);
            }
            double valLoss = logLoss(valPred, valY);
            if (valLoss < bestValLoss - 1e-6) {
                bestValLoss = valLoss;
                bestIteration = trees.size();
                sinceImprovement = 0;
            } else {
                sinceImprovement++;
                if (sinceImprovement >= PATIENCE) {
                    break;
                }
            }
        }
        return valX.length > 0 ? new ArrayList<>(trees.subList(0, Math.max(1, bestIteration))) : trees;
    }

    /**
     * Boosts the win-stage ensemble on the mirror-augmented fit set (plain
     * boosting, base score 0 - any constant would cancel in the
     * antisymmetrized wrapper anyway), validating each iteration through
     * the SAME wrapper the model serves with.
     */
    private static List<GbmTree.Node> boostAntisymmetric(double[][] fitX, double[] fitY,
                                                          double[][] valX, double[] valY, Params p) {
        int n = fitX.length;
        if (n == 0) {
            return List.of();
        }
        List<Integer> allIdx = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            allIdx.add(i);
        }
        double[] fitPred = new double[n];

        List<GbmTree.Node> trees = new ArrayList<>();
        int bestIteration = 0;
        double bestValLoss = valX.length > 0 ? Double.POSITIVE_INFINITY : Double.NaN;
        int sinceImprovement = 0;
        int cap = valX.length > 0 ? MAX_TREES : FALLBACK_TREE_COUNT;

        for (int t = 0; t < cap; t++) {
            double[] grad = new double[n];
            double[] hess = new double[n];
            for (int i = 0; i < n; i++) {
                double pi = sigmoid(fitPred[i]);
                grad[i] = pi - fitY[i];
                hess[i] = Math.max(pi * (1 - pi), 1e-6);
            }
            GbmTree.Node tree = GbmTree.buildTree(allIdx, fitX, grad, hess, p.maxDepth, p.lambda, MIN_CHILD_WEIGHT, MIN_GAIN);
            trees.add(tree);
            for (int i = 0; i < n; i++) {
                fitPred[i] += p.learningRate * GbmTree.predict(tree, fitX[i]);
            }
            if (valX.length == 0) {
                continue;
            }
            double valLoss = 0.0;
            for (int i = 0; i < valX.length; i++) {
                double raw = ensemblePredict(trees, p.learningRate, valX[i]);
                double rawNeg = ensemblePredict(trees, p.learningRate, negate(valX[i]));
                double logit = 0.5 * (raw - rawNeg);
                double pi = sigmoid(logit);
                valLoss += -Math.log(Math.max(valY[i] > 0.5 ? pi : 1 - pi, 1e-12));
            }
            valLoss /= valX.length;
            if (valLoss < bestValLoss - 1e-6) {
                bestValLoss = valLoss;
                bestIteration = trees.size();
                sinceImprovement = 0;
            } else {
                sinceImprovement++;
                if (sinceImprovement >= PATIENCE) {
                    break;
                }
            }
        }
        return valX.length > 0 ? new ArrayList<>(trees.subList(0, Math.max(1, bestIteration))) : trees;
    }

    private static double ensemblePredict(List<GbmTree.Node> trees, double learningRate, double[] x) {
        double sum = 0.0;
        for (GbmTree.Node tree : trees) {
            sum += learningRate * GbmTree.predict(tree, x);
        }
        return sum;
    }

    private static double[] negate(double[] x) {
        double[] out = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            out[i] = -x[i];
        }
        return out;
    }

    private static double sigmoid(double z) {
        return 1.0 / (1.0 + Math.exp(-z));
    }

    private static double logLoss(double[] pred, double[] y) {
        double sum = 0.0;
        for (int i = 0; i < pred.length; i++) {
            double pi = sigmoid(pred[i]);
            sum += -Math.log(Math.max(y[i] > 0.5 ? pi : 1 - pi, 1e-12));
        }
        return pred.length > 0 ? sum / pred.length : 0.0;
    }

    // ========================================================================
    // Prediction
    // ========================================================================

    @Override
    public Probs predict(FeatureExtractor.RawBoard board) {
        FeatureExtractor.Vectors v = FeatureExtractor.assemble(board, params.n0, true);
        double drawLogit = params.drawBaseScore + ensemblePredict(params.drawTrees, params.learningRate, v.sym);
        double pd = sigmoid(drawLogit);

        double[] anti = augmentedAnti(board, params.n0, params.embedding);
        double raw = ensemblePredict(params.winTrees, params.learningRate, anti);
        double rawNeg = ensemblePredict(params.winTrees, params.learningRate, negate(anti));
        double winLogit = 0.5 * (raw - rawNeg);
        double pw = sigmoid(winLogit);

        return new Probs((1.0 - pd) * pw, pd, (1.0 - pd) * (1.0 - pw));
    }

    @Override
    public String family() {
        return params.embedding != null ? E17_MlModels.FAMILY_GBM_EMB : E17_MlModels.FAMILY_GBM;
    }

    /** The trained embeddings, or null for a plain-GBM fit - used to export {@code player_profiles.playstyle_vector}. */
    public EmbeddingNet.Params getEmbedding() {
        return params.embedding;
    }

    public String toParamsJson() {
        return new Gson().toJson(params);
    }

    public static GbmModel fromParamsJson(String json) {
        return new GbmModel(new Gson().fromJson(json, Params.class));
    }
}
