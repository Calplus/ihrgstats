package com.calplus.ihrgstats.ml;

import com.calplus.ihrgstats.databasemanager.E17_MlModels;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;

/**
 * Two-stage symmetric logistic matchup model - Segment A's covariate
 * yardstick (and the sanity floor the Segment-B GBM must beat):
 *
 * - Stage D (draw): P(draw) = sigmoid(intercept + w_d . sym) on the
 *   symmetric features - identical whichever side is called A.
 * - Stage W (win): P(A wins | not draw) = sigmoid(w_w . anti) on the
 *   antisymmetric features, with NO intercept and NO centering, so
 *   swapping A and B exactly negates the logit.
 *
 * Together: P(A win) = (1-PD)*PW, P(draw) = PD, P(B win) = (1-PD)*(1-PW),
 * and P(A beats B) = 1 - P(B beats A) holds exactly by construction.
 *
 * Fitting is Newton-Raphson with L2 regularization on standardized
 * features (sym: mean+std; anti: std scaling only - centering would break
 * antisymmetry). Fully deterministic: zero initialization, fixed
 * iteration cap, no randomness - retraining on the same data reproduces
 * byte-identical parameters.
 */
public class LogisticModel implements MatchupPredictor {

    private static final int MAX_ITERATIONS = 25;
    private static final double CONVERGENCE = 1e-9;
    /** Intercept ridge factor: lambda/100 - just enough for numerical stability without biasing the draw rate toward 50%. */
    private static final double INTERCEPT_RIDGE_FACTOR = 0.01;

    /** Gson-serializable parameter set (arrays only - deterministic JSON). */
    public static class Params {
        public double n0;
        public double lambda;
        public double[] wDraw;    // [intercept, sym features...]
        public double[] wWin;     // [anti features], no intercept
        public double[] symMean;
        public double[] symStd;
        public double[] antiStd;
    }

    private final Params params;

    private LogisticModel(Params params) {
        this.params = params;
    }

    public Params getParams() {
        return params;
    }

    // ========================================================================
    // Fitting
    // ========================================================================

