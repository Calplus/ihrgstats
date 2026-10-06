package com.calplus.ihrgstats.e2e;

import com.calplus.ihrgstats.perf.ScaleDbBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static com.calplus.ihrgstats.e2e.ConversationDriver.ADMIN;
import static com.calplus.ihrgstats.e2e.ConversationDriver.GROUP;

/**
 * Lane b2, section 2: every routed command walked by the admin through the rig against a
 * stored scale database ({@code -Db2.db=<default.db> -Db2.size=1x}), each variant N times
 * (default 3) on a fresh copy of the database and a fresh fake server. Per run and step it
 * records the wall time from the injected update to the last user-facing send, the heap pool
 * peak during the run and heap after GC, text message count and lengths, keyboard button
 * counts, image and document sizes, and every Bot API rule violation the fake saw.
 *
 * <pre>mvn -o test -Dtest=B2CommandScaleE2eTest -Db2.db=... -Db2.size=1x [-Db2.runs=3] [-Db2.only=/rankplayers,...]</pre>
 */
public class B2CommandScaleE2eTest {

    private static final Pattern NOT_FORWARD = Pattern.compile("(?i).*(cancel|back|_no_|help_back).*");

    /** One walk: the command and a chooser for each keyboard (null = stop at that keyboard). */
    record Variant(String name, String command, int maxClicks, List<Predicate<RecordedCall.Button>> prefs) {
    }

    static Predicate<RecordedCall.Button> data(String regex) {
        Pattern p = Pattern.compile(regex);
        return b -> b.callbackData() != null && p.matcher(b.callbackData()).find();
    }

    static Predicate<RecordedCall.Button> label(String regex) {
        Pattern p = Pattern.compile(regex);
        return b -> b.text() != null && p.matcher(b.text()).find();
    }

    static List<Variant> variants() {
        Predicate<RecordedCall.Button> hall1 = label("^1$");
        Predicate<RecordedCall.Button> hall2 = label("^2$");
        Predicate<RecordedCall.Button> allYears = data("_allyears$");
        Predicate<RecordedCall.Button> all = data("_all$");
        List<Variant> v = new ArrayList<>();
        v.add(new Variant("help", "/help", 0, List.of()));
        v.add(new Variant("about", "/about", 0, List.of()));
        v.add(new Variant("rankplayers-all", "/rankplayers", 4, List.of(all)));
        v.add(new Variant("rankplayers-allyears", "/rankplayers", 4, List.of(allYears)));
        v.add(new Variant("rankhalls-all", "/rankhalls", 4, List.of(all)));
        v.add(new Variant("rankhalls-allyears", "/rankhalls", 4, List.of(allYears)));
        v.add(new Variant("infoplayer-all", "/infoplayer", 6, List.of(hall1, all)));
        v.add(new Variant("infoplayer-allyears", "/infoplayer", 6, List.of(hall1, allYears)));
        v.add(new Variant("infohall-all", "/infohall", 6, List.of(hall1, all)));
        v.add(new Variant("infohall-allyears", "/infohall", 6, List.of(hall1, allYears)));
        v.add(new Variant("infomatch-latest", "/infomatch", 6, List.of(data("latest"))));
        v.add(new Variant("infomatchhall", "/infomatchhall", 6, List.of(hall1, data("latest"))));
        v.add(new Variant("comparehalls-all", "/comparehalls", 6, List.of(hall1, hall2, all)));
        v.add(new Variant("comparehalls-allyears", "/comparehalls", 6, List.of(hall1, hall2, allYears)));
        v.add(new Variant("compareplayers-all", "/compareplayers", 8, List.of(hall1, hall2, all)));
        v.add(new Variant("compareplayers-allyears", "/compareplayers", 8, List.of(hall1, hall2, allYears)));
        v.add(new Variant("settings-homehall", "/settings", 1, List.of(data("(?i)homehall"))));
        v.add(new Variant("exportdatabase-xlsx", "/exportdatabase", 1, List.of(data("^export_db_xlsx_"))));
        v.add(new Variant("exportdatabase-db", "/exportdatabase", 1, List.of(data("^export_db_confirm_"))));
        v.add(new Variant("matchtypes", "/matchtypes", 0, List.of()));
        v.add(new Variant("recalculate", "/recalculate", 1, List.of(label("(?i)(start|yes|recalculate|confirm|proceed)"))));
        v.add(new Variant("admins", "/admins", 0, List.of()));
        v.add(new Variant("predict", "/predict", 6, List.of(hall1, hall2)));
        v.add(new Variant("modelstats", "/modelstats", 0, List.of()));
        v.add(new Variant("lineup", "/lineup", 4, List.of(hall2)));
        return v;
    }

