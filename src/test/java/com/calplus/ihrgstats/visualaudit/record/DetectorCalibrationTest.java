package com.calplus.ihrgstats.visualaudit.record;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Detector calibration on direct generator renders (no database, ~2 s):
 * the S2 shape ("Round N" labels in %-4s / %-3s columns) must be flagged
 * on unmodified code, and every plant kind must be planted and caught.
 * The full calibration over the harness variants (incl. the real 09/12)
 * runs in VisualAuditHarness.
 */
public class DetectorCalibrationTest {

    private String originalUserDir;
    private DrawRecorder recorder;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        recorder = DrawRecorder.enable();
        recorder.drain();
    }

    @AfterEach
    void tearDown() {
        DrawRecorder.disable();
        System.setProperty("user.dir", originalUserDir);
    }

    @Test
    void s2Shape_isFlaggedOnUnmodifiedGenerators() throws Exception {
        GeneratorFixtures.infoImage("crescent", "s2", "2026-01-01 00:00:00");
        DrawRecord r = recorder.drain().get(0);
        r.name = "fixture_info_s2";
        List<ImageChecks.Violation> vs = ImageChecks.run(r);
        assertTrue(vs.stream().anyMatch(v -> v.check().equals("ALIGN_ROW_SHIFT") && v.detail().startsWith("'Round 10")
                        && v.detail().contains("+1.0 char")),
                "Round 10 row shifted one character: " + vs);
        assertTrue(vs.stream().anyMatch(v -> v.check().equals("ALIGN_DELIMITER") && v.detail().contains("'Seat:")),
                "seating header '|' not over value cells: " + vs);
        assertTrue(vs.stream().anyMatch(v -> v.check().equals("ICON_GAP") && v.detail().startsWith("text 'Round 10'")),
                "Round 10 label touching its icon: " + vs);
    }

    @Test
    void everyPlantKind_isPlantedAndCaught() throws Exception {
        GeneratorFixtures.hallTable(12, "c1");
        GeneratorFixtures.playerTable(21, "c2");
        GeneratorFixtures.infoImage("binjai", "c3", "2026-01-01 00:00:00");
        GeneratorFixtures.comparisonImage("crescent", "1", "c4");
        List<DrawRecord> records = recorder.drain();
        records.forEach(r -> r.name = r.file);
        List<Plants.Result> results = new ArrayList<>();
        String report = Plants.calibrate(records, 20261005L, 4, results);
        System.out.println(report);
        for (String kind : Plants.KINDS) {
            long planted = results.stream().filter(x -> x.plant().kind().equals(kind)).count();
            long caught = results.stream().filter(x -> x.plant().kind().equals(kind) && x.caught()).count();
            assertTrue(planted > 0, kind + " never planted");
            assertEquals(planted, caught, kind + " missed - see report:\n" + report);
        }
    }
}
