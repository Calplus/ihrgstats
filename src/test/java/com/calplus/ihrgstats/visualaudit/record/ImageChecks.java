package com.calplus.ihrgstats.visualaudit.record;

import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.util.*;

/**
 * Mechanical checks over a {@link DrawRecord}, in pixels of the written PNG
 * with the real font advances the record measured.
 *
 * Thresholds are fixed here, before any image is counted, and are not tuned
 * per image:
 * <ul>
 *   <li>{@link #MIN_EDGE_MARGIN} 8 px - no glyph ink or opaque icon pixel
 *       closer than this to any canvas edge (the generators' own paddings
 *       are 10-20 px).</li>
 *   <li>{@link #MIN_ICON_GAP} 8 px - between a text's ink and the opaque
 *       pixels of an icon beside it on the same row: about half the 14 px
 *       table-font cell; less reads as "touching" at 24 px text.</li>
 *   <li>{@link #MIN_CELL_PAD} 3 px - glyph ink inside its row band, clear of
 *       the band's left/right edge.</li>
 *   <li>Telegram photo limits: file &le; 10 MB, width + height &le; 10000,
 *       aspect ratio &le; 20.</li>
 * </ul>
 * Alignment equality uses 0.5 px (left/right anchors) or 1 px (centre,
 * which integer division may round) tolerance.
 */
public final class ImageChecks {

    public static final int MIN_EDGE_MARGIN = 8;
    public static final int MIN_ICON_GAP = 8;
    public static final int MIN_CELL_PAD = 3;
    public static final long TELEGRAM_MAX_BYTES = 10L * 1024 * 1024;
    public static final int TELEGRAM_MAX_SUM = 10_000;
    public static final double TELEGRAM_MAX_RATIO = 20.0;

    private ImageChecks() {}

    /** One check failure. {@code seqs} are the draw-call sequence numbers involved (for plant attribution). */
    public record Violation(String check, String image, String detail, Set<Integer> seqs) {
        public String key() {
            return check + "|" + new TreeSet<>(seqs);
        }

        @Override
        public String toString() {
            return check + " | " + image + " | " + detail;
        }
    }

    public static List<Violation> run(DrawRecord r) {
        Ctx c = new Ctx(r);
        c.telegramLimits();
        c.canvasBounds();
        c.edgeMargins();
        c.glyphCoverage();
        c.iconAspect();
        c.overlaps();
        c.bands();
        c.alignment();
        c.iconGaps();
        return c.out;
    }

    // =====================================================================

    /** A horizontal row: one or more same-y bands merged (row band + highlight sub-rects). */
    static final class Row {
        int x, y, w, h;
        final List<DrawRecord.Rect> bands = new ArrayList<>();
        final List<DrawRecord.Text> texts = new ArrayList<>();
        final List<DrawRecord.Icon> icons = new ArrayList<>();
        int right() { return x + w; }
        int bottom() { return y + h; }
        String label() {
            StringBuilder sb = new StringBuilder("row y=" + y);
            if (!texts.isEmpty()) {
                String first = texts.get(0).s;
                sb.append(" '").append(first.length() > 24 ? first.substring(0, 24) + "..." : first).append("'");
            }
            return sb.toString();
        }
    }

    static final class Ctx {
        final DrawRecord r;
        final List<Violation> out = new ArrayList<>();
        final List<DrawRecord.Text> texts = new ArrayList<>();   // untransformed
        final List<DrawRecord.Icon> icons = new ArrayList<>();   // untransformed, visibly opaque
        List<Row> rows;

        Ctx(DrawRecord r) {
            this.r = r;
            for (DrawRecord.Text t : r.texts) if (!t.transformed) texts.add(t);
            for (DrawRecord.Icon i : r.icons) if (!i.transformed && !i.translucent && i.opaque != null) icons.add(i);
        }

        void v(String check, String detail, Integer... seqs) {
            out.add(new Violation(check, r.name, detail, new TreeSet<>(Arrays.asList(seqs))));
        }

        void v(String check, String detail, Collection<Integer> seqs) {
            out.add(new Violation(check, r.name, detail, new TreeSet<>(seqs)));
        }

