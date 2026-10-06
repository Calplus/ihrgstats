package com.calplus.ihrgstats.e2e;

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
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static com.calplus.ihrgstats.e2e.ConversationDriver.ADMIN;
import static com.calplus.ihrgstats.e2e.ConversationDriver.GROUP;
import static com.calplus.ihrgstats.e2e.ConversationDriver.MEMBER;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lane b2, section 5: through-the-rig measurements for the speed-up candidates, run once on
 * the control build and once with the measurement prototype applied (same flags, same
 * database). Gated by {@code -Db2.ab=<c1|c2|c3|c6>}; {@code -Db2.abLabel=control|proto}.
 * One line per measurement goes to target/b2/ab.tsv.
 *
 * <pre>mvn -o test -Dtest=B2CandidateAbE2eTest -Db2.ab=c2 -Db2.abLabel=control [-Db2.db=&lt;default.db&gt;]</pre>
 */
public class B2CandidateAbE2eTest {

    static void emit(String candidate, String metric, String value) throws Exception {
        Path dir = BotHarness.projectDir().resolve("target").resolve("b2");
        Files.createDirectories(dir);
        Path tsv = dir.resolve("ab.tsv");
        if (!Files.exists(tsv)) {
            Files.writeString(tsv, "# mvn -o test -Dtest=B2CandidateAbE2eTest -Db2.ab=<candidate> -Db2.abLabel=<control|proto> [-Db2.db=...]"
                    + " (this PC unless stated)\ncandidate\tlabel\tmetric\tvalue\tat\n", StandardCharsets.UTF_8);
        }
        String line = String.format(Locale.ROOT, "%s\t%s\t%s\t%s\t%s", candidate, System.getProperty("b2.abLabel", "?"), metric, value, Instant.now());
        System.out.println("[b2-ab] " + line);
        Files.writeString(tsv, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    static String stats(List<Long> v) {
        List<Long> s = new ArrayList<>(v);
        Collections.sort(s);
        return String.format(Locale.ROOT, "median=%d min=%d max=%d n=%d", s.get(s.size() / 2), s.get(0), s.get(s.size() - 1), s.size());
    }

    static BotHarness start(FakeTelegramServer fake, Path tmp, BotHarness.Options o) throws Exception {
        String db = System.getProperty("b2.db", "");
        Path work = tmp.resolve("work");
        if (!db.isEmpty()) {
            Path copy = work.resolve("database").resolve("core").resolve("default.db");
            Files.createDirectories(copy.getParent());
            Files.copy(Path.of(db), copy, StandardCopyOption.REPLACE_EXISTING);
            o.seed = BotHarness.Seed.NONE;
            o.currentYear = System.getProperty("b2.year", "2004");
        }
        return BotHarness.start(fake, work, o);
    }

    /** Candidate 1 (S1 leak fix): 500 commands with remote logging on; heap after GC, threads, hooks, ms per command. */
    @Test
    @EnabledIfSystemProperty(named = "b2.ab", matches = "c1")
    void c1_soak500(@TempDir Path tmp) throws Exception {
        int total = Integer.getInteger("b2.soakCommands", 500);
        List<String> rotation = List.of("/help", "/rankplayers", "/rankhalls", "/infohall", "/about");
        FakeTelegramServer fake = FakeTelegramServer.start();
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        o.devChatId = SoakE2eTest.DEV_CHAT;
        o.discordLogging = true;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        try {
            ConversationDriver a = bot.driver(ADMIN, GROUP);
            ConversationDriver m = bot.driver(MEMBER, GROUP);
            long t0 = System.nanoTime();
            emit("c1", "sample@0", SoakE2eTest.sample(0, fake, t0).toString().trim().replaceAll(" +", " "));
            int from = fake.callCount();
            int sent = 0;
            long lastMark = System.nanoTime();
            while (sent < total) {
                for (int i = 0; i < 25 && sent < total; i++) {
                    (sent % 2 == 0 ? a : m).sendText(rotation.get(sent % rotation.size()));
                    sent++;
                }
                long end = System.nanoTime() + Duration.ofSeconds(120).toNanos();
                while (System.nanoTime() < end && A1bSupport.userSends(A1bSupport.sliceFrom(fake, from), SoakE2eTest.DEV_CHAT).size() < sent) {
                    A1bSupport.sleep(20);
                }
                if (sent % 100 == 0) {
                    long ms = (System.nanoTime() - lastMark) / 1_000_000;
                    emit("c1", "ms_per_command@" + sent, String.format(Locale.ROOT, "%.1f", ms / 100.0));
                    emit("c1", "sample@" + sent, SoakE2eTest.sample(sent, fake, t0).toString().trim().replaceAll(" +", " "));
                    lastMark = System.nanoTime();
                }
            }
            emit("c1", "sample_columns", SoakE2eTest.HEADER.trim().replaceAll(" +", " "));
        } finally {
            bot.close();
            fake.close();
        }
    }

    /** Candidate 2 (no 1 s sleep after each poll): 30 back-to-back /help, each injected as soon as the previous reply arrived. */
    @Test
    @EnabledIfSystemProperty(named = "b2.ab", matches = "c2")
    void c2_backToBackLatency(@TempDir Path tmp) throws Exception {
        FakeTelegramServer fake = FakeTelegramServer.start();
        BotHarness bot = start(fake, tmp, new BotHarness.Options());
        try {
            ConversationDriver a = bot.driver(ADMIN, GROUP);
            A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
            List<Long> lat = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                int from = fake.callCount();
                long t0 = System.nanoTime();
                a.sendText("/help");
                fake.awaitCall(from, c -> c.isSend(), Duration.ofSeconds(20), "help reply");
                lat.add((System.nanoTime() - t0) / 1_000_000);
            }
            emit("c2", "help_back_to_back_ms", stats(lat) + " " + lat);
            // a 4-step wizard clicked as fast as the replies arrive (/infohall > hall 1 > all rounds)
            List<Long> wiz = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                long t0 = System.nanoTime();
                int from = fake.callCount();
                a.sendText("/infohall");
                RecordedCall kb = fake.awaitCall(from, c -> c.isSend() && c.hasKeyboard(), Duration.ofSeconds(20), "hall picker");
                from = fake.callCount();
                a.click(kb, "1");
                kb = fake.awaitCall(from, c -> c.isSend() && c.hasKeyboard(), Duration.ofSeconds(20), "round picker");
                from = fake.callCount();
                a.clickData(kb, kb.buttons().stream().filter(b -> b.callbackData() != null && b.callbackData().endsWith("_all")).findFirst()
                        .orElseThrow().callbackData());
                fake.awaitCall(from, c -> c.method.equalsIgnoreCase("sendDocument"), Duration.ofSeconds(60), "report document");
                wiz.add((System.nanoTime() - t0) / 1_000_000);
                A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
            }
            emit("c2", "infohall_wizard_total_ms", stats(wiz) + " " + wiz);
            assertTrue(fake.rejections().isEmpty(), fake.rejections().toString());
        } finally {
            bot.close();
            fake.close();
        }
    }

