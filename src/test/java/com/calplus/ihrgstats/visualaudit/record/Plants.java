package com.calplus.ihrgstats.visualaudit.record;

import java.awt.Rectangle;
import java.util.*;

/**
 * Detector calibration: plants one known defect into a COPY of a clean
 * record and reports whether {@link ImageChecks} catches it. A plant counts
 * as caught only by a violation of the expected check family that is new
 * (absent from the clean record's violations) and names a planted element.
 * Sites are only chosen where the clean record has no violation of that
 * family already, so a hit is never borrowed from a pre-existing defect.
 */
public final class Plants {

    private Plants() {}

    public static final List<String> KINDS = List.of(
            "ROW_SHIFT_1CHAR", "DROPPED_HEADER_CELL", "EDGE_CLIP_2PX", "LABEL_TOUCHING_ICON",
            "STRETCHED_ICON", "MISSING_GLYPH", "OVERLAPPING_RUNS");

    static final Map<String, String> EXPECTED = Map.of(
            "ROW_SHIFT_1CHAR", "ALIGN_",
            "DROPPED_HEADER_CELL", "ALIGN_",
            "EDGE_CLIP_2PX", "CANVAS_CLIP",
            "LABEL_TOUCHING_ICON", "ICON_GAP",
            "STRETCHED_ICON", "ICON_ASPECT",
            "MISSING_GLYPH", "GLYPH_MISSING",
            "OVERLAPPING_RUNS", "OVERLAP");

    /** One planted copy. */
    public record Plant(String kind, String image, String where, DrawRecord record, Set<Integer> seqs) {}

    /** Outcome of one plant. */
    public record Result(Plant plant, boolean caught, String evidence) {}

    /** Plants up to {@code perKind} sites of every kind into copies of {@code clean}. */
    public static List<Plant> plant(DrawRecord clean, Random rnd, int perKind) {
        List<ImageChecks.Violation> base = ImageChecks.run(clean);
        List<Plant> out = new ArrayList<>();
        for (String kind : KINDS) {
            List<java.util.function.Supplier<Plant>> candidates = candidates(kind, clean, base, rnd);
            Collections.shuffle(candidates, rnd);
            for (java.util.function.Supplier<Plant> c : candidates.subList(0, Math.min(perKind, candidates.size()))) {
                out.add(c.get());
            }
        }
        return out;
    }

    public static Result evaluate(Plant p, DrawRecord clean) {
        Set<String> baseKeys = new HashSet<>();
        for (ImageChecks.Violation v : ImageChecks.run(clean)) baseKeys.add(v.key() + "|" + v.detail());
        String prefix = EXPECTED.get(p.kind());
        for (ImageChecks.Violation v : ImageChecks.run(p.record())) {
            if (!v.check().startsWith(prefix)) continue;
            if (baseKeys.contains(v.key() + "|" + v.detail())) continue;
            if (Collections.disjoint(v.seqs(), p.seqs())) continue;
            return new Result(p, true, v.toString());
        }
        return new Result(p, false, "no new " + prefix + "* violation naming seqs " + p.seqs());
    }

    // =====================================================================