        // --- Telegram photo limits ------------------------------------------
        void telegramLimits() {
            if (r.fileBytes > TELEGRAM_MAX_BYTES) {
                v("TELEGRAM_BYTES", "file " + r.fileBytes + " bytes > " + TELEGRAM_MAX_BYTES);
            }
            if (r.width + r.height > TELEGRAM_MAX_SUM) {
                v("TELEGRAM_DIMENSIONS", "width+height " + (r.width + r.height) + " > " + TELEGRAM_MAX_SUM + " (" + r.width + "x" + r.height + ")");
            }
            double ratio = (double) Math.max(r.width, r.height) / Math.max(1, Math.min(r.width, r.height));
            if (ratio > TELEGRAM_MAX_RATIO) {
                v("TELEGRAM_RATIO", String.format(Locale.ROOT, "aspect ratio %.2f > %.0f (%dx%d)", ratio, TELEGRAM_MAX_RATIO, r.width, r.height));
            }
        }

        // --- nothing outside the canvas ---------------------------------------
        void canvasBounds() {
            Rectangle canvas = new Rectangle(0, 0, r.width, r.height);
            for (DrawRecord.Text t : texts) {
                if (t.ink != null && !canvas.contains(t.ink)) {
                    v("CANVAS_CLIP", "text '" + t.s + "' ink " + box(t.ink) + " outside " + r.width + "x" + r.height
                            + " by " + overhang(t.ink, canvas) + " px", t.seq);
                }
            }
            for (DrawRecord.Icon i : r.icons) {
                if (!i.transformed && !canvas.contains(i.dest)) {
                    v("CANVAS_CLIP", "icon " + src(i) + " dest " + box(i.dest) + " outside " + r.width + "x" + r.height
                            + " by " + overhang(i.dest, canvas) + " px", i.seq);
                }
            }
            for (DrawRecord.Rect x : r.rects) {
                if (!x.transformed && !isBackground(x) && !canvas.contains(x.bounds)) {
                    v("CANVAS_CLIP", "rect " + x.color + " " + box(x.bounds) + " outside " + r.width + "x" + r.height
                            + " by " + overhang(x.bounds, canvas) + " px", x.seq);
                }
            }
        }

        /** Edge-anchored fills (touching >= 2 edges of the uncropped canvas) or structural strips spanning >= half of it. */
        boolean isBackground(DrawRecord.Rect x) {
            Rectangle b = new Rectangle(x.bounds);
            b.translate(r.originX, r.originY);
            int edges = 0;
            if (b.x <= 0) edges++;
            if (b.y <= 0) edges++;
            if (b.x + b.width >= r.canvasWidth) edges++;
            if (b.y + b.height >= r.canvasHeight) edges++;
            return edges >= 2 || b.height >= r.canvasHeight / 2 || b.width >= r.canvasWidth - 1;
        }

        // --- margins to the canvas edges --------------------------------------
        void edgeMargins() {
            for (DrawRecord.Text t : texts) {
                if (t.ink == null) continue;
                String m = marginDetail(t.ink);
                if (m != null) v("EDGE_MARGIN", "text '" + t.s + "' ink " + box(t.ink) + " " + m, t.seq);
            }
            for (DrawRecord.Icon i : icons) {
                String m = marginDetail(i.opaque);
                if (m != null) v("EDGE_MARGIN", "icon " + src(i) + " opaque " + box(i.opaque) + " " + m, i.seq);
            }
        }

        String marginDetail(Rectangle b) {
            int left = b.x, top = b.y, right = r.width - (b.x + b.width), bottom = r.height - (b.y + b.height);
            List<String> bad = new ArrayList<>();
            if (left < MIN_EDGE_MARGIN) bad.add("left " + left);
            if (top < MIN_EDGE_MARGIN) bad.add("top " + top);
            if (right < MIN_EDGE_MARGIN) bad.add("right " + right);
            if (bottom < MIN_EDGE_MARGIN) bad.add("bottom " + bottom);
            return bad.isEmpty() ? null : "margin " + String.join(", ", bad) + " px < " + MIN_EDGE_MARGIN;
        }