    /**
     * Fits both stages on the training boards. Stage W trains only on
     * decisive (non-draw) boards; stage D on all boards. Empty or
     * degenerate inputs yield neutral (all-zero) weights rather than
     * failing - predictions then fall back toward 50/50.
     */
    public static LogisticModel fit(List<FeatureExtractor.RawBoard> train, double n0, double lambda) {
        Params p = new Params();
        p.n0 = n0;
        p.lambda = lambda;

        int n = train.size();
        double[][] sym = new double[n][];
        double[][] anti = new double[n][];
        for (int i = 0; i < n; i++) {
            FeatureExtractor.Vectors v = FeatureExtractor.assemble(train.get(i), n0);
            sym[i] = v.sym;
            anti[i] = v.anti;
        }

        // Standardization stats from the training set only.
        p.symMean = new double[FeatureExtractor.SYM_DIM];
        p.symStd = new double[FeatureExtractor.SYM_DIM];
        p.antiStd = new double[FeatureExtractor.ANTI_DIM];
        computeMeanStd(sym, p.symMean, p.symStd);
        computeStdOnly(anti, p.antiStd);

        // Stage D: [1, standardized sym...] on all boards, y = isDraw.
        double[][] xd = new double[n][];
        double[] yd = new double[n];
        for (int i = 0; i < n; i++) {
            xd[i] = drawDesignRow(sym[i], p);
            yd[i] = train.get(i).isDraw() ? 1.0 : 0.0;
        }
        double[] ridgeD = new double[1 + FeatureExtractor.SYM_DIM];
        ridgeD[0] = lambda * INTERCEPT_RIDGE_FACTOR;
        for (int j = 1; j < ridgeD.length; j++) {
            ridgeD[j] = lambda;
        }
        p.wDraw = newtonLogistic(xd, yd, ridgeD);

        // Stage W: standardized anti on decisive boards only, y = (A won).
        List<double[]> xwList = new ArrayList<>();
        List<Double> ywList = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!train.get(i).isDraw()) {
                xwList.add(winDesignRow(anti[i], p));
                ywList.add(train.get(i).outcomeA);
            }
        }
        double[][] xw = xwList.toArray(new double[0][]);
        double[] yw = new double[ywList.size()];
        for (int i = 0; i < yw.length; i++) {
            yw[i] = ywList.get(i);
        }
        double[] ridgeW = new double[FeatureExtractor.ANTI_DIM];
        java.util.Arrays.fill(ridgeW, lambda);
        p.wWin = newtonLogistic(xw, yw, ridgeW);

        return new LogisticModel(p);
    }

    private static double[] drawDesignRow(double[] symRaw, Params p) {
        double[] row = new double[1 + FeatureExtractor.SYM_DIM];
        row[0] = 1.0;
        for (int j = 0; j < FeatureExtractor.SYM_DIM; j++) {
            row[1 + j] = (symRaw[j] - p.symMean[j]) / p.symStd[j];
        }
        return row;
    }

    private static double[] winDesignRow(double[] antiRaw, Params p) {
        double[] row = new double[FeatureExtractor.ANTI_DIM];
        for (int j = 0; j < FeatureExtractor.ANTI_DIM; j++) {
            row[j] = antiRaw[j] / p.antiStd[j];
        }
        return row;
    }

    private static void computeMeanStd(double[][] x, double[] mean, double[] std) {
        int n = x.length;
        int d = mean.length;
        for (int j = 0; j < d; j++) {
            double sum = 0.0;
            for (double[] row : x) {
                sum += row[j];
            }
            mean[j] = n > 0 ? sum / n : 0.0;
            double sq = 0.0;
            for (double[] row : x) {
                double diff = row[j] - mean[j];
                sq += diff * diff;
            }
            double s = n > 1 ? Math.sqrt(sq / (n - 1)) : 0.0;
            std[j] = s > 1e-12 ? s : 1.0; // constant feature -> harmless unit scale
        }
    }

    /** Std WITHOUT centering: anti features must stay exactly antisymmetric. */
    private static void computeStdOnly(double[][] x, double[] std) {
        int n = x.length;
        int d = std.length;
        for (int j = 0; j < d; j++) {
            double sq = 0.0;
            for (double[] row : x) {
                sq += row[j] * row[j];
            }
            double s = n > 0 ? Math.sqrt(sq / n) : 0.0;
            std[j] = s > 1e-12 ? s : 1.0;
        }
    }

    /**
     * Newton-Raphson for L2-penalized logistic regression. Deterministic:
     * zero init, fixed cap. ridge[j] is the per-coefficient L2 strength.
     */
    static double[] newtonLogistic(double[][] x, double[] y, double[] ridge) {
        int d = ridge.length;
        double[] w = new double[d];
        if (x.length == 0) {
            return w;
        }
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            double[] grad = new double[d];
            double[][] hess = new double[d][d];
            for (int i = 0; i < x.length; i++) {
                double z = dot(w, x[i]);
                double pi = 1.0 / (1.0 + Math.exp(-z));
                double residual = y[i] - pi;
                double weight = Math.max(pi * (1.0 - pi), 1e-10);
                for (int j = 0; j < d; j++) {
                    grad[j] += residual * x[i][j];
                    for (int k = 0; k < d; k++) {
                        hess[j][k] += weight * x[i][j] * x[i][k];
                    }
                }
            }
            for (int j = 0; j < d; j++) {
                grad[j] -= ridge[j] * w[j];
                hess[j][j] += ridge[j];
            }
            double[] delta = solve(hess, grad);
            double maxDelta = 0.0;
            for (int j = 0; j < d; j++) {
                w[j] += delta[j];
                maxDelta = Math.max(maxDelta, Math.abs(delta[j]));
            }
            if (maxDelta < CONVERGENCE) {
                break;
            }
        }
        return w;
    }

    /** Gaussian elimination with partial pivoting - dimensions here are tiny (<=8). */
    static double[] solve(double[][] aIn, double[] bIn) {
        int d = bIn.length;
        double[][] a = new double[d][d];
        double[] b = new double[d];
        for (int i = 0; i < d; i++) {
            System.arraycopy(aIn[i], 0, a[i], 0, d);
            b[i] = bIn[i];
        }
        for (int col = 0; col < d; col++) {
            int pivot = col;
            for (int row = col + 1; row < d; row++) {
                if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) {
                    pivot = row;
                }
            }
            double[] tmpRow = a[col]; a[col] = a[pivot]; a[pivot] = tmpRow;
            double tmpB = b[col]; b[col] = b[pivot]; b[pivot] = tmpB;
            double diag = a[col][col];
            if (Math.abs(diag) < 1e-12) {
                continue; // singular direction - leave as zero step
            }
            for (int row = col + 1; row < d; row++) {
                double factor = a[row][col] / diag;
                for (int k = col; k < d; k++) {
                    a[row][k] -= factor * a[col][k];
                }
                b[row] -= factor * b[col];
            }
        }
        double[] xOut = new double[d];
        for (int row = d - 1; row >= 0; row--) {
            double sum = b[row];
            for (int k = row + 1; k < d; k++) {
                sum -= a[row][k] * xOut[k];
            }
            xOut[row] = Math.abs(a[row][row]) < 1e-12 ? 0.0 : sum / a[row][row];
        }
        return xOut;
    }

    private static double dot(double[] a, double[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    // ========================================================================
    // Prediction
    // ========================================================================

    @Override
    public Probs predict(FeatureExtractor.RawBoard board) {
        FeatureExtractor.Vectors v = FeatureExtractor.assemble(board, params.n0);
        double pd = 1.0 / (1.0 + Math.exp(-dot(params.wDraw, drawDesignRow(v.sym, params))));
        double pw = 1.0 / (1.0 + Math.exp(-dot(params.wWin, winDesignRow(v.anti, params))));
        return new Probs((1.0 - pd) * pw, pd, (1.0 - pd) * (1.0 - pw));
    }

    @Override
    public String family() {
        return E17_MlModels.FAMILY_LOGISTIC;
    }

    public String toParamsJson() {
        return new Gson().toJson(params);
    }

    public static LogisticModel fromParamsJson(String json) {
        return new LogisticModel(new Gson().fromJson(json, Params.class));
    }
}