    private static List<java.util.function.Supplier<Plant>> candidates(String kind, DrawRecord clean, List<ImageChecks.Violation> base, Random rnd) {
        ImageChecks.Ctx ctx = new ImageChecks.Ctx(clean);
        String prefix = EXPECTED.get(kind);
        Set<Integer> flagged = new HashSet<>();
        for (ImageChecks.Violation v : base) if (v.check().startsWith(prefix)) flagged.addAll(v.seqs());
        List<java.util.function.Supplier<Plant>> out = new ArrayList<>();
        switch (kind) {
            case "ROW_SHIFT_1CHAR" -> {
                for (List<ImageChecks.Row> block : ctx.blocks()) {
                    if (block.size() < 3 || anyFlagged(block, flagged)) continue;
                    for (ImageChecks.Row row : block.subList(1, block.size())) {
                        if (row.texts.isEmpty()) continue;
                        out.add(() -> {
                            DrawRecord copy = clean.copy();
                            Set<Integer> seqs = new HashSet<>();
                            boolean line = row.texts.size() == 1 && row.icons.isEmpty();
                            for (DrawRecord.Text t : row.texts) {
                                DrawRecord.Text c = text(copy, t.seq);
                                if (line) {
                                    c.s = " " + c.s;                  // one character inserted before the row
                                } else {
                                    c.x += (float) charWidth(c);       // every cell one character to the right
                                }
                                c.remeasure();
                                seqs.add(c.seq);
                            }
                            if (!line) {
                                int dx = (int) Math.round(charWidth(row.texts.get(0)));
                                for (DrawRecord.Icon i : row.icons) {
                                    icon(copy, i.seq).translate(dx, 0);
                                    seqs.add(i.seq);
                                }
                            }
                            return new Plant(kind, clean.name, row.label() + (line ? " (line, +1 leading char)" : " (cells +1 char)"), copy, seqs);
                        });
                    }
                }
            }
            case "DROPPED_HEADER_CELL" -> {
                for (List<ImageChecks.Row> block : ctx.blocks()) {
                    if (block.size() < 3 || anyFlagged(block, flagged)) continue;
                    ImageChecks.Row header = block.get(0);
                    boolean line = block.stream().allMatch(r -> r.texts.size() == 1 && r.icons.isEmpty());
                    if (line) {
                        DrawRecord.Text h = header.texts.get(0);
                        List<double[]> toks = ImageChecks.Ctx.tokens(h);
                        for (double[] tok : toks.subList(Math.min(1, toks.size()), toks.size())) {
                            out.add(() -> {
                                DrawRecord copy = clean.copy();
                                DrawRecord.Text c = text(copy, h.seq);
                                int a = (int) tok[2], b = (int) tok[3];
                                c.s = c.s.substring(0, a) + " ".repeat(b - a) + c.s.substring(b);
                                c.remeasure();
                                Set<Integer> seqs = new HashSet<>();
                                block.forEach(r -> seqs.add(r.texts.get(0).seq));
                                return new Plant(kind, clean.name, "line header '" + h.s.substring(a, b) + "' blanked", copy, seqs);
                            });
                        }
                    } else {
                        boolean hasHeader = header.texts.size() >= 2
                                && header.texts.stream().noneMatch(t -> t.s.chars().anyMatch(Character::isDigit));
                        if (!hasHeader) continue;
                        for (DrawRecord.Text drop : header.texts) {
                            out.add(() -> {
                                DrawRecord copy = clean.copy();
                                copy.texts.removeIf(t -> t.seq == drop.seq);
                                Set<Integer> seqs = new HashSet<>();
                                header.texts.forEach(t -> { if (t.seq != drop.seq) seqs.add(t.seq); });
                                block.get(1).texts.forEach(t -> seqs.add(t.seq));
                                return new Plant(kind, clean.name, "header cell '" + drop.s + "' dropped", copy, seqs);
                            });
                        }
                    }
                }
            }
            case "EDGE_CLIP_2PX" -> {
                for (DrawRecord.Text t : ctx.texts) {
                    if (t.ink == null || flagged.contains(t.seq)) continue;
                    out.add(() -> {
                        DrawRecord copy = clean.copy();
                        DrawRecord.Text c = text(copy, t.seq);
                        int[] d = pushPastNearestEdge(c.ink, clean.width, clean.height);
                        c.x += d[0];
                        c.y += d[1];
                        c.remeasure();
                        return new Plant(kind, clean.name, "text '" + t.s + "' pushed 2px past the " + edgeName(d), copy, Set.of(t.seq));
                    });
                }
                for (DrawRecord.Icon i : ctx.icons) {
                    if (flagged.contains(i.seq)) continue;
                    out.add(() -> {
                        DrawRecord copy = clean.copy();
                        DrawRecord.Icon c = icon(copy, i.seq);
                        int[] d = pushPastNearestEdge(c.dest, clean.width, clean.height);
                        c.translate(d[0], d[1]);
                        return new Plant(kind, clean.name, "icon " + src(i) + " pushed 2px past the " + edgeName(d), copy, Set.of(i.seq));
                    });
                }
            }
            case "LABEL_TOUCHING_ICON" -> {
                for (ImageChecks.Row row : ctx.rows()) {
                    for (DrawRecord.Icon i : row.icons) {
                        if (flagged.contains(i.seq)) continue;
                        DrawRecord.Text found = null;               // nearest text on the icon's left
                        for (DrawRecord.Text t : row.texts) {
                            if (t.ink != null && t.ink.x + t.ink.width <= i.opaque.x
                                    && (found == null || t.ink.x + t.ink.width > found.ink.x + found.ink.width)) found = t;
                        }
                        if (found == null || flagged.contains(found.seq)) continue;
                        DrawRecord.Text left = found;
                        int gap = i.opaque.x - (left.ink.x + left.ink.width);
                        out.add(() -> {
                            DrawRecord copy = clean.copy();
                            icon(copy, i.seq).translate(-gap, 0);  // gap -> 0: the label touches the icon
                            return new Plant(kind, clean.name, "icon moved " + gap + "px left onto '" + left.s + "'", copy, Set.of(i.seq, left.seq));
                        });
                    }
                }
            }
            case "STRETCHED_ICON" -> {
                for (DrawRecord.Icon i : clean.icons) {
                    if (flagged.contains(i.seq) || i.drawW <= 0 || i.transformed) continue;
                    out.add(() -> {
                        DrawRecord copy = clean.copy();
                        DrawRecord.Icon c = icon(copy, i.seq);
                        int w = (int) Math.round(c.drawW * 1.25);
                        c.dest.width += w - c.drawW;
                        c.drawW = w;
                        return new Plant(kind, clean.name, "icon " + src(i) + " width x1.25", copy, Set.of(i.seq));
                    });
                }
            }
            case "MISSING_GLYPH" -> {
                for (DrawRecord.Text t : clean.texts) {
                    if (t.s.isBlank() || flagged.contains(t.seq)) continue;
                    out.add(() -> {
                        DrawRecord copy = clean.copy();
                        DrawRecord.Text c = text(copy, t.seq);
                        int at = c.s.length() / 2;
                        c.s = c.s.substring(0, at) + "க" + c.s.substring(at); // Tamil KA - not in the Noto Latin fonts
                        c.remeasure();
                        return new Plant(kind, clean.name, "'" + t.s + "' + U+0B95", copy, Set.of(t.seq));
                    });
                }
            }
            case "OVERLAPPING_RUNS" -> {
                for (ImageChecks.Row row : ctx.rows()) {
                    List<DrawRecord.Text> ts = new ArrayList<>();
                    for (DrawRecord.Text t : row.texts) if (t.ink != null) ts.add(t);
                    ts.sort(Comparator.comparingInt(t -> t.ink.x));
                    for (int k = 0; k + 1 < ts.size(); k++) {
                        DrawRecord.Text a = ts.get(k), b = ts.get(k + 1);
                        if (flagged.contains(a.seq) || flagged.contains(b.seq)) continue;
                        int gap = b.ink.x - (a.ink.x + a.ink.width);
                        if (gap < 0) continue;
                        out.add(() -> {
                            DrawRecord copy = clean.copy();
                            DrawRecord.Text c = text(copy, b.seq);
                            c.x -= gap + 4;                           // 4 px into its left neighbour
                            c.remeasure();
                            return new Plant(kind, clean.name, "'" + b.s + "' moved " + (gap + 4) + "px onto '" + a.s + "'", copy, Set.of(a.seq, b.seq));
                        });
                    }
                }
            }
            default -> throw new IllegalArgumentException(kind);
        }
        return out;
    }