        // --- glyph coverage ---------------------------------------------------
        void glyphCoverage() {
            for (DrawRecord.Text t : r.texts) {
                int bad = t.font.canDisplayUpTo(t.s); // recomputed, never trusted from the record
                if (bad != -1) {
                    int cp = t.s.codePointAt(bad);
                    v("GLYPH_MISSING", "'" + t.s + "' in " + t.fontDescription() + ": no glyph for U+"
                            + String.format("%04X", cp) + " '" + new String(Character.toChars(cp)) + "' at index " + bad, t.seq);
                }
            }
        }

        // --- icon aspect ratio ------------------------------------------------
        void iconAspect() {
            Map<String, List<Integer>> grouped = new LinkedHashMap<>();
            for (DrawRecord.Icon i : r.icons) {
                if (i.drawW <= 0 || i.drawH <= 0) continue;
                double src = (double) i.sourceW / i.sourceH;
                double dst = (double) i.drawW / i.drawH;
                if (Math.abs(dst / src - 1.0) > 0.01) {
                    String k = src(i) + " " + i.sourceW + "x" + i.sourceH + " drawn " + i.drawW + "x" + i.drawH
                            + (i.transformed ? " (watermark)" : "")
                            + String.format(Locale.ROOT, " - stretched %.1f%%", (dst / src - 1.0) * 100);
                    grouped.computeIfAbsent(k, x -> new ArrayList<>()).add(i.seq);
                }
            }
            grouped.forEach((k, seqs) -> v("ICON_ASPECT", k + " x" + seqs.size(), seqs));
        }

        // --- overlaps -----------------------------------------------------------
        void overlaps() {
            List<Object> els = new ArrayList<>();
            for (DrawRecord.Text t : texts) if (t.ink != null) els.add(t);
            els.addAll(icons);
            els.sort(Comparator.comparingInt(e -> bounds(e).x));
            for (int a = 0; a < els.size(); a++) {
                Rectangle ra = bounds(els.get(a));
                for (int b = a + 1; b < els.size(); b++) {
                    Rectangle rb = bounds(els.get(b));
                    if (rb.x >= ra.x + ra.width) break;
                    Rectangle in = ra.intersection(rb);
                    if (in.width > 0 && in.height > 0) {
                        v("OVERLAP", describe(els.get(a)) + " overlaps " + describe(els.get(b)) + " by "
                                + in.width + "x" + in.height + " px", seq(els.get(a)), seq(els.get(b)));
                    }
                }
            }
        }

        // --- rows, bands ------------------------------------------------------
        List<Row> rows() {
            if (rows != null) return rows;
            rows = new ArrayList<>();
            List<DrawRecord.Rect> bands = new ArrayList<>();
            for (DrawRecord.Rect x : r.rects) {
                if (!x.transformed && !isBackground(x) && x.bounds.height >= 20 && x.bounds.height <= 40 && x.bounds.width >= 60) {
                    bands.add(x);
                }
            }
            for (DrawRecord.Rect b : bands) {
                Row hit = null;
                for (Row row : rows) {
                    if (row.y == b.bounds.y && row.h == b.bounds.height
                            && b.bounds.x < row.right() && b.bounds.x + b.bounds.width > row.x) {
                        hit = row;
                        break;
                    }
                }
                if (hit == null) {
                    hit = new Row();
                    hit.x = b.bounds.x; hit.y = b.bounds.y; hit.w = b.bounds.width; hit.h = b.bounds.height;
                    rows.add(hit);
                } else {
                    int nx = Math.min(hit.x, b.bounds.x);
                    int nr = Math.max(hit.right(), b.bounds.x + b.bounds.width);
                    hit.x = nx; hit.w = nr - nx;
                }
                hit.bands.add(b);
            }
            rows.sort(Comparator.<Row>comparingInt(x -> x.x).thenComparingInt(x -> x.y));
            // An element belongs to the row its vertical centre is in; among
            // same-height rows side by side (comparison images) the one
            // horizontally nearest - so a cell pushed past its band's edge
            // stays in its row (and is reported by BAND_FIT) instead of
            // silently leaving the row and its column.
            for (DrawRecord.Text t : texts) {
                Row row = nearestRow(t.logical.getCenterX(), t.logical.getCenterY());
                if (row != null) row.texts.add(t);
            }
            for (DrawRecord.Icon i : icons) {
                Row row = nearestRow(i.dest.getCenterX(), i.dest.getCenterY());
                if (row != null) row.icons.add(i);
            }
            for (Row row : rows) {
                row.texts.sort(Comparator.comparingInt(t -> t.seq));
                row.icons.sort(Comparator.comparingInt(i -> i.seq));
            }
            return rows;
        }

