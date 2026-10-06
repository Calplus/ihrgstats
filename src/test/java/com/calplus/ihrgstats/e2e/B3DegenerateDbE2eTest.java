package com.calplus.ihrgstats.e2e;

import com.calplus.ihrgstats.databasemanager.A3_Halls;
import com.calplus.ihrgstats.databasemanager.B4_Players;
import com.calplus.ihrgstats.databasemanager.B5_PlayerNames;
import com.calplus.ihrgstats.databasemanager.B6_PlayerYearStatus;
import com.calplus.ihrgstats.databasemanager.E17_MlModels;
import com.calplus.ihrgstats.telegrambot.utils.RoundCsvProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static com.calplus.ihrgstats.e2e.ConversationDriver.ADMIN;
import static com.calplus.ihrgstats.e2e.ConversationDriver.GROUP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lane b3, section 3: every routed command, as the admin, against five
 * degenerate databases. Each command is walked down one path (first useful
 * button at every keyboard, never Cancel, avoiding a label already chosen on
 * the path) until a step returns no keyboard. Every cell goes to
 * target/e2e-transcripts/b3-degenerate.tsv with a verdict: OK, SILENT (a step
 * got no user-facing reply), CRASH (an "Error processing"/"ERROR:" status
 * line), RAW (exception-like text shown to the user), HTTPnnn / REJECTED
 * (Telegram would refuse the request). Each scenario asserts that every cell
 * is OK except the cells listed in {@link #KNOWN} - today's defects, which a
 * method named ..._knownDefect_... documents; a fix makes that method fail
 * until the entry is removed, and any NEW failing cell fails it too.
 *
 * Run: mvn -o test -Dtest=B3DegenerateDbE2eTest   (~15 min, the trained case ingests 13 rounds)
 */
public class B3DegenerateDbE2eTest {

    /** scenario -> (command -> verdict kind) that fail today. */
    static final java.util.Map<String, java.util.Map<String, String>> KNOWN = java.util.Map.of(
            // B3-30: a rostered player with no boards is offered by the pickers, then the report throws
            // "Player X has no data for <year>" (CommandInfoPlayer.java:187, CommandComparePlayers.java:250) and the
            // reply is "Error generating ...: " + e.getMessage() (CommandInfoPlayer.java:159, CommandComparePlayers.java:221).
            "player-no-games", java.util.Map.of("/infoplayer", "ERROR", "/compareplayers", "ERROR"));

    static final List<String> COMMANDS = List.of("/help", "/about", "/rankplayers", "/rankhalls", "/comparehalls",
            "/compareplayers", "/infoplayer", "/infohall", "/infomatch", "/infomatchhall", "/predict", "/modelstats",
            "/lineup", "/exportdatabase", "/matchtypes", "/admins", "/recalculate", "/settings");
    static final Duration FIRST = Duration.ofSeconds(25);
    static final Duration QUIET = Duration.ofMillis(2000);
    static final int MAX_DEPTH = 6;
    static final Pattern RAW = Pattern.compile(
            "(?i)(exception|java\\.|\\bnull\\b|SQLITE_|at com\\.|stack ?trace|NumberFormat|IndexOutOfBounds|NoSuchElement|\\bNaN\\b|\\bInfinity\\b|For input string)");
    /** "❌ Error generating ...: " + e.getMessage() - a caught exception's text shown as the reply. */
    static final Pattern ERROR_WITH_EXCEPTION_TEXT = Pattern.compile("Error generating [^:]{1,40}: ");
    /** Halls that hold players in the sample corpus and the ROUND_ONE seed. */
    static final Pattern DATA_HALL = Pattern.compile("^([1-8]|Hall ?[1-8]|Hall[ABC])$");
    static final String GHOST = "Zed Quillfeather"; // fictional

    private FakeTelegramServer fake;

    @BeforeEach
    void startFake() throws Exception {
        fake = FakeTelegramServer.start();
    }

    @AfterEach
    void stopFake() {
        fake.close();
    }

    @Test
    void emptyDatabase(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.REFERENCE;
        battery(tmp, "empty", o, null);
    }

    @Test
    void oneRoundOnly(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        battery(tmp, "one-round", o, null);
    }

    @Test
    void roundsButNoCurrentYear(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        o.currentYear = "";
        battery(tmp, "no-current-year", o, null);
    }

    @Test
    void playerWithNoGames_knownDefect_B3_30(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        battery(tmp, "player-no-games", o, () -> {
            // A rostered player (name + year status in hall 1) who never played a board.
            A3_Halls.Hall hall = new A3_Halls().getHallByCode("01");
            if (hall == null) {
                hall = new A3_Halls().getHallByName("1");
            }
            assertNotNull(hall, "hall 1 exists in the seed");
            String now = "2001-06-01 00:00:00.000";
            String id = new B4_Players().generateNewPlayerId(hall.hallCode, now);
            new B5_PlayerNames().addOrUpdateName(id, GHOST, 2001, now);
            new B6_PlayerYearStatus().upsertStatus(id, 2001, hall.id, false, true, now);
            return "ghost " + id + " in hall " + hall.hallName;
        });
    }

    @Test
    void trainedModel(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.REFERENCE;
        o.currentYear = "2002";
        o.extraEnv.put("SETTINGS_HOMEHALL", "3");
        battery(tmp, "trained", o, () -> {
            // 2001 rounds 1-10 + 2002 rounds 1-3: two years, burn-in 10, three walk-forward rounds.
            List<String> files = new ArrayList<>();
            for (int r = 1; r <= 10; r++) files.add("2001_round_" + r + ".csv");
            for (int r = 1; r <= 3; r++) files.add("2002_round_" + r + ".csv");
            Path work = Path.of(System.getProperty("user.dir"));
            StringBuilder sb = new StringBuilder();
            for (String f : files) {
                Path copy = work.resolve("b3-ingest-" + f);
                Files.copy(BotHarness.sampleFile(f), copy);
                int year = Integer.parseInt(f.substring(0, 4));
                int round = Integer.parseInt(f.replaceAll("^\\d{4}_round_(\\d+)\\.csv$", "$1"));
                RoundCsvProcessor p = new RoundCsvProcessor();
                p.setMultiChoiceCallback(BotHarness::autoAnswer);
                boolean ok = p.processRound(copy.toString(), year, round, "2002-06-01 00:00:00.000");
                Files.deleteIfExists(copy);
                assertTrue(ok, "ingest " + f);
            }
            E17_MlModels.MlModel champ = new E17_MlModels().getChampion();
            assertNotNull(champ, "a champion model was trained");
            sb.append("champion ").append(champ.family).append(' ').append(champ.modelVersion);
            return sb.toString();
        });
    }

    interface Setup {
        String run() throws Exception;
    }

    private void battery(Path tmp, String scenario, BotHarness.Options o, Setup setup) throws Exception {
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        StringBuilder tsv = new StringBuilder();
        java.util.Map<String, String> failing = new java.util.TreeMap<>();
        int cells = 0;
        try {
            String setupNote = setup == null ? "" : setup.run();
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            for (String cmd : COMMANDS) {
                String row = walk(scenario, admin, cmd);
                tsv.append(row).append('\n');
                cells++;
                String kinds = kinds(row.split("\t")[2]);
                if (!kinds.isEmpty()) {
                    failing.put(cmd, kinds);
                }
            }
            Path out = A1bSupport.outDir().resolve("b3-degenerate.tsv");
            Files.createDirectories(out.getParent());
            Files.writeString(out, "# " + scenario + (setupNote.isEmpty() ? "" : " (" + setupNote + ")") + "\n" + tsv,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("[B3DegenerateDbE2eTest] " + scenario + " " + setupNote + "\n" + tsv);
        } finally {
            bot.writeTranscript("b3-degenerate-" + scenario);
            bot.close();
        }
        assertEquals(COMMANDS.size(), cells, "every command walked");
        assertEquals(new java.util.TreeMap<>(KNOWN.getOrDefault(scenario, java.util.Map.of())), failing,
                scenario + ": every command must reply, without crash or raw exception text (known defects excepted) - see "
                        + "target/e2e-transcripts/b3-degenerate.tsv");
    }

    /** Failure kinds of a verdict (DEPTH = walker ran out of steps, not a defect). */
    static String kinds(String verdict) {
        Set<String> k = new java.util.TreeSet<>();
        java.util.regex.Matcher m = Pattern.compile("(?:^|,)(SILENT|CRASH|ERROR|RAW|HTTP|REJECTED)(?=[\\[\\d ,]|$)").matcher(verdict);
        while (m.find()) {
            k.add(m.group(1));
        }
        return String.join("+", k);
    }

    private String walk(String scenario, ConversationDriver admin, String cmd) {
        A1bSupport.awaitIdle(fake, Duration.ofSeconds(15));
        int rejectionsBefore = fake.rejections().size();
        int from = fake.callCount();
        admin.sendText(cmd);
        A1bSupport.Step step = A1bSupport.awaitStep(fake, from, GROUP.idString(), null, FIRST, QUIET);
        List<String> path = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        Set<String> verdicts = new LinkedHashSet<>();
        List<String> steps = new ArrayList<>();
        steps.add(cmd + " => " + (step.silent() ? "SILENT " : "") + step.summary(null));
        check(step, verdicts);
        int depth = 0;
        while (step.keyboard() != null && depth < MAX_DEPTH) {
            RecordedCall kb = step.keyboard();
            RecordedCall.Button pick = choose(kb, used);
            if (pick == null) {
                steps.add("(no non-cancel button)");
                break;
            }
            used.add(pick.text());
            path.add(pick.text() + "{" + pick.callbackData() + "}");
            A1bSupport.awaitIdle(fake, Duration.ofSeconds(15));
            int f = fake.callCount();
            admin.clickData(kb, pick.callbackData());
            step = A1bSupport.awaitStep(fake, f, GROUP.idString(), null, FIRST, QUIET);
            steps.add(pick.text() + " => " + (step.silent() ? "SILENT " : "") + step.summary(null));
            check(step, verdicts);
            depth++;
        }
        if (step.keyboard() != null) {
            verdicts.add("DEPTH");
        }
        if (fake.rejections().size() > rejectionsBefore) {
            verdicts.add("REJECTED " + fake.rejections().subList(rejectionsBefore, fake.rejections().size()));
        }
        String verdict = verdicts.isEmpty() ? "OK" : String.join(",", verdicts);
        return scenario + "\t" + cmd + "\t" + verdict + "\t" + (path.isEmpty() ? "(command only)" : String.join(" > ", path))
                + "\t" + String.join(" || ", steps).replace('\t', ' ');
    }

    private static void check(A1bSupport.Step step, Set<String> verdicts) {
        if (step.silent()) {
            verdicts.add("SILENT");
        }
        for (RecordedCall c : A1bSupport.userSends(step.calls(), null)) {
            String t = c.text() != null ? c.text() : c.param("caption");
            if (t != null && ERROR_WITH_EXCEPTION_TEXT.matcher(t).find()) {
                String one = t.replace('\n', '/');
                verdicts.add("ERROR[" + (one.length() > 160 ? one.substring(0, 160) + "..." : one) + "]");
            } else if (t != null && (t.contains("Error processing") || t.contains("ERROR:"))) {
                String one = t.replace('\n', '/');
                verdicts.add("CRASH[" + (one.length() > 160 ? one.substring(0, 160) + "..." : one) + "]");
            } else if (t != null && RAW.matcher(t).find()) {
                String one = t.replace('\n', '/');
                verdicts.add("RAW[" + (one.length() > 160 ? one.substring(0, 160) + "..." : one) + "]");
            }
            if (!c.isOk()) {
                verdicts.add("HTTP" + c.status);
            }
        }
    }

    private static boolean isCancel(RecordedCall.Button b) {
        String d = b.callbackData() == null ? "" : b.callbackData().toLowerCase();
        String t = b.text() == null ? "" : b.text();
        return d.contains("cancel") || d.endsWith("_back") || t.contains("Cancel") || t.contains("❌") || t.contains("Back");
    }

    private static RecordedCall.Button choose(RecordedCall kb, Set<String> used) {
        List<RecordedCall.Button> usable = kb.buttons().stream().filter(b -> b.callbackData() != null && !isCancel(b)).toList();
        if (usable.isEmpty()) {
            return null;
        }
        // Prefer the ghost player, then a label not used yet on this path.
        for (RecordedCall.Button b : usable) {
            if (GHOST.equals(b.text())) {
                return b;
            }
        }
        // Then a hall that has players, not used yet on this path (so /predict, /compareplayers and
        // /lineup reach a report instead of "no players in hall 10").
        for (RecordedCall.Button b : usable) {
            if (b.text() != null && DATA_HALL.matcher(b.text().trim()).matches() && !used.contains(b.text())) {
                return b;
            }
        }
        for (RecordedCall.Button b : usable) {
            if (!used.contains(b.text())) {
                return b;
            }
        }
        return usable.get(0);
    }
}
