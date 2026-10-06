package com.calplus.ihrgstats.visualaudit.record;

import java.util.*;

/**
 * Lint for the monospaced text tables the bot sends inside Telegram
 * {@code <pre>} blocks (legacy ``` fences, converted by TelegramHtml).
 * Everything is measured in DISPLAY columns as a phone's monospace font
 * shows them: East-Asian wide/fullwidth characters and emoji count 2,
 * combining marks / ZWJ / variation selectors count 0 (wcwidth rules).
 *
 * Checks per fenced block:
 * <ul>
 *   <li>WIDTH - lines the code padded to the same length (equal UTF-16
 *       length) must also have the same display width;</li>
 *   <li>DELIMITER - every '|' at the same display column in every line
 *       of the block that has one, and the same count;</li>
 *   <li>HEADER - each header-cell start column (first line, tokens split on
 *       spaces and '|') has a cell starting under it in every row, or
 *       blanks there;</li>
 *   <li>SURROGATE - no lone UTF-16 surrogate (a cut emoji);</li>
 *   <li>PHONE - the widest line of each message, against the ~32 columns a
 *       phone shows without horizontal scrolling (reported per command).</li>
 * </ul>
 */
public final class TextTableLint {

    public static final int PHONE_COLUMNS = 32;

    private TextTableLint() {}

    public record Issue(String check, String source, int block, String detail) {
        @Override
        public String toString() {
            return check + " | " + source + " | block " + block + " | " + detail;
        }
    }

