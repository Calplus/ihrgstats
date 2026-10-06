package com.calplus.ihrgstats.e2e;

import com.calplus.ihrgstats.telegrambot.utils.RoundCsvProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.calplus.ihrgstats.e2e.ConversationDriver.ADMIN;
import static com.calplus.ihrgstats.e2e.ConversationDriver.GROUP;
import static com.calplus.ihrgstats.e2e.ConversationDriver.MEMBER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Lane a1b, scope 1-2: every picker command crawled through every distinct
 * button kind at every depth (as the admin, in the group), then four probes on
 * its first keyboard: a different user clicks it (foreign clicker), the owner
 * double-clicks, the owner clicks it again after the path completed (stale),
 * and forged / malformed callback data. Database: the ROUND_ONE seed plus
 * 2001 round 2 and 2002 round 1 ingested at test time (fictional samples,
 * copied to the temp folder first).
 */
public class PickerCrawlE2eTest {

    private static final Duration FIRST = Duration.ofSeconds(12);
    private static final Duration QUIET = Duration.ofMillis(1500);

    private FakeTelegramServer fake;
    private String transcriptSuffix = "";

    @BeforeEach
    void startFake() throws Exception {
        fake = FakeTelegramServer.start();
    }

    @AfterEach
    void stopFake() {
        fake.close();
    }

    static void ingestExtraRounds(Path tmp) throws Exception {
        for (String name : List.of("2001_round_2.csv", "2002_round_1.csv")) {
            Path copy = tmp.resolve("extra").resolve(name);
            Files.createDirectories(copy.getParent());
            Files.copy(BotHarness.sampleFile(name), copy);
            RoundCsvProcessor p = new RoundCsvProcessor();
            p.setMultiChoiceCallback(BotHarness::autoAnswer);
            int year = Integer.parseInt(name.substring(0, 4));
            int round = Integer.parseInt(name.replaceAll("^\\d{4}_round_(\\d+)\\.csv$", "$1"));
            assertTrue(p.processRound(copy.toString(), year, round, BotHarness.NOW), "ingest " + name);
        }
    }

    /** Crawls one command and runs the four probes; returns the crawl results. */
    private List<A1bSupport.PathResult> crawlAndProbe(Path tmp, String command, int maxPaths,
                                                      java.util.function.Predicate<RecordedCall.Button> skip) throws Exception {
        return crawlAndProbe(tmp, command, maxPaths, skip, d -> { });
    }

