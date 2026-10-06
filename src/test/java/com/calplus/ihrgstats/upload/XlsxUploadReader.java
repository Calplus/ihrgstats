package com.calplus.ihrgstats.upload;

import org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException;
import org.apache.poi.openxml4j.exceptions.OLE2NotOfficeXmlFileException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Lane b1 PROTOTYPE (test scope) of the v3.0 {@code .xlsx} upload reader.
 *
 * <p>Streaming: the workbook is never loaded as a DOM. POI's event model
 * gives the shared-strings table and styles; each sheet's XML is read with a
 * SAX handler written here (not POI's formatted-value handler) so every
 * cell's STORED value is used, never its display format: a hall typed as
 * {@code 4} but formatted {@code 0.0} reads "4", a score of 102.25 formatted
 * with no decimals still reads "102.25". Numbers are rounded to 15
 * significant digits (Excel's own precision, so computed values like
 * 102.24999999999999 read "102.25") and written without trailing zeros.
 *
 * <p>Rejected with a message naming the sheet and cell: dates in a cell,
 * TRUE/FALSE, error values (#N/A ...), formulas with no saved result,
 * line breaks inside a cell, merged cells, hidden rows or columns inside the
 * data, more than {@link #MAX_ROWS} rows, files over the size cap, macro
 * workbooks, old .xls / password-protected files, anything that is not a
 * readable .xlsx (including POI's zip-bomb guard).
 */
public final class XlsxUploadReader implements AutoCloseable {

    public static final long DEFAULT_MAX_BYTES = 5L * 1024 * 1024;
    public static final int MAX_ROWS = 2000;
    public static final int MAX_COLUMNS = 26;

    private static final String NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String MACRO_MAIN = "application/vnd.ms-excel.sheet.macroEnabled.main+xml";

    /** An upload that cannot be accepted; the message is meant for the uploader as is. */
    public static final class Rejected extends Exception {
        public Rejected(String message) {
            super(message);
        }
    }

    /** A sheet as listed in the workbook, in tab order. */
    public record SheetInfo(int index, String name, String relId, String state) {
        public boolean visible() {
            return state == null || state.equals("visible");
        }
    }

    private final String fileName;
    private final OPCPackage pkg;
    private final XSSFReader reader;
    private final ReadOnlySharedStringsTable strings;
    private final StylesTable styles;
    private final List<SheetInfo> sheets;

    private XlsxUploadReader(String fileName, OPCPackage pkg) throws Exception {
        this.fileName = fileName;
        this.pkg = pkg;
        if (!pkg.getPartsByContentType(MACRO_MAIN).isEmpty()) {
            throw new Rejected(fileName + " is a macro-enabled workbook. Save it as a plain Excel Workbook (.xlsx) and upload that.");
        }
        this.reader = new XSSFReader(pkg);
        this.strings = new ReadOnlySharedStringsTable(pkg, false); // phonetic (furigana) runs excluded
        this.styles = reader.getStylesTable();
        this.sheets = readSheetList();
    }

    /** Opens an upload; every failure becomes a {@link Rejected} with uploader-facing wording. */
    public static XlsxUploadReader open(Path file, long maxBytes) throws Rejected {
        String name = file.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xlsm") || lower.endsWith(".xltm")) {
            throw new Rejected(name + " is a macro-enabled workbook. Save it as a plain Excel Workbook (.xlsx) and upload that.");
        }
        if (lower.endsWith(".xls") || lower.endsWith(".xlsb") || lower.endsWith(".ods") || lower.endsWith(".numbers")) {
            throw new Rejected(name + " is not an .xlsx workbook. In your spreadsheet app use Save As > Excel Workbook (.xlsx), or save each round as CSV UTF-8.");
        }
        try {
            long size = Files.size(file);
            if (size > maxBytes) {
                throw new Rejected(String.format(Locale.ROOT, "%s is %.1f MB; the limit is %.1f MB. Remove other sheets or images and upload again.",
                        name, size / 1048576.0, maxBytes / 1048576.0));
            }
        } catch (IOException e) {
            throw new Rejected(name + " could not be read: " + e.getMessage());
        }
        OPCPackage pkg = null;
        try {
            pkg = OPCPackage.open(file.toFile(), PackageAccess.READ);
            return new XlsxUploadReader(name, pkg);
        } catch (Rejected r) {
            revert(pkg);
            throw r;
        } catch (OLE2NotOfficeXmlFileException e) {
            revert(pkg);
            throw new Rejected(name + " is an old-style .xls or a password-protected workbook. Remove the password and use Save As > Excel Workbook (.xlsx).");
        } catch (NotOfficeXmlFileException e) {
            revert(pkg);
            throw new Rejected(name + " is not a valid .xlsx workbook (was a CSV or another file renamed?).");
        } catch (Exception | Error e) {
            revert(pkg);
            String why = e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("zip bomb")
                    ? "it expands to far more data than its size suggests (zip bomb guard)" : e.getClass().getSimpleName();
            throw new Rejected(name + " could not be read as an Excel workbook: " + why + ".");
        }
    }

    private static void revert(OPCPackage pkg) {
        if (pkg != null) pkg.revert();
    }

    public List<SheetInfo> sheets() {
        return sheets;
    }

    @Override
    public void close() {
        pkg.revert(); // read-only: never write back
    }

    private List<SheetInfo> readSheetList() throws Exception {
        List<SheetInfo> out = new ArrayList<>();
        try (InputStream in = reader.getWorkbookData()) {
            parse(in, new DefaultHandler() {
                @Override
                public void startElement(String uri, String local, String qName, Attributes a) {
                    if (NS_MAIN.equals(uri) && "sheet".equals(local)) {
                        out.add(new SheetInfo(out.size(), a.getValue("name"), a.getValue(NS_REL, "id"), a.getValue("state")));
                    }
                }
            });
        }
        return out;
    }

    private static void parse(InputStream in, DefaultHandler handler) throws Exception {
        XMLReader xml = XMLHelper.newXMLReader(); // XXE-safe, namespace aware
        xml.setContentHandler(handler);
        xml.parse(new InputSource(in));
    }

    /**
     * Reads one sheet's cells as text, row by row. Trailing empty cells are
     * dropped from every row and rows with no text are skipped, so
     * formatted-but-empty areas never matter.
     */
    public List<String[]> readRows(SheetInfo sheet) throws Rejected {
        SheetHandler h = new SheetHandler(sheet.name());
        try (InputStream in = reader.getSheet(sheet.relId())) {
            parse(in, h);
        } catch (SAXException e) {
            if (e.getException() instanceof Rejected r) throw r;
            if (e.getCause() instanceof Rejected r) throw r;
            throw new Rejected("Sheet '" + sheet.name() + "' could not be read: " + e.getMessage());
        } catch (Rejected r) {
            throw r;
        } catch (Exception e) {
            String why = e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("zip bomb")
                    ? "it expands to far more data than its size suggests (zip bomb guard)" : e.getClass().getSimpleName() + ": " + e.getMessage();
            throw new Rejected("Sheet '" + sheet.name() + "' of " + fileName + " could not be read: " + why + ".");
        }
        int width = 0;
        List<String[]> rows = new ArrayList<>();
        for (var e : h.rows.entrySet()) {
            String[] cells = e.getValue();
            int last = cells.length - 1;
            while (last >= 0 && (cells[last] == null || cells[last].isBlank())) last--;
            if (last < 0) continue; // all-empty row
            String[] trimmed = new String[last + 1];
            for (int i = 0; i <= last; i++) trimmed[i] = cells[i] == null ? "" : cells[i];
            if (h.hiddenRows.contains(e.getKey())) {
                throw new Rejected(String.format("Sheet '%s': row %d is hidden. Unhide it (or delete it) and upload again.", sheet.name(), e.getKey()));
            }
            width = Math.max(width, trimmed.length);
            rows.add(trimmed);
        }
        for (int[] range : h.hiddenColumns) {
            if (range[0] <= width) {
                throw new Rejected(String.format("Sheet '%s': column %s is hidden. Unhide it (or delete it) and upload again.",
                        sheet.name(), columnName(range[0] - 1)));
            }
        }
        if (!h.merged.isEmpty()) {
            throw new Rejected(String.format("Sheet '%s': cells %s are merged. Unmerge them and fill every cell, then upload again.",
                    sheet.name(), h.merged.get(0)));
        }
        return rows;
    }

    static String columnName(int index) {
        StringBuilder b = new StringBuilder();
        int n = index + 1;
        while (n > 0) {
            int r = (n - 1) % 26;
            b.insert(0, (char) ('A' + r));
            n = (n - 1) / 26;
        }
        return b.toString();
    }

    /** Stored-value normaliser for numeric cells (see class javadoc). */
    static String normaliseNumber(String raw) {
        BigDecimal d = new BigDecimal(raw.trim()).round(new MathContext(15));
        if (d.signum() == 0) return "0";
        return d.stripTrailingZeros().toPlainString();
    }

    private final class SheetHandler extends DefaultHandler {
        final String sheetName;
        final TreeMap<Integer, String[]> rows = new TreeMap<>();
        final java.util.Set<Integer> hiddenRows = new java.util.HashSet<>();
        final List<int[]> hiddenColumns = new ArrayList<>();
        final List<String> merged = new ArrayList<>();
        int rowNumber;
        String[] current;
        String cellRef;
        String cellType;
        int styleIndex;
        boolean hasFormula;
        boolean inValue;
        boolean inInlineText;
        int phoneticDepth;
        final StringBuilder value = new StringBuilder();
        final StringBuilder inline = new StringBuilder();
        boolean sawValue;

        SheetHandler(String sheetName) {
            this.sheetName = sheetName;
        }

        private SAXException reject(String message) {
            return new SAXException(new Rejected(String.format("Sheet '%s', cell %s: %s", sheetName, cellRef, message)));
        }

        @Override
        public void startElement(String uri, String local, String qName, Attributes a) throws SAXException {
            if (!NS_MAIN.equals(uri)) return;
            switch (local) {
                case "row" -> {
                    rowNumber = Integer.parseInt(a.getValue("r") != null ? a.getValue("r") : String.valueOf(rowNumber + 1));
                    if (rowNumber > MAX_ROWS) {
                        throw new SAXException(new Rejected(String.format(
                                "Sheet '%s' has more than %d rows. A round sheet holds one row per board.", sheetName, MAX_ROWS)));
                    }
                    current = new String[MAX_COLUMNS];
                    if ("1".equals(a.getValue("hidden")) || "true".equals(a.getValue("hidden"))) hiddenRows.add(rowNumber);
                }
                case "col" -> {
                    if ("1".equals(a.getValue("hidden")) || "true".equals(a.getValue("hidden"))) {
                        hiddenColumns.add(new int[]{Integer.parseInt(a.getValue("min")), Integer.parseInt(a.getValue("max"))});
                    }
                }
                case "mergeCell" -> merged.add(a.getValue("ref"));
                case "c" -> {
                    cellRef = a.getValue("r");
                    cellType = a.getValue("t");
                    String s = a.getValue("s");
                    styleIndex = s == null ? 0 : Integer.parseInt(s);
                    hasFormula = false;
                    sawValue = false;
                    value.setLength(0);
                    inline.setLength(0);
                }
                case "f" -> hasFormula = true;
                case "v" -> {
                    inValue = true;
                    sawValue = true;
                }
                case "rPh" -> phoneticDepth++;
                case "t" -> {
                    if (phoneticDepth == 0) inInlineText = true;
                }
                default -> { }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inValue) value.append(ch, start, length);
            else if (inInlineText) inline.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String local, String qName) throws SAXException {
            if (!NS_MAIN.equals(uri)) return;
            switch (local) {
                case "v" -> inValue = false;
                case "t" -> inInlineText = false;
                case "rPh" -> phoneticDepth--;
                case "c" -> storeCell();
                case "row" -> {
                    rows.put(rowNumber, current);
                    current = null;
                }
                default -> { }
            }
        }

        private void storeCell() throws SAXException {
            int col = columnIndex(cellRef);
            String text;
            String t = cellType == null ? "n" : cellType;
            switch (t) {
                case "s" -> text = sawValue ? strings.getItemAt(Integer.parseInt(value.toString().trim())).getString() : "";
                case "inlineStr" -> text = inline.toString();
                case "str" -> text = value.toString();
                case "b" -> throw reject("TRUE/FALSE is not a name, hall or score. Type the value as text or a number.");
                case "e" -> throw reject("shows the error " + value + ". Fix the formula or type the value.");
                case "d" -> throw reject("holds a date. Format the column as Text or Number and retype the value.");
                default -> {
                    if (!sawValue) {
                        if (hasFormula) {
                            throw reject("has a formula with no saved result. Open the file in Excel, let it calculate, save, and upload again.");
                        }
                        text = "";
                    } else {
                        XSSFCellStyle style = styleIndex < styles.getNumCellStyles() ? styles.getStyleAt(styleIndex) : null;
                        if (style != null && DateUtil.isADateFormat(style.getDataFormat(), style.getDataFormatString())) {
                            throw reject("holds a date or time (Excel turns entries like 1-2 or 3/4 into dates). "
                                    + "Format the column as Text or Number and retype the value.");
                        }
                        text = normaliseNumber(value.toString());
                    }
                }
            }
            if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
                throw reject("contains a line break. Put the whole value on one line.");
            }
            if (col >= MAX_COLUMNS) {
                if (!text.isBlank()) {
                    throw reject("is outside the expected columns. Keep only the round's columns on this sheet.");
                }
                return;
            }
            if (current == null) current = new String[MAX_COLUMNS];
            current[col] = text;
        }
    }

    static int columnIndex(String ref) {
        int col = 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c < 'A' || c > 'Z') break;
            col = col * 26 + (c - 'A' + 1);
        }
        return col - 1;
    }

    // ------------------------------------------------- CSV bridge (prototype)

    /** Writes rows as an RFC-4180 CSV (UTF-8, no BOM) so the existing processors can be reused unchanged. */
    public static void writeCsv(List<String[]> rows, Path out) throws IOException {
        StringBuilder b = new StringBuilder();
        int width = rows.isEmpty() ? 0 : rows.get(0).length; // short rows (trimmed empties) are padded to the header
        for (String[] row : rows) {
            for (int i = 0; i < Math.max(width, row.length); i++) {
                if (i > 0) b.append(',');
                String v = i < row.length && row[i] != null ? row[i] : "";
                boolean q = v.indexOf(',') >= 0 || v.indexOf('"') >= 0;
                b.append(q ? '"' + v.replace("\"", "\"\"") + '"' : v);
            }
            b.append("\r\n");
        }
        Files.writeString(out, b.toString(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
