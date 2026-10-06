package com.calplus.ihrgstats.e2e;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static com.calplus.ihrgstats.e2e.ConversationDriver.ADMIN;
import static com.calplus.ihrgstats.e2e.ConversationDriver.GROUP;
import static com.calplus.ihrgstats.e2e.ConversationDriver.MEMBER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Lane a1b, scope 3: failure injection on every outgoing method the bot uses
 * (sendMessage, sendPhoto, sendDocument, editMessageReplyMarkup,
 * answerCallbackQuery, getFile, file download) x {429 retry_after, 500,
 * connection drop, slow beyond the request timeout, 400 parse error, 400 too
 * long}, one fault on the first matching call. Remote logging is on (Telegram
 * dev chat + Discord, both the fake) so "was anybody told" is measurable.
 * Plus getUpdates answered 401 / 409 / 429 / 500 / dropped / hung: poll rate,
 * what is reported, and how long recovery takes once the fault clears.
 */
public class FailureInjectionE2eTest {

    static final String DEV_CHAT = "-1009990999";
    static final Map<String, Fault> FAULTS = new LinkedHashMap<>();

    static {
        FAULTS.put("429 retry_after=2", Fault.tooManyRequests(2));
        FAULTS.put("500", Fault.serverError());
        FAULTS.put("connection drop", Fault.dropConnection());
        FAULTS.put("slow 2.5 s (timeout 1 s)", Fault.delay(Duration.ofMillis(2500)));
        FAULTS.put("400 parse error", Fault.badRequest("Bad Request: can't parse entities: Unsupported start tag \"q\" at byte offset 3"));
        FAULTS.put("400 too long", Fault.badRequest("Bad Request: message is too long"));
    }

    private FakeTelegramServer fake;

    @BeforeEach
    void startFake() throws Exception {
        fake = FakeTelegramServer.start();
    }

    @AfterEach
    void stopFake() {
        fake.close();
    }

    private BotHarness start(Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        o.devChatId = DEV_CHAT;
        o.discordLogging = true;
        o.systemProperties.put("ihrgstats.http.requestTimeoutMs", "1000");
        o.systemProperties.put("ihrgstats.http.fileTransferTimeoutMs", "1000");
        return BotHarness.start(fake, tmp.resolve("work"), o);
    }

    /** Outcome of one (method, fault) cell. */
    record Outcome(int attempts, boolean delivered, boolean userToldOfError, int logCalls, String summary, List<RecordedCall> calls) {
    }

    private Outcome measure(String method, Predicate<RecordedCall> target, int from) {
        // let retries, logs (5 s batch) and late sends land
        ConversationDriver any = new ConversationDriver(fake, ADMIN, GROUP);
        any.awaitQuiet(Duration.ofMillis(2500), Duration.ofSeconds(30));
        A1bSupport.sleep(5500); // ChannelLog batches for 5 s before posting
        any.awaitQuiet(Duration.ofMillis(1500), Duration.ofSeconds(20));
        List<RecordedCall> calls = A1bSupport.sliceFrom(fake, from);
        List<RecordedCall> mine = calls.stream().filter(c -> c.method.equalsIgnoreCase(method) && target.test(c)).toList();
        boolean delivered = mine.stream().anyMatch(RecordedCall::isOk);
        boolean told = A1bSupport.userSends(calls, DEV_CHAT).stream().anyMatch(c -> c.isOk() && c.text() != null
                && (c.text().contains("ERROR") || c.text().contains("❌") || c.text().contains("Failed") || c.text().contains("failed")));
        int logs = (int) calls.stream().filter(c -> A1bSupport.isLogTraffic(c, DEV_CHAT)).count();
        return new Outcome(mine.size(), delivered, told, logs, A1bSupport.summarize(calls, DEV_CHAT), calls);
    }

    private static Predicate<RecordedCall> toGroup() {
        return c -> GROUP.idString().equals(c.chatId());
    }

    private void record(String method, String fault, Outcome o) {
        A1bSupport.cell("fault", method, fault, "admin", "group", "attempts=" + o.attempts + " delivered=" + o.delivered
                + " userToldOfError=" + o.userToldOfError + " logCalls=" + o.logCalls + " :: " + o.summary);
        A1bSupport.ledger("(fault " + method + ")", o.calls);
    }

