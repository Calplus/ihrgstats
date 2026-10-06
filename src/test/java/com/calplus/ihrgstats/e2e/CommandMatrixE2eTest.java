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
import java.util.Set;

import static com.calplus.ihrgstats.e2e.ConversationDriver.ADMIN;
import static com.calplus.ihrgstats.e2e.ConversationDriver.GROUP;
import static com.calplus.ihrgstats.e2e.ConversationDriver.MEMBER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Lane a1b, scope 1: every routed command (and /start, unknown commands,
 * /cmd@bot, /cmd@otherbot, arguments, case) x actor (admin, non-admin) x chat
 * (group, private), plus the channel rules (right thread, wrong thread, no
 * thread, other group, private chat). Each cell's first reaction is written
 * to target/e2e-transcripts/a1b-matrix.tsv; tests assert today's behaviour,
 * and cells that show a defect are named ..._knownDefect_A1B-n.
 */
public class CommandMatrixE2eTest {

    /** The 18 routed commands (TelegramListener.handleCommand). */
    static final List<String> ROUTED = List.of("/settings", "/exportdatabase", "/rankplayers", "/rankhalls", "/comparehalls",
            "/compareplayers", "/about", "/help", "/infoplayer", "/infohall", "/infomatch", "/infomatchhall", "/matchtypes",
            "/recalculate", "/admins", "/predict", "/modelstats", "/lineup");
    /** Admin-only per plan A.3. */
    static final Set<String> ADMIN_ONLY = Set.of("/settings", "/exportdatabase", "/matchtypes", "/recalculate", "/admins",
            "/predict", "/modelstats", "/lineup");

    static final Duration FIRST = Duration.ofSeconds(6);
    static final Duration QUIET = Duration.ofMillis(1000);

    private FakeTelegramServer fake;

    @BeforeEach
    void startFake() throws Exception {
        fake = FakeTelegramServer.start();
    }

    @AfterEach
    void stopFake() {
        fake.close();
    }

    /** Sends one command and records its cell; cancels a pending choice_ dialog it opened. */
    private A1bSupport.Step run(ConversationDriver d, String group, String command, String label) {
        A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
        int from = fake.callCount();
        d.sendText(command);
        A1bSupport.Step s = A1bSupport.awaitStep(fake, from, null, null, FIRST, QUIET);
        String actor = d.user().id() == ADMIN.id() ? "admin" : d.user().id() == MEMBER.id() ? "member" : "user" + d.user().id();
        String chat = d.chat().type().equals("private") ? "private" : (d.chat().id() == GROUP.id() ? "group" : "group" + d.chat().id());
        A1bSupport.cell(group, label, "(command)", actor, chat,
                (s.silent() ? "SILENT" : "first reply " + s.firstReplyMs() + " ms") + " :: " + s.summary(null));
        A1bSupport.ledger(command.split("[ @]")[0].toLowerCase(), s.calls());
        if (s.keyboard() != null) {
            List<RecordedCall.Button> choices = s.keyboard().buttons().stream()
                    .filter(b -> b.callbackData() != null && b.callbackData().startsWith("choice_")).toList();
            if (!choices.isEmpty()) {
                // /recalculate opens a 60 s Start/Cancel dialog: cancel it so the next cell starts clean.
                A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
                int f = fake.callCount();
                d.clickData(s.keyboard(), choices.get(choices.size() - 1).callbackData());
                A1bSupport.awaitStep(fake, f, null, null, FIRST, QUIET);
            }
        }
        return s;
    }

    private static boolean saysDenied(A1bSupport.Step s) {
        return A1bSupport.userSends(s.calls(), null).stream()
                .anyMatch(c -> c.text() != null && (c.text().contains("Access Denied") || c.text().toLowerCase().contains("only administrators")
                        || c.text().toLowerCase().contains("not authorized") || c.text().toLowerCase().contains("admin")));
    }

