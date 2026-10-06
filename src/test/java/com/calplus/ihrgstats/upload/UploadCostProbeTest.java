package com.calplus.ihrgstats.upload;

import com.calplus.ihrgstats.calculations.RatingRecalculator;
import com.calplus.ihrgstats.ml.ExpEloDistiller;
import com.calplus.ihrgstats.ml.FeatureExtractor;
import com.calplus.ihrgstats.ml.ModelTrainer;
import com.calplus.ihrgstats.ml.PredictionService;
import com.calplus.ihrgstats.ml.RollingCacheUpdater;
import com.calplus.ihrgstats.upload.UploadTestSupport.Dialogs;
import com.calplus.ihrgstats.upload.UploadTestSupport.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.calplus.ihrgstats.upload.UploadTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Lane b1, input to the .xlsx design question "retrain once at the end of a
 * multi-round workbook?": per-round upload time over two corpus seasons, and
 * the cost of each whole-history step when run on its own on the final
 * database. Wall-clock on a shared developer machine (other lanes running):
 * indicative only, never asserted.
 */
public class UploadCostProbeTest {

    private String originalUserDir;

    @BeforeEach
    void saveUserDir() {
        originalUserDir = System.getProperty("user.dir");
    }

    @AfterEach
    void restoreUserDir() {
        System.setProperty("user.dir", originalUserDir);
        System.clearProperty("SETTINGS_CURRENTYEAR");
    }

    @Test
    void perRoundUploadCost_andWholeHistoryStepCosts(@TempDir Path tmp) throws Exception {
        initHome(tmp.resolve("h"));
        List<Script> script = new ArrayList<>(CORPUS_2001_SCRIPT);
        script.add(new Script("Player: Joyce Byers", 1));
        script.add(new Script("'Jessie Pinkman' may match existing player 'Jesse Pinkman'.", 0));
        script.add(new Script("'Margarey Tyrell' may match existing player 'Margaery Tyrell'.", 0));
        Dialogs d = new Dialogs(script);
        StringBuilder report = new StringBuilder("round | upload ms\n");
        for (int year : new int[]{2001, 2002}) {
            assertTrue(capped(sample(year + "_cappedlist.csv"), year).ok());
            for (int r = 1; r <= 10; r++) {
                long t0 = System.nanoTime();
                Outcome o = round(sample(year + "_round_" + r + ".csv"), year, r, d);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                assertTrue(o.ok(), year + " R" + r + ": " + o.joined() + " " + d.unexpected);
                report.append(year).append(" R").append(r).append(" | ").append(ms).append('\n');
            }
        }
        long t = System.nanoTime();
        new RatingRecalculator().recalculateAll(NOW);
        long recalc = (System.nanoTime() - t) / 1_000_000;
        t = System.nanoTime();
        List<FeatureExtractor.RawBoard> boards = new FeatureExtractor().extractAll();
        long extract = (System.nanoTime() - t) / 1_000_000;
        t = System.nanoTime();
        ModelTrainer.TrainOutcome out = new ModelTrainer().retrainAndSelect(boards, NOW);
        long train = (System.nanoTime() - t) / 1_000_000;
        t = System.nanoTime();
        new ExpEloDistiller().distillAndWrite(new PredictionService().loadChampion(), out.extractedBoards, NOW);
        long distill = (System.nanoTime() - t) / 1_000_000;
        t = System.nanoTime();
        new RollingCacheUpdater().updateAll(NOW);
        long cache = (System.nanoTime() - t) / 1_000_000;
        report.append(String.format("after 20 rounds, standalone: recalc %d ms | extract %d ms | retrain %d ms (trained=%s) | distill %d ms | rolling cache %d ms%n",
                recalc, extract, train, out.trained, distill, cache));
        System.out.println("[b1] cost probe\n" + report);
        java.nio.file.Files.createDirectories(java.nio.file.Paths.get(originalUserDir, "target", "b1"));
        java.nio.file.Files.writeString(java.nio.file.Paths.get(originalUserDir, "target", "b1", "cost-probe.txt"), report.toString());
    }
}
