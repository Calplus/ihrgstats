package com.calplus.ihrgstats.visualaudit.record;

import com.calplus.ihrgstats.utils.TableFormatter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Calibrates the text-table lint: clean ASCII tables pass, every planted
 * defect is caught, and today's TableFormatter / per-round text shapes are
 * measured on fictional script names.
 */
public class TextTableLintTest {

    private static final String[] HEADERS = {"#", "Player", "Hall", "Elo"};
    private static final TableFormatter.Alignment[] AL = {TableFormatter.Alignment.RIGHT, TableFormatter.Alignment.LEFT,
            TableFormatter.Alignment.CENTER, TableFormatter.Alignment.RIGHT};
    private static final int[] W = {3, 12, 4, 5};

    private static String table(String... names) {
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < names.length; i++) rows.add(new String[]{String.valueOf(i + 1), names[i], "BJ", String.valueOf(1500 - i)});
        return TableFormatter.formatTable(HEADERS, rows, AL, W);
    }

    @Test
    void displayWidths() {
        assertEquals(5, TextTableLint.width("Alice"));
        assertEquals(6, TextTableLint.width("王小明"));
        assertEquals(2, TextTableLint.width("😀"));
        assertEquals(3, TextTableLint.width("Zoé"));   // combining acute: zero width
        assertEquals(2, TextTableLint.width("✅"));
    }

    @Test
    void cleanAsciiTable_hasNoIssues() {
        List<TextTableLint.Issue> issues = TextTableLint.lint("clean", table("Alice Tan", "Bob Lim", "Chen Wei Ming"));
        assertEquals(List.of(), issues);
    }

    @Test
    void plantedDefects_areAllCaught() {
        String clean = table("Alice Tan", "Bob Lim", "Carol Ng");
        String[] lines = clean.split("\n");
        int caught = 0, planted = 0;
        // 1. one-character row shift (a space inserted at the start of a data row)
        planted++;
        String shifted = clean.replace(lines[3], " " + lines[3]);
        if (hit(shifted, "DELIMITER") || hit(shifted, "HEADER")) caught++;
        else fail("row shift missed: " + TextTableLint.lint("p1", shifted));
        // 2. dropped header cell (blanked)
        planted++;
        String dropped = clean.replace("Player", "      ");
        if (hit(dropped, "HEADER") || hit(dropped, "DELIMITER")) caught++;
        // a blank header over a filled column is a header defect only if a cell starts there:
        else fail("dropped header missed: " + TextTableLint.lint("p2", dropped));
        // 3. wide characters padded as narrow (CJK name, same UTF-16 length as its siblings)
        planted++;
        String wide = table("Alice Tan", "王小明", "Carol Ng");
        if (hit(wide, "WIDTH") && hit(wide, "DELIMITER")) caught++;
        else fail("wide chars missed: " + TextTableLint.lint("p3", wide));
        // 4. emoji cut in half by a char-count truncation
        planted++;
        String cut = table("Alice Tan", "Ana Lima 12😀x", "Carol Ng");
        if (hit(cut, "SURROGATE")) caught++;
        else fail("cut emoji missed: " + TextTableLint.lint("p4", cut) + "\n" + cut);
        // 5. the S2 text shape: 'Round N' labels in a %-4s column
        planted++;
        StringBuilder s2 = new StringBuilder("```\n").append(String.format("%-4s %-6s %-10s%n", "Rnd", "Rank", "Elo"));
        for (int r = 1; r <= 10; r++) s2.append(String.format("%-4s %-6d %-10d%n", "Round " + r, r, 1000 + r));
        s2.append("```");
        if (hit(s2.toString(), "HEADER")) caught++;
        else fail("S2 text shape missed: " + TextTableLint.lint("p5", s2.toString()));
        System.out.println("text-table lint plants caught: " + caught + "/" + planted);
        assertEquals(planted, caught);
    }

    /** Today's TableFormatter on fictional script names - measured, printed, and pinned as the current behaviour. */
    @Test
    void tableFormatter_onScriptNames_today() {
        String msg = table("José Ñúñez", "王小明", "Ana 😀 Lima", "Zoé Mélanie", "Kavya Raman", "Ana Lima 12😀x");
        List<TextTableLint.Issue> issues = TextTableLint.lint("TableFormatter", msg);
        issues.forEach(System.out::println);
        assertTrue(issues.stream().anyMatch(i -> i.check().equals("WIDTH") && i.detail().contains("王小明")), "CJK row wider than padded");
        assertTrue(issues.stream().anyMatch(i -> i.check().equals("SURROGATE")), "emoji cut by substring(0, width)");
        assertTrue(issues.stream().anyMatch(i -> i.check().equals("WIDTH") && i.detail().contains("Zoe")), "combining marks narrower than padded");
    }

    /** Gated: re-lints saved replies (*.message.txt) in -Dvisual.textlint=<folder> and rewrites text_lint.txt there. */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "visual.textlint", matches = ".+")
    void relintSavedReplies() throws Exception {
        java.nio.file.Path dir = java.nio.file.Path.of(System.getProperty("visual.textlint"));
        java.util.Map<String, String> msgs = new java.util.TreeMap<>();
        try (var s = java.nio.file.Files.list(dir)) {
            for (java.nio.file.Path p : s.filter(p -> p.getFileName().toString().endsWith(".message.txt")).toList()) {
                msgs.put(p.getFileName().toString().replace(".message.txt", ""), java.nio.file.Files.readString(p));
            }
        }
        assertFalse(msgs.isEmpty());
        java.util.List<String> issues = new java.util.ArrayList<>(), widths = new java.util.ArrayList<>();
        java.util.Map<String, Integer> totals = new java.util.TreeMap<>();
        for (var e : msgs.entrySet()) {
            for (TextTableLint.Issue i : TextTableLint.lint(e.getKey(), e.getValue())) {
                issues.add(i.toString());
                totals.merge(i.check(), 1, Integer::sum);
            }
            int pre = TextTableLint.maxPreWidth(e.getValue()), all = TextTableLint.maxWidth(e.getValue());
            widths.add(String.format(java.util.Locale.ROOT, "%-58s widest <pre> line %3d cols, widest line %3d cols%s", e.getKey(), pre, all,
                    pre > TextTableLint.PHONE_COLUMNS ? "  > 32 (phone scrolls)" : ""));
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add("Text-table lint over " + msgs.size() + " command replies (display columns; wide/emoji = 2, combining = 0). Issues by check: " + totals);
        out.add("");
        out.add("Widest lines per variant (phones show ~" + TextTableLint.PHONE_COLUMNS + " columns):");
        out.addAll(widths);
        out.add("");
        out.add("Issues:");
        out.addAll(issues);
        java.nio.file.Files.write(dir.resolve("text_lint.txt"), out, java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("re-linted " + msgs.size() + " replies: " + totals);
    }

    private static boolean hit(String message, String check) {
        return TextTableLint.lint("plant", message).stream().anyMatch(i -> i.check().equals(check));
    }
}