    // ------------------------------------------------------------------ sendMessage

    @Test
    void sendMessage_everyFault(@TempDir Path tmp) throws Exception {
        BotHarness bot = start(tmp);
        Map<String, Outcome> out = new LinkedHashMap<>();
        try {
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            for (Map.Entry<String, Fault> f : FAULTS.entrySet()) {
                // /about: a formatted (parse_mode=HTML) text reply, sent from a worker thread
                A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
                fake.clearFaults();
                fake.failWhen("sendMessage", toGroup(), f.getValue(), 1);
                int from = fake.callCount();
                admin.sendText("/about");
                Outcome o = measure("sendMessage", toGroup(), from);
                out.put(f.getKey(), o);
                record("sendMessage", f.getKey(), o);
            }
            fake.clearFaults();
            StringBuilder sb = new StringBuilder();
            out.forEach((k, v) -> sb.append(k).append(" => attempts=").append(v.attempts).append(" delivered=").append(v.delivered)
                    .append(" logs=").append(v.logCalls).append(" :: ").append(v.summary).append('\n'));
            // what the retried text looked like after a 429/500 (parse_mode stripped)
            for (Map.Entry<String, Outcome> e : out.entrySet()) {
                for (RecordedCall c : e.getValue().calls) {
                    if (c.method.equals("sendMessage") && toGroup().test(c) && c.isOk()) {
                        sb.append("  delivered after '").append(e.getKey()).append("': parse_mode=").append(c.parseMode())
                                .append(" text starts: ").append(c.text().substring(0, Math.min(80, c.text().length())).replace("\n", "/")).append('\n');
                    }
                }
            }
            A1bSupport.note("sendMessage faults (/about)", sb.toString());
            // Today (asserted): a 429 is answered by an immediate retry without parse_mode - the message arrives with
            // literal HTML tags, retry_after ignored (A1B-9); a dropped connection or timeout loses the reply (no retry).
            Outcome r429 = out.get("429 retry_after=2");
            assertEquals(2, r429.attempts, r429.summary);
            RecordedCall retried = r429.calls.stream().filter(c -> c.method.equals("sendMessage") && toGroup().test(c) && c.isOk())
                    .findFirst().orElseThrow();
            assertNull(retried.parseMode(), "retry is plain text");
            assertTrue(retried.text().contains("<b>"), "the user sees raw <b> tags (A1B-9)");
            assertFalse(out.get("connection drop").delivered, "dropped reply is lost (A1B-10)");
            // (the fake still completes a slow request after the client gave up, so "delivered" can read true here)
            assertEquals(1, out.get("slow 2.5 s (timeout 1 s)").attempts, "timed-out reply is not retried (A1B-10)");
        } finally {
            bot.writeTranscript("a1b-fault-sendMessage");
            bot.close();
        }
    }

    // ------------------------------------------------------------------ image + callback methods

    /** /rankplayers -> click "all rounds": editMessageReplyMarkup, answerCallbackQuery, sendMessage, sendPhoto, sendDocument. */
    private Outcome rankAllWithFault(BotHarness bot, String method, Fault fault, Predicate<RecordedCall> target) {
        ConversationDriver admin = bot.driver(ADMIN, GROUP);
        A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
        fake.clearFaults();
        int k0 = fake.callCount();
        admin.sendText("/rankplayers");
        A1bSupport.Step kb = A1bSupport.awaitStep(fake, k0, GROUP.idString(), DEV_CHAT, Duration.ofSeconds(12), Duration.ofMillis(800));
        assertNotNull(kb.keyboard(), kb.summary(DEV_CHAT));
        A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
        fake.failWhen(method, target, fault, 1);
        int from = fake.callCount();
        admin.clickData(kb.keyboard(), "rankplayers_round_all");
        return measure(method, target, from);
    }