    @Test
    void everyRoutedCommand_adminAndMember_groupAndPrivate(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        Map<String, A1bSupport.Step> results = new LinkedHashMap<>();
        try {
            for (ConversationDriver.User u : List.of(ADMIN, MEMBER)) {
                for (ConversationDriver.Chat chat : List.of(GROUP, ConversationDriver.Chat.privateWith(u))) {
                    ConversationDriver d = bot.driver(u, chat);
                    for (String cmd : ROUTED) {
                        String key = (u == ADMIN ? "admin" : "member") + "/" + chat.type() + "/" + cmd;
                        results.put(key, run(d, "routed", cmd, cmd));
                    }
                }
            }
            List<String> problems = new ArrayList<>();
            for (Map.Entry<String, A1bSupport.Step> e : results.entrySet()) {
                String key = e.getKey();
                A1bSupport.Step s = e.getValue();
                String cmd = key.substring(key.lastIndexOf('/'));
                boolean member = key.startsWith("member");
                // Today: every routed command answers every actor in every chat (allow-all on).
                if (s.silent()) {
                    problems.add(key + " was silent");
                }
                // Replies go back to the chat the command came from.
                String expectedChat = key.contains("/private/") ? (member ? MEMBER.idString() : ADMIN.idString()) : GROUP.idString();
                for (RecordedCall c : A1bSupport.userSends(s.calls(), null)) {
                    if (!expectedChat.equals(c.chatId())) {
                        problems.add(key + " replied in chat " + c.chatId());
                    }
                }
                if (member && ADMIN_ONLY.contains(cmd)) {
                    if (!saysDenied(s)) {
                        problems.add(key + " (admin-only) did not refuse the member: " + s.summary(null));
                    }
                    if (A1bSupport.userSends(s.calls(), null).stream().anyMatch(RecordedCall::hasKeyboard)) {
                        problems.add(key + " (admin-only) offered a keyboard to the member");
                    }
                }
            }
            A1bSupport.note("routed matrix problems (" + results.size() + " cells)", String.join("\n", problems));
            assertEquals(List.of(), problems);
            assertTrue(fake.rejections().isEmpty(), "rejections: " + fake.rejections());
        } finally {
            bot.writeTranscript("a1b-matrix-routed");
            bot.close();
        }
    }

    /** Picker commands in a private chat, as the admin and as a member: first non-cancel button at every step. */
    @Test
    void pickerMainPath_privateChat_adminAndMember(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        List<String> pickers = List.of("/rankplayers", "/rankhalls", "/comparehalls", "/compareplayers", "/infoplayer", "/infohall",
                "/infomatch", "/infomatchhall", "/help", "/settings", "/admins", "/matchtypes", "/predict", "/lineup", "/exportdatabase");
        List<String> problems = new ArrayList<>();
        try {
            for (ConversationDriver.User u : List.of(ADMIN, MEMBER)) {
                ConversationDriver.Chat pc = ConversationDriver.Chat.privateWith(u);
                ConversationDriver d = bot.driver(u, pc);
                for (String cmd : pickers) {
                    A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
                    int from = fake.callCount();
                    d.sendText(cmd);
                    A1bSupport.Step s = A1bSupport.awaitStep(fake, from, pc.idString(), null, Duration.ofSeconds(12), Duration.ofMillis(1500));
                    List<String> path = new ArrayList<>();
                    while (s.keyboard() != null && path.size() < 6) {
                        RecordedCall.Button b = s.keyboard().buttons().stream()
                                .filter(x -> x.callbackData() != null && !x.callbackData().contains("cancel") && !x.callbackData().contains("back")
                                        && !x.callbackData().startsWith("admins_remove") && !x.callbackData().startsWith("setting_toggle"))
                                .findFirst().orElse(null);
                        if (b == null) {
                            break;
                        }
                        path.add(b.text());
                        A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
                        int f = fake.callCount();
                        d.clickData(s.keyboard(), b.callbackData());
                        s = A1bSupport.awaitStep(fake, f, pc.idString(), null, Duration.ofSeconds(12), Duration.ofMillis(1500));
                    }
                    List<RecordedCall> all = A1bSupport.sliceFrom(fake, from);
                    A1bSupport.ledger(cmd, all);
                    String actor = u == ADMIN ? "admin" : "member";
                    A1bSupport.cell("private-path", cmd, path.isEmpty() ? "(command only)" : String.join(" > ", path), actor, "private",
                            (s.silent() ? "SILENT " : "") + s.summary(null));
                    for (RecordedCall c : A1bSupport.userSends(all, null)) {
                        if (!pc.idString().equals(c.chatId())) {
                            problems.add(actor + " " + cmd + " sent to chat " + c.chatId());
                        }
                    }
                    if (s.silent()) {
                        problems.add(actor + " " + cmd + " ended silent after " + path);
                    }
                }
            }
            A1bSupport.note("private-chat picker paths: problems", String.join("\n", problems));
            assertTrue(fake.rejections().isEmpty(), "rejections: " + fake.rejections());
        } finally {
            bot.writeTranscript("a1b-matrix-private-paths");
            bot.close();
        }
    }

