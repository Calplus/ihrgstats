package com.calplus.ihrgstats.upload;

import com.calplus.ihrgstats.telegrambot.utils.CsvLineParser;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Byte-level re-renderings of a clean corpus CSV the way spreadsheet tools
 * really save them. A file is parsed once (header + data rows, cell text
 * untouched) and written back with the chosen encoding, BOM, delimiter,
 * quoting, line ending, trailing empty cells / rows and per-cell rewrites.
 * Generated only in memory or in temp folders - the corpus is never touched.
 */
public final class CsvVariants {

    private CsvVariants() {}

    /** A parsed clean file: header + data rows (cells exactly as in the file). */
    public record Table(String fileName, boolean isRound, List<String[]> header, List<String[]> rows) {
        int columns() {
            return header.get(0).length;
        }
    }

    public static Table read(Path csv) throws Exception {
        String text = Files.readString(csv, StandardCharsets.UTF_8);
        List<String[]> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new StringReader(text))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                lines.add(CsvLineParser.parseLine(line));
            }
        }
        String name = csv.getFileName().toString();
        List<String[]> header = new ArrayList<>();
        header.add(lines.get(0));
        return new Table(name, name.contains("_round_"), header, lines.subList(1, lines.size()));
    }

    /** How to write a table back out. Every field is a knob a real tool turns. */
    public static final class Style {
        Charset charset = StandardCharsets.UTF_8;
        byte[] bom = new byte[0];
        char delimiter = ',';
        String lineEnd = "\r\n";
        boolean finalLineEnd = true;
        boolean quoteAll = false;
        int trailingEmptyCells = 0;
        int trailingEmptyRows = 0;
        int blankLinesBetweenRows = 0;
        /** Rewrites a data cell: (isRound, columnIndex, value) -> new value. */
        CellRewrite cell = (isRound, col, v) -> v;
        UnaryOperator<String> headerCell = UnaryOperator.identity();

        public Style charset(Charset c) { charset = c; return this; }
        public Style bom(byte... b) { bom = b; return this; }
        public Style delimiter(char d) { delimiter = d; return this; }
        public Style lineEnd(String e) { lineEnd = e; return this; }
        public Style noFinalLineEnd() { finalLineEnd = false; return this; }
        public Style quoteAll() { quoteAll = true; return this; }
        public Style trailingEmptyCells(int n) { trailingEmptyCells = n; return this; }
        public Style trailingEmptyRows(int n) { trailingEmptyRows = n; return this; }
        public Style blankLinesBetweenRows(int n) { blankLinesBetweenRows = n; return this; }
        public Style cell(CellRewrite f) { cell = f; return this; }
        public Style header(UnaryOperator<String> f) { headerCell = f; return this; }
    }

    @FunctionalInterface
    public interface CellRewrite {
        String apply(boolean isRound, int column, String value);
    }

    public static byte[] render(Table t, Style s) {
        StringBuilder sb = new StringBuilder();
        List<String[]> all = new ArrayList<>();
        String[] header = t.header().get(0).clone();
        for (int i = 0; i < header.length; i++) header[i] = s.headerCell.apply(header[i]);
        all.add(header);
        for (String[] row : t.rows()) {
            String[] out = row.clone();
            for (int i = 0; i < out.length; i++) out[i] = s.cell.apply(t.isRound(), i, out[i]);
            all.add(out);
        }
        int width = t.columns() + s.trailingEmptyCells;
        for (int i = 0; i < s.trailingEmptyRows; i++) all.add(new String[t.columns()]);
        for (int li = 0; li < all.size(); li++) {
            String[] row = all.get(li);
            for (int c = 0; c < width; c++) {
                if (c > 0) sb.append(s.delimiter);
                String v = c < row.length && row[c] != null ? row[c] : "";
                sb.append(quote(v, s));
            }
            boolean last = li == all.size() - 1;
            if (!last || s.finalLineEnd) sb.append(s.lineEnd);
            if (!last && li > 0) for (int b = 0; b < s.blankLinesBetweenRows; b++) sb.append(s.lineEnd);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(s.bom);
        bytes.writeBytes(sb.toString().getBytes(s.charset));
        return bytes.toByteArray();
    }

    /** Excel's rule: quote when the cell holds the delimiter, a quote or a line break. */
    private static String quote(String v, Style s) {
        boolean needs = s.quoteAll || v.indexOf(s.delimiter) >= 0 || v.indexOf('"') >= 0
                || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        if (!needs) return v;
        return '"' + v.replace("\"", "\"\"") + '"';
    }

    // ------------------------------------------------------------ shortcuts

    public static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    public static final byte[] UTF16LE_BOM = {(byte) 0xFF, (byte) 0xFE};
    public static final byte[] UTF16BE_BOM = {(byte) 0xFE, (byte) 0xFF};
    public static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    /** Score columns of a round file (score1, score2). */
    public static boolean isScore(boolean isRound, int col) {
        return isRound && (col == 2 || col == 5);
    }

    /** True for a cell that holds a plain number (not blank, not TIMEOUT). */
    public static boolean isNumber(String v) {
        try {
            Double.parseDouble(v.trim());
            return !v.trim().isEmpty();
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
