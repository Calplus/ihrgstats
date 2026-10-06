package com.calplus.ihrgstats.visualaudit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Independent answer sheets for the visual-audit variants: a recount of the
 * round CSVs the harness fed in (the synthetic and matrix CSVs it wrote, and
 * the committed fictional corpus), with its own CSV parser and its own
 * outcome rules - nothing here reads the database or calls the code the
 * renderer uses. What it can answer: boards, opponents, raw scores, results,
 * W-D-L, seats (board order within the hall) and average seat, hall board
 * records and cumulative board wins. What it cannot: Elo, rank order, ExpElo,
 * win probabilities (model outputs) - the sheets say so.
 *
 * Run after the harness: -Dvisual.answers=<output folder>
 */
@EnabledIfSystemProperty(named = "visual.answers", matches = ".+")
public class AnswerSheets {

    record Board(int year, int round, int index, String n1, String h1, String s1, String n2, String h2, String s2) {
        boolean walkover1() { return n1.equalsIgnoreCase("WALKOVER"); }
        boolean walkover2() { return n2.equalsIgnoreCase("WALKOVER"); }

        /** +1 side 1 wins, -1 side 2 wins, 0 draw - from the CSV alone. */
        int result() {
            if (walkover1()) return -1;
            if (walkover2()) return 1;
            if (s1.equalsIgnoreCase("TIMEOUT")) return -1;
            if (s2.equalsIgnoreCase("TIMEOUT")) return 1;
            int c = Double.compare(Double.parseDouble(s1), Double.parseDouble(s2));
            return Integer.signum(c);
        }

        String score(boolean side1) {
            String a = side1 ? s1 : s2, b = side1 ? s2 : s1;
            if (walkover1() || walkover2()) return "walkover";
            return (a.isEmpty() ? "(blank)" : a) + "-" + (b.isEmpty() ? "(blank)" : b);
        }

        String hall(boolean side1) {
            String h = side1 ? h1 : h2;
            return h.isEmpty() ? "(blank hall)" : h;
        }
    }