    private static boolean anyFlagged(List<ImageChecks.Row> block, Set<Integer> flagged) {
        for (ImageChecks.Row r : block) {
            for (DrawRecord.Text t : r.texts) if (flagged.contains(t.seq)) return true;
            for (DrawRecord.Icon i : r.icons) if (flagged.contains(i.seq)) return true;
        }
        return false;
    }

    /** Translation that puts the box 2 px past its nearest canvas edge. */
    private static int[] pushPastNearestEdge(Rectangle b, int w, int h) {
        int left = b.x, top = b.y, right = w - (b.x + b.width), bottom = h - (b.y + b.height);
        int min = Math.min(Math.min(left, right), Math.min(top, bottom));
        if (min == left) return new int[]{-(left + 2), 0};
        if (min == right) return new int[]{right + 2, 0};
        if (min == top) return new int[]{0, -(top + 2)};
        return new int[]{0, bottom + 2};
    }

    private static String edgeName(int[] d) {
        if (d[0] < 0) return "left edge";
        if (d[0] > 0) return "right edge";
        if (d[1] < 0) return "top edge";
        return "bottom edge";
    }

    private static double charWidth(DrawRecord.Text t) {
        return t.font.getStringBounds("M", t.frc).getWidth();
    }

    private static DrawRecord.Text text(DrawRecord r, int seq) {
        for (DrawRecord.Text t : r.texts) if (t.seq == seq) return t;
        throw new IllegalStateException("no text " + seq);
    }

