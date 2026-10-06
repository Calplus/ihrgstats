package com.calplus.ihrgstats.e2e;

import com.calplus.ihrgstats.databasemanager.A1_Rounds;
import com.calplus.ihrgstats.databasemanager.F16_Admins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.calplus.ihrgstats.e2e.ConversationDriver.ADMIN;
import static com.calplus.ihrgstats.e2e.ConversationDriver.GROUP;
import static com.calplus.ihrgstats.e2e.ConversationDriver.MEMBER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Lane a1b, scope 4: two wizards by two users at once, two uploads at once
 * (two admins, and one admin twice), 20 commands in rapid succession.
 */
public class ConcurrencyE2eTest {

    private static final Duration STEP = Duration.ofSeconds(25);
    private static final ConversationDriver.User ADMIN2 = new ConversationDriver.User(910003L, "Robin", "fake_admin_two");

    private FakeTelegramServer fake;

    @BeforeEach
    void startFake() throws Exception {
        fake = FakeTelegramServer.start();
    }

    @AfterEach
    void stopFake() {
        fake.close();
    }

    /** Next keyboard sent after index {@code from} whose text matches and that is not in {@code taken}. */
    private RecordedCall nextKeyboard(int from, Set<Long> taken, String what) {
        return fake.awaitCall(from, c -> c.isSend() && c.isOk() && c.hasKeyboard() && !taken.contains(c.seq), STEP, what);
    }

    @Test
    void twoWizardsAtOnce_interleaved_noCrossTalk(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        try {
            ConversationDriver a = bot.driver(ADMIN, GROUP);
            ConversationDriver m = bot.driver(MEMBER, GROUP);
            Set<Long> taken = new HashSet<>();
            int f0 = fake.callCount();
            a.sendText("/infoplayer");
            m.sendText("/infoplayer");
            RecordedCall k1 = nextKeyboard(f0, taken, "first hall picker");
            taken.add(k1.seq);
            RecordedCall k2 = nextKeyboard(f0, taken, "second hall picker");
            taken.add(k2.seq);
            // Pickers are identical; each user taps "their own" (the first for the admin, the second for the member).
            List<RecordedCall.Button> halls = k1.buttons();
            int f1 = fake.callCount();
            // halls "1" and "2" both have players in the fixture (hall 10, second in text order, has none)
            a.click(k1, "1");
            m.click(k2, "2");
            RecordedCall q1 = nextKeyboard(f1, taken, "player picker 1");
            taken.add(q1.seq);
            RecordedCall q2 = nextKeyboard(f1, taken, "player picker 2");
            taken.add(q2.seq);
            // the admin chose hall 1, the member hall 2: tell the pickers apart by the hall named in their text
            RecordedCall p1 = q1.text().contains("Hall 1</b>") ? q1 : q2;
            RecordedCall p2 = p1 == q1 ? q2 : q1;
            int f2 = fake.callCount();
            RecordedCall.Button pa = p1.buttons().stream().filter(b -> b.callbackData().startsWith("infoplayer_player_")).findFirst().orElseThrow();
            RecordedCall.Button pm = p2.buttons().stream().filter(b -> b.callbackData().startsWith("infoplayer_player_")).findFirst().orElseThrow();
            a.clickData(p1, pa.callbackData());
            m.clickData(p2, pm.callbackData());
            RecordedCall s1 = nextKeyboard(f2, taken, "round picker 1");
            taken.add(s1.seq);
            RecordedCall s2 = nextKeyboard(f2, taken, "round picker 2");
            taken.add(s2.seq);
            RecordedCall r1 = s1.text().contains(pa.text()) ? s1 : s2;
            RecordedCall r2 = r1 == s1 ? s2 : s1;
            int f3 = fake.callCount();
            a.clickData(r1, "infoplayer_round_all");
            m.clickData(r2, "infoplayer_round_all");
            fake.awaitCall(f3, c -> c.method.equals("sendDocument"), Duration.ofSeconds(40), "first report document");
            int docs = 0;
            long end = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (System.nanoTime() < end && docs < 2) {
                docs = (int) A1bSupport.sliceFrom(fake, f3).stream().filter(c -> c.method.equals("sendDocument")).count();
                A1bSupport.sleep(200);
            }
            a.awaitQuiet(Duration.ofMillis(1500), Duration.ofSeconds(20));
            List<RecordedCall> tail = A1bSupport.sliceFrom(fake, f3);
            List<String> reportTexts = tail.stream().filter(c -> c.method.equals("sendMessage") && c.text() != null)
                    .map(c -> c.text().lines().limit(3).reduce("", (x, y) -> x + y + " / ")).toList();
            String summary = "players clicked: admin=" + pa.text() + " member=" + pm.text() + "; documents=" + docs + "; report heads=" + reportTexts;
            A1bSupport.note("two wizards at once", summary + "\n" + A1bSupport.lines(tail));
            A1bSupport.cell("concurrency", "/infoplayer x2", "admin and member interleaved", "admin+member", "group", summary);
            A1bSupport.ledger("/infoplayer", A1bSupport.sliceFrom(fake, f0));
            assertEquals(2, docs, "both wizards complete");
            assertTrue(reportTexts.stream().anyMatch(t -> t.contains(pa.text())), "admin's report names the admin's player: " + reportTexts);
            assertTrue(reportTexts.stream().anyMatch(t -> t.contains(pm.text())), "member's report names the member's player: " + reportTexts);
            assertTrue(fake.rejections().isEmpty(), "rejections: " + fake.rejections());
        } finally {
            bot.writeTranscript("a1b-concurrency-two-wizards");
            bot.close();
        }
    }