    /** Quoted-field CSV line split (independent of CsvLineParser). */
    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (q && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else q = !q;
            } else if (c == ',' && !q) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString().trim());
        return out;
    }

    static List<Board> read(Path csv, int year, int round, Map<String, String> aliases) throws Exception {
        List<Board> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        int idx = 0;
        for (String l : lines.subList(1, lines.size())) {
            if (l.isBlank()) continue;
            List<String> f = split(l.replace("﻿", ""));
            while (f.size() < 6) f.add("");
            out.add(new Board(year, round, idx++, alias(f.get(0), aliases), f.get(1), f.get(2), alias(f.get(3), aliases), f.get(4), f.get(5)));
        }
        return out;
    }

    static String alias(String name, Map<String, String> aliases) {
        return aliases.getOrDefault(name, name);
    }

    /** One player's appearances: [board, side1?]. */
    static List<Object[]> appearances(List<Board> boards, String player) {
        List<Object[]> out = new ArrayList<>();
        for (Board b : boards) {
            if (b.n1().equals(player)) out.add(new Object[]{b, true});
            else if (b.n2().equals(player)) out.add(new Object[]{b, false});
        }
        return out;
    }

    /** Seat = 1-based position of this board among the boards of the player's hall in that round (CSV order). */
    static int seat(List<Board> boards, Board b, boolean side1) {
        String hall = side1 ? b.h1() : b.h2();
        int seat = 0;
        for (Board o : boards) {
            if (o.year() != b.year() || o.round() != b.round()) continue;
            if (o.h1().equalsIgnoreCase(hall) && !o.walkover1() || o.h2().equalsIgnoreCase(hall) && !o.walkover2()) seat++;
            if (o == b) return seat;
        }
        return seat;
    }

    static String playerSheet(List<Board> all, String player, Integer onlyRound, Map<Integer, String> labels) {
        StringBuilder sb = new StringBuilder("### Player: " + player + (onlyRound == null ? " (all rounds)" : " (up to round " + onlyRound + ")") + "\n\n");
        sb.append("| round | seat | hall | opponent | opp hall | score (own-opp, raw CSV) | result |\n|---|---|---|---|---|---|---|\n");
        int w = 0, d = 0, l = 0, seatSum = 0, n = 0;
        for (Object[] a : appearances(all, player)) {
            Board b = (Board) a[0];
            boolean s1 = (Boolean) a[1];
            if (onlyRound != null && b.round() > onlyRound) continue;
            int r = s1 ? b.result() : -b.result();
            if (r > 0) w++; else if (r < 0) l++; else d++;
            int seat = seat(all, b, s1);
            seatSum += seat;
            n++;
            sb.append("| ").append(b.year()).append(" ").append(labels.getOrDefault(b.round(), "Round " + b.round())).append(" | ").append(seat)
              .append(" | ").append(b.hall(s1)).append(" | ").append(s1 ? b.n2() : b.n1()).append(" | ").append(b.hall(!s1))
              .append(" | ").append(b.score(s1)).append(" | ").append(r > 0 ? "W" : r < 0 ? "L" : "D").append(" |\n");
        }
        sb.append("\nTotals: ").append(w).append(" W, ").append(d).append(" D, ").append(l).append(" L over ").append(n).append(" boards");
        if (n > 0) sb.append(String.format(Locale.ROOT, "; average seat %.1f", (double) seatSum / n));
        return sb.append("\n\n").toString();
    }

    static String hallSheet(List<Board> all, String hall, Integer onlyRound, Map<Integer, String> labels) {
        StringBuilder sb = new StringBuilder("### Hall: " + hall + (onlyRound == null ? " (all rounds)" : " (round " + onlyRound + " only)") + "\n\n");
        sb.append("| round | opponent hall(s) (boards) | boards W-D-L | score sum own-opp |\n|---|---|---|---|\n");
        Map<String, double[]> roster = new TreeMap<>(); // name -> [seatSum, boards]
        int tw = 0, td = 0, tl = 0;
        SortedMap<Integer, List<Object[]>> byRound = new TreeMap<>();
        for (Board b : all) {
            boolean in1 = b.h1().equalsIgnoreCase(hall) && !b.walkover1(), in2 = b.h2().equalsIgnoreCase(hall) && !b.walkover2();
            if (!in1 && !in2) continue;
            if (onlyRound != null && b.round() != onlyRound) continue;
            byRound.computeIfAbsent(b.year() * 1000 + b.round(), k -> new ArrayList<>()).add(new Object[]{b, in1});
        }
        for (Map.Entry<Integer, List<Object[]>> e : byRound.entrySet()) {
            int w = 0, d = 0, l = 0;
            double own = 0, opp = 0;
            Map<String, Integer> opps = new TreeMap<>();
            for (Object[] a : e.getValue()) {
                Board b = (Board) a[0];
                boolean s1 = (Boolean) a[1];
                int r = s1 ? b.result() : -b.result();
                if (r > 0) w++; else if (r < 0) l++; else d++;
                opps.merge((s1 ? b.walkover2() : b.walkover1()) ? "WALKOVER" : b.hall(!s1), 1, Integer::sum);
                own += num(s1 ? b.s1() : b.s2());
                opp += num(s1 ? b.s2() : b.s1());
                String name = s1 ? b.n1() : b.n2();
                double[] rs = roster.computeIfAbsent(name, k -> new double[2]);
                rs[0] += seat(all, b, s1);
                rs[1]++;
            }
            tw += w; td += d; tl += l;
            Board first = (Board) e.getValue().get(0)[0];
            sb.append("| ").append(first.year()).append(" ").append(labels.getOrDefault(first.round(), "Round " + first.round())).append(" | ")
              .append(opps).append(" | ").append(w).append("-").append(d).append("-").append(l).append(" | ")
              .append(fmt(own)).append("-").append(fmt(opp)).append(" (walkover/TIMEOUT cells count 0) |\n");
        }
        sb.append("\nBoards total: ").append(tw).append(" W, ").append(td).append(" D, ").append(tl).append(" L\n\n");
        sb.append("Roster seen (average seat = mean board position within the hall, seating grid 'Avg'):\n\n");
        roster.forEach((name, rs) -> sb.append(String.format(Locale.ROOT, "- %s: %d boards, avg seat %.1f%n", name, (int) rs[1], rs[0] / rs[1])));
        return sb.append("\n").toString();
    }

    static String roundSheet(List<Board> all, int year, int round, Map<Integer, String> labels) {
        StringBuilder sb = new StringBuilder("### Round " + year + " " + labels.getOrDefault(round, "Round " + round) + ": boards\n\n");
        sb.append("| # | side 1 (hall) | score | side 2 (hall) | winner |\n|---|---|---|---|---|\n");
        Map<String, int[]> cum = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Board b : all) {
            if (b.year() != year) continue;
            int r = b.result();
            if (b.round() <= round) {
                if (!b.walkover1()) cum.computeIfAbsent(b.hall(true), k -> new int[3])[r > 0 ? 0 : r == 0 ? 1 : 2]++;
                if (!b.walkover2()) cum.computeIfAbsent(b.hall(false), k -> new int[3])[r < 0 ? 0 : r == 0 ? 1 : 2]++;
            }
            if (b.round() != round) continue;
            sb.append("| ").append(b.index() + 1).append(" | ").append(b.n1()).append(" (").append(b.hall(true)).append(") | ")
              .append(b.score(true)).append(" | ").append(b.n2()).append(" (").append(b.hall(false)).append(") | ")
              .append(r > 0 ? b.n1() : r < 0 ? b.n2() : "draw").append(" |\n");
        }
        sb.append("\nCumulative board record per hall up to this round (W-D-L; the image's 'BoardWins' column may weight draws):\n\n");
        cum.forEach((h, c) -> sb.append("- ").append(h).append(": ").append(c[0]).append("-").append(c[1]).append("-").append(c[2]).append("\n"));
        return sb.append("\n").toString();
    }

    static double num(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return 0; }
    }

    static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.2f", d);
    }

    static final String NOT_RECOUNTED = "_Not recounted (model outputs): Elo/TrueElo, rank order, deltas, ExpElo, win probabilities, LR column._\n\n";

    @Test
    void writeAnswerSheets() throws Exception {
        Path w = Paths.get(System.getProperty("user.dir"));
        Path out = Paths.get(System.getProperty("visual.answers"));
        Files.createDirectories(out);
        List<String> index = new ArrayList<>();
        index.add("# Independent answer sheets\n");
        index.add("Recounted from the round CSVs with a separate parser (AnswerSheets.java); nothing read from the database.\n");

        // --- synthetic -------------------------------------------------------
        Path synth = w.resolve("temp/visual-audit/synthetic/csv");
        List<Board> s = new ArrayList<>();
        for (int r = 1; r <= 10; r++) s.addAll(read(synth.resolve("round_" + r + ".csv"), 2026, r, Map.of()));
        Map<Integer, String> noLabels = Map.of();
        Map<String, String> sheets = new LinkedHashMap<>();
        StringBuilder rank = new StringBuilder(NOT_RECOUNTED + "Season W-D-L per player (2026):\n\n");
        seasonTable(s, rank);
        sheets.put("01_rankplayers_allrounds", rank.toString());
        sheets.put("02_rankplayers_round6", NOT_RECOUNTED + playerSeason(s, 6));
        sheets.put("03_rankhalls_allrounds", NOT_RECOUNTED + hallSeason(s, null));
        sheets.put("04_rankhalls_round7", NOT_RECOUNTED + hallSeason(s, 7));
        sheets.put("06_infohall_binjai_allrounds", NOT_RECOUNTED + hallSheet(s, "Binjai", null, noLabels));
        sheets.put("07_infohall_mysteryville_allrounds", NOT_RECOUNTED + hallSheet(s, "Mysteryville", null, noLabels));
        sheets.put("08_infohall_hall1_round7", NOT_RECOUNTED + hallSheet(s, "1", 7, noLabels));
        sheets.put("09_infoplayer_longname", NOT_RECOUNTED + playerSheet(s, "Zara Zephyrine Quintessa Blackwood-Ashford", null, noLabels));
        sheets.put("10_infoplayer_capped_dormant", NOT_RECOUNTED + playerSheet(s, "Kai", null, noLabels));
        sheets.put("11_infoplayer_draw_timeout_walkover", NOT_RECOUNTED + playerSheet(s, "Ravi Kumar", null, noLabels));
        sheets.put("12_comparehalls_1v_crescent_allrounds", NOT_RECOUNTED + hallSheet(s, "1", null, noLabels) + hallSheet(s, "Crescent", null, noLabels));
        sheets.put("13_comparehalls_1v_mysteryville_round6", NOT_RECOUNTED + hallSheet(s, "1", 6, noLabels) + hallSheet(s, "Mysteryville", 6, noLabels));
        sheets.put("15_compareplayers_bartholomew_v_kai_allrounds", NOT_RECOUNTED + playerSheet(s, "Bartholomew Alexander Krieger", null, noLabels) + playerSheet(s, "Kai", null, noLabels));
        sheets.put("16_compareplayers_bartholomew_v_kai_round4", NOT_RECOUNTED + playerSheet(s, "Bartholomew Alexander Krieger", 4, noLabels) + playerSheet(s, "Kai", 4, noLabels));
        sheets.put("17_compareplayers_zara_v_ng_allrounds", NOT_RECOUNTED + playerSheet(s, "Zara Zephyrine Quintessa Blackwood-Ashford", null, noLabels) + playerSheet(s, "Ng", null, noLabels));
        for (int[] rv : new int[][]{{18, 4}, {19, 5}, {20, 6}, {21, 9}}) {
            String name = switch (rv[0]) {
                case 18 -> "18_infomatch_round4_draw";
                case 19 -> "19_infomatch_round5_walkover";
                case 20 -> "20_infomatch_round6_timeout";
                default -> "21_infomatch_round9_walkover_name1";
            };
            sheets.put(name, NOT_RECOUNTED + roundSheet(s, 2026, rv[1], noLabels));
        }
        sheets.put("22_infomatchhall_hall1_round7", NOT_RECOUNTED + roundSheet(s, 2026, 7, noLabels) + hallSheet(s, "1", 7, noLabels));
        sheets.put("24_infomatchhall_binjai_round6", NOT_RECOUNTED + roundSheet(s, 2026, 6, noLabels) + hallSheet(s, "Binjai", 6, noLabels));

        // --- matrix ----------------------------------------------------------
        Path mdir = w.resolve("temp/visual-audit/matrix/csv");
        List<Board> m = new ArrayList<>();
        if (Files.exists(mdir)) {
            m.addAll(read(mdir.resolve("2025_round_1.csv"), 2025, 1, Map.of()));
            for (int r = 1; r <= 21; r++) m.addAll(read(mdir.resolve("2026_round_" + r + ".csv"), 2026, r, Map.of()));
            List<Board> m26 = m.stream().filter(b -> b.year() == 2026).toList();
            Map<Integer, String> ml = Map.of(3, "QF", 4, "Grand Final Championship Decider");
            StringBuilder mr = new StringBuilder(NOT_RECOUNTED);
            seasonTable(m26, mr);
            sheets.put("m01_rankplayers_allrounds_scripts", mr.toString());
            sheets.put("m03_rankhalls_allrounds_scripts", NOT_RECOUNTED + hallSeason(m26, null));
            sheets.put("m04_infohall_tokyo_allrounds", NOT_RECOUNTED + hallSheet(m26, "東京会館", null, ml));
            sheets.put("m05_infohall_chennai_round4_longlabel", NOT_RECOUNTED + hallSheet(m26, "சென்னை மன்றம்", 4, ml));
            sheets.put("m06_infohall_7_walkover_primary", NOT_RECOUNTED + hallSheet(m26, "7", null, ml));
            sheets.put("m07_infoplayer_cjk", NOT_RECOUNTED + playerSheet(m26, "王小明", null, ml));
            sheets.put("m08_infoplayer_tamil", NOT_RECOUNTED + playerSheet(m26, "முருகன் செல்வம்", null, ml));
            sheets.put("m09_infoplayer_arabic", NOT_RECOUNTED + playerSheet(m26, "محمد الفارسي", null, ml));
            sheets.put("m10_infoplayer_emoji", NOT_RECOUNTED + playerSheet(m26, "Ana 😀 Lima", null, ml));
            sheets.put("m11_infoplayer_single_word_long", NOT_RECOUNTED + playerSheet(m26, "Maximilianoalexandrovichsson", null, ml));
            sheets.put("m12_infoplayer_combining", NOT_RECOUNTED + playerSheet(m26, "Zoé Mélanie", null, ml));
            sheets.put("m14_infoplayer_accented_round3_shortlabel", NOT_RECOUNTED + playerSheet(m26, "José Ñúñez-Peña", 3, ml));
            sheets.put("m15_comparehalls_tamil_v_arabic_allrounds", NOT_RECOUNTED + hallSheet(m26, "சென்னை மன்றம்", null, ml) + hallSheet(m26, "دار القاهرة", null, ml));
            sheets.put("m19_infomatch_round5_oversize_score", NOT_RECOUNTED + roundSheet(m26, 2026, 5, ml));
            sheets.put("m20_infomatch_round6_walkover_hall", NOT_RECOUNTED + roundSheet(m26, 2026, 6, ml));
            sheets.put("m21_infomatch_round4_longlabel", NOT_RECOUNTED + roundSheet(m26, 2026, 4, ml));
            sheets.put("m22_infomatchhall_7_round6_walkover_dual_icon", NOT_RECOUNTED + hallSheet(m26, "7", 6, ml));
            sheets.put("m23_infomatchhall_tokyo_round21", NOT_RECOUNTED + hallSheet(m26, "東京会館", 21, ml));
            List<Board> m25 = m.stream().filter(b -> b.year() == 2025).toList();
            sheets.put("m31_rankplayers_single_round_season", NOT_RECOUNTED + roundSheet(m25, 2025, 1, Map.of()));
            sheets.put("m32_infoplayer_single_round_season", NOT_RECOUNTED + playerSheet(m25, "José Ñúñez-Peña", null, Map.of()));
        }

        // --- corpus (2004, the rendered season) ------------------------------
        Path corpus = w.resolve("SAMPLE FILES");
        Map<String, String> aliases = Map.of("Hermoine Granger", "Hermione Granger", "Teddy Rosevelt", "Teddy Roosevelt",
                "Jessie Pinkman", "Jesse Pinkman", "Margarey Tyrell", "Margaery Tyrell", "Elven", "Eleven",
                "Aniya Forger", "Anya Forger", "Baracuda", "Barracuda", "Tigran Petrosyan", "Tigran Petrosian");
        List<Board> c = new ArrayList<>();
        for (int r = 1; r <= 10; r++) {
            Path f = corpus.resolve("2004_round_" + r + ".csv");
            if (Files.exists(f)) c.addAll(read(f, 2004, r, aliases));
        }
        String corpusNote = "_Corpus recount: 2004 CSVs, the harness's 'same person' dialog answers applied as aliases; the 'Player: X' "
                + "multi-choice identity dialogs are taken by name. See SAMPLE FILES/corpus_notes.md for the intended outliers._\n\n";
        StringBuilder cr = new StringBuilder(NOT_RECOUNTED + corpusNote);
        seasonTable(c, cr);
        sheets.put("25_corpus_rankplayers_allrounds", cr.toString());
        sheets.put("26_corpus_rankhalls_allrounds", NOT_RECOUNTED + corpusNote + hallSeason(c, null));
        sheets.put("27_corpus_infoplayer_bubblegum", NOT_RECOUNTED + corpusNote + playerSheet(c, "Princess Bubblegum", null, noLabels));
        sheets.put("28_corpus_infoplayer_hermione", NOT_RECOUNTED + corpusNote + playerSheet(c, "Hermione Granger", null, noLabels));
        sheets.put("29_corpus_comparehalls_3_v_hallB", NOT_RECOUNTED + corpusNote + hallSheet(c, "3", null, noLabels) + hallSheet(c, "HallB", null, noLabels));
        sheets.put("30_corpus_infomatch_2004r8", NOT_RECOUNTED + corpusNote + roundSheet(c, 2004, 8, noLabels));

        for (Map.Entry<String, String> e : sheets.entrySet()) {
            Files.writeString(out.resolve(e.getKey() + ".answers.md"), "# Answer sheet - " + e.getKey() + "\n\n" + e.getValue(), StandardCharsets.UTF_8);
            index.add("- " + e.getKey() + ".answers.md");
        }
        index.add("\nVariants without a sheet: g* (generator fixtures: their inputs are literal in GeneratorBoundaryCases.java), All-Years variants "
                + "(per-year summaries are Elo/rank model outputs), text-only variants.");
        Files.write(out.resolve("answer_sheets_index.md"), index, StandardCharsets.UTF_8);
        System.out.println("answer sheets: " + sheets.size() + " -> " + out);
    }

    static void seasonTable(List<Board> all, StringBuilder sb) {
        Map<String, int[]> rec = new TreeMap<>();
        Map<String, String> hallOf = new HashMap<>();
        for (Board b : all) {
            int r = b.result();
            if (!b.walkover1()) { rec.computeIfAbsent(b.n1(), k -> new int[3])[r > 0 ? 0 : r == 0 ? 1 : 2]++; hallOf.put(b.n1(), b.hall(true)); }
            if (!b.walkover2()) { rec.computeIfAbsent(b.n2(), k -> new int[3])[r < 0 ? 0 : r == 0 ? 1 : 2]++; hallOf.put(b.n2(), b.hall(false)); }
        }
        sb.append("| player | hall (last seen) | W | D | L |\n|---|---|---|---|---|\n");
        rec.forEach((p, x) -> sb.append("| ").append(p).append(" | ").append(hallOf.get(p)).append(" | ").append(x[0]).append(" | ")
                .append(x[1]).append(" | ").append(x[2]).append(" |\n"));
        sb.append("\n");
    }

    static String playerSeason(List<Board> all, int upToRound) {
        StringBuilder sb = new StringBuilder("Season W-D-L per player up to round " + upToRound + ":\n\n");
        seasonTable(all.stream().filter(b -> b.round() <= upToRound).toList(), sb);
        return sb.toString();
    }

    static String hallSeason(List<Board> all, Integer onlyRound) {
        Map<String, int[]> rec = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Board b : all) {
            if (onlyRound != null && b.round() != onlyRound) continue;
            int r = b.result();
            if (!b.walkover1()) rec.computeIfAbsent(b.hall(true), k -> new int[3])[r > 0 ? 0 : r == 0 ? 1 : 2]++;
            if (!b.walkover2()) rec.computeIfAbsent(b.hall(false), k -> new int[3])[r < 0 ? 0 : r == 0 ? 1 : 2]++;
        }
        StringBuilder sb = new StringBuilder("Board W-D-L per hall" + (onlyRound == null ? "" : " in round " + onlyRound) + ":\n\n| hall | W | D | L |\n|---|---|---|---|\n");
        rec.forEach((h, x) -> sb.append("| ").append(h).append(" | ").append(x[0]).append(" | ").append(x[1]).append(" | ").append(x[2]).append(" |\n"));
        return sb.append("\n").toString();
    }
}