    /** Candidate 3: /help sent together with the final /predict click; how long /help waits. */
    @Test
    @EnabledIfSystemProperty(named = "b2.ab", matches = "c3")
    void c3_concurrentCommandLatency(@TempDir Path tmp) throws Exception {
        FakeTelegramServer fake = FakeTelegramServer.start();
        BotHarness bot = start(fake, tmp, new BotHarness.Options());
        try {
            ConversationDriver a = bot.driver(ADMIN, GROUP);
            List<Long> helpMs = new ArrayList<>();
            List<Long> predictMs = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                A1bSupport.awaitIdle(fake, Duration.ofSeconds(20));
                int from = fake.callCount();
                a.sendText("/predict");
                RecordedCall kb = fake.awaitCall(from, c -> c.isSend() && c.hasKeyboard(), Duration.ofSeconds(20), "hall 1 picker");
                // hall 1 > first player > hall 2 > (second-player picker)
                for (int step = 0; step < 3; step++) {
                    A1bSupport.awaitIdle(fake, Duration.ofSeconds(20));
                    from = fake.callCount();
                    if (step == 1) {
                        a.clickData(kb, kb.buttons().stream().filter(x -> x.callbackData() != null && x.callbackData().startsWith("predict_selectplayer1_"))
                                .findFirst().orElseThrow().callbackData());
                    } else {
                        a.click(kb, step == 0 ? "1" : "2");
                    }
                    kb = fake.awaitCall(from, c -> c.isSend() && c.hasKeyboard(), Duration.ofSeconds(20), "picker " + step);
                }
                A1bSupport.awaitIdle(fake, Duration.ofSeconds(20));
                from = fake.callCount();
                String last = kb.buttons().stream().filter(x -> x.callbackData() != null && x.callbackData().startsWith("predict_selectplayer2_"))
                        .findFirst().orElseThrow().callbackData();
                Instant tClick = Instant.now();
                a.clickData(kb, last);
                Instant tHelp = Instant.now();
                a.sendText("/help");
                int f = from;
                RecordedCall help = fake.awaitCall(f, c -> c.isSend() && c.text() != null && c.text().contains("Help"), Duration.ofSeconds(60), "help");
                RecordedCall pred = fake.awaitCall(f, c -> c.isSend() && !c.hasKeyboard() && c.text() != null && !c.text().contains("Help"),
                        Duration.ofSeconds(60), "prediction");
                helpMs.add(Duration.between(tHelp, help.at).toMillis());
                predictMs.add(Duration.between(tClick, pred.at).toMillis());
            }
            emit("c3", "help_latency_while_predict_final_ms", stats(helpMs) + " " + helpMs);
            emit("c3", "predict_final_ms", stats(predictMs) + " " + predictMs);
        } finally {
            bot.close();
            fake.close();
        }
    }

    /** Candidate 6 (cached reports): the same report requested 6 times in a row; first vs repeats. */
    @Test
    @EnabledIfSystemProperty(named = "b2.ab", matches = "c6")
    void c6_repeatedReport(@TempDir Path tmp) throws Exception {
        FakeTelegramServer fake = FakeTelegramServer.start();
        BotHarness bot = start(fake, tmp, new BotHarness.Options());
        try {
            ConversationDriver a = bot.driver(ADMIN, GROUP);
            for (String[] v : new String[][]{{"/rankplayers", "_allyears"}, {"/comparehalls", "_all"}}) {
                List<Long> ms = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    A1bSupport.awaitIdle(fake, Duration.ofSeconds(20));
                    int from = fake.callCount();
                    a.sendText(v[0]);
                    RecordedCall kb = fake.awaitCall(from, c -> c.isSend() && c.hasKeyboard(), Duration.ofSeconds(20), "picker");
                    if (v[0].equals("/comparehalls")) {
                        for (String h : new String[]{"1", "2"}) {
                            A1bSupport.awaitIdle(fake, Duration.ofSeconds(20));
                            from = fake.callCount();
                            a.click(kb, h);
                            kb = fake.awaitCall(from, c -> c.isSend() && c.hasKeyboard(), Duration.ofSeconds(20), "picker");
                        }
                    }
                    A1bSupport.awaitIdle(fake, Duration.ofSeconds(20));
                    from = fake.callCount();
                    Instant t0 = Instant.now();
                    a.clickData(kb, kb.buttons().stream().filter(b -> b.callbackData() != null && b.callbackData().endsWith(v[1])).findFirst()
                            .orElseThrow().callbackData());
                    RecordedCall doc = fake.awaitCall(from, c -> c.method.equalsIgnoreCase("sendDocument"), Duration.ofSeconds(120), "document");
                    ms.add(Duration.between(t0, doc.at).toMillis());
                }
                emit("c6", v[0] + v[1] + "_ms_first_then_repeats", ms.toString());
            }
        } finally {
            bot.close();
            fake.close();
        }
    }
}