    private void everyFaultOn(Path tmp, String method, Predicate<RecordedCall> target, String transcript) throws Exception {
        BotHarness bot = start(tmp);
        Map<String, Outcome> out = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, Fault> f : FAULTS.entrySet()) {
                Outcome o = rankAllWithFault(bot, method, f.getValue(), target);
                out.put(f.getKey(), o);
                record(method, f.getKey(), o);
            }
            fake.clearFaults();
            StringBuilder sb = new StringBuilder();
            out.forEach((k, v) -> sb.append(k).append(" => attempts=").append(v.attempts).append(" delivered=").append(v.delivered)
                    .append(" told=").append(v.userToldOfError).append(" logs=").append(v.logCalls).append(" :: ").append(v.summary).append('\n'));
            A1bSupport.note(method + " faults (/rankplayers all)", sb.toString());
            if (method.equals("sendPhoto") || method.equals("sendDocument")) {
                for (Map.Entry<String, Outcome> e : out.entrySet()) {
                    // Today: one attempt, never retried, nobody told - not the user, not the remote logs (A1B-11).
                    assertEquals(1, e.getValue().attempts, e.getKey() + ": " + e.getValue().summary);
                    assertFalse(e.getValue().userToldOfError, e.getKey());
                }
            }
        } finally {
            bot.writeTranscript(transcript);
            bot.close();
        }
    }

    @Test
    void sendPhoto_everyFault_knownDefect_A1B_11(@TempDir Path tmp) throws Exception {
        everyFaultOn(tmp, "sendPhoto", toGroup(), "a1b-fault-sendPhoto");
    }

    @Test
    void sendDocument_everyFault_knownDefect_A1B_11(@TempDir Path tmp) throws Exception {
        everyFaultOn(tmp, "sendDocument", toGroup(), "a1b-fault-sendDocument");
    }

    @Test
    void editMessageReplyMarkup_everyFault(@TempDir Path tmp) throws Exception {
        everyFaultOn(tmp, "editMessageReplyMarkup", c -> true, "a1b-fault-editMessageReplyMarkup");
    }

    @Test
    void answerCallbackQuery_everyFault(@TempDir Path tmp) throws Exception {
        everyFaultOn(tmp, "answerCallbackQuery", c -> true, "a1b-fault-answerCallbackQuery");
    }

    // ------------------------------------------------------------------ upload transport

    @Test
    void getFile_and_download_everyFault(@TempDir Path tmp) throws Exception {
        BotHarness bot = start(tmp);
        Map<String, Outcome> out = new LinkedHashMap<>();
        try {
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            for (String method : List.of("getFile", FakeTelegramServer.FILE_DOWNLOAD)) {
                for (Map.Entry<String, Fault> f : FAULTS.entrySet()) {
                    A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
                    fake.clearFaults();
                    fake.failNext(method, f.getValue());
                    int from = fake.callCount();
                    admin.upload("notes.txt", "fictional".getBytes(StandardCharsets.UTF_8));
                    Outcome o = measure(method, c -> true, from);
                    out.put(method + " " + f.getKey(), o);
                    record(method, f.getKey(), o);
                }
            }
            fake.clearFaults();
            StringBuilder sb = new StringBuilder();
            out.forEach((k, v) -> sb.append(k).append(" => attempts=").append(v.attempts).append(" told=").append(v.userToldOfError)
                    .append(" logs=").append(v.logCalls).append(" :: ").append(v.summary).append('\n'));
            A1bSupport.note("getFile / download faults (upload notes.txt)", sb.toString());
            for (Map.Entry<String, Outcome> e : out.entrySet()) {
                if (!e.getKey().contains("slow")) {
                    assertTrue(e.getValue().userToldOfError, "a failed download is reported in the chat: " + e.getKey() + " " + e.getValue().summary);
                }
            }
        } finally {
            bot.writeTranscript("a1b-fault-upload-transport");
            bot.close();
        }
    }

    // ------------------------------------------------------------------ polling thread head-of-line

    /**
     * /help is answered on the polling thread: a slow sendMessage holds every other
     * user's update until it returns. Default request timeout (30 s) kept here; the
     * fake answers after 6 s.
     */
    @Test
    void slowSendOnPollingThread_blocksEveryoneElse_knownDefect_A1B_12(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        try {
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            ConversationDriver member = bot.driver(MEMBER, GROUP);
            // control: the member's /about with nothing slow in front of it
            A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
            int c0 = fake.callCount();
            member.sendText("/about");
            A1bSupport.Step control = A1bSupport.awaitStep(fake, c0, GROUP.idString(), null, Duration.ofSeconds(15), Duration.ofMillis(800));
            A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
            A1bSupport.sleep(1200);
            fake.failWhen("sendMessage", c -> c.text() != null && c.text().contains("Help"), Fault.delay(Duration.ofSeconds(6)), 1);
            int from = fake.callCount();
            admin.sendText("/help");
            A1bSupport.sleep(300);
            long t = System.nanoTime();
            member.sendText("/about");
            RecordedCall about = fake.awaitCall(from, c -> c.method.equals("sendMessage") && c.text() != null && c.text().contains("About"),
                    Duration.ofSeconds(30), "the member's /about reply");
            long blockedMs = (System.nanoTime() - t) / 1_000_000;
            A1bSupport.note("head-of-line blocking on the polling thread", "control /about first reply: " + control.firstReplyMs()
                    + " ms; /about behind a /help whose sendMessage takes 6 s: " + blockedMs + " ms (" + about + ")");
            A1bSupport.cell("fault", "sendMessage", "slow 6 s on /help (polling thread)", "member", "group",
                    "member's /about answered after " + blockedMs + " ms (control " + control.firstReplyMs() + " ms)");
            assertTrue(blockedMs >= 5000, "today the member waits for the admin's slow send (A1B-12): " + blockedMs + " ms");
        } finally {
            bot.writeTranscript("a1b-fault-polling-thread-blocking");
            bot.close();
        }
    }

    // ------------------------------------------------------------------ getUpdates

    @Test
    void getUpdates_401_409_429_500_drop_hang_rateReportingRecovery(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.REFERENCE;
        o.devChatId = DEV_CHAT;
        o.discordLogging = true;
        o.systemProperties.put("ihrgstats.http.longPollTimeoutMs", "3000");
        fake.setMaxLongPollHold(Duration.ofMillis(1500)); // below the 3 s client long-poll timeout
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        Map<String, Fault> polling = new LinkedHashMap<>();
        polling.put("401", Fault.unauthorized());
        polling.put("409", Fault.conflict());
        polling.put("429 retry_after=5", Fault.tooManyRequests(5));
        polling.put("500", Fault.serverError());
        polling.put("connection drop", Fault.dropConnection());
        polling.put("hang (long-poll timeout 3 s)", Fault.hang());
        StringBuilder sb = new StringBuilder();
        try {
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            for (Map.Entry<String, Fault> f : polling.entrySet()) {
                A1bSupport.awaitIdle(fake, Duration.ofSeconds(15));
                int from = fake.callCount();
                fake.failAlways("getUpdates", f.getValue());
                fake.releasePolls();
                fake.resumePolls();
                A1bSupport.sleep(8000);
                fake.clearFaults();
                List<RecordedCall> during = fake.calls().subList(from, fake.callCount());
                List<RecordedCall> polls = during.stream().filter(c -> c.method.equals("getUpdates") && c.fault != null).toList();
                List<Long> gaps = new ArrayList<>();
                for (int i = 1; i < polls.size(); i++) {
                    gaps.add(Duration.between(polls.get(i - 1).at, polls.get(i).at).toMillis());
                }
                long logs = during.stream().filter(c -> A1bSupport.isLogTraffic(c, DEV_CHAT)).count();
                long userMsgs = A1bSupport.userSends(during, DEV_CHAT).size();
                // recovery: a command sent right after the fault clears
                long t = System.nanoTime();
                int r0 = fake.callCount();
                admin.sendText("/help");
                RecordedCall reply = fake.awaitCall(r0, c -> c.method.equals("sendMessage") && GROUP.idString().equals(c.chatId()),
                        Duration.ofSeconds(40), "recovery reply after " + f.getKey());
                long recoveryMs = (System.nanoTime() - t) / 1_000_000;
                String line = String.format("%-30s faulted polls in 8 s=%d gaps(ms)=%s remote-log calls=%d user msgs=%d recovery /help reply after %d ms",
                        f.getKey(), polls.size(), gaps, logs, userMsgs, recoveryMs);
                sb.append(line).append('\n');
                A1bSupport.cell("fault", "getUpdates", f.getKey(), "-", "-", line);
                assertNotNull(reply);
            }
            A1bSupport.note("getUpdates faults (8 s each, then recovery)", sb.toString());
        } finally {
            bot.writeTranscript("a1b-fault-getUpdates");
            bot.close();
        }
    }
}