    /** Display width of one code point (wcwidth-style). */
    public static int cpWidth(int cp) {
        if (cp == 0) return 0;
        int type = Character.getType(cp);
        if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || type == Character.FORMAT) return 0;
        if (cp == 0x200B || cp == 0x200D || (cp >= 0xFE00 && cp <= 0xFE0F) || (cp >= 0xE0100 && cp <= 0xE01EF)) return 0;
        if (isWide(cp)) return 2;
        return 1;
    }

    static boolean isWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)
                || (cp >= 0x231A && cp <= 0x231B) || (cp >= 0x23E9 && cp <= 0x23EC) || cp == 0x23F0 || cp == 0x23F3
                || (cp >= 0x25FD && cp <= 0x25FE) || (cp >= 0x2614 && cp <= 0x2615) || (cp >= 0x2648 && cp <= 0x2653)
                || cp == 0x267F || cp == 0x2693 || cp == 0x26A1 || (cp >= 0x26AA && cp <= 0x26AB) || (cp >= 0x26BD && cp <= 0x26BE)
                || (cp >= 0x26C4 && cp <= 0x26C5) || cp == 0x26CE || cp == 0x26D4 || cp == 0x26EA || (cp >= 0x26F2 && cp <= 0x26F3)
                || cp == 0x26F5 || cp == 0x26FA || cp == 0x26FD || cp == 0x2705 || (cp >= 0x270A && cp <= 0x270B) || cp == 0x2728
                || cp == 0x274C || cp == 0x274E || (cp >= 0x2753 && cp <= 0x2755) || cp == 0x2757 || (cp >= 0x2795 && cp <= 0x2797)
                || cp == 0x27B0 || cp == 0x27BF || (cp >= 0x2B1B && cp <= 0x2B1C) || cp == 0x2B50 || cp == 0x2B55
                || (cp >= 0x2E80 && cp <= 0x303E) || (cp >= 0x3041 && cp <= 0x33FF) || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0xA000 && cp <= 0xA4CF) || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0xFE30 && cp <= 0xFE4F) || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6) || (cp >= 0x1F004 && cp <= 0x1F0CF) || (cp >= 0x1F18E && cp <= 0x1F19A)
                || (cp >= 0x1F200 && cp <= 0x1F251) || (cp >= 0x1F300 && cp <= 0x1F64F) || (cp >= 0x1F680 && cp <= 0x1F6FF)
                || (cp >= 0x1F7E0 && cp <= 0x1F7EB) || cp == 0x1F7F0 || (cp >= 0x1F90C && cp <= 0x1F9FF) || (cp >= 0x1FA70 && cp <= 0x1FAFF)
                || (cp >= 0x20000 && cp <= 0x3FFFD);
    }

    /** Display width of a string; a lone surrogate counts 1 (it shows as a replacement box). */
    public static int width(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            w += cpWidth(cp);
            i += Character.charCount(cp);
        }
        return w;
    }

    /** Display column at which UTF-16 index {@code idx} starts. */
    static int col(String s, int idx) {
        return width(s.substring(0, idx));
    }

    /** True for scripts whose monospace rendering cannot be predicted by column counting (shaping, RTL, spacing marks). */
    static boolean complexScript(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
            if (sc == Character.UnicodeScript.ARABIC || sc == Character.UnicodeScript.HEBREW || sc == Character.UnicodeScript.TAMIL
                    || sc == Character.UnicodeScript.DEVANAGARI || sc == Character.UnicodeScript.THAI || sc == Character.UnicodeScript.BENGALI) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    /** The fenced (```) blocks of a legacy-markdown message, without the fence lines. */
    public static List<List<String>> blocks(String message) {
        List<List<String>> out = new ArrayList<>();
        if (message == null) return out;
        List<String> cur = null;
        for (String line : message.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("```")) {
                if (cur == null) {
                    cur = new ArrayList<>();
                } else {
                    out.add(cur);
                    cur = null;
                }
                continue;
            }
            if (cur != null) cur.add(line);
        }
        return out;
    }

    public static List<Issue> lint(String source, String message) {
        List<Issue> out = new ArrayList<>();
        List<List<String>> bs = blocks(message);
        for (int b = 0; b < bs.size(); b++) {
            List<String> lines = new ArrayList<>(bs.get(b));
            lines.removeIf(String::isBlank);
            lintBlock(source, b + 1, lines, out);
        }
        for (int i = 0; i < (message == null ? 0 : message.length()); i++) {
            char c = message.charAt(i);
            boolean lone = Character.isHighSurrogate(c) ? (i + 1 >= message.length() || !Character.isLowSurrogate(message.charAt(i + 1)))
                    : Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(message.charAt(i - 1)));
            if (lone) {
                int from = Math.max(0, i - 12), to = Math.min(message.length(), i + 12);
                out.add(new Issue("SURROGATE", source, 0, "lone surrogate U+" + String.format("%04X", (int) c) + " at index " + i
                        + " near '" + message.substring(from, to).replaceAll("[\\uD800-\\uDFFF]", "?") + "' (an emoji cut in half)"));
                if (Character.isHighSurrogate(c)) i++;
            }
        }
        return out;
    }

    static void lintBlock(String source, int b, List<String> lines, List<Issue> out) {
        if (lines.size() < 2) return;
        boolean complex = lines.stream().anyMatch(TextTableLint::complexScript);
        String note = complex ? " [block contains RTL/complex-script text: true rendering wider/narrower than counted]" : "";
        // WIDTH - only for fully padded blocks (every line the same UTF-16 length, as TableFormatter
        // and %-Ns tables produce): the code meant equal widths, so display widths must be equal too.
        boolean padded = lines.stream().mapToInt(String::length).distinct().count() == 1;
        if (padded) {
            int ref = width(lines.get(0));
            for (String l : lines) {
                if (width(l) != ref) {
                    out.add(new Issue("WIDTH", source, b, "'" + clip(l) + "' is " + width(l) + " columns, the block's other lines (all "
                            + l.length() + " chars) are " + ref + note));
                }
            }
        }
        // DELIMITER
        List<String> withBar = new ArrayList<>();
        for (String l : lines) if (l.indexOf('|') >= 0) withBar.add(l);
        if (withBar.size() >= 2) {
            List<Integer> ref = bars(withBar.get(0));
            for (String l : withBar.subList(1, withBar.size())) {
                List<Integer> d = bars(l);
                if (!d.equals(ref)) {
                    int first = -1;
                    for (int i = 0; i < Math.min(d.size(), ref.size()); i++) if (!d.get(i).equals(ref.get(i))) { first = i; break; }
                    out.add(new Issue("DELIMITER", source, b, "'" + clip(l) + "' '|' at columns " + abbreviate(d) + " vs " + abbreviate(ref)
                            + " in '" + clip(withBar.get(0)) + "'" + (first >= 0 ? " (first differs at #" + (first + 1) + ": "
                            + (d.get(first) - ref.get(first)) + " cols)" : " (count " + d.size() + " vs " + ref.size() + ")") + note));
                }
            }
        }
        String header = lines.get(0);
        boolean delimited = lines.stream().allMatch(l -> l.indexOf('|') >= 0);
        // HEADER_EMPTY ('|' tables): a column that has data must have a header cell
        if (delimited && header.chars().noneMatch(Character::isDigit)) {
            String[] hc = header.split("\\|", -1);
            for (int c = 0; c < hc.length; c++) {
                if (!hc[c].isBlank()) continue;
                for (String row : lines.subList(1, lines.size())) {
                    String[] rc = row.split("\\|", -1);
                    if (c < rc.length && !rc[c].isBlank() && !rc[c].matches("[-= ]+")) {
                        out.add(new Issue("HEADER", source, b, "column #" + c + " has data ('" + rc[c].trim() + "') but an empty header cell" + note));
                        break;
                    }
                }
            }
        }
        // HEADER (space-aligned blocks whose first line has no digit: a header row). '|' tables
        // are aligned by their delimiters instead - their cells may be centred or right-aligned.
        if (!delimited && header.chars().noneMatch(Character::isDigit) && !header.matches("[-=| ]+")) {
            List<int[]> ht = tokens(header);
            for (String row : lines.subList(1, lines.size())) {
                if (row.matches("[-=| ]+")) continue; // separator rule
                List<int[]> rt = tokens(row);
                List<String> bad = new ArrayList<>();
                for (int[] h : ht) {
                    boolean starts = false, blank = true;
                    for (int[] r : rt) {
                        if (r[0] == h[0]) starts = true;
                        if (r[0] < h[1] && r[1] > h[0]) blank = false;
                    }
                    if (!starts && !blank) bad.add("'" + header.substring(h[2], h[3]) + "'@col " + h[0]);
                }
                if (!bad.isEmpty()) {
                    out.add(new Issue("HEADER", source, b, "'" + clip(row) + "' has no cell starting under " + String.join(", ", bad) + note));
                }
            }
        }
    }

    static List<Integer> bars(String l) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < l.length(); i++) if (l.charAt(i) == '|') out.add(col(l, i));
        return out;
    }

    /** Tokens split on spaces and '|': [startCol, endCol, startIdx, endIdx]. */
    static List<int[]> tokens(String s) {
        List<int[]> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '|')) i++;
            if (i >= s.length()) break;
            int j = i;
            while (j < s.length() && s.charAt(j) != ' ' && s.charAt(j) != '|') j++;
            out.add(new int[]{col(s, i), col(s, j), i, j});
            i = j;
        }
        return out;
    }

    /** Widest display line of a message (fenced blocks and plain lines alike). */
    public static int maxWidth(String message) {
        int max = 0;
        if (message == null) return 0;
        for (String l : message.split("\n")) {
            if (l.trim().startsWith("```")) continue;
            max = Math.max(max, width(l.replace("**", "")));
        }
        return max;
    }

    /** Widest line inside fenced blocks only (the part a phone cannot re-wrap). */
    public static int maxPreWidth(String message) {
        int max = 0;
        for (List<String> b : blocks(message)) for (String l : b) max = Math.max(max, width(l));
        return max;
    }

    static String clip(String s) {
        return s.length() > 48 ? s.substring(0, 48) + "..." : s;
    }

    static String abbreviate(List<Integer> xs) {
        if (xs.size() <= 6) return xs.toString();
        return xs.subList(0, 6).toString().replace("]", ", ...]") + " (" + xs.size() + ")";
    }
}