    /** Drives all pending choice_ dialogs for the given users until both uploads report a terminal line. */
    private Map<String, String> driveUploads(Map<Long, ConversationDriver> drivers, int from, int expectedTerminals, Duration max) {
        Set<Long> answered = new HashSet<>();
        Map<String, String> terminals = new LinkedHashMap<>();
        long end = System.nanoTime() + max.toNanos();
        while (System.nanoTime() < end && terminals.size() < expectedTerminals) {
            for (RecordedCall c : A1bSupport.userSends(A1bSupport.sliceFrom(fake, from), null)) {
                if (answered.contains(c.seq)) {
                    continue;
                }
                if (c.hasKeyboard() && c.buttons().stream().anyMatch(b -> b.callbackData().startsWith("choice_"))) {
                    answered.add(c.seq);
                    String[] labels = c.buttons().stream().map(RecordedCall.Button::text).toArray(String[]::new);
                    int pick = BotHarness.autoAnswer(c.text(), labels);
                    // the dialog belongs to whoever uploaded; try each user - only the owner's click resolves it
                    for (ConversationDriver d : drivers.values()) {
                        d.clickData(c, c.buttons().get(pick).callbackData());
                    }
                } else if (c.text() != null && (c.text().contains("processed successfully") || c.text().contains("ERROR"))) {
                    answered.add(c.seq);
                    terminals.put("#" + c.seq, c.text().lines().findFirst().orElse(""));
                }
            }
            A1bSupport.sleep(250);
        }
        return terminals;
    }

