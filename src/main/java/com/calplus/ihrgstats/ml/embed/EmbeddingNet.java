package com.calplus.ihrgstats.ml.embed;

import com.calplus.ihrgstats.ml.FeatureExtractor;

import java.util.*;

/**
 * Hand-built, dependency-free neural net that learns a low-dimensional
 * embedding per player and per hall, plus a small MLP head, to capture
 * matchup structure a linear rating diff cannot: genuine non-transitivity
 * ("A specifically beats B, regardless of either one's overall strength").
 *
 * Architecture: {@code raw(A,B) = MLP(concat(embA, embB, hallEmbA, hallEmbB))},
 * one hidden tanh layer, one linear output unit (a logit). This raw function
 * is NOT symmetric on its own - swapping A and B is a different input, not a
 * negation of it - so it is never served directly. Every caller must go
 * through {@link #interactionScore} or {@link #interactionFeatures}, which
 * apply the same algebraic wrapper already proven for the tree ensembles in
 * {@code GbmModel}: {@code f(A,B) = 0.5*(raw(A,B) - raw(B,A))}. This is
 * EXACTLY antisymmetric regardless of what the net learned, and - because it
 * is a genuine bilinear-style interaction of two independent embeddings
 * rather than a difference of two scalars - it can represent a cyclic
 * A-beats-B-beats-C-beats-A relationship that no antisymmetric function of a
 * single rating-diff feature ever could.
 *
 * Training: full-batch gradient descent with momentum (a documented
 * simplification of "SGD" - at this data scale, one gradient step per epoch
 * over the whole mirror-augmented set is both simpler to reason about and
 * fast enough) and hand-derived backpropagation through the tanh layer and
 * into the embedding tables themselves - only players/halls seen in the
 * training set ever move, everyone else keeps their seeded init. Early
 * stopping mirrors {@code GbmModel}: a chronological (by round) validation
 * slice, patience-based, keep the best-seen snapshot.
 *
 * Determinism: initialization draws from a single {@code new Random(42)} in
 * a fixed order - every player id (sorted lexicographically), then every
 * hall id (sorted numerically), then the MLP weights - so retraining the
 * same database twice reproduces byte-identical parameters regardless of
 * map/set iteration order upstream.
 */
public final class EmbeddingNet {

    public static final int HALL_DIM = 2;
    private static final int HIDDEN_UNITS = 12;
    private static final double L2 = 1e-3;
    private static final double LEARNING_RATE = 0.05;
    private static final double MOMENTUM = 0.9;
    private static final int MAX_EPOCHS = 120;
    private static final int FALLBACK_EPOCHS = 60;
    private static final int PATIENCE = 10;
    private static final int MIN_ROUNDS_FOR_VALIDATION = 6;
    private static final double VALIDATION_FRACTION = 0.2;
    private static final double EMBED_INIT_SCALE = 0.1;

    private EmbeddingNet() {
    }

    /** Gson-serializable parameter set. Map keys: player_id (String), hall_id (int, serialized as a JSON string key by Gson). */
    public static class Params {
        public int dim;
        public int hallDim;
        public int hiddenUnits;
        public Map<String, double[]> playerEmb = new LinkedHashMap<>();
        public Map<Integer, double[]> hallEmb = new LinkedHashMap<>();
        public double[][] w1; // [hiddenUnits][inputSize]
        public double[] b1;   // [hiddenUnits]
        public double[] w2;   // [hiddenUnits]
        public double b2;

        int inputSize() {
            return 2 * dim + 2 * hallDim;
        }
    }

    /** Package-visible (not private) so {@code EmbeddingNetTest} can drive a direct analytic-vs-numeric gradient check. */
    record Example(String playerA, int hallA, String playerB, int hallB, double y) {
    }

    private static class Forward {
        double[] x;
        double[] z1;
        double[] h;
        double raw;
    }

    // ========================================================================
    // Fitting
    // ========================================================================

