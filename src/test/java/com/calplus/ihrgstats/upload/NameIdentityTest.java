package com.calplus.ihrgstats.upload;

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
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.calplus.ihrgstats.upload.UploadTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Lane b1, scope item 2: name identity. A player is uploaded in round 1
 * under a canonical name and in round 2 under a variant that a person would
 * read as the same name (same hall, same year, same opponent). Each pair
 * runs in its own fresh database. Outcome per pair:
 * <ul>
 *   <li>EXACT - same player, no question asked;</li>
 *   <li>DIALOG - the admin is asked (answered "same person" here);</li>
 *   <li>SILENT_DUPLICATE - a second player is created with no question: a bug.</li>
 * </ul>
 * All names are invented. Artefact: {@code target/b1/name-identity.txt}.
 */
public class NameIdentityTest {

    enum Kind { EXACT, DIALOG, SILENT_DUPLICATE }

    record Probe(String id, String canonical, String variant) {}

    private static final String OPPONENT = "Quentin Abernathy";

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

    static String nfd(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD);
    }

    /** Full-width Latin letters (U+FF21..), as CJK input methods type them. */
    static String fullWidth(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) b.append(c >= '!' && c <= '~' ? (char) (c - '!' + 0xFF01) : c);
        return b.toString();
    }

    static final List<Probe> PROBES = List.of(
            new Probe("ascii-case", "Corwin Baxendale", "CORWIN BAXENDALE"),
            new Probe("outer-spaces", "Corwin Baxendale", "  Corwin Baxendale "),
            new Probe("double-space", "Corwin Baxendale", "Corwin  Baxendale"),
            new Probe("four-spaces", "Corwin Baxendale", "Corwin    Baxendale"),
            new Probe("tab-inside", "Corwin Baxendale", "Corwin\tBaxendale"),
            new Probe("nbsp-inside", "Corwin Baxendale", "Corwin\u00A0Baxendale"),
            new Probe("nbsp-trailing", "Corwin Baxendale", "Corwin Baxendale\u00A0"),
            new Probe("zwsp-inside", "Corwin Baxendale", "Corwin Bax\u200Bendale"),
            new Probe("bom-leading", "Corwin Baxendale", "\uFEFFCorwin Baxendale"),
            new Probe("curly-apostrophe", "Dara O'Fennelly", "Dara O\u2019Fennelly"),
            new Probe("en-dash", "Ines Ortiz-Calloway", "Ines Ortiz\u2013Calloway"),
            new Probe("nfd-one-accent", "Zo\u00EB Quillfeather", nfd("Zo\u00EB Quillfeather")),
            new Probe("nfd-three-accents", "Ren\u00E9e Lef\u00E8vre-Aub\u00E9", nfd("Ren\u00E9e Lef\u00E8vre-Aub\u00E9")),
            new Probe("nfd-vietnamese-style", "L\u1EF1u Th\u1EA3o M\u1EF9", nfd("L\u1EF1u Th\u1EA3o M\u1EF9")),
            new Probe("full-width-latin", "Kai Rossette", fullWidth("Kai") + " " + fullWidth("Rossette")),
            new Probe("nonascii-case-latin", "\u00C9mile Dravencourt", "\u00C9MILE DRAVENCOURT"),
            new Probe("nonascii-case-lower", "\u00C9mile Dravencourt", "\u00E9mile dravencourt"),
            new Probe("ascii-case-only-around-accent", "Zo\u00EB Quillfeather", "zo\u00EB quillfeather"),
            new Probe("nonascii-case-upper", "Zo\u00EB Quillfeather", "ZO\u00CB QUILLFEATHER"),
            new Probe("cyrillic-case", "\u0410\u043B\u0451\u043D\u0430 \u0412\u0435\u0440\u0435\u0441\u043A", "\u0410\u041B\u0401\u041D\u0410 \u0412\u0415\u0420\u0415\u0421\u041A"),
            new Probe("short-name-zwsp", "Ix", "Ix\u200B"),
            new Probe("cjk-identical", "\u6797\u5C0F\u96E8", "\u6797\u5C0F\u96E8"),
            new Probe("cjk-ideographic-space", "\u6797 \u5C0F\u96E8", "\u6797\u3000\u5C0F\u96E8"));

    /** What each probe does TODAY (asserted). SILENT_DUPLICATE entries are the B1-6 defect. */
    static final Map<String, Kind> TODAY = new LinkedHashMap<>();
    static {
        TODAY.put("ascii-case", Kind.EXACT);
        TODAY.put("outer-spaces", Kind.EXACT);
        TODAY.put("double-space", Kind.DIALOG);
        TODAY.put("four-spaces", Kind.SILENT_DUPLICATE);
        TODAY.put("tab-inside", Kind.DIALOG);
        TODAY.put("nbsp-inside", Kind.DIALOG);
        TODAY.put("nbsp-trailing", Kind.DIALOG);
        TODAY.put("zwsp-inside", Kind.DIALOG);
        TODAY.put("bom-leading", Kind.DIALOG);
        TODAY.put("curly-apostrophe", Kind.DIALOG);
        TODAY.put("en-dash", Kind.DIALOG);
        TODAY.put("nfd-one-accent", Kind.DIALOG);
        TODAY.put("nfd-three-accents", Kind.SILENT_DUPLICATE);
        TODAY.put("nfd-vietnamese-style", Kind.SILENT_DUPLICATE);
        TODAY.put("full-width-latin", Kind.SILENT_DUPLICATE);
        TODAY.put("nonascii-case-latin", Kind.EXACT);
        TODAY.put("ascii-case-only-around-accent", Kind.EXACT);
        TODAY.put("nonascii-case-lower", Kind.SILENT_DUPLICATE);
        TODAY.put("nonascii-case-upper", Kind.SILENT_DUPLICATE);
        TODAY.put("cyrillic-case", Kind.SILENT_DUPLICATE);
        TODAY.put("short-name-zwsp", Kind.SILENT_DUPLICATE);
        TODAY.put("cjk-identical", Kind.EXACT);
        TODAY.put("cjk-ideographic-space", Kind.DIALOG);
    }

    record Observed(Kind kind, List<String> dialogs, int players, String storedNames) {}

    static Path roundFile(Path dir, String file, String probeName) throws Exception {
        Files.createDirectories(dir);
        Path p = dir.resolve(file);
        Files.writeString(p, "name1,hall1,score1,name2,hall2,score2\r\n"
                + quote(probeName) + ",1,120," + OPPONENT + ",2,80\r\n", StandardCharsets.UTF_8);
        return p;
    }

    static String quote(String v) {
        return v.contains(",") || v.contains("\"") ? '"' + v.replace("\"", "\"\"") + '"' : v;
    }

    static Observed observe(Path home, Probe probe) throws Exception {
        initHome(home);
        Dialogs dialogs = new Dialogs(List.of(new Script("may match existing player", 0)));
        Outcome r1 = round(roundFile(home.resolve("csv"), "r1.csv", probe.canonical()), 2001, 1, dialogs);
        assertTrue(r1.ok(), probe.id() + " round 1: " + r1.joined());
        assertEquals(0, dialogs.seen.size(), probe.id() + ": round 1 must not ask anything");
        Outcome r2 = round(roundFile(home.resolve("csv"), "r2.csv", probe.variant()), 2001, 2, dialogs);
        assertTrue(r2.ok(), probe.id() + " round 2: " + r2.joined() + " dialogs " + dialogs.seen);
        int players = playerCount();
        StringBuilder names = new StringBuilder();
        for (String[] r : query("SELECT player_id, name FROM player_names ORDER BY player_id, name")) {
            names.append(r[0]).append('=').append(UploadVariantsTest.printable(r[1])).append("; ");
        }
        Kind kind;
        if (!dialogs.seen.isEmpty()) kind = Kind.DIALOG;
        else kind = players == 2 ? Kind.EXACT : Kind.SILENT_DUPLICATE;
        return new Observed(kind, dialogs.seen, players, names.toString());
    }

    /**
     * Runs every probe and asserts today's outcome. The SILENT_DUPLICATE rows
     * are finding B1-6; the DIALOG rows whose two names render identically
     * are finding B1-7 (see the artefact).
     */
    @Test
    void nameVariants_sameHallSameYear_outcomeToday_knownDefect_B1_6(@TempDir Path tmp) throws Exception {
        StringBuilder report = new StringBuilder("probe | today | players after round 2 | stored names | dialog shown to the admin\n");
        List<String> mismatches = new ArrayList<>();
        int silent = 0;
        for (Probe p : PROBES) {
            Observed o = observe(tmp.resolve(p.id()), p);
            if (o.kind() == Kind.SILENT_DUPLICATE) silent++;
            if (o.kind() != TODAY.get(p.id())) mismatches.add(p.id() + ": expected " + TODAY.get(p.id()) + " got " + o.kind());
            if (o.kind() == Kind.DIALOG) {
                assertEquals(2, o.players(), p.id() + ": answering 'same person' must merge");
            }
            report.append(String.format("%-22s| %-16s| %d | %s| %s%n", p.id(), o.kind(), o.players(), o.storedNames(),
                    o.dialogs().isEmpty() ? "-" : UploadVariantsTest.printable(o.dialogs().get(0).replace("\n", " / "))));
        }
        Path out = Paths.get(originalUserDir, "target", "b1");
        Files.createDirectories(out);
        Files.writeString(out.resolve("name-identity.txt"), report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);
        assertEquals(PROBES.size(), TODAY.size());
        assertTrue(mismatches.isEmpty(), "today's behaviour changed:\n" + String.join("\n", mismatches));
        assertEquals(8, silent, "silent duplicates today");
    }

    /**
     * Halls are never created by an upload, so a hall variant can only be
     * accepted as the same hall or rejected - never duplicated. ASCII case is
     * matched; every other variant is rejected naming the cell value.
     */
    @Test
    void hallVariants_areMatchedOrRejected_neverDuplicated(@TempDir Path tmp) throws Exception {
        Map<String, String> hallVariants = new LinkedHashMap<>();
        hallVariants.put("halla", "ACCEPT");
        hallVariants.put("HALLA", "ACCEPT");
        hallVariants.put(" HallA ", "ACCEPT");
        hallVariants.put("HallA\u00A0", "REJECT");
        hallVariants.put("Hall\u200BA", "REJECT");
        hallVariants.put(fullWidth("HallA"), "REJECT");
        hallVariants.put("1.0", "REJECT");
        hallVariants.put("01", "REJECT");
        hallVariants.put(fullWidth("1"), "REJECT");
        StringBuilder report = new StringBuilder();
        int i = 0;
        for (Map.Entry<String, String> e : hallVariants.entrySet()) {
            Path home = tmp.resolve("h" + (i++));
            initHome(home);
            int hallsBefore = Integer.parseInt(query("SELECT COUNT(*) FROM halls").get(0)[0]);
            Path csv = home.resolve("r1.csv");
            Files.writeString(csv, "name1,hall1,score1,name2,hall2,score2\r\nCorwin Baxendale," + e.getKey() + ",120," + OPPONENT + ",2,80\r\n");
            Outcome o = round(csv, 2001, 1, new Dialogs(List.of()));
            int hallsAfter = Integer.parseInt(query("SELECT COUNT(*) FROM halls").get(0)[0]);
            assertEquals(hallsBefore, hallsAfter, "an upload must never create a hall");
            assertEquals(e.getValue().equals("ACCEPT"), o.ok(), UploadVariantsTest.printable(e.getKey()) + ": " + o.joined());
            if (o.ok()) {
                assertEquals("HA", query("SELECT h.hall_code FROM player_year_status s JOIN halls h ON h.id = s.hall_id "
                        + "JOIN player_names n ON n.player_id = s.player_id WHERE n.name = 'Corwin Baxendale'").get(0)[0]);
            } else {
                assertTrue(o.joined().contains("Unknown hall: '" + e.getKey().trim() + "'"), o.joined());
            }
            report.append(UploadVariantsTest.printable(e.getKey())).append(" -> ").append(o.ok() ? "same hall HallA" : UploadVariantsTest.printable(o.messages().get(0))).append('\n');
        }
        System.out.println(report);
    }

    /**
     * B1-8: the capped list is matched to players by exact text (ASCII case
     * folded only), with no fuzzy step and no report of entries that never
     * match. A list typed with a curly apostrophe, a double space, a trailing
     * no-break space, NFD accents or a non-ASCII case change leaves the
     * player silently NOT capped; the upload reports success either way.
     */
    @Test
    void cappedListNameVariants_playerSilentlyNotCapped_knownDefect_B1_8(@TempDir Path tmp) throws Exception {
        Map<String, String[]> cases = new LinkedHashMap<>(); // id -> {name in capped list, name in round file}
        cases.put("control-ascii-case", new String[]{"CORWIN BAXENDALE", "Corwin Baxendale"});
        cases.put("curly-apostrophe", new String[]{"Dara O\u2019Fennelly", "Dara O'Fennelly"});
        cases.put("double-space", new String[]{"Corwin  Baxendale", "Corwin Baxendale"});
        cases.put("nbsp-trailing", new String[]{"Corwin Baxendale\u00A0", "Corwin Baxendale"});
        cases.put("nfd-one-accent", new String[]{nfd("Zo\u00EB Quillfeather"), "Zo\u00EB Quillfeather"});
        cases.put("nonascii-case", new String[]{"ZO\u00CB QUILLFEATHER", "Zo\u00EB Quillfeather"});
        StringBuilder report = new StringBuilder();
        int i = 0;
        for (Map.Entry<String, String[]> c : cases.entrySet()) {
            Path home = tmp.resolve("c" + (i++));
            initHome(home);
            Path list = home.resolve("2001_cappedlist.csv");
            Files.writeString(list, "name,hall\r\n" + quote(c.getValue()[0]) + ",1\r\n", StandardCharsets.UTF_8);
            Outcome capped = capped(list, 2001);
            assertTrue(capped.ok(), capped.joined());
            Outcome r1 = round(roundFile(home.resolve("csv"), "r1.csv", c.getValue()[1]), 2001, 1, new Dialogs(List.of()));
            assertTrue(r1.ok(), r1.joined());
            String flag = query("SELECT s.capped FROM player_year_status s JOIN player_names n ON n.player_id = s.player_id "
                    + "WHERE s.year = 2001 AND n.name = '" + c.getValue()[1].replace("'", "''") + "'").get(0)[0];
            String mapped = query("SELECT mapped FROM capped_imports WHERE year = 2001").get(0)[0];
            boolean isCapped = flag.equals("1") || flag.equalsIgnoreCase("true");
            report.append(String.format("%-20s capped=%s importMapped=%s uploadSaid=%s%n", c.getKey(), isCapped, mapped,
                    UploadVariantsTest.printable(capped.joined())));
            if (c.getKey().startsWith("control")) {
                assertTrue(isCapped, "ASCII case difference must still cap the player");
            } else {
                assertFalse(isCapped, c.getKey() + ": today the player is NOT capped (fixed? flip this assertion)");
                assertTrue(mapped.equals("0") || mapped.equalsIgnoreCase("false"), c.getKey() + ": the list row stays unclaimed");
                assertTrue(r1.messages().stream().noneMatch(m -> m.toLowerCase().contains("capped")),
                        c.getKey() + ": the round upload says nothing about the unmatched capped entry");
            }
        }
        System.out.println(report);
    }

    /**
     * B1-5: Excel's plain "CSV (Comma delimited)" on a Western Windows writes
     * Windows-1252. The processors read with the JVM default charset (UTF-8),
     * so every accented letter and curly quote becomes U+FFFD - accepted and
     * stored silently as the player's name.
     */
    @Test
    void windows1252AccentedNames_storedAsReplacementCharacter_knownDefect_B1_5(@TempDir Path tmp) throws Exception {
        Path home = tmp.resolve("cp1252");
        initHome(home);
        Path r1 = home.resolve("r1.csv");
        Files.write(r1, ("name1,hall1,score1,name2,hall2,score2\r\n"
                + "Zo\u00EB Quillfeather,1,120,Ren\u00E9e Marchetti,2,80\r\n"
                + "Dara O\u2019Fennelly,3,90,Quentin Abernathy,4,60\r\n").getBytes(CsvVariants.WINDOWS_1252));
        Outcome o = round(r1, 2001, 1, new Dialogs(List.of()));
        assertTrue(o.ok(), "today the file is accepted without a word: " + o.joined());
        List<String> stored = new ArrayList<>();
        for (String[] r : query("SELECT name FROM player_names ORDER BY name")) stored.add(r[0]);
        assertTrue(stored.contains("Zo\uFFFD Quillfeather"), "stored: " + stored);
        assertTrue(stored.contains("Ren\uFFFDe Marchetti"), "stored: " + stored);
        assertTrue(stored.contains("Dara O\uFFFDFennelly"), "stored: " + stored);
        assertTrue(o.messages().stream().noneMatch(m -> m.contains("encoding") || m.contains("\uFFFD")),
                "no warning today: " + o.joined());

        // The same names later uploaded correctly (UTF-8) are no longer the same text.
        Dialogs d = new Dialogs(List.of());
        d.fallbackAnswer = 1; // "Treat as different people"
        Path r2 = home.resolve("r2.csv");
        Files.writeString(r2, "name1,hall1,score1,name2,hall2,score2\r\n"
                + "Zo\u00EB Quillfeather,1,120,Ren\u00E9e Marchetti,2,80\r\n", StandardCharsets.UTF_8);
        Outcome o2 = round(r2, 2001, 2, d);
        assertTrue(o2.ok(), o2.joined());
        assertEquals(2, d.seen.size(), "each corrupted name now triggers a 'possible spelling' dialog: " + d.seen);
        assertTrue(d.seen.get(0).contains("\uFFFD"), "the dialog shows the replacement character to the admin");
        System.out.println("[b1] cp1252 stored names: " + UploadVariantsTest.printable(String.join(" | ", stored))
                + "\n[b1] dialog: " + UploadVariantsTest.printable(d.seen.get(0).replace("\n", " / ")));
    }

    /**
     * Formula-like NAMES (=, +, -, @) are stored verbatim, and the .xlsx
     * database export writes them as text cells - Excel will not evaluate
     * them (no formula injection through /exportdatabase).
     */
    @Test
    void formulaLikeNames_storedVerbatim_andExportedAsTextCells(@TempDir Path tmp) throws Exception {
        String[] names = {"=HYPERLINK(\"http://example.invalid\",\"x\")", "+Corwin Baxendale", "-Dara Fennelly", "@Ines Calloway"};
        System.setProperty("TELEGRAM_ADMIN_USERID", "b1_admin");
        try {
            initHome(tmp.resolve("h"));
            StringBuilder csv = new StringBuilder("name1,hall1,score1,name2,hall2,score2\r\n");
            csv.append(quote(names[0])).append(",1,120,").append(quote(names[1])).append(",2,80\r\n");
            csv.append(quote(names[2])).append(",3,90,").append(quote(names[3])).append(",4,60\r\n");
            Path r1 = tmp.resolve("r1.csv");
            Files.writeString(r1, csv.toString(), StandardCharsets.UTF_8);
            Outcome o = round(r1, 2001, 1, new Dialogs(List.of()));
            assertTrue(o.ok(), o.joined());
            for (String n : names) {
                assertEquals(1, query("SELECT COUNT(*) FROM player_names WHERE name = '" + n.replace("'", "''") + "'").size());
                assertEquals("1", query("SELECT COUNT(*) FROM player_names WHERE name = '" + n.replace("'", "''") + "'").get(0)[0], n);
            }
            var export = new com.calplus.ihrgstats.telegrambot.commands.CommandExportDatabase().executeXlsxExport("b1_admin");
            assertTrue(export.success, export.message);
            int found = 0;
            try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(Files.newInputStream(export.exportedFilePath))) {
                for (var row : wb.getSheet("player_names")) {
                    for (var cell : row) {
                        assertNotEquals(org.apache.poi.ss.usermodel.CellType.FORMULA, cell.getCellType(), "no formula cells in the export");
                        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                                && java.util.Arrays.asList(names).contains(cell.getStringCellValue())) found++;
                    }
                }
            }
            assertEquals(names.length, found, "every formula-like name is a plain text cell in the export");
        } finally {
            System.clearProperty("TELEGRAM_ADMIN_USERID");
        }
    }
}
