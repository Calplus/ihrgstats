package com.calplus.ihrgstats.upload;

import com.calplus.ihrgstats.upload.CsvVariants.Table;
import com.calplus.ihrgstats.upload.UploadTestSupport.Dialogs;
import com.calplus.ihrgstats.upload.XlsxUploadPlanner.Plan;
import com.calplus.ihrgstats.upload.XlsxUploadPlanner.RunReport;
import com.calplus.ihrgstats.upload.XlsxUploadReader.Rejected;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbookType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static com.calplus.ihrgstats.upload.UploadTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Lane b1, scope item 5: the {@code .xlsx} upload PROTOTYPE
 * ({@link XlsxUploadReader} + {@link XlsxUploadPlanner}) against the
 * fictional corpus. Workbooks are generated at test time from the corpus
 * CSVs the way a person typing them into Excel would get them (numbers as
 * number cells, numeric halls as numbers), with deliberate noise: display
 * formats that differ from the stored value, formulas with computed
 * (binary-noisy) results, formatted-but-empty areas, extra sheets, shuffled
 * tab order, a hidden sheet. The resulting database must equal the CSV
 * ingest (semantic dump). Hazards each have a test.
 */
public class XlsxPrototypeTest {

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

    // ---------------------------------------------------------- generator

    /** Noise knobs for one generated sheet. */
    static final class Noise {
        boolean hallFormatOneDecimal;   // numeric hall 4 displayed as "4.0"
        boolean scoreFormatNoDecimals;  // 102.25 displayed as "102"
        boolean scoresAsFormulas;       // =v+0.1-0.1 with the computed (noisy) cached result
        boolean styledEmptyArea;        // formatted-but-empty rows below and columns to the right
        boolean upperCaseHeader;
    }

