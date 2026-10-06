package com.calplus.ihrgstats.visualaudit.record;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lane b3 gap test for mutants M12 (column width) and M13 (canvas height):
 * ImageChecks already SEES both - overlapping cells, a last row drawn off the
 * canvas - but it only reports, so no build failed. This turns the two
 * geometry invariants of the rank tables into assertions: no text or icon
 * overlaps another, and nothing is drawn more than 1 px outside the canvas
 * (1 px = the known descender clip on player tables, a2 finding).
 */
public class B3ImageGapTest {

    private static final Pattern OUTSIDE = Pattern.compile("outside \\d+x\\d+ by (\\d+) px");

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
    void rankTables_haveNoOverlapAndNothingOffCanvas() throws Exception {
        GeneratorFixtures.hallTable(12, "g12");
        GeneratorFixtures.hallTable(3, "g3");
        GeneratorFixtures.playerTable(21, "g21");
        GeneratorFixtures.playerTable(1, "g1");
        List<DrawRecord> records = recorder.drain();
        assertEquals(4, records.size());
        List<String> bad = new ArrayList<>();
        for (DrawRecord r : records) {
            r.name = r.file;
            for (ImageChecks.Violation v : ImageChecks.run(r)) {
                if (v.check().equals("OVERLAP")) {
                    bad.add(v.toString());
                } else if (v.check().equals("CANVAS_CLIP")) {
                    Matcher m = OUTSIDE.matcher(v.detail());
                    if (!m.find() || Integer.parseInt(m.group(1)) > 1) {
                        bad.add(v.toString());
                    }
                }
            }
        }
        assertTrue(bad.isEmpty(), "table geometry broken:\n" + String.join("\n", bad));
    }
}