        /** Max horizontal distance (px) at which an element outside every band still counts as part of the nearest row. */
        static final int ROW_CAPTURE_PX = 100;

        Row nearestRow(double cx, double cy) {
            Row best = null;
            double bestDist = Double.MAX_VALUE;
            for (Row row : rows) {
                if (cy < row.y || cy >= row.bottom()) continue;
                double d = cx < row.x ? row.x - cx : (cx >= row.right() ? cx - row.right() + 1 : 0);
                if (d < bestDist) {
                    bestDist = d;
                    best = row;
                }
            }
            return bestDist <= ROW_CAPTURE_PX ? best : null;
        }

        /** Rows sharing x/width, stacked contiguously, form a block (one table). */
        List<List<Row>> blocks() {
            List<List<Row>> blocks = new ArrayList<>();
            List<Row> cur = null;
            Row prev = null;
            for (Row row : rows()) {
                if (prev != null && row.x == prev.x && row.w == prev.w && row.y == prev.bottom()) {
                    cur.add(row);
                } else {
                    cur = new ArrayList<>();
                    cur.add(row);
                    blocks.add(cur);
                }
                prev = row;
            }
            return blocks;
        }

        void bands() {
            List<Row> rs = rows();
            Map<Integer, Integer> heights = new TreeMap<>();
            for (Row row : rs) heights.merge(row.h, 1, Integer::sum);
            int modal = heights.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0);
            for (Row row : rs) {
                if (row.h != modal) {
                    v("BAND_HEIGHT", row.label() + " band height " + row.h + " px != modal " + modal + " px",
                            row.bands.stream().map(b -> b.seq).toList());
                }
                for (DrawRecord.Text t : row.texts) {
                    if (t.ink == null) continue;
                    List<String> bad = new ArrayList<>();
                    if (t.ink.y < row.y) bad.add("top " + (t.ink.y - row.y));
                    if (t.ink.y + t.ink.height > row.bottom()) bad.add("bottom +" + (t.ink.y + t.ink.height - row.bottom()));
                    if (t.ink.x < row.x + MIN_CELL_PAD) bad.add("left pad " + (t.ink.x - row.x));
                    if (t.ink.x + t.ink.width > row.right() - MIN_CELL_PAD) bad.add("right pad " + (row.right() - t.ink.x - t.ink.width));
                    if (!bad.isEmpty()) {
                        v("BAND_FIT", "text '" + t.s + "' ink " + box(t.ink) + " vs band " + box(new Rectangle(row.x, row.y, row.w, row.h))
                                + ": " + String.join(", ", bad) + " px (min pad " + MIN_CELL_PAD + ")", t.seq);
                    }
                }
                for (DrawRecord.Icon i : row.icons) {
                    if (i.opaque.y < row.y || i.opaque.y + i.opaque.height > row.bottom()
                            || i.opaque.x < row.x || i.opaque.x + i.opaque.width > row.right()) {
                        v("BAND_FIT", "icon " + src(i) + " opaque " + box(i.opaque) + " outside band "
                                + box(new Rectangle(row.x, row.y, row.w, row.h)), i.seq);
                    }
                }
            }
            for (int a = 0; a < rs.size(); a++) {
                for (int b = a + 1; b < rs.size(); b++) {
                    Row x = rs.get(a), y = rs.get(b);
                    boolean hOverlap = x.x < y.right() && y.x < x.right();
                    boolean vOverlap = x.y < y.bottom() && y.y < x.bottom();
                    if (hOverlap && vOverlap && x.y != y.y) {
                        v("BAND_OVERLAP", x.label() + " and " + y.label() + " overlap vertically",
                                x.bands.get(0).seq, y.bands.get(0).seq);
                    }
                }
            }
        }

        // --- alignment ----------------------------------------------------------
        void alignment() {
            for (List<Row> block : blocks()) {
                if (block.size() < 2) continue;
                boolean allLines = true;
                for (Row row : block) {
                    if (!(row.texts.size() == 1 && row.icons.isEmpty())) {
                        allLines = false;
                        break;
                    }
                }
                if (allLines) {
                    lineBlock(block);
                } else {
                    cellBlock(block);
                }
            }
        }

