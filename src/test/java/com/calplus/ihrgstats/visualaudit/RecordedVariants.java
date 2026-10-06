package com.calplus.ihrgstats.visualaudit;

import com.calplus.ihrgstats.visualaudit.record.DrawRecord;
import com.calplus.ihrgstats.visualaudit.record.DrawRecorder;
import com.calplus.ihrgstats.visualaudit.record.ImageChecks;
import com.calplus.ihrgstats.visualaudit.record.Plants;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * The visual-audit harness's recording side: every exported variant is
 * rendered twice with the draw-call recorder on, its record dumped as JSON,
 * mechanically checked, and kept for the detector calibration. State is
 * static so one Maven run's tests (synthetic, matrix, generator, corpus)
 * accumulate into one report set.
 */
public final class RecordedVariants {

    private RecordedVariants() {}

    static final Map<String, DrawRecord> RECORDS = new LinkedHashMap<>();
    static final Map<String, List<ImageChecks.Violation>> VIOLATIONS = new LinkedHashMap<>();
    static final List<String> DETERMINISM = new ArrayList<>();
    static final List<String> NO_IMAGE = new ArrayList<>();
    static final Map<String, String> MESSAGES = new LinkedHashMap<>();
    private static volatile String lastMessage;
    private static DrawRecorder recorder;

    /** Keeps the command's text reply (for the text-table lint) and returns its image path. */
    static Path keep(com.calplus.ihrgstats.utils.TelegramCommandUtils.CommandResponse response) {
        lastMessage = response == null ? null : response.message;
        return response == null ? null : response.imagePath;
    }

    static void start() {
        recorder = DrawRecorder.enable();
        recorder.drain();
    }

    static void stop() {
        DrawRecorder.disable();
    }

    static synchronized void export(Path exportsDir, String name, Callable<Path> render, String outliers, List<String> coverage) throws Exception {
        recorder.drain();
        lastMessage = null;
        Path first = render.call();
        List<DrawRecord> recs = recorder.drain();
        if (lastMessage != null) MESSAGES.put(name, lastMessage);
        if (first == null) {
            coverage.add(name + ".png -> NO IMAGE PRODUCED (text-only response) | " + outliers);
            NO_IMAGE.add(name + " | " + outliers);
            return;
        }
        if (recs.size() != 1) {
            throw new IllegalStateException(name + ": expected exactly one draw record, got " + recs.size());
        }
        BufferedImage img1 = ImageIO.read(first.toFile());
        Files.copy(first, exportsDir.resolve(name + ".png"), StandardCopyOption.REPLACE_EXISTING);
        DrawRecord r = recs.get(0);
        r.name = name;
        Files.writeString(exportsDir.resolve(name + ".record.json"), r.toJson(), StandardCharsets.UTF_8);
        List<ImageChecks.Violation> vs = ImageChecks.run(r);
        RECORDS.put(name, r);
        VIOLATIONS.put(name, vs);
        coverage.add(name + ".png | " + r.width + "x" + r.height + " | " + ImageChecks.countByCheck(vs) + " | " + outliers);

        // determinism: second render of the same variant
        Path second = render.call();
        List<DrawRecord> recs2 = recorder.drain();
        BufferedImage img2 = ImageIO.read(second.toFile());
        DETERMINISM.add(compare(name, r, img1, recs2.isEmpty() ? null : recs2.get(0), img2));
    }

    /** Export for direct generator calls (no command wrapper). */
    static void exportDirect(Path exportsDir, String name, Callable<Path> render, String outliers, List<String> coverage) throws Exception {
        export(exportsDir, name, render, outliers, coverage);
    }

