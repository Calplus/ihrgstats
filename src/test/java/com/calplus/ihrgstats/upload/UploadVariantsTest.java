package com.calplus.ihrgstats.upload;

import com.calplus.ihrgstats.telegrambot.utils.B1ParseAccess;
import com.calplus.ihrgstats.upload.CsvVariants.Style;
import com.calplus.ihrgstats.upload.CsvVariants.Table;
import com.calplus.ihrgstats.upload.UploadTestSupport.Dialogs;
import com.calplus.ihrgstats.upload.UploadTestSupport.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static com.calplus.ihrgstats.upload.CsvVariants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Lane b1, scope item 1: every corpus file (39 round files + 4 capped lists)
 * re-rendered the way spreadsheet tools save CSV, fed to the production
 * parsers, and held against the clean parse of the untouched file. Each
 * variant must either parse to exactly the clean rows (scores compared as
 * numbers) or be rejected. Variants where today's behaviour is wrong have
 * their own {@code _knownDefect_} test pinning what happens today.
 *
 * <p>The database-level half ingests the whole 2001 season (capped list +
 * 10 rounds) through the real pipeline and compares semantic database dumps
 * ({@link UploadTestSupport#dump()}); the oracle is first calibrated
 * (two clean ingests identical, one planted score change detected).
 *
 * <p>Artefact: {@code target/b1/variant-matrix.txt}.
 */
public class UploadVariantsTest {

    enum Result { IDENTICAL, REJECTED, DIFFERENT }

    /** A rendering plus what the code does with it TODAY for a given file. */
    record Variant(String id, String what, Style style, Predicate<Table> rejectedToday) {}

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

    static List<Path> corpusFiles() throws Exception {
        try (Stream<Path> s = Files.list(UploadTestSupport.SAMPLE_DIR)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".csv")).sorted().toList();
        }
    }

    private static boolean hasFractionalScore(Table t) {
        if (!t.isRound()) return false;
        for (String[] r : t.rows()) {
            for (int c : new int[]{2, 5}) {
                if (r[c].contains(".")) return true;
            }
        }
        return false;
    }

    private static boolean hasNumericScore(Table t) {
        if (!t.isRound()) return false;
        for (String[] r : t.rows()) {
            if (isNumber(r[2]) || isNumber(r[5])) return true;
        }
        return false;
    }

    private static final Predicate<Table> NEVER = t -> false;
    private static final Predicate<Table> ALWAYS = t -> true;

    /** Renderings whose outcome today is the correct one (identical, or rejected). */
    static List<Variant> correctToday() {
        List<Variant> v = new ArrayList<>();
        v.add(new Variant("rerender-crlf", "baseline: the renderer itself (CRLF, Excel quoting)", new Style(), NEVER));
        v.add(new Variant("lf", "LF line endings (Google Sheets, LibreOffice on Linux)", new Style().lineEnd("\n"), NEVER));
        v.add(new Variant("cr", "bare CR line endings (Excel 'CSV (Macintosh)')", new Style().lineEnd("\r"), NEVER));
        v.add(new Variant("no-final-eol", "no line break after the last row", new Style().noFinalLineEnd(), NEVER));
        v.add(new Variant("blank-lines", "an empty line between every data row", new Style().blankLinesBetweenRows(1), NEVER));
        v.add(new Variant("quote-all", "every cell quoted (LibreOffice 'quote all text cells')", new Style().quoteAll(), NEVER));
        v.add(new Variant("header-upper", "header in capitals", new Style().header(String::toUpperCase), NEVER));
        v.add(new Variant("header-padded", "header cells space-padded", new Style().header(h -> " " + h + " "), NEVER));
        v.add(new Variant("cells-padded", "every data cell space-padded", new Style().cell((r, c, s) -> s.isEmpty() ? s : "  " + s + " "), NEVER));
        v.add(new Variant("scores-10.0", "integral scores written as 10.0, fractions with a trailing 0",
                new Style().cell((r, c, s) -> isScore(r, c) && isNumber(s) ? (s.contains(".") ? s + "0" : s + ".0") : s), NEVER));
        v.add(new Variant("scores-plus", "scores with a leading '+'",
                new Style().cell((r, c, s) -> isScore(r, c) && isNumber(s) ? "+" + s : s), NEVER));
        v.add(new Variant("cp1252", "Windows-1252 (Excel 'CSV (Comma delimited)'); the corpus is ASCII-only",
                new Style().charset(WINDOWS_1252), NEVER));
        v.add(new Variant("utf16le-bom-tab", "UTF-16LE + BOM, tab-separated (Excel 'Unicode Text' renamed .csv)",
                new Style().charset(StandardCharsets.UTF_16LE).bom(UTF16LE_BOM).delimiter('\t'), ALWAYS));
        v.add(new Variant("utf16le-bom", "UTF-16LE + BOM, comma-separated",
                new Style().charset(StandardCharsets.UTF_16LE).bom(UTF16LE_BOM), ALWAYS));
        v.add(new Variant("utf16be-bom", "UTF-16BE + BOM, comma-separated",
                new Style().charset(StandardCharsets.UTF_16BE).bom(UTF16BE_BOM), ALWAYS));
        v.add(new Variant("semicolon", "semicolon-separated (Excel in comma-decimal locales)", new Style().delimiter(';'), ALWAYS));
        v.add(new Variant("semicolon+decimal-comma", "semicolon-separated with decimal commas (the real comma-decimal Excel output)",
                new Style().delimiter(';').cell((r, c, s) -> isScore(r, c) ? s.replace('.', ',') : s), ALWAYS));
        v.add(new Variant("tab", "tab-separated", new Style().delimiter('\t'), ALWAYS));
        v.add(new Variant("decimal-comma", "fractional scores with a decimal comma (quoted, comma-separated)",
                new Style().cell((r, c, s) -> isScore(r, c) ? s.replace('.', ',') : s), UploadVariantsTest::hasFractionalScore));
        v.add(new Variant("score-formula", "scores written as formulas (=102.25)",
                new Style().cell((r, c, s) -> isScore(r, c) && isNumber(s) ? "=" + s : s), UploadVariantsTest::hasNumericScore));
        v.add(new Variant("score-minus", "scores with a leading '-' (formula-like; negative)",
                new Style().cell((r, c, s) -> isScore(r, c) && isNumber(s) ? "-" + s : s), UploadVariantsTest::hasNumericScore));
        v.add(new Variant("score-at", "scores with a leading '@' (formula-like)",
                new Style().cell((r, c, s) -> isScore(r, c) && isNumber(s) ? "@" + s : s), UploadVariantsTest::hasNumericScore));
        v.add(new Variant("score-text-formula", "scores in Excel's text-forcing form (=\"102.25\")",
                new Style().cell((r, c, s) -> isScore(r, c) && isNumber(s) ? "=\"" + s + "\"" : s), UploadVariantsTest::hasNumericScore));
        v.add(new Variant("score-nbsp", "scores followed by a no-break space (pasted from a web page)",
                new Style().cell((r, c, s) -> isScore(r, c) && isNumber(s) ? s + "\u00A0" : s), UploadVariantsTest::hasNumericScore));
        v.add(new Variant("name-linebreak", "an Alt+Enter line break inside the first name cell of every row (quoted newline)",
                new Style().cell((r, c, s) -> c == 0 && !s.equalsIgnoreCase("WALKOVER") ? s.replaceFirst(" ", "\n") : s),
                t -> t.rows().stream().anyMatch(r -> !r[0].equalsIgnoreCase("WALKOVER") && r[0].contains(" "))));
        return v;
    }

    /** Renderings that SHOULD ingest identically but are rejected today (pinned by _knownDefect_ tests). */
    static Map<String, Style> defectiveToday() {
        Map<String, Style> v = new LinkedHashMap<>();
        v.put("utf8-bom", new Style().bom(UTF8_BOM));
        v.put("trailing-empty-cells", new Style().trailingEmptyCells(2));
        v.put("trailing-empty-rows", new Style().trailingEmptyRows(3));
        return v;
    }

    /** Parse-level comparison: names/halls as parsed, scores numerically. */
    static boolean sameRows(List<String[]> a, List<String[]> b, boolean isRound) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            String[] x = a.get(i), y = b.get(i);
            if (x.length != y.length) return false;
            for (int c = 0; c < x.length; c++) {
                if (isScore(isRound, c) && isNumber(x[c]) && isNumber(y[c])) {
                    if (Double.parseDouble(x[c].trim()) != Double.parseDouble(y[c].trim())) return false;
                } else if (!x[c].equals(y[c])) {
                    return false;
                }
            }
        }
        return true;
    }

    record Cell(Result result, String message) {}

    static Cell run(Path tmp, Table t, byte[] bytes, List<String[]> clean) throws Exception {
        Path f = tmp.resolve(t.fileName());
        Files.write(f, bytes);
        try {
            List<String[]> parsed = B1ParseAccess.parse(f, t.isRound());
            return new Cell(sameRows(clean, parsed, t.isRound()) ? Result.IDENTICAL : Result.DIFFERENT, null);
        } catch (Exception e) {
            return new Cell(Result.REJECTED, e.getMessage());
        }
    }

    @Test
    void parseMatrix_everyCorpusFile_everyVariant_isIdenticalOrRejected(@TempDir Path tmp) throws Exception {
        List<Path> files = corpusFiles();
        assertEquals(43, files.size(), "39 round files + 4 capped lists expected");
        StringBuilder report = new StringBuilder("variant | files identical | rejected | DIFFERENT | first rejection message\n");
        List<String> failures = new ArrayList<>();
        int checks = 0;
        for (Variant v : correctToday()) {
            int same = 0, rej = 0, diff = 0;
            String firstMsg = null;
            String firstCapped = null;
            for (Path file : files) {
                Table t = read(file);
                List<String[]> clean = B1ParseAccess.parse(file, t.isRound());
                Cell cell = run(tmp, t, render(t, v.style()), clean);
                checks++;
                boolean expectRejected = v.rejectedToday().test(t);
                switch (cell.result()) {
                    case IDENTICAL -> same++;
                    case REJECTED -> {
                        rej++;
                        if (t.isRound() && firstMsg == null) firstMsg = cell.message();
                        if (!t.isRound() && firstCapped == null) firstCapped = cell.message();
                    }
                    case DIFFERENT -> diff++;
                }
                Result want = expectRejected ? Result.REJECTED : Result.IDENTICAL;
                if (cell.result() != want) {
                    failures.add(v.id() + " / " + t.fileName() + ": expected " + want + ", got " + cell.result() + " " + cell.message());
                }
            }
            report.append(String.format("%-24s| %2d | %2d | %2d | %s || %s%n", v.id(), same, rej, diff, printable(firstMsg), printable(firstCapped)));
        }
        for (Map.Entry<String, Style> d : defectiveToday().entrySet()) {
            int same = 0, rej = 0, diff = 0;
            String firstMsg = null;
            String firstCapped = null;
            for (Path file : files) {
                Table t = read(file);
                Cell cell = run(tmp, t, render(t, d.getValue()), B1ParseAccess.parse(file, t.isRound()));
                switch (cell.result()) {
                    case IDENTICAL -> same++;
                    case REJECTED -> {
                        rej++;
                        if (t.isRound() && firstMsg == null) firstMsg = cell.message();
                        if (!t.isRound() && firstCapped == null) firstCapped = cell.message();
                    }
                    case DIFFERENT -> diff++;
                }
            }
            report.append(String.format("%-24s| %2d | %2d | %2d | %s || %s   [SHOULD BE IDENTICAL - known defect]%n",
                    d.getKey(), same, rej, diff, printable(firstMsg), printable(firstCapped)));
        }
        Path out = Paths.get(originalUserDir, "target", "b1");
        Files.createDirectories(out);
        Files.writeString(out.resolve("variant-matrix.txt"), report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);
        assertTrue(checks >= 43 * 20, "matrix must cover >= 860 file/variant pairs, covered " + checks);
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    // ------------------------------------------------------- known defects

    /** Renders every corpus file with {@code style} and returns file name -> rejection message (null = accepted). */
    private static Map<String, String> rejections(Path tmp, Style style) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        for (Path file : corpusFiles()) {
            Table t = read(file);
            Cell cell = run(tmp, t, render(t, style), B1ParseAccess.parse(file, t.isRound()));
            out.put(t.fileName(), cell.result() == Result.REJECTED ? cell.message() : null);
        }
        return out;
    }

    private static void assertAllRejected(Map<String, String> got, String roundMessage, String cappedMessage) {
        assertEquals(43, got.size());
        got.forEach((file, msg) -> assertEquals(file.contains("_round_") ? roundMessage : cappedMessage, msg,
                file + " - today's message changed (fixed? then flip this test to IDENTICAL)"));
    }

    /**
     * B1-1: Excel's "CSV UTF-8" always starts with a BOM. Today every such
     * file is rejected, and the round-file message names the invisible BOM
     * as if the header were wrong ("found 'name1'"). Should be IDENTICAL.
     */
    @Test
    void utf8Bom_everyCorpusFileRejected_knownDefect_B1_1(@TempDir Path tmp) throws Exception {
        assertAllRejected(rejections(tmp, defectiveToday().get("utf8-bom")),
                "Invalid CSV header: Expected 'name1' at column 1, found '\uFEFFname1'",
                "Invalid CSV format: Header must be 'name,hall' (case insensitive)");
    }

    /** B1-2: two trailing empty columns on every line (Excel used-range artefact). Should be IDENTICAL. */
    @Test
    void trailingEmptyCells_everyCorpusFileRejected_knownDefect_B1_2(@TempDir Path tmp) throws Exception {
        assertAllRejected(rejections(tmp, defectiveToday().get("trailing-empty-cells")),
                "Invalid CSV format: Header must have exactly 6 columns (name1,hall1,score1,name2,hall2,score2)",
                "Invalid CSV format: Header must have exactly 2 columns (name,hall)");
    }

    /** B1-3: rows of empty cells after the data (",,,,,"). Should be skipped like blank lines. */
    @Test
    void trailingEmptyRows_everyCorpusFileRejected_knownDefect_B1_3(@TempDir Path tmp) throws Exception {
        Map<String, String> got = rejections(tmp, defectiveToday().get("trailing-empty-rows"));
        assertEquals(43, got.size());
        got.forEach((file, msg) -> {
            assertNotNull(msg, file + " was accepted - fixed? then flip this test to IDENTICAL");
            assertTrue(msg.matches(file.contains("_round_")
                            ? "Invalid CSV format at line \\d+: Player names cannot be empty"
                            : "Invalid CSV format at line \\d+: Player name cannot be empty"), file + ": " + msg);
        });
    }

    /**
     * B1-4: wrong encoding or delimiter is rejected (correct), but the
     * message never names the cause - UTF-16 shows mojibake with NULs, a
     * semicolon/tab file is told its header needs "6 columns (name1,...)".
     */
    @Test
    void wrongEncodingOrDelimiter_messagesDoNotNameTheCause_knownDefect_B1_4(@TempDir Path tmp) throws Exception {
        Map<String, Style> byId = new LinkedHashMap<>();
        for (Variant v : correctToday()) byId.put(v.id(), v.style());
        String sixColumns = "Invalid CSV format: Header must have exactly 6 columns (name1,hall1,score1,name2,hall2,score2)";
        String twoColumns = "Invalid CSV format: Header must have exactly 2 columns (name,hall)";
        for (String id : new String[]{"semicolon", "semicolon+decimal-comma", "tab", "utf16le-bom-tab"}) {
            assertAllRejected(rejections(tmp, byId.get(id)), sixColumns, twoColumns);
        }
        assertAllRejected(rejections(tmp, byId.get("utf16le-bom")),
                "Invalid CSV header: Expected 'name1' at column 1, found '\uFFFD\uFFFDn\u0000a\u0000m\u0000e\u00001\u0000'",
                "Invalid CSV format: Header must be 'name,hall' (case insensitive)");
        assertAllRejected(rejections(tmp, byId.get("utf16be-bom")),
                "Invalid CSV header: Expected 'name1' at column 1, found '\uFFFD\uFFFD\u0000n\u0000a\u0000m\u0000e\u00001\u0000'",
                "Invalid CSV format: Header must be 'name,hall' (case insensitive)");
    }

    /** Shows invisible characters the way a reviewer needs to see them. */
    static String printable(String s) {
        if (s == null) return "-";
        StringBuilder b = new StringBuilder();
        s.codePoints().forEach(cp -> {
            if (cp < 0x20 || (cp >= 0x7F && cp < 0xA0) || cp == 0xA0 || cp == 0xFEFF || cp == 0xFFFD || (cp >= 0x200B && cp <= 0x200F)) {
                b.append(String.format("\\u%04X", cp));
            } else {
                b.appendCodePoint(cp);
            }
        });
        return b.toString();
    }
}
