package com.calplus.ihrgstats.ml;

import java.util.ArrayList;
import java.util.List;

/**
 * A single regression tree fit by exact-greedy second-order gradient
 * boosting (the XGBoost split objective), hand-rolled and dependency-free.
 *
 * Split gain for a candidate partition of the current node's samples into
 * left/right: {@code 0.5 * (GL^2/(HL+lambda) + GR^2/(HR+lambda) - (GL+GR)^2/(HL+HR+lambda)) - minGain},
 * where G/H are summed gradients/hessians of the logistic loss. Leaf value
 * is {@code -G/(H+lambda)} (unscaled - the caller applies the boosting
 * learning-rate shrinkage when adding a tree's contribution to the
 * ensemble).
 *
 * Missing values (NaN) are handled the way XGBoost handles them: for each
 * candidate split, both "missing goes left" and "missing goes right" are
 * evaluated and whichever wins is recorded on the node, so prediction
 * routes NaN inputs through a direction the data itself justified rather
 * than an arbitrary default.
 */
public class GbmTree {

    /** A tree node - either a leaf (featureIndex < 0) or an internal split. */
    public static class Node {
        public int featureIndex = -1;
        public double threshold;
        public boolean missingGoesLeft;
        public double leafValue;
        public Node left;
        public Node right;
    }

    private GbmTree() {
    }

    /**
     * Builds one tree over the given sample indices.
     *
     * @param features feature matrix, one row per sample (indexed by the values in {@code sampleIdx})
     * @param grad per-sample gradient of the loss w.r.t. the current ensemble prediction
     * @param hess per-sample (positive) hessian
     */
    public static Node buildTree(List<Integer> sampleIdx, double[][] features, double[] grad, double[] hess,
                                 int maxDepth, double lambda, double minChildWeight, double minGain) {
        return buildNode(sampleIdx, features, grad, hess, 0, maxDepth, lambda, minChildWeight, minGain);
    }

    private static Node buildNode(List<Integer> sampleIdx, double[][] features, double[] grad, double[] hess,
                                  int depth, int maxDepth, double lambda, double minChildWeight, double minGain) {
        Node node = new Node();
        double sumG = 0.0;
        double sumH = 0.0;
        for (int idx : sampleIdx) {
            sumG += grad[idx];
            sumH += hess[idx];
        }
        node.leafValue = -sumG / (sumH + lambda);

        if (depth >= maxDepth || sampleIdx.size() < 2 || sumH < 2 * minChildWeight) {
            return node;
        }

        int numFeatures = features[sampleIdx.get(0)].length;
        Split best = null;
        for (int f = 0; f < numFeatures; f++) {
            Split candidate = bestSplitForFeature(sampleIdx, features, grad, hess, f, sumG, sumH, lambda, minChildWeight, minGain);
            if (candidate != null && (best == null || candidate.gain > best.gain)) {
                best = candidate;
            }
        }
        if (best == null) {
            return node;
        }

        node.featureIndex = best.featureIndex;
        node.threshold = best.threshold;
        node.missingGoesLeft = best.missingGoesLeft;
        node.left = buildNode(best.leftIdx, features, grad, hess, depth + 1, maxDepth, lambda, minChildWeight, minGain);
        node.right = buildNode(best.rightIdx, features, grad, hess, depth + 1, maxDepth, lambda, minChildWeight, minGain);
        return node;
    }

    private static class Split {
        int featureIndex;
        double threshold;
        boolean missingGoesLeft;
        double gain;
        List<Integer> leftIdx;
        List<Integer> rightIdx;
    }