    /**
     * /cmd@thisbot, /cmd@otherbot, upper case, arguments, /start and unknown commands.
     * Today: the bot answers commands addressed to another bot, and stays silent for
     * arguments, /start and unknown commands (owner decision: always reply).
     */
    @Test
    void commandVariants_knownDefect_A1B_1_and_A1B_2(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        try {
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            List<String> otherBotAnswered = new ArrayList<>();
            List<String> thisBotSilent = new ArrayList<>();
            for (String cmd : ROUTED) {
                A1bSupport.Step mine = run(admin, "variant", cmd + "@" + FakeTelegramServer.BOT_USERNAME, cmd + "@thisbot");
                if (mine.silent()) {
                    thisBotSilent.add(cmd);
                }
                A1bSupport.Step other = run(admin, "variant", cmd + "@some_other_bot", cmd + "@otherbot");
                if (!other.silent()) {
                    otherBotAnswered.add(cmd);
                }
                A1bSupport.Step upper = run(admin, "variant", cmd.toUpperCase(), cmd.toUpperCase());
                assertFalse(upper.silent(), cmd.toUpperCase() + " is routed case-insensitively today");
            }
            assertEquals(List.of(), thisBotSilent, "/cmd@thisbot is answered");
            // A1B-1: a command addressed to another bot is answered as if it were ours.
            assertEquals(ROUTED, otherBotAnswered, "today every /cmd@otherbot is answered (A1B-1)");

            // A1B-2: arguments, /start, unknown commands - silent. Sent as one batch (all expected silent).
            List<String> silentOnes = new ArrayList<>();
            for (String cmd : ROUTED) {
                silentOnes.add(cmd + " 2001");
            }
            silentOnes.addAll(List.of("/start", "/start@" + FakeTelegramServer.BOT_USERNAME, "/nosuchcommand", "/",
                    "/rank players", "/ranks"));
            // "/help@" (empty bot name): everything from '@' is cut, so it is answered as /help.
            A1bSupport.Step emptyBot = run(admin, "variant", "/help@", "/help@");
            assertFalse(emptyBot.silent(), "/help@ is answered as /help");
            A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
            int from = fake.callCount();
            for (String s : silentOnes) {
                admin.sendText(s);
            }
            admin.awaitQuiet(Duration.ofSeconds(4), Duration.ofSeconds(20));
            List<RecordedCall> replies = A1bSupport.userSends(A1bSupport.sliceFrom(fake, from), null);
            for (String s : silentOnes) {
                A1bSupport.cell("variant", s, "(command)", "admin", "group", replies.isEmpty() ? "SILENT (batch)" : "batch had replies: " + replies);
            }
            A1bSupport.ledger("(unknown/args)", A1bSupport.sliceFrom(fake, from));
            assertEquals(List.of(), replies, "today arguments, /start and unknown commands get no reply at all (A1B-2)");

            assertTrue(fake.rejections().isEmpty(), "rejections: " + fake.rejections());
        } finally {
            bot.writeTranscript("a1b-matrix-variants");
            bot.close();
        }
    }

