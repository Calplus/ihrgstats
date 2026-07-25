package com.calplus.ihrgstats.ml;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for {@link GbmTree}: correct split recovery on a known
 * step function, leaf-value formula, and the missing-value
 * learned-direction mechanism.
 */
public class GbmTreeTest {

    private static List<Integer> range(int n) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            idx.add(i);
        }
        return idx;
    }

    /** Pure regression setting: grad = pred - target (identity link), hess = 1, target is a clean step at x=0. */
    @Test
    void recoversAKnownStepSplit() {
        double[][] x = new double[20][1];
        double[] target = new double[20];
        for (int i = 0; i < 20; i++) {
            x[i][0] = i - 10; // -10..9
            target[i] = x[i][0] < 0 ? -5.0 : 5.0;
        }
        double[] grad = new double[20];
        double[] hess = new double[20];
        for (int i = 0; i < 20; i++) {
            grad[i] = -target[i]; // pred starts at 0, grad = pred - target
            hess[i] = 1.0;
        }
        GbmTree.Node root = GbmTree.buildTree(range(20), x, grad, hess, 2, 0.01, 0.5, 1e-6);
        assertEquals(0, root.featureIndex, "should split on the only feature");
        assertTrue(root.threshold > -1 && root.threshold < 1, "threshold should land at the true step, got " + root.threshold);
        assertTrue(GbmTree.predict(root, new double[]{-5}) < 0, "left side should predict negative");
        assertTrue(GbmTree.predict(root, new double[]{5}) > 0, "right side should predict positive");
    }

    @Test
    void depthOneWhenMinGainExceedsAnyAchievableSplit() {
        double[][] x = {{1}, {2}, {3}, {4}};
        double[] grad = {-1, 1, -1, 1};
        double[] hess = {1, 1, 1, 1};
        // A minGain far larger than any split on 4 points could possibly clear
        // forces the tree to degenerate to a single leaf regardless of lambda.
        GbmTree.Node root = GbmTree.buildTree(range(4), x, grad, hess, 3, 1.0, 0.5, 1e6);
        assertEquals(1, GbmTree.depth(root));
    }

    @Test
    void missingValuesRouteThroughLearnedDirection() {
        // Two present clusters strongly separated at x=0; missing values are secretly
        // all "high" - the split search should learn to send them right.
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            xs.add(-10.0 + i * 0.1);
            ys.add(-5.0);
        }
        for (int i = 0; i < 10; i++) {
            xs.add(10.0 + i * 0.1);
            ys.add(5.0);
        }
        // 6 missing samples that behave like the "high" cluster.
        for (int i = 0; i < 6; i++) {
            xs.add(Double.NaN);
            ys.add(5.0);
        }
        int n = xs.size();
        double[][] x = new double[n][1];
        double[] grad = new double[n];
        double[] hess = new double[n];
        for (int i = 0; i < n; i++) {
            x[i][0] = xs.get(i);
            grad[i] = -ys.get(i);
            hess[i] = 1.0;
        }
        GbmTree.Node root = GbmTree.buildTree(range(n), x, grad, hess, 1, 0.01, 0.5, 1e-6);
        assertFalse(root.missingGoesLeft, "missing values behaved like the high cluster - should route right");
        assertTrue(GbmTree.predict(root, new double[]{Double.NaN}) > 0);
    }

    @Test
    void leafValueMatchesXgboostFormula() {
        double[][] x = {{1}};
        double[] grad = {2.0};
        double[] hess = {4.0};
        GbmTree.Node root = GbmTree.buildTree(range(1), x, grad, hess, 3, 1.0, 0.0, 1e-6);
        assertEquals(1, GbmTree.depth(root), "a single sample can never split");
        assertEquals(-2.0 / (4.0 + 1.0), root.leafValue, 1e-12);
    }
}
