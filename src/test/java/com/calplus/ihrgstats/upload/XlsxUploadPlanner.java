package com.calplus.ihrgstats.upload;

import com.calplus.ihrgstats.databasemanager.A1_Rounds;
import com.calplus.ihrgstats.databasemanager.A3_Halls;
import com.calplus.ihrgstats.telegrambot.utils.CappedListProcessor;
import com.calplus.ihrgstats.telegrambot.utils.RoundCsvProcessor;
import com.calplus.ihrgstats.upload.XlsxUploadReader.Rejected;
import com.calplus.ihrgstats.upload.XlsxUploadReader.SheetInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lane b1 PROTOTYPE (test scope) of the proposed {@code .xlsx} upload rules -
 * see review record b1_xlsx_design.md for the owner-facing proposal.
 *
 * <p>File names (case-insensitive, same scheme as the CSV files):
 * {@code [YYYY_]round_N.xlsx} = one round, first sheet;
 * {@code [YYYY_]cappedlist.xlsx} = the capped list, first sheet;
 * {@code [YYYY_]rounds.xlsx} = a multi-round workbook, one round per sheet.
 *
 * <p>Sheet names in a multi-round workbook: {@code Round 3}, {@code round_3},
 * {@code R3}, {@code 2001 Round 3}, {@code 2001_round_3} (a year in the sheet
 * name must equal the file's year) and optionally {@code Capped list} /
 * {@code cappedlist}. Other sheets and hidden sheets are skipped and listed
 * in the reply. Order: capped list first, then rounds ascending by number
 * (tab order does not matter). Rounds already stored for the year are
 * skipped (re-uploading after a failure resumes where it stopped).
 *
 * <p>Every sheet is read and checked (structure, halls) BEFORE anything is
 * written; a round that then fails (cancelled dialog, database error) stops
 * the run - earlier rounds stay stored, later ones are not processed, and
 * the reply says exactly which.
 */
public final class XlsxUploadPlanner {

    public enum Kind { SINGLE_ROUND, SINGLE_CAPPED, MULTI_ROUND, UNKNOWN }

    public record FileRole(Kind kind, Integer year, int round) {}

    private static final Pattern ROUND_FILE = Pattern.compile("^(?:(\\d{4})_)?round_(\\d+)\\.xlsx$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CAPPED_FILE = Pattern.compile("^(?:(\\d{4})_)?cappedlist\\.xlsx$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MULTI_FILE = Pattern.compile("^(?:(\\d{4})_)?rounds\\.xlsx$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROUND_SHEET = Pattern.compile("^(?:(\\d{4})[ _-]*)?(?:round|r)[ _-]*(\\d{1,3})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CAPPED_SHEET = Pattern.compile("^(?:(\\d{4})[ _-]*)?capped[ _-]*list$", Pattern.CASE_INSENSITIVE);

    private XlsxUploadPlanner() {}

    public static FileRole classify(String fileName) {
        Matcher m;
        if ((m = ROUND_FILE.matcher(fileName)).matches()) return new FileRole(Kind.SINGLE_ROUND, year(m.group(1)), Integer.parseInt(m.group(2)));
        if ((m = CAPPED_FILE.matcher(fileName)).matches()) return new FileRole(Kind.SINGLE_CAPPED, year(m.group(1)), 0);
        if ((m = MULTI_FILE.matcher(fileName)).matches()) return new FileRole(Kind.MULTI_ROUND, year(m.group(1)), 0);
        return new FileRole(Kind.UNKNOWN, null, 0);
    }

    private static Integer year(String g) {
        return g == null ? null : Integer.parseInt(g);
    }

    /** One unit of work: a sheet that becomes one capped-list or round upload. */
    public record Step(SheetInfo sheet, boolean capped, int round, List<String[]> rows) {}

    public record Plan(int year, List<Step> steps, List<String> notes) {}

    /**
     * Reads and checks every sheet the upload will use, without writing
     * anything. {@code currentYear} stands in for settings.currentYear when
     * the file name has no year.
     */
    public static Plan plan(Path file, XlsxUploadReader wb, Integer currentYear) throws Rejected, Exception {
        String name = file.getFileName().toString();
        FileRole role = classify(name);
        if (role.kind() == Kind.UNKNOWN) {
            throw new Rejected("Unknown file name: " + name + ". Accepted: [YYYY_]round_N.xlsx, [YYYY_]cappedlist.xlsx, "
                    + "[YYYY_]rounds.xlsx (one round per sheet), or the same names as .csv.");
        }
        Integer year = role.year() != null ? role.year() : currentYear;
        if (year == null) {
            throw new Rejected(name + " has no year and no current year is set. Name it " + "YYYY_" + name + " or set settings.currentYear.");
        }
        List<String> notes = new ArrayList<>();
        List<Step> steps = new ArrayList<>();
        if (wb.sheets().isEmpty()) throw new Rejected(name + " has no sheets.");

        if (role.kind() != Kind.MULTI_ROUND) {
            SheetInfo first = wb.sheets().get(0);
            if (!first.visible()) {
                throw new Rejected(name + ": the first sheet ('" + first.name() + "') is hidden. Move the round's sheet to the first tab.");
            }
            for (SheetInfo s : wb.sheets().subList(1, wb.sheets().size())) notes.add("Sheet '" + s.name() + "' ignored (only the first sheet is read).");
            boolean capped = role.kind() == Kind.SINGLE_CAPPED;
            steps.add(new Step(first, capped, role.round(), checked(wb, first, capped)));
            return new Plan(year, steps, notes);
        }

        TreeMap<Integer, SheetInfo> rounds = new TreeMap<>();
        SheetInfo cappedSheet = null;
        for (SheetInfo s : wb.sheets()) {
            String sn = s.name().trim();
            Matcher rm = ROUND_SHEET.matcher(sn);
            Matcher cm = CAPPED_SHEET.matcher(sn);
            if (!s.visible()) {
                notes.add("Hidden sheet '" + s.name() + "' skipped.");
                continue;
            }
            if (rm.matches()) {
                if (rm.group(1) != null && Integer.parseInt(rm.group(1)) != year) {
                    throw new Rejected("Sheet '" + s.name() + "' is for " + rm.group(1) + " but the file is for " + year + ". Nothing was processed.");
                }
                int n = Integer.parseInt(rm.group(2));
                if (n < 1) throw new Rejected("Sheet '" + s.name() + "': round numbers start at 1. Nothing was processed.");
                SheetInfo dup = rounds.put(n, s);
                if (dup != null) {
                    throw new Rejected("Sheets '" + dup.name() + "' and '" + s.name() + "' are both round " + n + ". Nothing was processed.");
                }
            } else if (cm.matches()) {
                if (cm.group(1) != null && Integer.parseInt(cm.group(1)) != year) {
                    throw new Rejected("Sheet '" + s.name() + "' is for " + cm.group(1) + " but the file is for " + year + ". Nothing was processed.");
                }
                if (cappedSheet != null) throw new Rejected("Two capped-list sheets: '" + cappedSheet.name() + "' and '" + s.name() + "'. Nothing was processed.");
                cappedSheet = s;
            } else {
                notes.add("Sheet '" + s.name() + "' skipped (not a round or capped-list sheet name).");
            }
        }
        if (rounds.isEmpty() && cappedSheet == null) {
            throw new Rejected(name + " has no sheet named like 'Round 1' or 'Capped list'. Nothing was processed.");
        }
        int latest = new A1_Rounds().getLatestRoundOrder(year);
        int expected = latest + 1;
        if (cappedSheet != null) steps.add(new Step(cappedSheet, true, 0, checked(wb, cappedSheet, true)));
        for (var e : rounds.entrySet()) {
            if (e.getKey() <= latest) {
                notes.add("Round " + e.getKey() + " ('" + e.getValue().name() + "') is already stored for " + year
                        + " - skipped. To replace a stored round, upload it on its own as " + year + "_round_" + e.getKey() + ".xlsx or .csv.");
                continue;
            }
            if (e.getKey() != expected) {
                throw new Rejected("Round " + expected + " is missing: the workbook goes from round " + (expected - 1 == latest ? latest + " (stored)" : String.valueOf(expected - 1))
                        + " to '" + e.getValue().name() + "'. Nothing was processed.");
            }
            steps.add(new Step(e.getValue(), false, e.getKey(), checked(wb, e.getValue(), false)));
            expected++;
        }
        return new Plan(year, steps, notes);
    }

    /** Reads a sheet and runs the structural checks that need no dialog (header, halls). */
    private static List<String[]> checked(XlsxUploadReader wb, SheetInfo s, boolean capped) throws Exception {
        List<String[]> rows = wb.readRows(s);
        if (rows.isEmpty()) throw new Rejected("Sheet '" + s.name() + "' is empty. Nothing was processed.");
        String[] header = rows.get(0);
        String[] want = capped ? new String[]{"name", "hall"} : new String[]{"name1", "hall1", "score1", "name2", "hall2", "score2"};
        if (header.length != want.length) {
            throw new Rejected(String.format("Sheet '%s', row 1: the header must be exactly %s (found %d columns). Nothing was processed.",
                    s.name(), String.join(" | ", want), header.length));
        }
        for (int i = 0; i < want.length; i++) {
            if (!header[i].trim().equalsIgnoreCase(want[i])) {
                throw new Rejected(String.format("Sheet '%s', cell %s1: expected '%s', found '%s'. Nothing was processed.",
                        s.name(), XlsxUploadReader.columnName(i), want[i], header[i]));
            }
        }
        A3_Halls halls = new A3_Halls();
        int[] hallCols = capped ? new int[]{1} : new int[]{1, 4};
        for (int r = 1; r < rows.size(); r++) {
            String[] row = rows.get(r);
            for (int c : hallCols) {
                String h = c < row.length ? row[c].trim() : "";
                boolean walkoverSide = !capped && (c == 1 ? row[0] : (row.length > 3 ? row[3] : "")).trim().equalsIgnoreCase("WALKOVER");
                if (h.isEmpty() || walkoverSide) continue;
                if (halls.getHallByName(h) == null) {
                    throw new Rejected(String.format("Sheet '%s', cell %s%d: unknown hall '%s'. Nothing was processed.",
                            s.name(), XlsxUploadReader.columnName(c), r + 1, h));
                }
            }
        }
        return rows;
    }

    /** What happened to a run: per-step lines for the reply, and whether it completed. */
    public record RunReport(List<String> lines, boolean complete, int stepsDone) {}

    /**
     * Processes a plan step by step through the EXISTING processors (via a
     * temp CSV per sheet - the prototype's only bridge). Stops at the first
     * failure.
     */
    public static RunReport run(Plan plan, Path workDir, String now, RoundCsvProcessor.MultiChoiceCallback dialogs,
                                Consumer<String> chat) throws Exception {
        List<String> lines = new ArrayList<>(plan.notes());
        Files.createDirectories(workDir);
        int done = 0;
        int total = plan.steps().size();
        for (Step step : plan.steps()) {
            Path csv = workDir.resolve((step.capped() ? "cappedlist" : "round_" + step.round()) + ".csv");
            XlsxUploadReader.writeCsv(step.rows(), csv);
            boolean ok;
            String label = step.capped() ? "capped list" : "round " + step.round();
            if (step.capped()) {
                CappedListProcessor p = new CappedListProcessor();
                p.setUploadChatCallback(chat::accept);
                ok = p.processCappedList(csv.toString(), plan.year(), now);
            } else {
                RoundCsvProcessor p = new RoundCsvProcessor();
                p.setMultiChoiceCallback(dialogs);
                p.setUploadChatCallback(chat::accept);
                ok = p.processRound(csv.toString(), plan.year(), step.round(), now);
            }
            if (!ok) {
                List<String> rest = new ArrayList<>();
                for (Step s : plan.steps().subList(done + 1, total)) rest.add(s.capped() ? "capped list" : "round " + s.round());
                lines.add(String.format("Stopped at %s (sheet '%s'), step %d of %d: see the message above. %s Not processed: %s. "
                                + "Fix the sheet and upload the same workbook again - stored rounds are skipped.",
                        label, step.sheet().name(), done + 1, total,
                        done == 0 ? "Nothing was stored." : "Stored before it: " + done + " step(s).",
                        rest.isEmpty() ? "nothing else" : String.join(", ", rest)));
                return new RunReport(lines, false, done);
            }
            done++;
            lines.add(String.format("%d of %d: %s (sheet '%s') done.", done, total, label, step.sheet().name()));
        }
        return new RunReport(lines, true, done);
    }

    static Comparator<Step> order() {
        return Comparator.comparing((Step s) -> !s.capped()).thenComparingInt(Step::round);
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