    static String compare(String name, DrawRecord a, BufferedImage ia, DrawRecord b, BufferedImage ib) {
        if (b == null) return "NONDETERMINISTIC | " + name + " | second render produced no record";
        if (ia.getWidth() != ib.getWidth() || ia.getHeight() != ib.getHeight()) {
            return "NONDETERMINISTIC | " + name + " | size " + ia.getWidth() + "x" + ia.getHeight() + " vs " + ib.getWidth() + "x" + ib.getHeight();
        }
        List<Rectangle> mask = new ArrayList<>();
        for (DrawRecord rec : List.of(a, b)) {
            for (DrawRecord.Text t : rec.texts) {
                if (t.s.startsWith("Generated:") && t.ink != null) {
                    Rectangle m = new Rectangle(t.ink);
                    m.grow(2, 2);
                    mask.add(m);
                }
            }
        }
        long diff = 0;
        for (int y = 0; y < ia.getHeight(); y++) {
            for (int x = 0; x < ia.getWidth(); x++) {
                if (ia.getRGB(x, y) != ib.getRGB(x, y)) {
                    boolean masked = false;
                    for (Rectangle m : mask) if (m.contains(x, y)) { masked = true; break; }
                    if (!masked) diff++;
                }
            }
        }
        List<String> ta = texts(a), tb = texts(b);
        boolean sameRecord = ta.equals(tb) && a.icons.size() == b.icons.size() && a.rects.size() == b.rects.size();
        String verdict = diff == 0 && sameRecord ? "DETERMINISTIC" : "NONDETERMINISTIC";
        return verdict + " | " + name + " | pixels differing outside the timestamp: " + diff
                + " | record texts/icons/rects equal: " + sameRecord + " (" + a.texts.size() + "/" + a.icons.size() + "/" + a.rects.size() + ")";
    }

    private static List<String> texts(DrawRecord r) {
        List<String> out = new ArrayList<>();
        for (DrawRecord.Text t : r.texts) {
            out.add(t.s.startsWith("Generated:") ? "Generated:<masked>" : t.s + "@" + t.x + "," + t.y);
        }
        return out;
    }

    /** Writes violations, summary, determinism and the full plant calibration for everything recorded so far. */
    static synchronized void writeReports(Path dir) throws Exception {
        List<String> all = new ArrayList<>();
        List<String> summary = new ArrayList<>();
        Map<String, Integer> totals = new TreeMap<>();
        for (Map.Entry<String, List<ImageChecks.Violation>> e : VIOLATIONS.entrySet()) {
            DrawRecord r = RECORDS.get(e.getKey());
            Map<String, Integer> c = ImageChecks.countByCheck(e.getValue());
            c.forEach((k, v) -> totals.merge(k, v, Integer::sum));
            summary.add(e.getKey() + " | " + r.generator + " | " + r.width + "x" + r.height + " | " + r.fileBytes + " B | texts "
                    + r.texts.size() + " icons " + r.icons.size() + " rects " + r.rects.size() + " | " + c);
            e.getValue().forEach(v -> all.add(v.toString()));
        }
        summary.add("");
        summary.add("images checked: " + VIOLATIONS.size() + " | text-only variants: " + NO_IMAGE.size() + " | violations by check: " + totals);
        Files.write(dir.resolve("checks_summary.txt"), summary, StandardCharsets.UTF_8);
        Files.write(dir.resolve("checks_violations.txt"), all, StandardCharsets.UTF_8);
        Files.write(dir.resolve("determinism.txt"), DETERMINISM, StandardCharsets.UTF_8);
        Files.write(dir.resolve("text_only_variants.txt"), NO_IMAGE, StandardCharsets.UTF_8);
        writeTextLint(dir);

        List<Plants.Result> results = new ArrayList<>();
        String report = Plants.calibrate(new ArrayList<>(RECORDS.values()), 20261005L, 3, results);
        List<String> lines = new ArrayList<>();
        lines.add("Planted-defect calibration over " + RECORDS.size() + " clean records (thresholds: edge margin "
                + ImageChecks.MIN_EDGE_MARGIN + "px, icon gap " + ImageChecks.MIN_ICON_GAP + "px, cell pad " + ImageChecks.MIN_CELL_PAD + "px)");
        lines.add(report);
        lines.add("");
        lines.addAll(s2Calibration());
        Files.write(dir.resolve("calibration.txt"), lines, StandardCharsets.UTF_8);
        List<String> detail = new ArrayList<>();
        for (Plants.Result res : results) {
            detail.add((res.caught() ? "CAUGHT" : "MISSED") + " | " + res.plant().kind() + " | " + res.plant().image() + " | "
                    + res.plant().where() + " | " + res.evidence());
        }
        Files.write(dir.resolve("calibration_detail.txt"), detail, StandardCharsets.UTF_8);
    }