    /** Writes a corpus table into a sheet the way Excel would store typed values. */
    static void fill(Workbook wb, Sheet sheet, Table t, Noise n) {
        CellStyle hallStyle = wb.createCellStyle();
        hallStyle.setDataFormat(wb.createDataFormat().getFormat("0.0"));
        CellStyle scoreStyle = wb.createCellStyle();
        scoreStyle.setDataFormat(wb.createDataFormat().getFormat("0"));
        CellStyle fill = wb.createCellStyle();
        fill.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.LIGHT_YELLOW.getIndex());
        fill.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
        Row header = sheet.createRow(0);
        String[] h = t.header().get(0);
        for (int i = 0; i < h.length; i++) header.createCell(i).setCellValue(n.upperCaseHeader ? h[i].toUpperCase() : h[i]);
        int r = 1;
        for (String[] row : t.rows()) {
            Row xr = sheet.createRow(r++);
            for (int c = 0; c < row.length; c++) {
                String v = row[c].trim();
                if (v.isEmpty()) continue;
                Cell cell = xr.createCell(c);
                boolean hallCol = t.isRound() ? (c == 1 || c == 4) : c == 1;
                boolean scoreCol = CsvVariants.isScore(t.isRound(), c);
                if (CsvVariants.isNumber(v) && (hallCol || scoreCol)) {
                    double d = Double.parseDouble(v);
                    if (scoreCol && n.scoresAsFormulas) {
                        cell.setCellFormula(v + "+0.1-0.1");
                    } else {
                        cell.setCellValue(d);
                    }
                    if (hallCol && n.hallFormatOneDecimal) cell.setCellStyle(hallStyle);
                    if (scoreCol && n.scoreFormatNoDecimals) cell.setCellStyle(scoreStyle);
                } else {
                    cell.setCellValue(v);
                }
            }
            if (n.styledEmptyArea) {
                for (int c = row.length; c < row.length + 3; c++) xr.createCell(c).setCellStyle(fill);
            }
        }
        if (n.styledEmptyArea) {
            for (int extra = 0; extra < 5; extra++) {
                Row xr = sheet.createRow(r++);
                for (int c = 0; c < h.length + 3; c++) xr.createCell(c).setCellStyle(fill);
            }
        }
    }

    static void save(Workbook wb, Path out) throws Exception {
        if (wb instanceof XSSFWorkbook x) {
            x.getCreationHelper().createFormulaEvaluator().evaluateAll(); // store cached results like Excel does
        }
        try (OutputStream os = Files.newOutputStream(out)) {
            wb.write(os);
        }
        wb.close();
    }

    static Noise noise(int i) {
        Noise n = new Noise();
        n.hallFormatOneDecimal = i % 2 == 0;
        n.scoreFormatNoDecimals = i % 3 == 0;
        n.scoresAsFormulas = i % 2 == 1;
        n.styledEmptyArea = i % 4 != 3;
        n.upperCaseHeader = i % 5 == 4;
        return n;
    }

    /** Runs one workbook upload end to end through the prototype; returns its report. */
    static RunReport upload(Path xlsx, Integer currentYear, Dialogs dialogs, List<String> chat) throws Exception {
        try (XlsxUploadReader wb = XlsxUploadReader.open(xlsx, XlsxUploadReader.DEFAULT_MAX_BYTES)) {
            Plan plan = XlsxUploadPlanner.plan(xlsx, wb, currentYear);
            System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(plan.year()));
            return XlsxUploadPlanner.run(plan, xlsx.resolveSibling(xlsx.getFileName() + ".work"), NOW, dialogs, chat::add);
        }
    }

    static String[] season2001Files() {
        String[] names = new String[11];
        names[0] = "2001_cappedlist.csv";
        for (int r = 1; r <= 10; r++) names[r] = "2001_round_" + r + ".csv";
        return names;
    }

    // ------------------------------------------------------- equivalence

    /**
     * Eleven single-sheet uploads (2001_cappedlist.xlsx + 2001_round_N.xlsx),
     * each with different noise and two of them written by POI's streaming
     * writer (inline strings instead of the shared-string table), plus an
     * extra "Notes" sheet after the first. Database == CSV ingest.
     */
    @Test
    void singleRoundWorkbooks_wholeSeason_identicalToCsvIngest(@TempDir Path tmp) throws Exception {
        Map<String, List<String>> csv = UploadDbEquivalenceTest.season2001(tmp.resolve("csv"), UploadTestSupport::sample);

        initHome(tmp.resolve("xlsx"));
        Path dir = tmp.resolve("books");
        Files.createDirectories(dir);
        Dialogs d = new Dialogs(CORPUS_2001_SCRIPT);
        List<String> chat = new ArrayList<>();
        String[] files = season2001Files();
        for (int i = 0; i < files.length; i++) {
            Table t = CsvVariants.read(sample(files[i]));
            Workbook wb = (i == 4 || i == 9) ? new SXSSFWorkbook() : new XSSFWorkbook();
            Noise n = noise(i);
            if (wb instanceof SXSSFWorkbook) n.scoresAsFormulas = false; // SXSSF cannot evaluate; cached results need XSSF
            fill(wb, wb.createSheet("Sheet1"), t, n);
            wb.createSheet("Notes").createRow(0).createCell(0).setCellValue("anything here is ignored");
            Path xlsx = dir.resolve(files[i].replace(".csv", ".xlsx"));
            save(wb, xlsx);
            RunReport rep = upload(xlsx, null, d, chat);
            assertTrue(rep.complete(), xlsx.getFileName() + ": " + rep.lines() + " / " + chat);
            assertTrue(rep.lines().get(0).contains("Sheet 'Notes' ignored"), rep.lines().toString());
        }
        assertTrue(d.unexpected.isEmpty(), d.unexpected.toString());
        assertEquals(3, d.identityDialogCount());
        assertSameDatabase(csv, dump(), "2001 season from single-round .xlsx files vs CSV ingest");
    }

    /**
     * One multi-round workbook 2001_rounds.xlsx: 10 round sheets named in
     * five different styles and shuffled, a capped-list sheet in the middle,
     * a "Notes" sheet and a hidden "Round 11". Processed capped list first,
     * then rounds 1..10. Database == CSV ingest.
     */
    @Test
    void multiRoundWorkbook_wholeSeason_identicalToCsvIngest(@TempDir Path tmp) throws Exception {
        Map<String, List<String>> csv = UploadDbEquivalenceTest.season2001(tmp.resolve("csv"), UploadTestSupport::sample);

        Path xlsx = tmp.resolve("2001_rounds.xlsx");
        writeMultiRound(xlsx, 10, true, 42L);
        initHome(tmp.resolve("xlsx"));
        Dialogs d = new Dialogs(CORPUS_2001_SCRIPT);
        List<String> chat = new ArrayList<>();
        RunReport rep = upload(xlsx, null, d, chat);
        assertTrue(rep.complete(), rep.lines() + " / " + chat);
        assertEquals(11, rep.stepsDone());
        assertTrue(rep.lines().stream().anyMatch(l -> l.equals("Hidden sheet 'Round 11' skipped.")), rep.lines().toString());
        assertTrue(rep.lines().stream().anyMatch(l -> l.startsWith("Sheet 'Notes' skipped")), rep.lines().toString());
        assertTrue(rep.lines().stream().anyMatch(l -> l.startsWith("1 of 11: capped list")), rep.lines().toString());
        assertTrue(rep.lines().stream().anyMatch(l -> l.startsWith("11 of 11: round 10")), rep.lines().toString());
        assertSameDatabase(csv, dump(), "2001 season from one multi-round workbook vs CSV ingest");
        System.out.println("[b1] multi-round reply lines:\n  " + String.join("\n  ", rep.lines()));
    }

    /** Sheet names in five styles, tab order shuffled by {@code seed}. */
    static void writeMultiRound(Path out, int rounds, boolean withExtras, long seed) throws Exception {
        String[] styles = {"Round %d", "round_%d", "R%d", "2001 Round %d", "2001_round_%d"};
        List<Integer> order = new ArrayList<>();
        for (int r = 1; r <= rounds; r++) order.add(r);
        Collections.shuffle(order, new Random(seed));
        XSSFWorkbook wb = new XSSFWorkbook();
        if (withExtras) wb.createSheet("Notes").createRow(0).createCell(0).setCellValue("season notes - ignored");
        int i = 0;
        for (int r : order) {
            if (i == rounds / 2) fill(wb, wb.createSheet("Capped list"), CsvVariants.read(sample("2001_cappedlist.csv")), noise(7));
            fill(wb, wb.createSheet(String.format(styles[r % styles.length], r)), CsvVariants.read(sample("2001_round_" + r + ".csv")), noise(i));
            i++;
        }
        if (withExtras) {
            Sheet hidden = wb.createSheet("Round 11");
            fill(wb, hidden, CsvVariants.read(sample("2001_round_1.csv")), noise(0));
            wb.setSheetHidden(wb.getSheetIndex(hidden), true);
        }
        save(wb, out);
    }

    /**
     * Round k of n fails (the identity dialog in round 3 is cancelled):
     * capped list, rounds 1-2 stay stored, rounds 3-4 are not processed, the
     * reply says so. Uploading the SAME workbook again skips the stored rounds
     * and finishes; the result equals the CSV ingest of rounds 1-4.
     */
    @Test
    void multiRoundWorkbook_roundKFails_stopsCleanly_andReuploadResumes(@TempDir Path tmp) throws Exception {
        initHome(tmp.resolve("csv"));
        ingestSeason(2001, 4, UploadTestSupport::sample, new Dialogs(CORPUS_2001_SCRIPT));
        Map<String, List<String>> csv = dump();

        Path xlsx = tmp.resolve("2001_rounds.xlsx");
        writeMultiRound(xlsx, 4, false, 7L);
        initHome(tmp.resolve("xlsx"));
        List<Script> cancelTeddy = new ArrayList<>(CORPUS_2001_SCRIPT.subList(0, 2));
        cancelTeddy.add(new Script("'Teddy Rosevelt' may match", 2)); // Cancel processing
        List<String> chat = new ArrayList<>();
        RunReport first = upload(xlsx, null, new Dialogs(cancelTeddy), chat);
        assertFalse(first.complete());
        assertEquals(3, first.stepsDone(), "capped list + rounds 1-2 stored");
        String stop = first.lines().get(first.lines().size() - 1);
        assertTrue(stop.startsWith("Stopped at round 3"), stop);
        assertTrue(stop.contains("step 4 of 5") && stop.contains("Not processed: round 4"), stop);
        assertEquals(2, new com.calplus.ihrgstats.databasemanager.A1_Rounds().getLatestRoundOrder(2001));

        RunReport second = upload(xlsx, null, new Dialogs(CORPUS_2001_SCRIPT), chat);
        assertTrue(second.complete(), second.lines().toString());
        assertTrue(second.lines().stream().anyMatch(l -> l.startsWith("Round 1 (") && l.contains("already stored")), second.lines().toString());
        assertSameDatabase(csv, dump(), "failed-then-resumed workbook vs CSV ingest of rounds 1-4");
        System.out.println("[b1] stop line: " + stop + "\n[b1] resume lines: " + second.lines());
    }

    /** A structural problem in ANY sheet rejects the whole workbook before anything is written. */
    @Test
    void multiRoundWorkbook_badSheet_nothingProcessed(@TempDir Path tmp) throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        for (int r = 1; r <= 4; r++) fill(wb, wb.createSheet("Round " + r), CsvVariants.read(sample("2001_round_" + r + ".csv")), noise(r));
        wb.getSheet("Round 4").getRow(5).getCell(1).setCellValue("Hall Nine");
        Path xlsx = tmp.resolve("2001_rounds.xlsx");
        save(wb, xlsx);
        initHome(tmp.resolve("h"));
        Map<String, List<String>> before = dump();
        Rejected e = assertThrows(Rejected.class, () -> upload(xlsx, null, new Dialogs(CORPUS_2001_SCRIPT), new ArrayList<>()));
        assertEquals("Sheet 'Round 4', cell B6: unknown hall 'Hall Nine'. Nothing was processed.", e.getMessage());
        assertSameDatabase(before, dump(), "rejected workbook");
    }

    @Test
    void multiRoundWorkbook_namingRules(@TempDir Path tmp) throws Exception {
        assertEquals(XlsxUploadPlanner.Kind.MULTI_ROUND, XlsxUploadPlanner.classify("2001_Rounds.xlsx").kind());
        assertEquals(XlsxUploadPlanner.Kind.SINGLE_ROUND, XlsxUploadPlanner.classify("2001_round_7.xlsx").kind());
        assertEquals(7, XlsxUploadPlanner.classify("round_7.XLSX").round());
        assertEquals(XlsxUploadPlanner.Kind.SINGLE_CAPPED, XlsxUploadPlanner.classify("cappedlist.xlsx").kind());
        assertEquals(XlsxUploadPlanner.Kind.UNKNOWN, XlsxUploadPlanner.classify("season 2001.xlsx").kind());
        initHome(tmp.resolve("h"));
        // year in a sheet name must match the file
        XSSFWorkbook wb = new XSSFWorkbook();
        fill(wb, wb.createSheet("2002 Round 1"), CsvVariants.read(sample("2001_round_1.csv")), noise(0));
        Path x1 = tmp.resolve("2001_rounds.xlsx");
        save(wb, x1);
        assertEquals("Sheet '2002 Round 1' is for 2002 but the file is for 2001. Nothing was processed.",
                assertThrows(Rejected.class, () -> upload(x1, null, new Dialogs(List.of()), new ArrayList<>())).getMessage());
        // duplicate round numbers
        wb = new XSSFWorkbook();
        fill(wb, wb.createSheet("Round 1"), CsvVariants.read(sample("2001_round_1.csv")), noise(0));
        fill(wb, wb.createSheet("R1"), CsvVariants.read(sample("2001_round_1.csv")), noise(0));
        Path x2 = tmp.resolve("x2").resolve("2001_rounds.xlsx");
        Files.createDirectories(x2.getParent());
        save(wb, x2);
        assertEquals("Sheets 'Round 1' and 'R1' are both round 1. Nothing was processed.",
                assertThrows(Rejected.class, () -> upload(x2, null, new Dialogs(List.of()), new ArrayList<>())).getMessage());
        // a gap
        wb = new XSSFWorkbook();
        fill(wb, wb.createSheet("Round 1"), CsvVariants.read(sample("2001_round_1.csv")), noise(0));
        fill(wb, wb.createSheet("Round 3"), CsvVariants.read(sample("2001_round_3.csv")), noise(0));
        Path x3 = tmp.resolve("x3").resolve("2001_rounds.xlsx");
        Files.createDirectories(x3.getParent());
        save(wb, x3);
        assertEquals("Round 2 is missing: the workbook goes from round 1 to 'Round 3'. Nothing was processed.",
                assertThrows(Rejected.class, () -> upload(x3, null, new Dialogs(List.of()), new ArrayList<>())).getMessage());
        assertEquals(0, new com.calplus.ihrgstats.databasemanager.A1_Rounds().getLatestRoundOrder(2001));
    }

    // ------------------------------------------------------------- hazards

    private static Path oneSheet(Path dir, String name, java.util.function.Consumer<XSSFWorkbook> body) throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        body.accept(wb);
        Path p = dir.resolve(name);
        save(wb, p);
        return p;
    }

    private static List<String[]> readFirst(Path p) throws Exception {
        try (XlsxUploadReader r = XlsxUploadReader.open(p, XlsxUploadReader.DEFAULT_MAX_BYTES)) {
            return r.readRows(r.sheets().get(0));
        }
    }

    private static String rejection(Path p) {
        return assertThrows(Rejected.class, () -> readFirst(p)).getMessage();
    }

    private static Row header(Sheet s) {
        Row h = s.createRow(0);
        String[] cols = {"name1", "hall1", "score1", "name2", "hall2", "score2"};
        for (int i = 0; i < cols.length; i++) h.createCell(i).setCellValue(cols[i]);
        return h;
    }

    @Test
    void storedValuesWin_overDisplayFormats(@TempDir Path tmp) throws Exception {
        Path p = oneSheet(tmp, "2001_round_1.xlsx", wb -> {
            Sheet s = wb.createSheet("Sheet1");
            header(s);
            CellStyle oneDec = wb.createCellStyle();
            oneDec.setDataFormat(wb.createDataFormat().getFormat("0.0"));
            CellStyle noDec = wb.createCellStyle();
            noDec.setDataFormat(wb.createDataFormat().getFormat("#,##0"));
            Row r = s.createRow(1);
            r.createCell(0).setCellValue("Corwin Baxendale");
            Cell hall = r.createCell(1);
            hall.setCellValue(4);
            hall.setCellStyle(oneDec);                 // shows 4.0
            Cell s1 = r.createCell(2);
            s1.setCellValue(102.25);
            s1.setCellStyle(noDec);                    // shows 102
            r.createCell(3).setCellValue("Dara O'Fennelly");
            r.createCell(4).setCellValue("HallA");
            r.createCell(5).setCellFormula("102.35-0.1"); // 102.24999999999999 stored
        });
        List<String[]> rows = readFirst(p);
        assertArrayEquals(new String[]{"Corwin Baxendale", "4", "102.25", "Dara O'Fennelly", "HallA", "102.25"}, rows.get(1));
        assertEquals("4", XlsxUploadReader.normaliseNumber("4.0"));
        assertEquals("0", XlsxUploadReader.normaliseNumber("-0"));
        assertEquals("102.25", XlsxUploadReader.normaliseNumber("102.24999999999999"));
        assertEquals("370", XlsxUploadReader.normaliseNumber("3.7E2"));
    }

    @Test
    void cellHazards_areRejectedNamingSheetAndCell(@TempDir Path tmp) throws Exception {
        // a date (Excel turned "1-2" into 2 January)
        assertEquals("Sheet 'Sheet1', cell C2: holds a date or time (Excel turns entries like 1-2 or 3/4 into dates). "
                + "Format the column as Text or Number and retype the value.", rejection(oneSheet(tmp, "d.xlsx", wb -> {
            Sheet s = wb.createSheet("Sheet1");
            header(s);
            Cell c = s.createRow(1).createCell(2);
            c.setCellValue(java.time.LocalDate.of(2001, 1, 2));
            CellStyle ds = wb.createCellStyle();
            ds.setDataFormat(wb.createDataFormat().getFormat("d-mmm"));
            c.setCellStyle(ds);
        })));
        assertTrue(rejection(oneSheet(tmp, "b.xlsx", wb -> {
            Sheet s = wb.createSheet("Sheet1");
            header(s);
            s.createRow(1).createCell(2).setCellValue(true);
        })).startsWith("Sheet 'Sheet1', cell C2: TRUE/FALSE"));
        assertEquals("Sheet 'Sheet1', cell F2: shows the error #DIV/0!. Fix the formula or type the value.", rejection(oneSheet(tmp, "e.xlsx", wb -> {
            Sheet s = wb.createSheet("Sheet1");
            header(s);
            s.createRow(1).createCell(5).setCellFormula("1/0");
        })));
        assertTrue(rejection(oneSheet(tmp, "n.xlsx", wb -> {
            Sheet s = wb.createSheet("Sheet1");
            header(s);
            Cell c = s.createRow(1).createCell(1);
            c.setCellValue("Corwin\nBaxendale");
        })).contains("cell B2: contains a line break"));
        assertTrue(rejection(oneSheet(tmp, "m.xlsx", wb -> {
            Sheet s = wb.createSheet("Sheet1");
            header(s);
            for (int r = 1; r <= 3; r++) s.createRow(r).createCell(1).setCellValue(1);
            s.addMergedRegion(new CellRangeAddress(1, 3, 1, 1));
        })).contains("cells B2:B4 are merged"));
        assertEquals("Sheet 'Sheet1': row 3 is hidden. Unhide it (or delete it) and upload again.", rejection(oneSheet(tmp, "h.xlsx", wb -> {
            Sheet s = wb.createSheet("Sheet1");
            header(s);
            s.createRow(1).createCell(0).setCellValue("A");
            Row hidden = s.createRow(2);
            hidden.createCell(0).setCellValue("B");
            hidden.setZeroHeight(true);
        })));
        assertTrue(rejection(oneSheet(tmp, "hc.xlsx", wb -> {
            Sheet s = wb.createSheet("Sheet1");
            header(s);
            s.createRow(1).createCell(0).setCellValue("A");
            s.setColumnHidden(2, true);
        })).contains("column C is hidden"));
    }

    @Test
    void formulaWithoutSavedResult_isRejected(@TempDir Path tmp) throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet s = wb.createSheet("Sheet1");
        header(s);
        s.createRow(1).createCell(2).setCellFormula("100+2.25");
        Path p = tmp.resolve("f.xlsx");
        try (OutputStream os = Files.newOutputStream(p)) {
            wb.write(os); // no evaluateAll(): no cached result, as written by many non-Excel tools
        }
        assertTrue(rejection(p).contains("cell C2: has a formula with no saved result"), rejection(p));
    }

    @Test
    void trailingFormattedArea_andUnevenRows_areIgnored(@TempDir Path tmp) throws Exception {
        Path p = oneSheet(tmp, "t.xlsx", wb -> {
            Noise n = new Noise();
            n.styledEmptyArea = true;
            try {
                fill(wb, wb.createSheet("Sheet1"), CsvVariants.read(sample("2001_round_1.csv")), n);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        List<String[]> rows = readFirst(p);
        assertEquals(31, rows.size(), "header + 30 boards; the 5 formatted empty rows are dropped");
        assertEquals(6, rows.get(0).length, "the 3 formatted empty columns are dropped");
    }

    @Test
    void fileLevelHazards_areRejectedWithAFix(@TempDir Path tmp) throws Exception {
        // macro workbook by extension, and by content (an .xlsm renamed .xlsx)
        Path xlsm = tmp.resolve("2001_round_1.xlsm");
        Files.write(xlsm, new byte[]{1});
        assertTrue(assertThrows(Rejected.class, () -> XlsxUploadReader.open(xlsm, XlsxUploadReader.DEFAULT_MAX_BYTES))
                .getMessage().contains("macro-enabled workbook"));
        XSSFWorkbook macro = new XSSFWorkbook(XSSFWorkbookType.XLSM);
        header(macro.createSheet("Sheet1"));
        Path renamed = tmp.resolve("2001_round_2.xlsx");
        save(macro, renamed);
        assertTrue(rejection(renamed).contains("macro-enabled workbook"), rejection(renamed));
        // an old .xls saved under an .xlsx name
        Path xls = tmp.resolve("2001_round_3.xlsx");
        try (HSSFWorkbook old = new HSSFWorkbook(); OutputStream os = Files.newOutputStream(xls)) {
            header(old.createSheet("Sheet1"));
            old.write(os);
        }
        assertTrue(rejection(xls).contains("old-style .xls or a password-protected workbook"), rejection(xls));
        // a CSV renamed .xlsx
        Path csv = tmp.resolve("2001_round_4.xlsx");
        Files.copy(sample("2001_round_1.csv"), csv);
        assertTrue(rejection(csv).contains("is not a valid .xlsx workbook"), rejection(csv));
        // over the size cap
        Path big = tmp.resolve("2001_round_5.xlsx");
        Files.write(big, new byte[1024]);
        assertTrue(assertThrows(Rejected.class, () -> XlsxUploadReader.open(big, 512)).getMessage().contains("the limit is"));
        // too many rows (a pasted export, not a round)
        Path rows = oneSheet(tmp, "2001_round_6.xlsx", wb -> {
            Sheet s = wb.createSheet("Sheet1");
            header(s);
            for (int r = 1; r <= XlsxUploadReader.MAX_ROWS + 5; r++) s.createRow(r).createCell(0).setCellValue("n" + r);
        });
        assertTrue(rejection(rows).contains("has more than " + XlsxUploadReader.MAX_ROWS + " rows"), rejection(rows));
    }

    /**
     * Zip bomb: a valid workbook whose sheet XML is padded with a 40 MB
     * comment (compresses about 1000:1). POI's ZipSecureFile ratio guard
     * (default 1%) trips while streaming the entry; the upload is rejected.
     */
    @Test
    void zipBomb_isRejectedByThePoiGuard(@TempDir Path tmp) throws Exception {
        Path plain = oneSheet(tmp, "plain.xlsx", wb -> header(wb.createSheet("Sheet1")));
        Path bomb = tmp.resolve("2001_round_1.xlsx");
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(plain));
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(bomb))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                byte[] data = in.readAllBytes();
                out.putNextEntry(new ZipEntry(e.getName()));
                if (e.getName().equals("xl/worksheets/sheet1.xml")) {
                    String xml = new String(data, StandardCharsets.UTF_8);
                    int at = xml.indexOf("<sheetData");
                    out.write(xml.substring(0, at).getBytes(StandardCharsets.UTF_8));
                    byte[] pad = new byte[1 << 20];
                    java.util.Arrays.fill(pad, (byte) 'A');
                    out.write("<!--".getBytes(StandardCharsets.UTF_8));
                    for (int i = 0; i < 40; i++) out.write(pad);
                    out.write("-->".getBytes(StandardCharsets.UTF_8));
                    out.write(xml.substring(at).getBytes(StandardCharsets.UTF_8));
                } else {
                    out.write(data);
                }
                out.closeEntry();
            }
        }
        long size = Files.size(bomb);
        assertTrue(size < 200_000, "the bomb is small on disk: " + size);
        String msg = assertThrows(Rejected.class, () -> readFirst(bomb)).getMessage();
        assertTrue(msg.contains("zip bomb guard"), msg);
        System.out.println("[b1] zip bomb (" + size + " bytes on disk, 40 MB inflated): " + msg);
    }
}