    public static Params fit(List<FeatureExtractor.RawBoard> train, int dim) {
        Params p = initParams(train, dim);
        List<FeatureExtractor.RawBoard> decisive = train.stream().filter(rb -> !rb.isDraw()).toList();
        if (decisive.isEmpty()) {
            return p;
        }

        List<Integer> roundSeqs = decisive.stream().map(rb -> rb.roundSeq).distinct().sorted().toList();
        boolean canValidate = roundSeqs.size() >= MIN_ROUNDS_FOR_VALIDATION;
        int splitSeq = canValidate
                ? roundSeqs.get((int) Math.max(1, roundSeqs.size() * (1 - VALIDATION_FRACTION)))
                : Integer.MAX_VALUE;

        List<FeatureExtractor.RawBoard> fitBoards = new ArrayList<>();
        List<FeatureExtractor.RawBoard> valBoards = new ArrayList<>();
        for (FeatureExtractor.RawBoard rb : decisive) {
            (rb.roundSeq < splitSeq ? fitBoards : valBoards).add(rb);
        }
        if (!canValidate || valBoards.isEmpty()) {
            fitBoards = decisive;
            valBoards = List.of();
        }

        List<Example> fitEx = toMirroredExamples(fitBoards);
        List<Example> valEx = toMirroredExamples(valBoards);

        Map<String, double[]> velPlayer = new HashMap<>();
        Map<Integer, double[]> velHall = new HashMap<>();
        double[][] velW1 = new double[p.hiddenUnits][p.inputSize()];
        double[] velB1 = new double[p.hiddenUnits];
        double[] velW2 = new double[p.hiddenUnits];
        double[] velB2 = new double[1];

        Params best = deepCopy(p);
        double bestValLoss = Double.POSITIVE_INFINITY;
        int sinceImprovement = 0;
        int cap = valEx.isEmpty() ? FALLBACK_EPOCHS : MAX_EPOCHS;

        for (int epoch = 0; epoch < cap; epoch++) {
            GradAccum g = new GradAccum(p);
            for (Example ex : fitEx) {
                accumulate(p, ex, g);
            }
            applyUpdate(p, g, fitEx.size(), velPlayer, velHall, velW1, velB1, velW2, velB2);

            if (valEx.isEmpty()) {
                continue;
            }
            double valLoss = meanLoss(p, valEx);
            if (valLoss < bestValLoss - 1e-6) {
                bestValLoss = valLoss;
                best = deepCopy(p);
                sinceImprovement = 0;
            } else {
                sinceImprovement++;
                if (sinceImprovement >= PATIENCE) {
                    break;
                }
            }
        }
        return valEx.isEmpty() ? p : best;
    }

    private static List<Example> toMirroredExamples(List<FeatureExtractor.RawBoard> boards) {
        List<Example> examples = new ArrayList<>(boards.size() * 2);
        for (FeatureExtractor.RawBoard rb : boards) {
            examples.add(new Example(rb.a.playerId, rb.a.hallId, rb.b.playerId, rb.b.hallId, rb.outcomeA));
            examples.add(new Example(rb.b.playerId, rb.b.hallId, rb.a.playerId, rb.a.hallId, 1.0 - rb.outcomeA));
        }
        return examples;
    }

    static Params initParams(List<FeatureExtractor.RawBoard> train, int dim) {
        Params p = new Params();
        p.dim = dim;
        p.hallDim = HALL_DIM;
        p.hiddenUnits = HIDDEN_UNITS;

        TreeSet<String> playerIds = new TreeSet<>();
        TreeSet<Integer> hallIds = new TreeSet<>();
        for (FeatureExtractor.RawBoard rb : train) {
            playerIds.add(rb.a.playerId);
            playerIds.add(rb.b.playerId);
            hallIds.add(rb.a.hallId);
            hallIds.add(rb.b.hallId);
        }

        Random rnd = new Random(42);
        for (String id : playerIds) {
            p.playerEmb.put(id, randomVector(rnd, dim, EMBED_INIT_SCALE));
        }
        for (int id : hallIds) {
            p.hallEmb.put(id, randomVector(rnd, p.hallDim, EMBED_INIT_SCALE));
        }

        int inputSize = p.inputSize();
        double w1Scale = 1.0 / Math.sqrt(Math.max(1, inputSize));
        double w2Scale = 1.0 / Math.sqrt(p.hiddenUnits);
        p.w1 = new double[p.hiddenUnits][inputSize];
        for (int u = 0; u < p.hiddenUnits; u++) {
            p.w1[u] = randomVector(rnd, inputSize, w1Scale);
        }
        p.b1 = new double[p.hiddenUnits];
        p.w2 = randomVector(rnd, p.hiddenUnits, w2Scale);
        p.b2 = 0.0;
        return p;
    }