    /** Lints every kept text reply's fenced tables; writes text_lint.txt (issues + widest lines per variant). */
    static void writeTextLint(Path dir) throws Exception {
        List<String> lines = new ArrayList<>();
        Map<String, Integer> totals = new TreeMap<>();
        List<String> widths = new ArrayList<>();
        for (Map.Entry<String, String> e : MESSAGES.entrySet()) {
            List<com.calplus.ihrgstats.visualaudit.record.TextTableLint.Issue> issues =
                    com.calplus.ihrgstats.visualaudit.record.TextTableLint.lint(e.getKey(), e.getValue());
            issues.forEach(i -> { lines.add(i.toString()); totals.merge(i.check(), 1, Integer::sum); });
            int pre = com.calplus.ihrgstats.visualaudit.record.TextTableLint.maxPreWidth(e.getValue());
            int all = com.calplus.ihrgstats.visualaudit.record.TextTableLint.maxWidth(e.getValue());
            widths.add(String.format(Locale.ROOT, "%-58s widest <pre> line %3d cols, widest line %3d cols%s", e.getKey(), pre, all,
                    pre > com.calplus.ihrgstats.visualaudit.record.TextTableLint.PHONE_COLUMNS ? "  > 32 (phone scrolls)" : ""));
        }
        List<String> out = new ArrayList<>();
        out.add("Text-table lint over " + MESSAGES.size() + " command replies (display columns; wide/emoji = 2, combining = 0). Issues by check: " + totals);
        out.add("");
        out.add("Widest lines per variant (phones show ~" + com.calplus.ihrgstats.visualaudit.record.TextTableLint.PHONE_COLUMNS + " columns):");
        out.addAll(widths);
        out.add("");
        out.add("Issues:");
        out.addAll(lines);
        Files.write(dir.resolve("text_lint.txt"), out, StandardCharsets.UTF_8);
        for (Map.Entry<String, String> e : MESSAGES.entrySet()) {
            Files.writeString(dir.resolve(e.getKey() + ".message.txt"), e.getValue(), StandardCharsets.UTF_8);
        }
    }

    /** The known defect S2 on unmodified code: variants 09 and 12 must be flagged three ways each. */
    static List<String> s2Calibration() {
        List<String> out = new ArrayList<>();
        out.add("S2 calibration on unmodified code (variants 09 and 12):");
        String[][] expectations = {
                {"09_infoplayer_longname", "ALIGN_ROW_SHIFT", "'Round 10", "Stats Per Round: Round 10 row shifted one character"},
                {"09_infoplayer_longname", "ALIGN_DELIMITER", "'Seat:", "Seating header cells not over their value cells"},
                {"09_infoplayer_longname", "ICON_GAP", "text 'Round 10'", "Round 10 label touching its outcome icon"},
                {"12_comparehalls_1v_crescent_allrounds", "ALIGN_ROW_SHIFT", "'Round 10", "Hall Elo: Round 10 row shifted one character"},
                {"12_comparehalls_1v_crescent_allrounds", "ALIGN_DELIMITER", "", "Seating header cells not over their value cells"},
                {"12_comparehalls_1v_crescent_allrounds", "ICON_GAP", "text 'Round 10'", "Round 10 label touching its outcome icon"},
        };
        for (String[] e : expectations) {
            List<ImageChecks.Violation> vs = VIOLATIONS.get(e[0]);
            if (vs == null) {
                out.add("  NOT RENDERED | " + e[0] + " | " + e[3]);
                continue;
            }
            Optional<ImageChecks.Violation> hit = vs.stream()
                    .filter(v -> v.check().equals(e[1]) && v.detail().contains(e[2])).findFirst();
            out.add("  " + (hit.isPresent() ? "CAUGHT" : "MISSED") + " | " + e[0] + " | " + e[3]
                    + (hit.map(v -> " | " + v.detail()).orElse("")));
        }
        return out;
    }
}