    /**
     * Single ascending pass over the non-missing samples: at each gap
     * between distinct consecutive values, "low" (below threshold) and
     * "high" (at/above threshold) sums are known directly from the scan,
     * independent of where missing values go. Both missing-direction
     * assignments are then just two ways of combining low/high with the
     * missing mass - evaluated at every gap without a second pass.
     */
    private static Split bestSplitForFeature(List<Integer> sampleIdx, double[][] features, double[] grad, double[] hess,
                                             int featureIndex, double sumG, double sumH,
                                             double lambda, double minChildWeight, double minGain) {
        List<Integer> present = new ArrayList<>();
        List<Integer> missingIdx = new ArrayList<>();
        double missingG = 0.0;
        double missingH = 0.0;
        for (int idx : sampleIdx) {
            if (Double.isNaN(features[idx][featureIndex])) {
                missingIdx.add(idx);
                missingG += grad[idx];
                missingH += hess[idx];
            } else {
                present.add(idx);
            }
        }
        if (present.size() < 2) {
            return null;
        }
        present.sort((i1, i2) -> Double.compare(features[i1][featureIndex], features[i2][featureIndex]));

        double totalG = sumG + missingG;
        double totalH = sumH + missingH;
        double parentScore = (totalG * totalG) / (totalH + lambda);

        int n = present.size();
        double lowG = 0.0;
        double lowH = 0.0;
        Split best = null;
        for (int i = 0; i < n - 1; i++) {
            int idx = present.get(i);
            lowG += grad[idx];
            lowH += hess[idx];

            double thisVal = features[idx][featureIndex];
            double nextVal = features[present.get(i + 1)][featureIndex];
            if (thisVal == nextVal) {
                continue; // threshold must separate distinct values
            }
            double highG = (sumG - lowG);
            double highH = (sumH - lowH);
            double threshold = (thisVal + nextVal) / 2.0;

            // Option 1: missing joins the high (right) side.
            best = considerSplit(featureIndex, threshold, false, lowG, lowH, highG + missingG, highH + missingH,
                    parentScore, lambda, minChildWeight, minGain, present, i, best);
            // Option 2: missing joins the low (left) side.
            best = considerSplit(featureIndex, threshold, true, lowG + missingG, lowH + missingH, highG, highH,
                    parentScore, lambda, minChildWeight, minGain, present, i, best);
        }
        if (best != null) {
            (best.missingGoesLeft ? best.leftIdx : best.rightIdx).addAll(missingIdx);
        }
        return best;
    }

    private static Split considerSplit(int featureIndex, double threshold, boolean missingGoesLeft,
                                       double gL, double hL, double gR, double hR,
                                       double parentScore, double lambda, double minChildWeight, double minGain,
                                       List<Integer> present, int gapIndex, Split currentBest) {
        if (hL < minChildWeight || hR < minChildWeight) {
            return currentBest;
        }
        double gain = 0.5 * ((gL * gL) / (hL + lambda) + (gR * gR) / (hR + lambda) - parentScore) - minGain;
        if (gain <= 0 || (currentBest != null && gain <= currentBest.gain)) {
            return currentBest;
        }
        Split s = new Split();
        s.featureIndex = featureIndex;
        s.threshold = threshold;
        s.missingGoesLeft = missingGoesLeft;
        s.gain = gain;
        s.leftIdx = new ArrayList<>(present.subList(0, gapIndex + 1));
        s.rightIdx = new ArrayList<>(present.subList(gapIndex + 1, present.size()));
        return s;
    }

    /** Routes a feature vector through the tree; NaN at a split follows that node's learned missing-direction. */
    public static double predict(Node node, double[] x) {
        while (node.featureIndex >= 0) {
            double v = x[node.featureIndex];
            boolean goLeft = Double.isNaN(v) ? node.missingGoesLeft : v < node.threshold;
            node = goLeft ? node.left : node.right;
        }
        return node.leafValue;
    }

    /** Max depth of the tree (1 = a single leaf, no splits) - used by tests/diagnostics. */
    public static int depth(Node node) {
        if (node.featureIndex < 0) {
            return 1;
        }
        return 1 + Math.max(depth(node.left), depth(node.right));
    }
}