    @Test
    void twoUploadsAtOnce_twoAdmins_andOneAdminTwice(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        try {
            new F16_Admins().addAdmin(F16_Admins.PLATFORM_TELEGRAM, ADMIN2.idString(), null, BotHarness.NOW);
            ConversationDriver a = bot.driver(ADMIN, GROUP);
            ConversationDriver b = bot.driver(ADMIN2, GROUP);
            Map<Long, ConversationDriver> drivers = new LinkedHashMap<>();
            drivers.put(ADMIN.id(), a);
            drivers.put(ADMIN2.id(), b);
            Path dir = tmp.resolve("up");
            Files.createDirectories(dir);
            for (String n : List.of("2001_round_2.csv", "2001_round_3.csv", "2001_round_4.csv", "2001_round_5.csv")) {
                Files.copy(BotHarness.sampleFile(n), dir.resolve(n));
            }
            long t0 = System.nanoTime();
            int from = fake.callCount();
            a.upload(dir.resolve("2001_round_2.csv"));
            b.upload(dir.resolve("2001_round_3.csv"));
            Map<String, String> done = driveUploads(drivers, from, 2, Duration.ofSeconds(150));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            A1bSupport.cell("concurrency", "upload x2", "two admins at once", "admin+admin2", "group", ms + " ms, terminals=" + done);
            A1bSupport.note("two uploads by two admins", ms + " ms; terminals " + done + "\n" + A1bSupport.lines(A1bSupport.sliceFrom(fake, from)));
            assertNotNull(new A1_Rounds().getRoundByYearAndOrder(2001, 2));
            assertNotNull(new A1_Rounds().getRoundByYearAndOrder(2001, 3));

            // one admin, two files at once
            int from2 = fake.callCount();
            a.upload(dir.resolve("2001_round_4.csv"));
            a.upload(dir.resolve("2001_round_5.csv"));
            Map<String, String> done2 = driveUploads(drivers, from2, 2, Duration.ofSeconds(150));
            A1bSupport.cell("concurrency", "upload x2", "one admin, two files at once", "admin", "group", "terminals=" + done2);
            A1bSupport.note("two uploads by one admin", "terminals " + done2 + "\n" + A1bSupport.lines(A1bSupport.sliceFrom(fake, from2)));
            A1bSupport.ledger("(upload)", A1bSupport.sliceFrom(fake, from));
            assertNotNull(new A1_Rounds().getRoundByYearAndOrder(2001, 4));
            assertNotNull(new A1_Rounds().getRoundByYearAndOrder(2001, 5));
            assertTrue(fake.rejections().isEmpty(), "rejections: " + fake.rejections());
        } finally {
            bot.writeTranscript("a1b-concurrency-two-uploads");
            bot.close();
        }
    }

    @Test
    void twentyCommandsInRapidSuccession(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        try {
            ConversationDriver a = bot.driver(ADMIN, GROUP);
            ConversationDriver m = bot.driver(MEMBER, GROUP);
            List<String> cmds = new ArrayList<>(CommandMatrixE2eTest.ROUTED);
            cmds.remove("/recalculate"); // opens a 60 s dialog
            cmds.addAll(List.of("/help", "/about", "/rankplayers"));
            cmds = cmds.subList(0, 20);
            A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
            int from = fake.callCount();
            long t0 = System.nanoTime();
            for (int i = 0; i < cmds.size(); i++) {
                (i % 2 == 0 ? a : m).sendText(cmds.get(i));
            }
            long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            int replies = 0;
            while (System.nanoTime() < end && replies < cmds.size()) {
                replies = A1bSupport.userSends(A1bSupport.sliceFrom(fake, from), null).size();
                A1bSupport.sleep(100);
            }
            long allMs = (System.nanoTime() - t0) / 1_000_000;
            a.awaitQuiet(Duration.ofMillis(1500), Duration.ofSeconds(20));
            List<RecordedCall> sends = A1bSupport.userSends(A1bSupport.sliceFrom(fake, from), null);
            long polls = A1bSupport.count(fake.calls().subList(from, fake.callCount()), "getUpdates");
            String s = cmds.size() + " commands -> " + sends.size() + " replies in " + allMs + " ms; getUpdates calls=" + polls
                    + "; failed sends=" + sends.stream().filter(c -> !c.isOk()).count();
            A1bSupport.note("20 rapid commands", s + "\n" + A1bSupport.lines(sends));
            A1bSupport.cell("concurrency", "20 commands", "rapid, admin/member alternating", "admin+member", "group", s);
            A1bSupport.ledger("(20 rapid)", A1bSupport.sliceFrom(fake, from));
            assertTrue(sends.size() >= cmds.size(), s);
            assertTrue(fake.rejections().isEmpty(), "rejections: " + fake.rejections());
        } finally {
            bot.writeTranscript("a1b-concurrency-20-rapid");
            bot.close();
        }
    }
}
