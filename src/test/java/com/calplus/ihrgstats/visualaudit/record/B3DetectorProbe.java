package com.calplus.ihrgstats.visualaudit.record;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Lane b3 mutation campaign: renders the generator fixtures (no database)
 * and writes every ImageChecks violation to
 * {@code target/mut/detectors/<b3.label>.txt}, so a mutant's detector
 * output can be diffed against the unmutated baseline. A mutant counts as
 * "killed by the image detectors" when its file contains a violation line
 * the baseline does not. Report-only: asserts nothing about the product.
 *
 * Run: mvn -o test -Dtest=B3DetectorProbe -Db3.detectors=true -Db3.label=baseline
 */
@EnabledIfSystemProperty(named = "b3.detectors", matches = "true",
        disabledReason = "lane b3 mutation probe - run with -Db3.detectors=true")
public class B3DetectorProbe {

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
    void writeDetectorReport() throws Exception {
        List<String> names = new ArrayList<>();
        GeneratorFixtures.hallTable(12, "b3h12");
        names.add("hallTable12");
        GeneratorFixtures.hallTable(3, "b3h3");
        names.add("hallTable3");
        GeneratorFixtures.playerTable(21, "b3p21");
        names.add("playerTable21");
        GeneratorFixtures.playerTable(1, "b3p1");
        names.add("playerTable1");
        GeneratorFixtures.infoImage("3", "b3info", "2026-01-01 00:00:00");
        names.add("infoImage");
        GeneratorFixtures.comparisonImage("4", "5", "b3cmp");
        names.add("comparisonImage");
        List<DrawRecord> records = recorder.drain();

        StringBuilder out = new StringBuilder();
        out.append("records=").append(records.size()).append('\n');
        int total = 0;
        for (int i = 0; i < records.size(); i++) {
            DrawRecord r = records.get(i);
            r.name = i < names.size() ? names.get(i) : ("extra" + i);
            List<ImageChecks.Violation> vs = ImageChecks.run(r);
            total += vs.size();
            Map<String, Integer> counts = new TreeMap<>(ImageChecks.countByCheck(vs));
            out.append("== ").append(r.name).append(" canvas=").append(r.canvasWidth).append('x').append(r.canvasHeight)
                    .append(" png=").append(r.width).append('x').append(r.height)
                    .append(" texts=").append(r.texts.size()).append(" icons=").append(r.icons.size())
                    .append(" counts=").append(counts).append('\n');
            List<String> lines = new ArrayList<>();
            for (ImageChecks.Violation v : vs) {
                // Drop the timestamped "Generated:" text from details so runs compare equal.
                // Named hall icons are redacted (lane rule: no hall names in anything written).
                lines.add(v.check() + " | " + v.detail().replaceAll("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}", "<ts>")
                        .replaceAll("halls/[A-Za-z][A-Za-z_ -]*\\.png", "halls/<named>.png"));
            }
            lines.sort(null);
            for (String l : lines) {
                out.append("  ").append(l).append('\n');
            }
        }
        out.append("total=").append(total).append('\n');
        String label = System.getProperty("b3.label", "unlabelled");
        Path dir = Path.of(originalUserDir, "target", "mut", "detectors");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(label + ".txt"), out.toString(), StandardCharsets.UTF_8);
        System.out.println("[B3DetectorProbe] " + label + ": " + records.size() + " records, " + total + " violations");
    }
}