    /**
     * Channel rules configured (public chat + commands thread 11 + upload thread 12,
     * allow-all off): a command, plain text and an upload from every place.
     */
    @Test
    void channelRules_everyPlace_knownDefect_A1B_3(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        o.publicChatId = GROUP.idString();
        o.commandsThreadId = "11";
        o.fileUploadThreadId = "12";
        o.allowAllChannelsProcessing = false;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        ConversationDriver.Chat otherGroup = ConversationDriver.Chat.group(-1009990077L);
        try {
            Map<String, ConversationDriver> places = new LinkedHashMap<>();
            places.put("commands-thread", bot.driver(ADMIN, GROUP).inThread(11L));
            places.put("upload-thread", bot.driver(ADMIN, GROUP).inThread(12L));
            places.put("other-thread", bot.driver(ADMIN, GROUP).inThread(13L));
            places.put("no-thread", bot.driver(ADMIN, GROUP));
            places.put("other-group", bot.driver(ADMIN, otherGroup));
            places.put("private", bot.driver(ADMIN, ConversationDriver.Chat.privateWith(ADMIN)));
            Map<String, String> outcome = new LinkedHashMap<>();
            for (Map.Entry<String, ConversationDriver> p : places.entrySet()) {
                ConversationDriver d = p.getValue();
                for (String what : List.of("/help", "/rankplayers", "/nosuchcommand", "hello there", "UPLOAD")) {
                    A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
                    int from = fake.callCount();
                    if (what.equals("UPLOAD")) {
                        d.upload("notes.txt", "not a round file".getBytes(StandardCharsets.UTF_8));
                    } else {
                        d.sendText(what);
                    }
                    A1bSupport.Step s = A1bSupport.awaitStep(fake, from, null, null, FIRST, QUIET);
                    String sum = s.silent() ? "SILENT" : s.summary(null);
                    outcome.put(p.getKey() + " " + what, sum);
                    A1bSupport.cell("channel-rules", what, p.getKey(), "admin", p.getKey(), sum);
                    A1bSupport.ledger("(channel rules)", s.calls());
                }
            }
            // Callbacks bypass the channel filter: a button press on a message in another group / a private chat
            // (e.g. a keyboard sent there while allow-all was still on) is processed and answered there.
            for (ConversationDriver.Chat where : List.of(otherGroup, ConversationDriver.Chat.privateWith(ADMIN))) {
                com.google.gson.JsonObject msg = new com.google.gson.JsonObject();
                msg.addProperty("message_id", 4242);
                com.google.gson.JsonObject chatJson = new com.google.gson.JsonObject();
                chatJson.addProperty("id", where.id());
                chatJson.addProperty("type", where.type());
                msg.add("chat", chatJson);
                msg.addProperty("date", java.time.Instant.now().getEpochSecond());
                ConversationDriver d = bot.driver(ADMIN, where);
                A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
                int from = fake.callCount();
                d.clickOnMessage(msg, "rankplayers_round_all");
                A1bSupport.Step s = A1bSupport.awaitStep(fake, from, null, null, Duration.ofSeconds(12), Duration.ofMillis(1500));
                String key = (where.type().equals("private") ? "private" : "other-group") + " callback rankplayers_round_all";
                outcome.put(key, s.silent() ? "SILENT" : s.summary(null));
                A1bSupport.cell("channel-rules", "callback rankplayers_round_all", where.type().equals("private") ? "private" : "other-group",
                        "admin", where.type(), outcome.get(key));
                A1bSupport.ledger("(channel rules)", s.calls());
            }
            assertTrue(outcome.get("other-group callback rankplayers_round_all").contains("sendPhoto"),
                    "today a callback from outside the configured chat runs the report there (A1B-18): "
                            + outcome.get("other-group callback rankplayers_round_all"));
            // Today's behaviour, asserted:
            assertTrue(outcome.get("commands-thread /help").contains("[kb"), outcome.get("commands-thread /help"));
            for (String place : List.of("upload-thread", "other-thread", "no-thread")) {
                assertTrue(outcome.get(place + " /help").contains("Wrong Channel"), place + ": " + outcome.get(place + " /help"));
            }
            // Other group and private chat: the channel filter answers "Wrong Channel" into that chat.
            assertTrue(outcome.get("other-group /help").contains("Wrong Channel"), outcome.get("other-group /help"));
            assertTrue(outcome.get("private /help").contains("Wrong Channel"), outcome.get("private /help"));
            // A1B-3: plain chatter (not a command, not an upload) outside the two threads is answered with "Wrong Channel".
            for (String place : List.of("other-thread", "no-thread", "other-group", "private")) {
                assertTrue(outcome.get(place + " hello there").contains("Wrong Channel"), place + ": " + outcome.get(place + " hello there"));
            }
            // A1B-2 (channel variant): an unknown command is silent in the commands thread but gets "Wrong Channel" elsewhere.
            assertEquals("SILENT", outcome.get("commands-thread /nosuchcommand"));
            A1bSupport.note("channel rules outcomes", outcome.entrySet().stream().map(e -> e.getKey() + " => " + e.getValue())
                    .reduce("", (a, b) -> a + b + "\n"));
            assertTrue(fake.rejections().isEmpty(), "rejections: " + fake.rejections());
        } finally {
            bot.writeTranscript("a1b-matrix-channel-rules");
            bot.close();
        }
    }
}