    private List<A1bSupport.PathResult> crawlAndProbe(Path tmp, String command, int maxPaths,
                                                      java.util.function.Predicate<RecordedCall.Button> skip,
                                                      java.util.function.Consumer<ConversationDriver> prepare) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        String name = command.substring(1);
        List<A1bSupport.PathResult> results;
        List<String> problems = new ArrayList<>();
        try {
            ingestExtraRounds(tmp);
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            prepare.accept(admin);
            A1bSupport.Crawler crawler = new A1bSupport.Crawler(fake, admin, command, null);
            crawler.maxPaths = maxPaths;
            crawler.firstReplyMax = FIRST;
            crawler.quiet = QUIET;
            crawler.skip = skip;
            int start = fake.callCount();
            results = crawler.crawl();
            A1bSupport.ledger(command, A1bSupport.sliceFrom(fake, start));
            for (A1bSupport.PathResult r : results) {
                String kind = r.diverged() ? "DIVERGED(" + r.note() + ")" : r.last().keyboard() != null ? "KEYBOARD" : r.last().silent() ? "SILENT" : "TERMINAL";
                A1bSupport.cell("picker", command, r.pathString(), "admin", "group", kind + " :: " + r.last().summary(null));
                if (r.last().silent()) {
                    problems.add(r.pathString() + " -> SILENT");
                }
                for (RecordedCall c : r.last().calls()) {
                    if (!c.isOk() && !c.method.equals("answerCallbackQuery")) {
                        problems.add(r.pathString() + " -> " + c + " " + c.responseBody);
                    }
                }
            }

            // ---- probes on the first keyboard
            A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
            int p0 = fake.callCount();
            admin.sendText(command);
            A1bSupport.Step first = A1bSupport.awaitStep(fake, p0, GROUP.idString(), null, FIRST, QUIET);
            if (first.keyboard() != null) {
                RecordedCall kb = first.keyboard();
                RecordedCall.Button target = kb.buttons().stream()
                        .filter(b -> b.callbackData() != null && !skip.test(b)
                                && !b.callbackData().contains("cancel") && !b.callbackData().contains("back"))
                        .findFirst().orElse(kb.buttons().get(0));

                // foreign clicker: the member taps the admin's keyboard
                ConversationDriver member = bot.driver(MEMBER, GROUP);
                A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
                int f = fake.callCount();
                member.clickData(kb, target.callbackData());
                A1bSupport.Step foreign = A1bSupport.awaitStep(fake, f, GROUP.idString(), null, Duration.ofSeconds(6), QUIET);
                A1bSupport.cell("probe", command, "member taps admin's '" + target.text() + "'", "member", "group",
                        (foreign.silent() ? "SILENT " : "") + foreign.summary(null));
                // ... then the admin taps the same (now stripped) button
                A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
                int f2 = fake.callCount();
                admin.clickData(kb, target.callbackData());
                A1bSupport.Step after = A1bSupport.awaitStep(fake, f2, GROUP.idString(), null, Duration.ofSeconds(6), QUIET);
                A1bSupport.cell("probe", command, "admin taps own button after the member did", "admin", "group",
                        (after.silent() ? "SILENT " : "") + after.summary(null));

                // double-click: two callbacks for the same button in one poll batch
                A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
                int d0 = fake.callCount();
                admin.sendText(command);
                A1bSupport.Step fresh = A1bSupport.awaitStep(fake, d0, GROUP.idString(), null, FIRST, QUIET);
                if (fresh.keyboard() != null) {
                    RecordedCall.Button t2 = fresh.keyboard().buttons().stream()
                            .filter(b -> b.text().equals(target.text())).findFirst().orElse(fresh.keyboard().buttons().get(0));
                    A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
                    int d1 = fake.callCount();
                    admin.clickData(fresh.keyboard(), t2.callbackData());
                    admin.clickData(fresh.keyboard(), t2.callbackData());
                    admin.awaitQuiet(Duration.ofMillis(2500), Duration.ofSeconds(30));
                    List<RecordedCall> dbl = A1bSupport.sliceFrom(fake, d1);
                    A1bSupport.cell("probe", command, "double-click '" + t2.text() + "'", "admin", "group",
                            "keyboards sent=" + A1bSupport.userSends(dbl, null).stream().filter(RecordedCall::hasKeyboard).count()
                                    + " sends=" + A1bSupport.userSends(dbl, null).size() + " :: " + A1bSupport.summarize(dbl, null));
                    A1bSupport.ledger(command, dbl);

                    // stale: the same first-keyboard button again, long after
                    A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
                    int s0 = fake.callCount();
                    admin.clickData(kb, target.callbackData());
                    A1bSupport.Step stale = A1bSupport.awaitStep(fake, s0, GROUP.idString(), null, Duration.ofSeconds(6), QUIET);
                    A1bSupport.cell("probe", command, "stale tap on an old keyboard", "admin", "group",
                            (stale.silent() ? "SILENT " : "") + stale.summary(null));
                }

                // malformed / forged data with this command's prefix
                String prefix = target.callbackData().contains("_")
                        ? target.callbackData().substring(0, target.callbackData().indexOf('_') + 1) : target.callbackData();
                for (String bad : List.of(prefix, prefix + "zzz", prefix + "999999", prefix + "round_-1", prefix + "player_ZZZZZZ",
                        prefix + "hall_", "x".repeat(64))) {
                    A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
                    int m0 = fake.callCount();
                    admin.clickData(kb, bad);
                    A1bSupport.Step m = A1bSupport.awaitStep(fake, m0, GROUP.idString(), null, Duration.ofSeconds(5), Duration.ofMillis(1000));
                    String sum = m.silent() ? "SILENT " + m.summary(null) : m.summary(null);
                    A1bSupport.cell("malformed", command, bad, "admin", "group", sum);
                    A1bSupport.ledger(command, m.calls());
                }
            } else {
                A1bSupport.cell("probe", command, "(no keyboard on second run)", "admin", "group", first.summary(null));
            }
            A1bSupport.note("crawl " + command + ": " + results.size() + " paths, " + crawler.replays + " replays, problems",
                    String.join("\n", problems));
            assertTrue(fake.rejections().isEmpty(), "rejections: " + fake.rejections());
            assertFalse(results.isEmpty());
        } finally {
            bot.writeTranscript("a1b-crawl-" + name + transcriptSuffix);
            bot.close();
        }
        return results;
    }

    private static boolean noSkip(RecordedCall.Button b) {
        return false;
    }

    @Test
    void crawl_rankplayers(@TempDir Path tmp) throws Exception {
        crawlAndProbe(tmp, "/rankplayers", 20, PickerCrawlE2eTest::noSkip);
    }

    @Test
    void crawl_rankhalls(@TempDir Path tmp) throws Exception {
        crawlAndProbe(tmp, "/rankhalls", 20, PickerCrawlE2eTest::noSkip);
    }

    @Test
    void crawl_comparehalls(@TempDir Path tmp) throws Exception {
        crawlAndProbe(tmp, "/comparehalls", 30, PickerCrawlE2eTest::noSkip);
    }

    @Test
    void crawl_compareplayers(@TempDir Path tmp) throws Exception {
        crawlAndProbe(tmp, "/compareplayers", 30, PickerCrawlE2eTest::noSkip);
    }

    @Test
    void crawl_infoplayer(@TempDir Path tmp) throws Exception {
        crawlAndProbe(tmp, "/infoplayer", 30, PickerCrawlE2eTest::noSkip);
    }

    @Test
    void crawl_infohall(@TempDir Path tmp) throws Exception {
        crawlAndProbe(tmp, "/infohall", 25, PickerCrawlE2eTest::noSkip);
    }

    @Test
    void crawl_infomatch(@TempDir Path tmp) throws Exception {
        crawlAndProbe(tmp, "/infomatch", 25, PickerCrawlE2eTest::noSkip);
    }

    @Test
    void crawl_infomatchhall(@TempDir Path tmp) throws Exception {
        crawlAndProbe(tmp, "/infomatchhall", 25, PickerCrawlE2eTest::noSkip);
    }

    @Test
    void crawl_help(@TempDir Path tmp) throws Exception {
        crawlAndProbe(tmp, "/help", 45, PickerCrawlE2eTest::noSkip);
    }

    @Test
    void crawl_settings(@TempDir Path tmp) throws Exception {
        crawlAndProbe(tmp, "/settings", 30, PickerCrawlE2eTest::noSkip);
    }

    @Test
    void crawl_admins(@TempDir Path tmp) throws Exception {
        // Removing the only admin would end the crawl; removal is probed in DialogE2eTest.
        crawlAndProbe(tmp, "/admins", 15, b -> b.callbackData().startsWith("admins_remove_"));
    }

    @Test
    void crawl_matchtypes(@TempDir Path tmp) throws Exception {
        crawlAndProbe(tmp, "/matchtypes", 20, PickerCrawlE2eTest::noSkip);
    }

    @Test
    void crawl_predict(@TempDir Path tmp) throws Exception {
        crawlAndProbe(tmp, "/predict", 30, PickerCrawlE2eTest::noSkip);
    }

    @Test
    void crawl_lineup_noHomeHall(@TempDir Path tmp) throws Exception {
        crawlAndProbe(tmp, "/lineup", 30, PickerCrawlE2eTest::noSkip);
    }

    /** /lineup with a home hall chosen first through /settings (the first hall button). */
    @Test
    void crawl_lineup_withHomeHall(@TempDir Path tmp) throws Exception {
        transcriptSuffix = "-with-home-hall";
        crawlAndProbe(tmp, "/lineup", 30, PickerCrawlE2eTest::noSkip, admin -> {
            int f = fake.callCount();
            admin.sendText("/settings");
            A1bSupport.Step s = A1bSupport.awaitStep(fake, f, GROUP.idString(), null, FIRST, QUIET);
            int g = fake.callCount();
            admin.clickData(s.keyboard(), "setting_homeHall_select");
            A1bSupport.Step h = A1bSupport.awaitStep(fake, g, GROUP.idString(), null, FIRST, QUIET);
            RecordedCall.Button first = h.keyboard().buttons().stream()
                    .filter(b -> b.callbackData().startsWith("setting_homeHall_") && !b.callbackData().endsWith("manual")).findFirst().orElseThrow();
            int k = fake.callCount();
            admin.clickData(h.keyboard(), first.callbackData());
            A1bSupport.Step done = A1bSupport.awaitStep(fake, k, GROUP.idString(), null, FIRST, QUIET);
            A1bSupport.cell("picker-setup", "/lineup", "home hall set to " + first.text(), "admin", "group", done.summary(null));
        });
    }

    @Test
    void crawl_exportdatabase(@TempDir Path tmp) throws Exception {
        crawlAndProbe(tmp, "/exportdatabase", 10, PickerCrawlE2eTest::noSkip);
    }
}