    private static double[] randomVector(Random rnd, int n, double scale) {
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            v[i] = rnd.nextGaussian() * scale;
        }
        return v;
    }

    // ========================================================================
    // Forward / backward
    // ========================================================================

    private static double[] lookup(Map<String, double[]> map, String key, int dim) {
        double[] v = map.get(key);
        return v != null ? v : new double[dim];
    }

    private static double[] lookup(Map<Integer, double[]> map, int key, int dim) {
        double[] v = map.get(key);
        return v != null ? v : new double[dim];
    }

    private static double[] concat4(double[] a, double[] b, double[] c, double[] d) {
        double[] out = new double[a.length + b.length + c.length + d.length];
        int pos = 0;
        for (double[] part : new double[][]{a, b, c, d}) {
            System.arraycopy(part, 0, out, pos, part.length);
            pos += part.length;
        }
        return out;
    }

    private static Forward forward(Params p, double[] eA, double[] eB, double[] hA, double[] hB) {
        Forward f = new Forward();
        f.x = concat4(eA, eB, hA, hB);
        f.z1 = new double[p.hiddenUnits];
        f.h = new double[p.hiddenUnits];
        for (int u = 0; u < p.hiddenUnits; u++) {
            double s = p.b1[u];
            double[] row = p.w1[u];
            for (int i = 0; i < f.x.length; i++) {
                s += row[i] * f.x[i];
            }
            f.z1[u] = s;
            f.h[u] = Math.tanh(s);
        }
        double raw = p.b2;
        for (int u = 0; u < p.hiddenUnits; u++) {
            raw += p.w2[u] * f.h[u];
        }
        f.raw = raw;
        return f;
    }

    /** Raw (non-antisymmetric) forward pass - never served directly, see class javadoc. */
    public static double rawScore(Params p, String playerA, int hallA, String playerB, int hallB) {
        double[] eA = lookup(p.playerEmb, playerA, p.dim);
        double[] eB = lookup(p.playerEmb, playerB, p.dim);
        double[] hA = lookup(p.hallEmb, hallA, p.hallDim);
        double[] hB = lookup(p.hallEmb, hallB, p.hallDim);
        return forward(p, eA, eB, hA, hB).raw;
    }

    /** Exactly antisymmetric: interactionScore(A,B) == -interactionScore(B,A). */
    public static double interactionScore(Params p, String playerA, int hallA, String playerB, int hallB) {
        double raw = rawScore(p, playerA, hallA, playerB, hallB);
        double rawSwapped = rawScore(p, playerB, hallB, playerA, hallA);
        return 0.5 * (raw - rawSwapped);
    }

    /** Number of extra antisymmetric feature dims {@link #interactionFeatures} produces for this net. */
    public static int featureCount(Params p) {
        return 1 + p.dim + p.hallDim;
    }

    /**
     * Extra antisymmetric feature dims for GBM_EMB: the net's own
     * antisymmetrized interaction score, plus the raw per-dimension
     * embedding diffs (linear signal, cheap for trees to split on directly)
     * for both player and hall embeddings.
     */
    public static double[] interactionFeatures(Params p, String playerA, int hallA, String playerB, int hallB) {
        double[] eA = lookup(p.playerEmb, playerA, p.dim);
        double[] eB = lookup(p.playerEmb, playerB, p.dim);
        double[] hA = lookup(p.hallEmb, hallA, p.hallDim);
        double[] hB = lookup(p.hallEmb, hallB, p.hallDim);

        double[] out = new double[1 + p.dim + p.hallDim];
        out[0] = interactionScore(p, playerA, hallA, playerB, hallB);
        for (int i = 0; i < p.dim; i++) {
            out[1 + i] = eA[i] - eB[i];
        }
        for (int i = 0; i < p.hallDim; i++) {
            out[1 + p.dim + i] = hA[i] - hB[i];
        }
        return out;
    }

    static class GradAccum {
        final Map<String, double[]> playerGrad = new HashMap<>();
        final Map<Integer, double[]> hallGrad = new HashMap<>();
        final double[][] gw1;
        final double[] gb1;
        final double[] gw2;
        double gb2;
        final int dim;
        final int hallDim;

        GradAccum(Params p) {
            gw1 = new double[p.hiddenUnits][p.inputSize()];
            gb1 = new double[p.hiddenUnits];
            gw2 = new double[p.hiddenUnits];
            dim = p.dim;
            hallDim = p.hallDim;
        }
    }

    static void accumulate(Params p, Example ex, GradAccum g) {
        double[] eA = lookup(p.playerEmb, ex.playerA(), p.dim);
        double[] eB = lookup(p.playerEmb, ex.playerB(), p.dim);
        double[] hA = lookup(p.hallEmb, ex.hallA(), p.hallDim);
        double[] hB = lookup(p.hallEmb, ex.hallB(), p.hallDim);
        Forward f = forward(p, eA, eB, hA, hB);

        double pred = sigmoid(f.raw);
        double dRaw = pred - ex.y();

        double[] dh = new double[p.hiddenUnits];
        for (int u = 0; u < p.hiddenUnits; u++) {
            g.gw2[u] += dRaw * f.h[u];
            dh[u] = dRaw * p.w2[u];
        }
        g.gb2 += dRaw;

        double[] dz1 = new double[p.hiddenUnits];
        for (int u = 0; u < p.hiddenUnits; u++) {
            dz1[u] = dh[u] * (1.0 - f.h[u] * f.h[u]);
            g.gb1[u] += dz1[u];
            double[] row = g.gw1[u];
            for (int i = 0; i < f.x.length; i++) {
                row[i] += dz1[u] * f.x[i];
            }
        }

        double[] dx = new double[f.x.length];
        for (int u = 0; u < p.hiddenUnits; u++) {
            double[] wRow = p.w1[u];
            double dz = dz1[u];
            for (int i = 0; i < dx.length; i++) {
                dx[i] += wRow[i] * dz;
            }
        }

        int d = p.dim;
        int hd = p.hallDim;
        addSlice(g.playerGrad, ex.playerA(), d, dx, 0);
        addSlice(g.playerGrad, ex.playerB(), d, dx, d);
        addSlice(g.hallGrad, ex.hallA(), hd, dx, 2 * d);
        addSlice(g.hallGrad, ex.hallB(), hd, dx, 2 * d + hd);
    }

    private static void addSlice(Map<String, double[]> grad, String key, int dim, double[] dx, int offset) {
        double[] slot = grad.computeIfAbsent(key, k -> new double[dim]);
        for (int i = 0; i < dim; i++) {
            slot[i] += dx[offset + i];
        }
    }

    private static void addSlice(Map<Integer, double[]> grad, Integer key, int dim, double[] dx, int offset) {
        double[] slot = grad.computeIfAbsent(key, k -> new double[dim]);
        for (int i = 0; i < dim; i++) {
            slot[i] += dx[offset + i];
        }
    }

    private static void applyUpdate(Params p, GradAccum g, int n,
                                    Map<String, double[]> velPlayer, Map<Integer, double[]> velHall,
                                    double[][] velW1, double[] velB1, double[] velW2, double[] velB2) {
        double invN = n > 0 ? 1.0 / n : 0.0;

        for (Map.Entry<String, double[]> e : g.playerGrad.entrySet()) {
            double[] param = p.playerEmb.get(e.getKey());
            double[] vel = velPlayer.computeIfAbsent(e.getKey(), k -> new double[g.dim]);
            momentumStep(param, vel, e.getValue(), invN);
        }
        for (Map.Entry<Integer, double[]> e : g.hallGrad.entrySet()) {
            double[] param = p.hallEmb.get(e.getKey());
            double[] vel = velHall.computeIfAbsent(e.getKey(), k -> new double[g.hallDim]);
            momentumStep(param, vel, e.getValue(), invN);
        }
        for (int u = 0; u < p.hiddenUnits; u++) {
            momentumStep(p.w1[u], velW1[u], g.gw1[u], invN);
        }
        momentumStep(p.b1, velB1, g.gb1, invN);
        momentumStep(p.w2, velW2, g.gw2, invN);
        double b2Grad = g.gb2 * invN;
        velB2[0] = MOMENTUM * velB2[0] - LEARNING_RATE * b2Grad;
        p.b2 += velB2[0];
    }

    private static void momentumStep(double[] param, double[] vel, double[] gradSum, double invN) {
        for (int i = 0; i < param.length; i++) {
            double grad = gradSum[i] * invN + L2 * param[i];
            vel[i] = MOMENTUM * vel[i] - LEARNING_RATE * grad;
            param[i] += vel[i];
        }
    }

    static double meanLoss(Params p, List<Example> examples) {
        if (examples.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (Example ex : examples) {
            double raw = rawScore(p, ex.playerA(), ex.hallA(), ex.playerB(), ex.hallB());
            double pred = sigmoid(raw);
            sum += -Math.log(Math.max(ex.y() > 0.5 ? pred : 1 - pred, 1e-12));
        }
        return sum / examples.size();
    }

    private static double sigmoid(double z) {
        return 1.0 / (1.0 + Math.exp(-z));
    }

    private static Params deepCopy(Params p) {
        Params c = new Params();
        c.dim = p.dim;
        c.hallDim = p.hallDim;
        c.hiddenUnits = p.hiddenUnits;
        for (Map.Entry<String, double[]> e : p.playerEmb.entrySet()) {
            c.playerEmb.put(e.getKey(), e.getValue().clone());
        }
        for (Map.Entry<Integer, double[]> e : p.hallEmb.entrySet()) {
            c.hallEmb.put(e.getKey(), e.getValue().clone());
        }
        c.w1 = new double[p.w1.length][];
        for (int i = 0; i < p.w1.length; i++) {
            c.w1[i] = p.w1[i].clone();
        }
        c.b1 = p.b1.clone();
        c.w2 = p.w2.clone();
        c.b2 = p.b2;
        return c;
    }
}