        /** Single-run rows whose columns live inside one monospaced string. */
        void lineBlock(List<Row> block) {
            List<DrawRecord.Text> lines = new ArrayList<>();
            for (Row row : block) lines.add(row.texts.get(0));
            // (1) delimiters '|' at the same pixel x in every row
            List<List<Double>> delims = new ArrayList<>();
            boolean anyDelim = false;
            for (DrawRecord.Text t : lines) {
                List<Double> d = new ArrayList<>();
                for (int i = 0; i < t.s.length(); i++) {
                    if (t.s.charAt(i) == '|') d.add(t.x + adv(t, i));
                }
                anyDelim |= !d.isEmpty();
                delims.add(d);
            }
            if (anyDelim) {
                List<Double> ref = delims.get(0);
                for (int k = 1; k < lines.size(); k++) {
                    List<Double> d = delims.get(k);
                    if (d.size() != ref.size()) {
                        v("ALIGN_DELIMITER", "'" + clip(lines.get(k).s) + "' has " + d.size() + " '|' vs " + ref.size()
                                + " in '" + clip(lines.get(0).s) + "'", lines.get(0).seq, lines.get(k).seq);
                    }
                    int n = Math.min(d.size(), ref.size());
                    int bad = 0;
                    double worst = 0;
                    int firstBad = -1;
                    for (int j = 0; j < n; j++) {
                        double delta = d.get(j) - ref.get(j);
                        if (Math.abs(delta) > 0.5) {
                            bad++;
                            if (firstBad < 0) firstBad = j;
                            if (Math.abs(delta) > Math.abs(worst)) worst = delta;
                        }
                    }
                    if (bad > 0) {
                        v("ALIGN_DELIMITER", bad + " of " + n + " '|' delimiters of '" + clip(lines.get(k).s) + "' not under those of '"
                                + clip(lines.get(0).s) + "' (first at #" + (firstBad + 1) + ", worst " + px(worst) + " = "
                                + chars(worst, lines.get(k)) + ")", lines.get(0).seq, lines.get(k).seq);
                    }
                }
            }
            // (2) every header column start has a cell starting under it (or blank under it)
            DrawRecord.Text header = lines.get(0);
            List<double[]> headerTokens = tokens(header);
            for (int k = 1; k < lines.size(); k++) {
                DrawRecord.Text row = lines.get(k);
                List<double[]> rowTokens = tokens(row);
                List<String> bad = new ArrayList<>();
                for (double[] ht : headerTokens) {
                    boolean starts = false;
                    boolean blank = true;
                    for (double[] rt : rowTokens) {
                        if (Math.abs(rt[0] - ht[0]) <= 0.5) starts = true;
                        if (rt[0] < ht[1] && rt[1] > ht[0]) blank = false;
                    }
                    if (!starts && !blank) {
                        double nearest = nearestStart(rowTokens, ht[0]);
                        bad.add("'" + header.s.substring((int) ht[2], (int) ht[3]) + "'@" + px(ht[0]) + " (cell starts at "
                                + px(nearest) + ", " + px(nearest - ht[0]) + ")");
                    }
                }
                if (!bad.isEmpty()) {
                    v("ALIGN_HEADER", "'" + clip(row.s) + "' cells not under header columns: " + String.join("; ", bad),
                            header.seq, row.seq);
                }
            }
            // (3) a column every data row starts at must have a header column there
            if (lines.size() >= 3) {
                Set<Long> common = null;
                for (int k = 1; k < lines.size(); k++) {
                    Set<Long> starts = new TreeSet<>();
                    for (double[] rt : tokens(lines.get(k))) starts.add(Math.round(rt[0] * 2));
                    if (common == null) common = starts; else common.retainAll(starts);
                }
                Set<Long> headerStarts = new TreeSet<>();
                for (double[] ht : headerTokens) headerStarts.add(Math.round(ht[0] * 2));
                List<String> orphan = new ArrayList<>();
                for (Long s : common) {
                    if (!headerStarts.contains(s)) orphan.add(px(s / 2.0));
                }
                if (!orphan.isEmpty()) {
                    List<Integer> seqs = new ArrayList<>();
                    lines.forEach(t -> seqs.add(t.seq));
                    v("ALIGN_HEADER_MISSING", "data column(s) at x=" + String.join(", ", orphan) + " have no header cell in '"
                            + clip(header.s) + "'", seqs);
                }
            }
            // (4) data rows against each other: k-th cell start or end must match the column's mode
            List<DrawRecord.Text> data = lines.subList(1, lines.size());
            if (data.size() >= 2) {
                Map<Integer, List<DrawRecord.Text>> byCount = new TreeMap<>();
                for (DrawRecord.Text t : data) byCount.computeIfAbsent(tokens(t).size(), x -> new ArrayList<>()).add(t);
                for (List<DrawRecord.Text> group : byCount.values()) {
                    if (group.size() < 2) continue;
                    int n = tokens(group.get(0)).size();
                    double[] ms = new double[n], me = new double[n];
                    List<List<double[]>> toks = new ArrayList<>();
                    for (DrawRecord.Text t : group) toks.add(tokens(t));
                    boolean[] usable = new boolean[n];
                    for (int col = 0; col < n; col++) {
                        List<Double> starts = new ArrayList<>(), ends = new ArrayList<>();
                        for (List<double[]> tk : toks) { starts.add(tk.get(col)[0]); ends.add(tk.get(col)[1]); }
                        ms[col] = mode(starts);
                        me[col] = mode(ends);
                        usable[col] = count(starts, ms[col]) >= 2 || count(ends, me[col]) >= 2;
                    }
                    for (int i = 0; i < group.size(); i++) {
                        for (int col = 0; col < n; col++) {
                            double[] tok = toks.get(i).get(col);
                            boolean sOk = Math.abs(tok[0] - ms[col]) <= 0.5;
                            boolean eOk = Math.abs(tok[1] - me[col]) <= 0.5;
                            if (usable[col] && !sOk && !eOk) {
                                double d = tok[0] - ms[col];
                                v("ALIGN_ROW_SHIFT", "'" + clip(group.get(i).s) + "' cell #" + (col + 1) + " shifted " + px(d)
                                        + " (" + chars(d, group.get(i)) + ") vs the other rows", group.get(i).seq);
                                break;
                            }
                        }
                    }
                }
            }
        }

