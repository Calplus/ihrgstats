package com.calplus.ihrgstats.e2e;

import com.calplus.ihrgstats.telegrambot.listener.TelegramListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static com.calplus.ihrgstats.e2e.ConversationDriver.ADMIN;
import static com.calplus.ihrgstats.e2e.ConversationDriver.GROUP;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterisation probes for the polling and startup seeds S5, S6 and S7: what the
 * real listener does when getUpdates answers 401 / 409 / 429, when the startup
 * offset call fails, how long an update waits between polls, and what a blank
 * token or a webhook URL does.
 *
 * <p>These assert TODAY's behaviour (each is a lane-a1a FINDING) so they run green
 * as reproducers; when the behaviour is fixed, flip the assertion marked FINDING.
 * Measurements are printed as "[A1A-PROBE] ..." lines.
 */
public class PollingFailureE2eTest {

    private static final String DEV_CHAT = "-1009990099";

    private FakeTelegramServer fake;

    @BeforeEach
    void startFake() throws Exception {
        fake = FakeTelegramServer.start();
    }

    @AfterEach
    void stopFake() {
        fake.close();
    }

    private static long longField(TelegramListener listener, String name) throws Exception {
        Field f = TelegramListener.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getLong(listener);
    }

    private static boolean boolField(TelegramListener listener, String name) throws Exception {
        Field f = TelegramListener.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getBoolean(listener);
    }

    private List<RecordedCall> longPolls() {
        return fake.calls().stream().filter(c -> c.method.equals("getUpdates") && c.param("timeout") != null).toList();
    }

    /** Runs the listener with every long poll answered by {@code fault} for {@code window}; returns the polls seen. */
    private List<RecordedCall> pollUnder(Path tmp, Fault fault, Duration window, String transcript) throws Exception {
        fake.failWhen("getUpdates", c -> c.param("timeout") != null, fault, -1);
        BotHarness.Options o = new BotHarness.Options();
        o.devChatId = DEV_CHAT; // enables the Telegram log channel and the status heartbeat
        o.awaitFirstPoll = false;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        try {
            Thread.sleep(800); // startup logs and the first heartbeat settle
            int sendsBefore = (int) fake.calls().stream().filter(RecordedCall::isSend).count();
            Thread.sleep(window.toMillis());
            long lastPoll = longField(bot.listener(), "lastPollCompletedAt");
            long ageMs = System.currentTimeMillis() - lastPoll;
            List<RecordedCall> polls = longPolls();
            int sendsDuring = (int) fake.calls().stream().filter(RecordedCall::isSend).count() - sendsBefore;
            System.out.println("[A1A-PROBE] " + transcript + ": " + polls.size() + " long polls answered '" + fault
                    + "' in ~" + (window.toMillis() + 800) + " ms; messages sent during the failure window: " + sendsDuring
                    + "; lastPollCompletedAt age " + ageMs + " ms (heartbeat warns only above 120000 ms)");
            // FINDING (S5): the failure is invisible - no log or chat message while polling fails...
            assertEquals(0, sendsDuring, "FINDING: nothing reports the polling failure");
            // ...and the liveness stamp stays fresh, so the heartbeat keeps reporting "online".
            assertTrue(ageMs < 2500, "FINDING: lastPollCompletedAt is stamped on any status (TL:526)");
            return polls;
        } finally {
            bot.writeTranscript(transcript);
            bot.close();
        }
    }

    private static long medianGapMs(List<RecordedCall> polls) {
        long[] gaps = new long[Math.max(0, polls.size() - 1)];
        for (int i = 1; i < polls.size(); i++) {
            gaps[i - 1] = polls.get(i).at.toEpochMilli() - polls.get(i - 1).at.toEpochMilli();
        }
        java.util.Arrays.sort(gaps);
        return gaps.length == 0 ? -1 : gaps[gaps.length / 2];
    }

    @Test
    void getUpdates401_revokedToken_isRetriedEverySecondForever_andNothingReportsIt(@TempDir Path tmp) throws Exception {
        List<RecordedCall> polls = pollUnder(tmp, Fault.unauthorized(), Duration.ofMillis(3500), "probe-s5-getupdates-401");
        long gap = medianGapMs(polls);
        System.out.println("[A1A-PROBE] 401 median gap between polls: " + gap + " ms");
        assertTrue(polls.size() >= 3, "FINDING: a revoked token is retried in a tight loop, polls=" + polls.size());
        assertTrue(gap >= 900 && gap < 2000, "one retry per second (sleep(1000) at TL:435), gap=" + gap);
    }

    @Test
    void getUpdates409_secondInstance_isRetriedEverySecondForever_andNothingReportsIt(@TempDir Path tmp) throws Exception {
        List<RecordedCall> polls = pollUnder(tmp, Fault.conflict(), Duration.ofMillis(3500), "probe-s5-getupdates-409");
        assertTrue(polls.size() >= 3, "FINDING: 409 (another instance polling) is retried forever, polls=" + polls.size());
    }

    @Test
    void getUpdates429_retryAfterIsIgnored_nextPollAfterOneSecond(@TempDir Path tmp) throws Exception {
        List<RecordedCall> polls = pollUnder(tmp, Fault.tooManyRequests(5), Duration.ofMillis(3500), "probe-s5-getupdates-429");
        long gap = medianGapMs(polls);
        System.out.println("[A1A-PROBE] 429 retry_after=5 s, median gap between polls: " + gap + " ms");
        assertTrue(gap < 5000, "FINDING: retry_after=5 s ignored on the polling path, gap=" + gap + " ms");
        assertTrue(polls.size() >= 3);
    }