    record StepRec(int buttons, int keyboardRows, long ms) {
    }

    @Test
    @EnabledIfSystemProperty(named = "b2.db", matches = ".+")
    void everyCommand(@TempDir Path tmp) throws Exception {
        Path db = Path.of(System.getProperty("b2.db"));
        String size = System.getProperty("b2.size", "?");
        int runs = Integer.getInteger("b2.runs", 3);
        String only = System.getProperty("b2.only", "");
        Duration firstReply = Duration.ofSeconds(Integer.getInteger("b2.firstReplySec", 180));
        Duration quiet = Duration.ofMillis(Integer.getInteger("b2.quietMs", 4000));
        Path out = BotHarness.projectDir().resolve("target").resolve("b2");
        Files.createDirectories(out);
        Path tsv = out.resolve("commands_" + size + ".tsv");
        if (!Files.exists(tsv)) {
            Files.writeString(tsv, String.format(Locale.ROOT, "# mvn -o test -Dtest=B2CommandScaleE2eTest -Db2.db=%s -Db2.size=%s -Db2.runs=%d -Db2.only=%s -Db2.year=%s"
                            + " | java %s | %d cpus | max heap %d MB%n", db, size, runs, only, System.getProperty("b2.year", "2004"),
                    System.getProperty("java.version"), Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() >> 20),
                    StandardCharsets.UTF_8);
            Files.writeString(tsv, "size\tvariant\trun\twall_ms\tsteps_ms\tbuttons_per_step\theap_peak_mb\theap_after_gc_mb\ttext_msgs\ttext_lens\t"
                    + "max_text_len\tphotos(wxh,bytes)\tdocs(name,bytes)\tviolations\trejections\tsilent\tsummary\n", StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
        }
        int n = 0;
        for (Variant v : variants()) {
            if (!only.isEmpty() && List.of(only.split(",")).stream().noneMatch(o -> v.name().startsWith(o.replace("/", "")))) {
                continue;
            }
            FakeTelegramServer fake = FakeTelegramServer.start();
            Path work = tmp.resolve("w" + (n++));
            Path dbCopy = work.resolve("database").resolve("core").resolve("default.db");
            Files.createDirectories(dbCopy.getParent());
            Files.copy(db, dbCopy, StandardCopyOption.REPLACE_EXISTING);
            BotHarness.Options o = new BotHarness.Options();
            o.seed = BotHarness.Seed.NONE;
            o.currentYear = System.getProperty("b2.year", "2004");
            o.extraEnv.put("SETTINGS_HOMEHALL", "1");
            BotHarness bot = BotHarness.start(fake, work, o);
            StringBuilder lines = new StringBuilder();
            try {
                ConversationDriver admin = bot.driver(ADMIN, GROUP);
                for (int run = 1; run <= runs; run++) {
                    lines.append(walk(fake, admin, v, size, run, firstReply, quiet)).append('\n');
                }
            } finally {
                bot.writeTranscript("b2-" + size + "-" + v.name());
                bot.close();
                fake.close();
            }
            Files.writeString(tsv, lines.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            System.out.print(lines);
        }
    }

    private static String walk(FakeTelegramServer fake, ConversationDriver admin, Variant v, String size, int run,
                               Duration firstReply, Duration quiet) {
        A1bSupport.awaitIdle(fake, Duration.ofSeconds(30));
        System.gc();
        ScaleDbBuilder.resetHeapPeak();
        int startIdx = fake.callCount();
        List<StepRec> steps = new ArrayList<>();
        int prefIdx = 0;
        Instant t0 = Instant.now();
        int from = fake.callCount();
        admin.sendText(v.command());
        A1bSupport.Step s = A1bSupport.awaitStep(fake, from, null, null, firstReply, quiet);
        steps.add(stepRec(s, t0));
        int clicks = 0;
        while (s.keyboard() != null && clicks < v.maxClicks()) {
            List<RecordedCall.Button> buttons = s.keyboard().buttons().stream()
                    .filter(b -> b.callbackData() != null && !NOT_FORWARD.matcher(b.callbackData()).matches()).toList();
            if (buttons.isEmpty()) {
                break;
            }
            RecordedCall.Button pick = null;
            for (int i = prefIdx; i < v.prefs().size() && pick == null; i++) {
                Predicate<RecordedCall.Button> p = v.prefs().get(i);
                pick = buttons.stream().filter(p).findFirst().orElse(null);
                if (pick != null) {
                    prefIdx = i + 1;
                }
            }
            if (pick == null) {
                pick = buttons.get(0);
            }
            A1bSupport.awaitIdle(fake, Duration.ofSeconds(30));
            Instant tc = Instant.now();
            int f = fake.callCount();
            admin.clickData(s.keyboard(), pick.callbackData());
            if (v.name().equals("recalculate")) {
                // the retrain after the recalculation sends nothing: wait for the completion message itself
                fake.awaitCall(f, c -> c.isSend() && c.text() != null && c.text().contains("Recalculation Complete"),
                        Duration.ofSeconds(Integer.getInteger("b2.recalcMaxSec", 3600)), "recalculation completion message");
            }
            s = A1bSupport.awaitStep(fake, f, null, null, firstReply, quiet);
            steps.add(stepRec(s, tc));
            clicks++;
        }
        long peak = ScaleDbBuilder.heapPeakMb();
        System.gc();
        long afterGc = ScaleDbBuilder.heapUsedMb();
        List<RecordedCall> calls = A1bSupport.sliceFrom(fake, startIdx);
        List<RecordedCall> sends = A1bSupport.userSends(calls, null);
        List<Integer> textLens = new ArrayList<>();
        List<String> photos = new ArrayList<>();
        List<String> docs = new ArrayList<>();
        for (RecordedCall c : sends) {
            if (c.method.equalsIgnoreCase("sendMessage") && c.text() != null) {
                textLens.add(c.text().length());
            }
            if (c.file() != null) {
                RecordedCall.FilePart fp = c.file();
                if (c.method.equalsIgnoreCase("sendPhoto")) {
                    photos.add(fp.width() + "x" + fp.height() + "," + fp.size() + (c.isOk() ? "" : ",REJECTED"));
                } else {
                    docs.add(fp.fileName() + "," + fp.size() + (c.isOk() ? "" : ",REJECTED"));
                }
            }
        }
        long violations = calls.stream().mapToLong(c -> c.violations.size()).sum();
        long rejections = calls.stream().filter(c -> c.violations.stream().anyMatch(RecordedCall.Violation::rejected)).count();
        long wall = steps.stream().mapToLong(StepRec::ms).sum();
        String vtext = calls.stream().flatMap(c -> c.violations.stream()).map(Object::toString).distinct().limit(3).toList().toString();
        return String.format(Locale.ROOT, "%s\t%s\t%d\t%d\t%s\t%s\t%d\t%d\t%d\t%s\t%d\t%s\t%s\t%d\t%d\t%s\t%s",
                size, v.name(), run, wall,
                steps.stream().map(r -> String.valueOf(r.ms())).toList(),
                steps.stream().map(r -> r.buttons() + "/" + r.keyboardRows()).toList(),
                peak, afterGc, textLens.size(), textLens, textLens.stream().max(Comparator.naturalOrder()).orElse(0),
                photos, docs, violations, rejections, s.silent(),
                (A1bSupport.summarize(calls, null) + " " + (violations > 0 ? vtext : "")).replace('\t', ' '));
    }

    /** Buttons and rows of the step's keyboard (0 if none) and ms from the injection to the step's last user-facing send. */
    private static StepRec stepRec(A1bSupport.Step s, Instant injected) {
        List<RecordedCall> sends = A1bSupport.userSends(s.calls(), null);
        Instant last = sends.isEmpty() ? injected : sends.get(sends.size() - 1).at;
        int buttons = 0;
        int rows = 0;
        RecordedCall kbCall = s.keyboard();
        if (kbCall == null) {
            // a rejected keyboard is still a keyboard: report the largest one attempted
            kbCall = s.calls().stream().filter(RecordedCall::hasKeyboard).max(Comparator.comparingInt(c -> c.buttons().size())).orElse(null);
        }
        if (kbCall != null) {
            buttons = kbCall.buttons().size();
            rows = kbCall.buttons().stream().mapToInt(RecordedCall.Button::row).max().orElse(-1) + 1;
        }
        return new StepRec(buttons, rows, Math.max(0, Duration.between(injected, last).toMillis()));
    }
}