    private static DrawRecord.Icon icon(DrawRecord r, int seq) {
        for (DrawRecord.Icon i : r.icons) if (i.seq == seq) return i;
        throw new IllegalStateException("no icon " + seq);
    }

    private static String src(DrawRecord.Icon i) {
        return i.source != null ? i.source : i.srcW + "x" + i.srcH;
    }

    /** Runs every plant over every record and returns a printable hit-rate report. */
    public static String calibrate(List<DrawRecord> cleanRecords, long seed, int perKind, List<Result> sink) {
        Random rnd = new Random(seed);
        Map<String, int[]> tally = new LinkedHashMap<>();
        Map<String, Integer> eligibleImages = new LinkedHashMap<>();
        for (String k : KINDS) {
            tally.put(k, new int[2]);
            eligibleImages.put(k, 0);
        }
        List<Result> misses = new ArrayList<>();
        for (DrawRecord clean : cleanRecords) {
            Set<String> kindsHere = new HashSet<>();
            for (Plant p : plant(clean, rnd, perKind)) {
                Result res = evaluate(p, clean);
                sink.add(res);
                kindsHere.add(p.kind());
                int[] t = tally.get(p.kind());
                t[0]++;
                if (res.caught()) t[1]++; else misses.add(res);
            }
            kindsHere.forEach(k -> eligibleImages.merge(k, 1, Integer::sum));
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%-22s %8s %8s %8s %10s%n", "plant", "planted", "caught", "hit-rate", "images"));
        for (String k : KINDS) {
            int[] t = tally.get(k);
            sb.append(String.format(Locale.ROOT, "%-22s %8d %8d %7.1f%% %10d%n", k, t[0], t[1],
                    t[0] == 0 ? 0.0 : 100.0 * t[1] / t[0], eligibleImages.get(k)));
        }
        sb.append("records: ").append(cleanRecords.size()).append(", seed ").append(seed).append(", up to ")
                .append(perKind).append(" sites per kind per image\n");
        if (!misses.isEmpty()) {
            sb.append("MISSES:\n");
            for (Result m : misses) {
                sb.append("  ").append(m.plant().kind()).append(" | ").append(m.plant().image()).append(" | ")
                        .append(m.plant().where()).append(" | ").append(m.evidence()).append('\n');
            }
        }
        return sb.toString();
    }
}