    @Test
    void failedStartupOffsetCall_replaysTheBacklog(@TempDir Path tmp) throws Exception {
        // Control: with a working offset call, an update queued before start is skipped.
        fake.enqueueUpdate(backlogHelp());
        BotHarness control = BotHarness.start(fake, tmp.resolve("control"));
        boolean controlReplied;
        try {
            ConversationDriver d = control.driver(ADMIN, GROUP);
            d.mark();
            controlReplied = d.awaitQuiet(Duration.ofMillis(1500), Duration.ofSeconds(5))
                    && fake.calls().stream().anyMatch(RecordedCall::isSend);
        } finally {
            control.close();
        }
        assertFalse(controlReplied, "control: the pre-start backlog is skipped");

        // Probe: the offset call fails once (500), the backlog is processed as if new.
        try (FakeTelegramServer probe = FakeTelegramServer.start()) {
            probe.enqueueUpdate(backlogHelp());
            probe.failWhen("getUpdates", c -> "-1".equals(c.param("offset")), Fault.serverError(), 1);
            BotHarness bot = BotHarness.start(probe, tmp.resolve("probe"));
            try {
                RecordedCall replay = probe.awaitCall(0, RecordedCall::isSend, Duration.ofSeconds(10), "a reply to the backlog /help");
                System.out.println("[A1A-PROBE] offset call 500 -> first poll " + longPollsOf(probe).get(0).param("offset")
                        + " replays the backlog: " + replay);
                assertTrue(replay.hasKeyboard(), "FINDING (S5): an old /help from before the start was answered");
            } finally {
                bot.writeTranscript("probe-s5-offset-failure-replay");
                bot.close();
            }
        }
    }

    private static List<RecordedCall> longPollsOf(FakeTelegramServer f) {
        return f.calls().stream().filter(c -> c.method.equals("getUpdates") && c.param("timeout") != null).toList();
    }

    private static com.google.gson.JsonObject backlogHelp() {
        com.google.gson.JsonObject from = new com.google.gson.JsonObject();
        from.addProperty("id", ADMIN.id());
        from.addProperty("is_bot", false);
        from.addProperty("first_name", ADMIN.firstName());
        com.google.gson.JsonObject chat = new com.google.gson.JsonObject();
        chat.addProperty("id", GROUP.id());
        chat.addProperty("type", "supergroup");
        com.google.gson.JsonObject m = new com.google.gson.JsonObject();
        m.addProperty("message_id", 1);
        m.add("from", from);
        m.add("chat", chat);
        m.addProperty("date", 1);
        m.addProperty("text", "/help");
        com.google.gson.JsonObject u = new com.google.gson.JsonObject();
        u.add("message", m);
        return u;
    }

    @Test
    void secondUpdate_waitsForThePostPollSleep(@TempDir Path tmp) throws Exception {
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"));
        try {
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            long t1 = System.nanoTime();
            admin.sendText("/help");
            admin.awaitKeyboard();
            long first = (System.nanoTime() - t1) / 1_000_000;

            long t2 = System.nanoTime();
            admin.sendText("/help");
            admin.awaitKeyboard();
            long second = (System.nanoTime() - t2) / 1_000_000;
            System.out.println("[A1A-PROBE] S6 reply latency: update into a held poll " + first
                    + " ms; next update right after " + second + " ms");
            assertTrue(first < 600, "a held poll delivers at once: " + first);
            assertTrue(second >= 500, "FINDING (S6): the update waits out the 1 s sleep after every poll (TL:435): " + second);
        } finally {
            bot.writeTranscript("probe-s6-poll-sleep-latency");
            bot.close();
        }
    }

    @Test
    void blankToken_startReturnsWithoutPolling(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.botToken = "";
        o.awaitFirstPoll = false;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        try {
            Thread.sleep(1500);
            boolean running = boolField(bot.listener(), "isRunning");
            System.out.println("[A1A-PROBE] S7 blank token: isRunning=" + running + ", calls=" + fake.callCount());
            assertFalse(running, "FINDING (S7): start() logs an error and returns; Main then join()s forever (Main.java:82)");
            assertEquals(0, fake.callCount(), "no request at all is made");
        } finally {
            bot.close();
        }
    }

    @Test
    void webhookUrl_setsTheWebhook_thenDeletesItAndLongPolls(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.extraEnv.put("INTERNET_WEBHOOK_URL", fake.baseUrl() + "/webhook-probe");
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        try {
            List<String> order = fake.calls().stream().map(c -> c.method).filter(m -> !m.equals("getUpdates")).toList();
            System.out.println("[A1A-PROBE] S7 webhook mode call order: " + order + " then long polling");
            int set = order.indexOf("setWebhook");
            int del = order.indexOf("deleteWebhook");
            assertTrue(set >= 0, "the webhook is registered: " + order);
            assertTrue(del > set, "FINDING (S7): ...and deleted right after, then the bot long-polls: " + order);
            assertEquals("", fake.webhookUrl(), "no webhook remains");
            assertTrue(fake.heldPolls() > 0, "long polling is active");
        } finally {
            bot.writeTranscript("probe-s7-webhook-mode");
            bot.close();
        }
    }
}