        /** Rows of separately drawn cells (rank tables, victory records). */
        void cellBlock(List<Row> block) {
            Row first = block.get(0);
            boolean hasHeader = block.size() >= 3 && !first.texts.isEmpty()
                    && first.texts.stream().noneMatch(t -> t.s.chars().anyMatch(Character::isDigit));
            List<Row> data = hasHeader ? block.subList(1, block.size()) : block;
            Map<Integer, Integer> counts = new TreeMap<>();
            for (Row row : data) counts.merge(row.texts.size(), 1, Integer::sum);
            int modal = counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0);
            if (hasHeader && first.texts.size() != modal) {
                v("ALIGN_HEADER_CELLS", first.label() + " header has " + first.texts.size() + " cells, its rows have " + modal,
                        first.texts.stream().map(t -> t.seq).toList());
            }
            Map<Integer, List<Row>> byCount = new TreeMap<>();
            for (Row row : block) byCount.computeIfAbsent(row.texts.size(), x -> new ArrayList<>()).add(row);
            for (List<Row> group : byCount.values()) {
                if (group.size() < 2) continue;
                int n = group.get(0).texts.size();
                for (int col = 0; col < n; col++) {
                    List<DrawRecord.Text> cells = new ArrayList<>();
                    for (Row row : group) cells.add(row.texts.get(col));
                    checkColumn(cells, "cell #" + (col + 1));
                }
            }
            // The first cell (rank, round label, row label) is one column across
            // EVERY row of the block, whatever the row's cell count - covers
            // rows that are the only one of their shape (e.g. an "-NA-" row).
            List<DrawRecord.Text> firstCells = new ArrayList<>();
            for (Row row : block) if (!row.texts.isEmpty()) firstCells.add(row.texts.get(0));
            if (byCount.size() > 1 && firstCells.size() >= 2) {
                int before = out.size();
                checkColumn(firstCells, "first cell");
                // keep only reports about rows the per-shape pass could not compare
                Set<Integer> comparable = new HashSet<>();
                for (List<Row> group : byCount.values()) {
                    if (group.size() >= 2) group.forEach(r -> { if (!r.texts.isEmpty()) comparable.add(r.texts.get(0).seq); });
                }
                out.subList(before, out.size()).removeIf(v -> comparable.containsAll(v.seqs()));
            }
            Map<Integer, List<Row>> byIcons = new TreeMap<>();
            for (Row row : block) if (!row.icons.isEmpty()) byIcons.computeIfAbsent(row.icons.size(), x -> new ArrayList<>()).add(row);
            for (List<Row> group : byIcons.values()) {
                if (group.size() < 2) continue;
                for (int k = 0; k < group.get(0).icons.size(); k++) {
                    List<Double> xs = new ArrayList<>();
                    for (Row row : group) xs.add((double) row.icons.get(k).dest.x);
                    double m = mode(xs);
                    for (int i = 0; i < group.size(); i++) {
                        if (Math.abs(xs.get(i) - m) > 0.5) {
                            DrawRecord.Icon ic = group.get(i).icons.get(k);
                            v("ALIGN_ICON", group.get(i).label() + " icon #" + (k + 1) + " at x=" + ic.dest.x + " vs column x="
                                    + px(m) + " (" + px(xs.get(i) - m) + ")", ic.seq);
                        }
                    }
                }
            }
        }

        void checkColumn(List<DrawRecord.Text> cells, String what) {
            List<Double> lefts = new ArrayList<>(), rights = new ArrayList<>(), centers = new ArrayList<>();
            for (DrawRecord.Text t : cells) {
                lefts.add((double) t.x);
                rights.add(t.x + t.advance);
                centers.add(t.x + t.advance / 2);
            }
            double ml = mode(lefts), mr = mode(rights), mc = mode(centers);
            int al = countWithin(lefts, ml, 0.5), ar = countWithin(rights, mr, 0.5), ac = countWithin(centers, mc, 1.0);
            if (al == cells.size() || ar == cells.size() || ac == cells.size()) return;
            // anchor = the one most cells agree on; report the cells off it
            String anchor;
            List<Double> vals;
            double m;
            double tol;
            if (al >= ar && al >= ac) { anchor = "left"; vals = lefts; m = ml; tol = 0.5; }
            else if (ar >= ac) { anchor = "right"; vals = rights; m = mr; tol = 0.5; }
            else { anchor = "centre"; vals = centers; m = mc; tol = 1.0; }
            for (int i = 0; i < cells.size(); i++) {
                if (Math.abs(vals.get(i) - m) > tol) {
                    DrawRecord.Text t = cells.get(i);
                    v("ALIGN_COLUMN", what + " '" + clip(t.s) + "' " + anchor + " edge at " + px(vals.get(i)) + " vs column "
                            + px(m) + " (" + px(vals.get(i) - m) + " = " + chars(vals.get(i) - m, t) + ")", t.seq);
                }
            }
        }

        // --- label-to-icon gaps --------------------------------------------------
        /**
         * For every icon in a row: the text whose ink ends nearest before the
         * icon's opaque left edge (among texts starting left of it), and the
         * text whose ink starts nearest after its right edge (among texts
         * ending right of it). Nearest by edge, not by start position, so a
         * long run overlapping its neighbours is still compared with the icon.
         * A negative gap means the text runs into the icon.
         */
        void iconGaps() {
            for (Row row : rows()) {
                for (DrawRecord.Icon icon : row.icons) {
                    Rectangle ic = icon.opaque;
                    DrawRecord.Text left = null, right = null;
                    for (DrawRecord.Text t : row.texts) {
                        if (t.ink == null) continue;
                        if (t.ink.x < ic.x && (left == null || t.ink.x + t.ink.width > left.ink.x + left.ink.width)) left = t;
                        if (t.ink.x + t.ink.width > ic.x + ic.width && (right == null || t.ink.x < right.ink.x)) right = t;
                    }
                    if (left != null) {
                        int gap = ic.x - (left.ink.x + left.ink.width);
                        if (gap < MIN_ICON_GAP) {
                            v("ICON_GAP", describe(left) + " -> " + describe(icon) + " gap " + gap + " px < " + MIN_ICON_GAP, left.seq, icon.seq);
                        }
                    }
                    if (right != null) {
                        int gap = right.ink.x - (ic.x + ic.width);
                        if (gap < MIN_ICON_GAP) {
                            v("ICON_GAP", describe(icon) + " -> " + describe(right) + " gap " + gap + " px < " + MIN_ICON_GAP, icon.seq, right.seq);
                        }
                    }
                }
            }
        }

        // --- helpers ------------------------------------------------------------
        /** Pixel advance of the first {@code chars} characters of the run, with the run's real font. */
        static double adv(DrawRecord.Text t, int chars) {
            if (chars <= 0) return 0;
            return t.font.getStringBounds(t.s.substring(0, chars), t.frc).getWidth();
        }

        /** Tokens = maximal runs of characters other than space and '|': [startPx, endPx, startIdx, endIdx]. */
        static List<double[]> tokens(DrawRecord.Text t) {
            List<double[]> out = new ArrayList<>();
            String s = t.s;
            int i = 0;
            while (i < s.length()) {
                while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '|')) i++;
                if (i >= s.length()) break;
                int j = i;
                while (j < s.length() && s.charAt(j) != ' ' && s.charAt(j) != '|') j++;
                out.add(new double[]{t.x + adv(t, i), t.x + adv(t, j), i, j});
                i = j;
            }
            return out;
        }

        static double nearestStart(List<double[]> toks, double x) {
            double best = Double.NaN;
            for (double[] t : toks) if (Double.isNaN(best) || Math.abs(t[0] - x) < Math.abs(best - x)) best = t[0];
            return best;
        }

        static double mode(List<Double> xs) {
            Map<Long, Integer> c = new HashMap<>();
            for (double x : xs) c.merge(Math.round(x * 2), 1, Integer::sum);
            long best = c.entrySet().stream().max(Map.Entry.<Long, Integer>comparingByValue()
                    .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder()))).get().getKey();
            return best / 2.0;
        }

        static int count(List<Double> xs, double m) {
            return countWithin(xs, m, 0.5);
        }

        static int countWithin(List<Double> xs, double m, double tol) {
            int n = 0;
            for (double x : xs) if (Math.abs(x - m) <= tol) n++;
            return n;
        }

        static String chars(double px, DrawRecord.Text t) {
            double cw = t.font.getStringBounds("M", t.frc).getWidth();
            return String.format(Locale.ROOT, "%+.1f char", px / cw);
        }

        static Rectangle bounds(Object e) {
            return e instanceof DrawRecord.Text t ? t.ink : ((DrawRecord.Icon) e).opaque;
        }

        static int seq(Object e) {
            return e instanceof DrawRecord.Text t ? t.seq : ((DrawRecord.Icon) e).seq;
        }

        static String describe(Object e) {
            if (e instanceof DrawRecord.Text t) return "text '" + clip(t.s) + "' " + box(t.ink);
            DrawRecord.Icon i = (DrawRecord.Icon) e;
            return "icon " + src(i) + " " + box(i.opaque);
        }

        static String src(DrawRecord.Icon i) {
            return i.source != null ? i.source : "image " + i.srcW + "x" + i.srcH;
        }

        static String clip(String s) {
            return s.length() > 40 ? s.substring(0, 40) + "..." : s;
        }

        static String box(Rectangle2D b) {
            if (b == null) return "[]";
            return "[" + DrawRecord.num(b.getX()) + "," + DrawRecord.num(b.getY()) + " " + DrawRecord.num(b.getWidth()) + "x" + DrawRecord.num(b.getHeight()) + "]";
        }

        static String px(double d) {
            return (d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.1f", d)) + "px";
        }

        static int overhang(Rectangle b, Rectangle canvas) {
            int o = 0;
            o = Math.max(o, canvas.x - b.x);
            o = Math.max(o, canvas.y - b.y);
            o = Math.max(o, b.x + b.width - canvas.width);
            o = Math.max(o, b.y + b.height - canvas.height);
            return o;
        }
    }

    /** Summary line per check family, for reports. */
    public static Map<String, Integer> countByCheck(List<Violation> vs) {
        Map<String, Integer> m = new TreeMap<>();
        for (Violation v : vs) m.merge(v.check(), 1, Integer::sum);
        return m;
    }

}
