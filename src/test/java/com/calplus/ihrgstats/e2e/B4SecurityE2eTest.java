package com.calplus.ihrgstats.e2e;

import com.calplus.ihrgstats.databasemanager.F16_Admins;
import com.calplus.ihrgstats.telegrambot.listener.TelegramListener;
import com.calplus.ihrgstats.utils.DatabaseHelper;
import com.calplus.ihrgstats.utils.EnvironmentManager;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.calplus.ihrgstats.e2e.ConversationDriver.ADMIN;
import static com.calplus.ihrgstats.e2e.ConversationDriver.GROUP;
import static com.calplus.ihrgstats.e2e.ConversationDriver.MEMBER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Lane b4 (round 3): authorisation, injection, secrets and abuse-cost probes
 * through the real TelegramListener against the fake Bot API. Every scenario
 * runs in a @TempDir sandbox with the fake token only; nothing leaves loopback.
 *
 * Tests named *_knownDefect_* assert TODAY's (defective) behaviour so they stay
 * green until the product is fixed; the others assert a property that holds.
 * Notes go to target/e2e-transcripts/b4-notes.txt.
 */
public class B4SecurityE2eTest {

    private static final Duration STEP = Duration.ofSeconds(20);
    private static final Duration QUIET = Duration.ofMillis(1500);
    private static final ConversationDriver.User DEMOTED = new ConversationDriver.User(930003L, "Riley", "fake_second_admin");
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

    // ------------------------------------------------------------------ helpers

    private static Path notesFile() throws IOException {
        Path dir = BotHarness.projectDir().resolve("target").resolve("e2e-transcripts");
        Files.createDirectories(dir);
        return dir.resolve("b4-notes.txt");
    }

    static synchronized void note(String title, String body) {
        try {
            String token = FakeTelegramServer.FAKE_TOKEN;
            String text = "\n=== " + title + " ===\n" + body.replace(token, "<FAKE-TOKEN>") + "\n";
            Files.writeString(notesFile(), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Runs {@code action}, waits until the bot is quiet, returns every non-poll call it caused. */
    private List<RecordedCall> act(ConversationDriver d, Runnable action) {
        int from = fake.callCount();
        action.run();
        d.awaitQuiet(QUIET, Duration.ofSeconds(60));
        List<RecordedCall> all = fake.calls();
        return all.subList(from, all.size()).stream().filter(c -> !c.method.equals("getUpdates")).toList();
    }

    private static List<RecordedCall> sends(List<RecordedCall> calls) {
        return calls.stream().filter(RecordedCall::isSend).toList();
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "(no text)";
        }
        String t = s.strip();
        int nl = t.indexOf('\n');
        t = nl < 0 ? t : t.substring(0, nl);
        if (t.length() <= 110) {
            return t;
        }
        int cut = Character.isHighSurrogate(t.charAt(109)) ? 109 : 110;
        return t.substring(0, cut) + "...";
    }

    private static String summary(List<RecordedCall> calls) {
        List<RecordedCall> s = sends(calls);
        if (s.isEmpty()) {
            return "SILENT (" + calls.stream().map(c -> c.method).distinct().collect(Collectors.joining(",")) + ")";
        }
        return s.stream().map(c -> c.method + "[" + c.chatId() + "]: " + firstLine(c.text())).collect(Collectors.joining(" | "));
    }

    private static String sha256(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }

    private static List<String> rows(String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = DatabaseHelper.getDefaultConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    sb.append(i > 1 ? "|" : "").append(rs.getString(i));
                }
                out.add(sb.toString());
            }
        }
        return out;
    }

    private static int update(String sql, Object... args) throws Exception {
        try (Connection c = DatabaseHelper.getDefaultConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        }
    }

    private static List<Path> outputFiles(BotHarness bot, String suffix) throws IOException {
        Path out = bot.workDir().resolve("output");
        if (!Files.isDirectory(out)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(out)) {
            return s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(suffix)).sorted().toList();
        }
    }

    private static long dirBytes(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        }
    }

    private static long dirCount(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    // ================================================================== 1. authorisation

    /**
     * A member forges every admin-only callback (as a modified client, a stale
     * keyboard or another user's keyboard would deliver it) and then types the
     * text each admin wizard waits for. Nothing may change: env file, admins,
     * match types, exports, settings.
     */
    @Test
    void authz_memberForgesEveryAdminCallbackAndWizardText_changesNothing(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        StringBuilder matrix = new StringBuilder("forged callback_data\tmember sees\n");
        try {
            Path env = bot.workDir().resolve(".env.properties");
            String envBefore = sha256(env);
            List<String> adminsBefore = rows("SELECT platform, platform_user_id FROM admins ORDER BY id");
            List<String> typesBefore = rows("SELECT * FROM match_types ORDER BY id");
            String sysBefore = System.getProperty("SETTINGS_ALLOWNONADMINUPLOADS") + "/" + System.getProperty("SETTINGS_ALLOWALLCHANNELSPROCESSING")
                    + "/" + System.getProperty("SETTINGS_CURRENTYEAR") + "/" + System.getProperty("SETTINGS_TIMEZONE") + "/" + System.getProperty("SETTINGS_HOMEHALL");

            ConversationDriver member = bot.driver(MEMBER, GROUP);
            member.sendText("/help");
            RecordedCall carrier = member.awaitKeyboard(STEP);

            String[] forged = {
                    "setting_toggle_settings.allowNonAdminUploads", "setting_toggle_settings.allowAllChannelsProcessing",
                    "setting_homeHall_select", "setting_homeHall_manual", "setting_homeHall_1",
                    "setting_timezone_select", "setting_timezone_manual", "setting_timezone_8",
                    "setting_currentYear_select", "settings_cancel",
                    "export_db_xlsx_920002", "export_db_confirm_920002", "export_db_cancel_920002", "export_db_confirm_910001",
                    "matchtypes_list", "matchtypes_create", "matchtypes_editselect", "matchtypes_edit_1",
                    "matchtypes_assignselect", "matchtypes_assignmt_1", "matchtypes_cancel",
                    "predict_selecthall1_1", "predict_selectplayer1_000001", "predict_cancel",
                    "lineup_selectopponent_1", "lineup_cancel",
                    "admins_list", "admins_addstart", "admins_removeselect", "admins_remove_1", "admins_cancel",
                    "choice_0_00000000-0000-0000-0000-000000000000"
            };
            List<String> successLeaks = new ArrayList<>();
            for (String data : forged) {
                List<RecordedCall> calls = act(member, () -> member.clickData(carrier, data));
                String s = summary(calls);
                matrix.append(data).append('\t').append(s).append('\n');
                for (RecordedCall c : sends(calls)) {
                    String t = c.text() == null ? "" : c.text();
                    if (c.method.equalsIgnoreCase("sendDocument") || t.contains("✅") || t.contains("Successfully")
                            || t.contains("Reply with") || t.contains("Select an admin") || t.contains("Export Database")) {
                        successLeaks.add(data + " -> " + c.method + ": " + firstLine(t));
                    }
                }
            }
            // The texts each admin wizard step would accept.
            for (String text : new String[]{"2099", "UTC+8", "1", "TELEGRAM 920002 Morgan", "Blitz 300 30 fast games"}) {
                List<RecordedCall> calls = act(member, () -> member.sendText(text));
                matrix.append("text '").append(text).append("'\t").append(summary(calls)).append('\n');
                if (!sends(calls).isEmpty() && sends(calls).stream().anyMatch(c -> String.valueOf(c.text()).contains("✅"))) {
                    successLeaks.add("text " + text + " -> " + summary(calls));
                }
            }

            String sysAfter = System.getProperty("SETTINGS_ALLOWNONADMINUPLOADS") + "/" + System.getProperty("SETTINGS_ALLOWALLCHANNELSPROCESSING")
                    + "/" + System.getProperty("SETTINGS_CURRENTYEAR") + "/" + System.getProperty("SETTINGS_TIMEZONE") + "/" + System.getProperty("SETTINGS_HOMEHALL");
            note("authz: member forges every admin callback (" + forged.length + ") + 5 wizard texts", matrix
                    + "\nenv sha before=" + envBefore + " after=" + sha256(env)
                    + "\nsettings props before=" + sysBefore + " after=" + sysAfter
                    + "\nadmins before=" + adminsBefore + " after=" + rows("SELECT platform, platform_user_id FROM admins ORDER BY id")
                    + "\nmatch_types unchanged=" + typesBefore.equals(rows("SELECT * FROM match_types ORDER BY id"))
                    + "\nexports in output/: " + outputFiles(bot, ".xlsx") + outputFiles(bot, ".db")
                    + "\nsuccess-looking replies: " + successLeaks);

            assertEquals(envBefore, sha256(env), ".env.properties must not change");
            assertEquals(sysBefore, sysAfter, "settings system properties must not change");
            assertEquals(adminsBefore, rows("SELECT platform, platform_user_id FROM admins ORDER BY id"));
            assertEquals(typesBefore, rows("SELECT * FROM match_types ORDER BY id"));
            assertTrue(outputFiles(bot, ".xlsx").isEmpty() && outputFiles(bot, ".db").isEmpty(), "no export may be produced");
            assertTrue(fake.calls().stream().noneMatch(c -> c.method.equalsIgnoreCase("sendDocument")), "no document may be sent");
            assertTrue(successLeaks.isEmpty(), "admin behaviour reached by a member: " + successLeaks);
        } finally {
            writeTranscriptSafe(bot, "b4-authz-forged-callbacks");
            bot.close();
        }
    }

    /**
     * Wizard state of one user cannot be driven by another; a demoted admin's
     * open wizard and old keyboards stop working; a member cannot answer an
     * admin's /recalculate confirmation.
     */
    @Test
    void authz_foreignWizardState_demotedAdmin_foreignConfirmation(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        StringBuilder log = new StringBuilder();
        try {
            Path env = bot.workDir().resolve(".env.properties");
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            ConversationDriver member = admin.as(MEMBER);

            // (a) admin opens the current-year text step; the member types a year.
            admin.sendText("/settings");
            RecordedCall settings = admin.awaitKeyboard(STEP);
            List<RecordedCall> prompt = act(admin, () -> admin.click(settings, "setting_currentYear_select"));
            String envBefore = sha256(env);
            List<RecordedCall> foreignYear = act(member, () -> member.sendText("2099"));
            log.append("(a) admin waits for a year; member types 2099 -> ").append(summary(foreignYear))
                    .append("; env changed=").append(!envBefore.equals(sha256(env))).append('\n');
            assertEquals(envBefore, sha256(env), "a member's text must not complete the admin's wizard");
            List<RecordedCall> ownYear = act(admin, () -> admin.sendText("2001"));
            log.append("    admin then types 2001 -> ").append(summary(ownYear)).append('\n');

            // (b) a member answers the admin's /recalculate confirmation.
            admin.sendText("/recalculate");
            RecordedCall confirm = admin.awaitKeyboard(STEP);
            String startData = confirm.buttons().get(0).callbackData();
            List<RecordedCall> foreignStart = act(member, () -> member.clickData(confirm, startData));
            log.append("(b) member clicks the admin's 'Start recalculation' (").append(startData).append(") -> ").append(summary(foreignStart)).append('\n');
            assertTrue(sends(foreignStart).stream().noneMatch(c -> String.valueOf(c.text()).contains("Recalculating")),
                    "a member must not start the admin's recalculation");
            List<RecordedCall> cancel = act(admin, () -> admin.clickData(confirm, confirm.buttons().get(1).callbackData()));
            log.append("    admin clicks Cancel -> ").append(summary(cancel)).append('\n');

            // (c) a second admin opens /admins 'Add' and /settings, is then demoted, and uses both.
            new F16_Admins().addAdmin(F16_Admins.PLATFORM_TELEGRAM, String.valueOf(DEMOTED.id()), "second", BotHarness.NOW);
            ConversationDriver second = admin.as(DEMOTED);
            second.sendText("/admins");
            RecordedCall adminsMenu = second.awaitKeyboard(STEP);
            act(second, () -> second.click(adminsMenu, "admins_addstart"));
            second.sendText("/settings");
            RecordedCall secondSettings = second.awaitKeyboard(STEP);
            new F16_Admins().removeAdmin(F16_Admins.PLATFORM_TELEGRAM, String.valueOf(DEMOTED.id()));
            List<String> adminsBefore = rows("SELECT platform, platform_user_id FROM admins ORDER BY id");
            String envBefore2 = sha256(env);
            List<RecordedCall> addAfterDemotion = act(second, () -> second.sendText("TELEGRAM " + DEMOTED.id() + " back"));
            List<RecordedCall> toggleAfterDemotion = act(second, () -> second.clickData(secondSettings, "setting_toggle_settings.allowNonAdminUploads"));
            log.append("(c) demoted admin finishes his open 'Add admin' step -> ").append(summary(addAfterDemotion)).append('\n');
            log.append("    demoted admin taps his old /settings toggle -> ").append(summary(toggleAfterDemotion)).append('\n');
            assertEquals(adminsBefore, rows("SELECT platform, platform_user_id FROM admins ORDER BY id"), "demoted admin must not re-add himself");
            assertEquals(envBefore2, sha256(env), "demoted admin must not toggle a setting");

            // (d) the newest admin can remove the bootstrap admin (no owner role); restart keeps it removed.
            new F16_Admins().addAdmin(F16_Admins.PLATFORM_TELEGRAM, String.valueOf(DEMOTED.id()), "second", BotHarness.NOW);
            second.sendText("/admins");
            RecordedCall menu2 = second.awaitKeyboard(STEP);
            act(second, () -> second.click(menu2, "admins_removeselect"));
            RecordedCall pick = fake.calls().stream().filter(c -> c.hasKeyboard() && c.buttons().stream()
                    .anyMatch(b -> b.callbackData() != null && b.callbackData().startsWith("admins_remove_"))).reduce((a, b) -> b).orElseThrow();
            String ownerButton = pick.buttons().stream().filter(b -> b.text().contains("910001")).findFirst().orElseThrow().callbackData();
            List<RecordedCall> removeOwner = act(second, () -> second.clickData(pick, ownerButton));
            log.append("(d) newer admin removes the bootstrap admin 910001 -> ").append(summary(removeOwner))
                    .append("; admins now ").append(rows("SELECT platform, platform_user_id FROM admins ORDER BY id")).append('\n');
            boolean ownerGone = rows("SELECT platform_user_id FROM admins").stream().noneMatch(r -> r.equals("910001"));
            List<RecordedCall> ownerTries = act(admin, () -> admin.sendText("/settings"));
            log.append("    bootstrap admin (still TELEGRAM_ADMIN_USERID in .env) runs /settings -> ").append(summary(ownerTries)).append('\n');
            assertTrue(ownerGone, "today any admin can remove the bootstrap admin");
            note("authz: foreign wizard state, foreign confirmation, demoted admin, owner removal", log.toString());
        } finally {
            writeTranscriptSafe(bot, "b4-authz-wizards");
            bot.close();
        }
    }

    /**
     * settings.allowNonAdminUploads=true: the member's own "yes" is the only
     * gate, and the destructive dialogs that follow (reprocess = replace the
     * round and delete later rounds; capped list = full replace) are answered
     * by that same member - no admin is asked or told in the chat.
     */
    @Test
    void authz_nonAdminUploadsEnabled_memberReprocessesRoundAndReplacesCappedList(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        o.allowNonAdminUploads = true;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        StringBuilder log = new StringBuilder();
        try {
            ConversationDriver member = bot.driver(MEMBER, GROUP);
            byte[] round1 = Files.readAllBytes(BotHarness.sampleFile("2001_round_1.csv"));
            String q = "SELECT COUNT(*) || '|' || MAX(m.id) FROM matches m JOIN rounds r ON m.round_id = r.id WHERE r.year = 2001 AND r.round_order = 1";
            String matchesBefore = rows(q).get(0);
            List<RecordedCall> ask = act(member, () -> member.upload("2001_round_1.csv", round1));
            List<RecordedCall> yes = act(member, () -> member.sendText("yes"));
            RecordedCall dialog = sends(yes).stream().filter(RecordedCall::hasKeyboard).findFirst().orElseThrow(
                    () -> new AssertionError("no reprocess dialog: " + summary(yes)));
            List<RecordedCall> reprocess = act(member, () -> member.click(dialog, "Continue and reprocess"));
            String matchesAfter = rows(q).get(0);
            log.append("member uploads 2001_round_1.csv -> ").append(summary(ask)).append('\n')
                    .append("member 'yes' -> ").append(summary(yes)).append('\n')
                    .append("member taps 'Continue and reprocess' -> ").append(summary(reprocess)).append('\n')
                    .append("round 1 matches (count|max id) before=").append(matchesBefore).append(" after=").append(matchesAfter).append('\n');

            List<String> cappedBefore = rows("SELECT COUNT(*) FROM capped_imports WHERE year = 2001");
            List<RecordedCall> ask2 = act(member, () -> member.upload("2001_cappedlist.csv", "name,hall\nNobody Real,1\n".getBytes(StandardCharsets.UTF_8)));
            List<RecordedCall> yes2 = act(member, () -> member.sendText("yes"));
            List<String> cappedAfter = rows("SELECT COUNT(*) FROM capped_imports WHERE year = 2001");
            log.append("member uploads a one-row 2001_cappedlist.csv + 'yes' -> ").append(summary(ask2)).append(" || ").append(summary(yes2)).append('\n')
                    .append("capped_imports for 2001 before=").append(cappedBefore).append(" after=").append(cappedAfter).append('\n');
            boolean adminTold = fake.calls().stream().anyMatch(c -> c.isSend() && String.valueOf(ADMIN.id()).equals(c.chatId()));
            log.append("any message to the admin's private chat: ").append(adminTold).append('\n');
            note("authz: non-admin uploads enabled -> member reprocesses a round and replaces the capped list", log.toString());
            assertNotEquals(matchesBefore, matchesAfter, "the member's reprocess rewrote round 1");
            assertEquals("1", cappedAfter.get(0), "the member's one-row list replaced the year's capped list");
        } finally {
            writeTranscriptSafe(bot, "b4-authz-nonadmin-uploads");
            bot.close();
        }
    }

    // ================================================================== 2. injection

    /**
     * File names are echoed into chat replies without HTML escaping; a name
     * that contains a formatting tag switches the reply to parse_mode=HTML, so
     * any other tag in the name (a link) is rendered as markup by the bot. The
     * same name reaches the remote logs: Telegram escapes it, Discord posts it
     * raw with no allowed_mentions (an "@everyone" in a file name is posted as is).
     */
    @Test
    void injection_fileNameMarkupInReplies_andMentionsInDiscordLog_knownDefect(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        o.devChatId = DEV_CHAT;
        o.discordLogging = true;
        o.allowNonAdminUploads = true;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        StringBuilder log = new StringBuilder();
        try {
            String hostile = "<b>notice</b> <a href=\"https://example.invalid/login\">tap here to verify</a>.txt";
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            // (a) admin path: the name is echoed by "Unknown file type" AFTER the download. '<', '>', '"'
            // and ':' are legal in Linux file names (production) but not on Windows, where the
            // download step fails first - so this part is asserted on Linux only.
            List<RecordedCall> a = act(admin, () -> admin.upload(hostile, "x".getBytes(StandardCharsets.UTF_8)));
            log.append("(a) admin uploads '").append(hostile).append("' (os=").append(System.getProperty("os.name")).append(") -> ").append(summary(a)).append('\n');
            boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
            if (!windows) {
                RecordedCall reply = sends(a).stream().filter(c -> GROUP.idString().equals(c.chatId())
                        && String.valueOf(c.text()).contains("Unknown file type")).findFirst().orElseThrow(
                        () -> new AssertionError("no 'Unknown file type' reply: " + summary(a)));
                log.append("    reply parse_mode=").append(reply.parseMode()).append(" status=").append(reply.status)
                        .append("\n    text=").append(reply.text()).append('\n');
                assertEquals("HTML", reply.parseMode(), "the file name's <b> switches the reply to HTML");
                assertTrue(reply.text().contains("<a href=\"https://example.invalid/login\">"), "the link from the file name is sent as live markup");
            }

            // Non-admin with uploads enabled: the yes/no prompt echoes the name too (before any admin is involved).
            ConversationDriver member = admin.as(MEMBER);
            List<RecordedCall> m = act(member, () -> member.upload(hostile, "x".getBytes(StandardCharsets.UTF_8)));
            RecordedCall prompt = sends(m).stream().filter(c -> String.valueOf(c.text()).contains("you are not an admin")).findFirst().orElseThrow(
                    () -> new AssertionError("no non-admin prompt: " + summary(m)));
            log.append("(b) member (uploads enabled) uploads the same name -> prompt parse_mode=").append(prompt.parseMode())
                    .append("\n    text=").append(prompt.text()).append('\n');
            assertEquals("HTML", prompt.parseMode());
            assertTrue(prompt.text().contains("<a href="), "a non-admin can make the bot post a link");
            act(member, () -> member.sendText("no"));

            // Discord / dev-chat logs: the name of a member's file is logged before any admin check.
            String mention = "@everyone urgent round.csv";
            List<RecordedCall> d = act(member, () -> member.upload(mention, "x".getBytes(StandardCharsets.UTF_8)));
            act(member, () -> member.sendText("no"));
            List<RecordedCall> discord = fake.calls().stream().filter(c -> c.method.equals(FakeTelegramServer.DISCORD_CREATE_MESSAGE)).toList();
            List<RecordedCall> discordWithMention = discord.stream().filter(c -> String.valueOf(c.param("content")).contains("@everyone")).toList();
            List<RecordedCall> devLog = fake.calls().stream().filter(c -> c.method.equalsIgnoreCase("sendMessage") && DEV_CHAT.equals(c.chatId())).toList();
            boolean devLogRawLink = devLog.stream().anyMatch(c -> String.valueOf(c.text()).contains("<a href=\"https://example.invalid"));
            boolean devLogEscaped = devLog.stream().anyMatch(c -> String.valueOf(c.text()).contains("&lt;a href="));
            boolean allowedMentions = discord.stream().anyMatch(c -> c.params.has("allowed_mentions"));
            log.append("(c) member uploads '").append(mention).append("': discord posts=").append(discord.size())
                    .append(", posts containing @everyone=").append(discordWithMention.size())
                    .append(", any allowed_mentions field=").append(allowedMentions).append('\n');
            discordWithMention.stream().limit(2).forEach(c -> log.append("    discord content: ").append(firstLine(c.param("content"))).append('\n'));
            log.append("    dev-chat log: raw link=").append(devLogRawLink).append(" escaped=").append(devLogEscaped).append('\n');
            assertFalse(discordWithMention.isEmpty(), "the member's file name reaches the Discord log verbatim");
            assertFalse(allowedMentions, "today no allowed_mentions is sent with Discord log posts");
            assertFalse(devLogRawLink, "the Telegram dev-chat log escapes names");
            note("injection: file names in replies and remote logs", log.toString());
        } finally {
            writeTranscriptSafe(bot, "b4-injection-filenames");
            bot.close();
        }
    }

    /**
     * Hall names are interpolated into /infohall's and /comparehalls' bold
     * headers without escaping. Halls are seeded by code today (no user path
     * creates or renames one), so this is latent: the test renames hall '1'
     * directly in the sandbox database to show what the send path does.
     */
    @Test
    void injection_hallNameNotEscapedInHeaders_latent(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        StringBuilder log = new StringBuilder();
        try {
            int hallId = Integer.parseInt(rows("SELECT id FROM halls WHERE hall_name = '1'").get(0));
            String[] names = {"<a href=\"https://example.invalid/\">Hall Link</a>", "Smith & <Jones>"};
            ConversationDriver member = bot.driver(MEMBER, GROUP);
            for (String name : names) {
                update("UPDATE halls SET hall_name = ? WHERE id = ?", name, hallId);
                member.sendText("/infohall");
                RecordedCall halls = member.awaitKeyboard(STEP);
                List<RecordedCall> step = act(member, () -> member.clickData(halls, "infohall_hall_" + hallId));
                for (RecordedCall c : sends(step)) {
                    log.append("hall '").append(name).append("' -> ").append(c.method).append(" status=").append(c.status)
                            .append(" parse_mode=").append(c.parseMode()).append(" violations=").append(c.violations)
                            .append("\n    text=").append(firstLine(c.text() == null ? "" : c.text().replace("\n", " / "))).append('\n');
                }
            }
            List<RecordedCall> all = fake.calls();
            boolean liveLink = all.stream().anyMatch(c -> c.isOk() && "HTML".equals(c.parseMode())
                    && String.valueOf(c.text()).contains("<a href=\"https://example.invalid/\">Hall Link</a>"));
            boolean rejectedThenPlain = all.stream().anyMatch(c -> !c.isOk() && String.valueOf(c.text()).contains("Smith & <Jones>"));
            log.append("live link sent=").append(liveLink).append(", '&'/'<' name rejected by HTML parser then resent=").append(rejectedThenPlain).append('\n');
            note("injection: hall names in /infohall header (latent; hall renamed in sandbox DB)", log.toString());
            assertTrue(liveLink, "an unescaped hall name is sent as live HTML");
            assertTrue(rejectedThenPlain, "an '&'/'<' hall name breaks the HTML parse (400) and falls back");
        } finally {
            writeTranscriptSafe(bot, "b4-injection-hallnames");
            bot.close();
        }
    }

    /**
     * Seed S7 / A1B-5 established that "../" escapes temp/&lt;uuid&gt;/. An
     * absolute path needs no "..": Path.resolve(absolute) returns it unchanged,
     * so a filter that only strips ".." would not fix the hole. (On Linux only
     * '/' separates; the backslash variant is shown for the Windows dev box.)
     */
    @Test
    void pathTraversal_absoluteFileName_writesAndDeletesOutsideTemp_knownDefect(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        StringBuilder log = new StringBuilder();
        try {
            Path victimDir = tmp.resolve("outside");
            Files.createDirectories(victimDir);
            Path sentinel = victimDir.resolve("sentinel_round_1.csv");
            Files.writeString(sentinel, "SENTINEL - must survive", StandardCharsets.UTF_8);
            // processFile lower-cases the name first: use a lower-case absolute path.
            String absName = sentinel.toAbsolutePath().toString().toLowerCase();
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            List<RecordedCall> a = act(admin, () -> admin.upload(absName, "OVERWRITTEN".getBytes(StandardCharsets.UTF_8)));
            boolean survived = Files.exists(sentinel);
            log.append("absolute name '").append(absName.replace(tmp.toString().toLowerCase(), "<tmp>")).append("' -> ").append(summary(a))
                    .append("\n    sentinel survived=").append(survived).append(", outside dir still exists=").append(Files.exists(victimDir)).append('\n');

            Path sentinel2 = bot.workDir().resolve("bs_round_1.csv");
            Files.writeString(sentinel2, "SENTINEL 2", StandardCharsets.UTF_8);
            List<RecordedCall> b = act(admin, () -> admin.upload("..\\..\\bs_round_1.csv", "x".getBytes(StandardCharsets.UTF_8)));
            boolean survived2 = Files.exists(sentinel2);
            log.append("backslash name '..\\\\..\\\\bs_round_1.csv' -> ").append(summary(b)).append("\n    sentinel survived=").append(survived2)
                    .append(" (os=").append(System.getProperty("os.name")).append("; on Linux the backslash is an ordinary character)\n");
            // The same hole aimed at the bot's own database (relative name, as in A1B-5).
            Path db = DatabaseHelper.getDefaultDatabasePath();
            long dbBytes = Files.size(db);
            List<RecordedCall> c = act(admin, () -> admin.upload("../../database/core/default.db", "x".getBytes(StandardCharsets.UTF_8)));
            boolean dbSurvived = Files.exists(db);
            boolean dbDirSurvived = Files.isDirectory(db.getParent());
            List<RecordedCall> after = act(admin, () -> admin.sendText("/rankhalls"));
            log.append("name '../../database/core/default.db' (db was ").append(dbBytes).append(" bytes) -> ").append(summary(c))
                    .append("\n    database file survived=").append(dbSurvived).append(", database/core/ survived=").append(dbDirSurvived)
                    .append("\n    next command /rankhalls -> ").append(summary(after)).append('\n');
            note("path traversal: absolute and backslash file names, and the database", log.toString());
            assertFalse(survived, "today an absolute file name overwrites and then deletes a file anywhere the bot can write");
            assertFalse(dbSurvived, "today one crafted upload name deletes the live database");
        } finally {
            writeTranscriptSafe(bot, "b4-path-traversal");
            bot.close();
        }
    }

    /**
     * Formula injection: player names that a spreadsheet would treat as a
     * formula ("=", "+", "-", "@", DDE) are written by the .xlsx export as
     * string cells, never formula cells.
     */
    @Test
    void xlsxExport_formulaLikeNames_areWrittenAsTextCells(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        StringBuilder log = new StringBuilder();
        try {
            Map<String, String> renames = new LinkedHashMap<>();
            renames.put("Gus Fring", "=1+1");
            renames.put("Kim Wexler", "=HYPERLINK(\"https://example.invalid/\",\"open\")");
            renames.put("Walter White", "+1+1");
            renames.put("Mike Ehrmantraut", "-2+3");
            renames.put("Marceline", "@SUM(1,1)");
            renames.put("Princess Bubblegum", "=cmd|' /C calc'!A0");
            int renamed = 0;
            for (Map.Entry<String, String> e : renames.entrySet()) {
                renamed += update("UPDATE player_names SET name = ? WHERE name = ?", e.getValue(), e.getKey());
            }
            assertEquals(renames.size(), renamed, "fixture names present");

            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            admin.sendText("/exportdatabase");
            RecordedCall choice = admin.awaitKeyboard(STEP);
            act(admin, () -> admin.clickFirst(choice, btn -> btn.callbackData() != null && btn.callbackData().startsWith("export_db_xlsx_")));
            List<Path> xlsx = outputFiles(bot, ".xlsx");
            assertEquals(1, xlsx.size(), "one export written: " + xlsx);
            byte[] bytes = Files.readAllBytes(xlsx.get(0));

            int formulaCells = 0;
            int stringCells = 0;
            List<String> found = new ArrayList<>();
            try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                for (Sheet sheet : wb) {
                    for (Row row : sheet) {
                        for (Cell cell : row) {
                            if (cell.getCellType() == CellType.FORMULA) {
                                formulaCells++;
                            } else if (cell.getCellType() == CellType.STRING) {
                                stringCells++;
                                if (renames.containsValue(cell.getStringCellValue())) {
                                    found.add(sheet.getSheetName() + "!" + cell.getAddress() + "=" + cell.getStringCellValue());
                                }
                            }
                        }
                    }
                }
            }
            int fTags = 0;
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                ZipEntry e;
                while ((e = zip.getNextEntry()) != null) {
                    if (e.getName().startsWith("xl/worksheets/")) {
                        String xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                        fTags += xml.split("<f[ >]", -1).length - 1;
                    }
                }
            }
            log.append("export ").append(bytes.length).append(" bytes; string cells=").append(stringCells).append(", formula cells=").append(formulaCells)
                    .append(", <f> elements in sheet XML=").append(fTags).append("\nformula-like names found as text: ").append(found).append('\n');
            note("formula injection: .xlsx export", log.toString());
            assertEquals(0, formulaCells);
            assertEquals(0, fTags);
            assertTrue(found.size() >= renames.size(), "every formula-like name is present as a text cell: " + found);
        } finally {
            writeTranscriptSafe(bot, "b4-xlsx-formula");
            bot.close();
        }
    }

    // ================================================================== 3. secrets

    /**
     * Normal and failing paths with every remote log enabled: the bot token
     * must never appear in a message body, a log post or a Discord post. A
     * getFile answer whose file_path is not a valid URI makes URI.create throw
     * with the full download URL (token included) - that text goes to stderr.
     */
    @Test
    void secrets_tokenNeverInBodies_butMalformedFilePathPrintsItToStderr(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        o.devChatId = DEV_CHAT;
        o.discordLogging = true;
        o.extraEnv.put("DISCORD_ADMIN_USERID", "5550009");
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        String token = FakeTelegramServer.FAKE_TOKEN;
        PrintStream origErr = System.err;
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        StringBuilder log = new StringBuilder();
        try {
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            ConversationDriver member = admin.as(MEMBER);
            act(admin, () -> admin.sendText("/about"));
            act(admin, () -> admin.upload("notes.txt", "x".getBytes(StandardCharsets.UTF_8)));
            fake.failNext("getFile", Fault.serverError());
            act(admin, () -> admin.upload("2001_round_2.csv", "x".getBytes(StandardCharsets.UTF_8)));
            fake.failNext("sendMessage", Fault.serverError());
            act(admin, () -> admin.sendText("/help"));
            act(member, () -> member.sendText("/settings"));
            act(member, () -> member.upload("2001_round_2.csv", "x".getBytes(StandardCharsets.UTF_8)));

            System.setErr(new PrintStream(new TeeStream(origErr, errBuf), true, StandardCharsets.UTF_8));
            fake.failNext("getFile", Fault.http(200, "{\"ok\":true,\"result\":{\"file_id\":\"F1\",\"file_unique_id\":\"U1\",\"file_size\":1,\"file_path\":\"documents/file 1.csv\"}}"));
            List<RecordedCall> bad = act(admin, () -> admin.upload("2001_round_2.csv", "x".getBytes(StandardCharsets.UTF_8)));
            System.setErr(origErr);
            String err = errBuf.toString(StandardCharsets.UTF_8);

            int scanned = 0;
            List<String> leaks = new ArrayList<>();
            for (RecordedCall c : fake.calls()) {
                scanned++;
                String body = c.params.toString();
                if (body.contains(token)) {
                    leaks.add(c.method + ": " + firstLine(body));
                }
                for (Map.Entry<String, String> h : c.headers.entrySet()) {
                    if (h.getValue() != null && h.getValue().contains(token)) {
                        leaks.add(c.method + " header " + h.getKey());
                    }
                }
            }
            long discordPosts = fake.calls().stream().filter(c -> c.method.equals(FakeTelegramServer.DISCORD_CREATE_MESSAGE)).count();
            long devPosts = fake.calls().stream().filter(c -> c.method.equalsIgnoreCase("sendMessage") && DEV_CHAT.equals(c.chatId())).count();
            boolean stderrHasToken = err.contains(token);
            String errLine = err.lines().filter(l -> l.contains(token)).findFirst().orElse("(none)");
            log.append("calls scanned=").append(scanned).append(" (discord posts=").append(discordPosts).append(", dev-chat log posts=").append(devPosts)
                    .append("); token in any body/header: ").append(leaks).append('\n')
                    .append("malformed file_path -> user sees: ").append(summary(bad)).append('\n')
                    .append("stderr contains the token: ").append(stderrHasToken).append("\n    first such line: ").append(errLine).append('\n');
            note("secrets: token in bodies, logs and stderr", log.toString());
            assertTrue(leaks.isEmpty(), "token reached a message/log body: " + leaks);
            assertTrue(stderrHasToken, "today the download URL with the token is printed to stderr (nohup.out)");
        } finally {
            System.setErr(origErr);
            writeTranscriptSafe(bot, "b4-secrets-bodies");
            bot.close();
        }
    }

    /**
     * A token pasted with trailing whitespace (java.util.Properties keeps
     * trailing spaces) makes every Bot API URL invalid; the listener's send
     * path logs e.getMessage() through LogHelper, and the IllegalArgumentException
     * text contains the whole URL - so the Telegram token is posted to the
     * Discord log channel (heartbeat at startup, then every 5 minutes).
     * Runs on the sandbox the harness builds (fake URLs, host guard, temp dir);
     * the listener is constructed here because the harness (rightly) refuses
     * any token other than the exact fake one. The invalid URL never leaves the JVM.
     */
    @Test
    void secrets_tokenWithTrailingSpace_isPostedToDiscordLog_knownDefect(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.devChatId = DEV_CHAT;
        o.discordLogging = true;
        o.startListener = false;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        TelegramListener listener = null;
        PrintStream origErr = System.err;
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        try {
            EnvironmentManager.ensureSystemPropertiesLoaded();
            System.setProperty("TELEGRAM_BOT_TOKEN", FakeTelegramServer.FAKE_TOKEN + " ");
            System.setErr(new PrintStream(new TeeStream(origErr, errBuf), true, StandardCharsets.UTF_8));
            listener = new TelegramListener();
            listener.start();
            RecordedCall leak = fake.awaitCall(0, c -> c.method.equals(FakeTelegramServer.DISCORD_CREATE_MESSAGE)
                    && String.valueOf(c.param("content")).contains(FakeTelegramServer.FAKE_TOKEN), Duration.ofSeconds(20), "discord post with the token");
            long telegramCalls = fake.calls().stream().filter(c -> !c.method.startsWith("discord.")).count();
            String content = leak.param("content");
            note("secrets: token with a trailing space -> Discord log", "discord content: " + firstLine(content.replace("\n", " / "))
                    + "\nTelegram API calls that reached the fake: " + telegramCalls
                    + "\nstderr lines with the token: " + errBuf.toString(StandardCharsets.UTF_8).lines().filter(l -> l.contains(FakeTelegramServer.FAKE_TOKEN)).count());
            assertTrue(content.contains("/bot" + FakeTelegramServer.FAKE_TOKEN), content);
        } finally {
            System.setErr(origErr);
            if (listener != null) {
                listener.stop();
                // the polling loop sleeps 5 s after each failed poll before it re-checks isRunning
                waitForThreads(t -> {
                    for (StackTraceElement el : t.getStackTrace()) {
                        if (el.getClassName().startsWith(TelegramListener.class.getName())) {
                            return true;
                        }
                    }
                    return false;
                }, Duration.ofSeconds(12));
            }
            writeTranscriptSafe(bot, "b4-secrets-trailing-space-token");
            bot.close();
        }
    }

    /**
     * The frozen rig's TranscriptWriter can cut a long Discord post inside a
     * surrogate pair (emoji), which Files.writeString rejects as unmappable;
     * keep the scenario's result and record the rig problem instead.
     */
    private static void writeTranscriptSafe(BotHarness bot, String name) {
        try {
            bot.writeTranscript(name);
        } catch (Exception | Error e) {
            note("rig: transcript " + name + " not written", e.toString());
        }
    }

    private static void waitForThreads(Predicate<Thread> p, Duration max) throws InterruptedException {
        long end = System.nanoTime() + max.toNanos();
        while (System.nanoTime() < end) {
            boolean any = Thread.getAllStackTraces().keySet().stream().filter(t -> t != Thread.currentThread()).anyMatch(p);
            if (!any) {
                return;
            }
            Thread.sleep(100);
        }
    }

    // ================================================================== 4. abuse cost

    /**
     * What one non-admin can make the bot do, and what each action costs:
     * Bot API calls, remote-log posts and admin pings, threads started, process
     * CPU, wall time, and files left in output/ (never cleaned). Remote logging
     * on (dev chat + Discord with admin ids), defaults otherwise.
     */
    @Test
    void abuse_costPerNonAdminAction(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        o.devChatId = DEV_CHAT;
        o.discordLogging = true;
        o.extraEnv.put("DISCORD_ADMIN_USERID", "5550009");
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        StringBuilder table = new StringBuilder(String.format("%-46s %4s %6s %6s %6s %7s %7s %8s %8s %7s %9s%n",
                "action (member)", "n", "sends", "keybd", "devlog", "discord", "pings", "threads", "cpu_ms", "wall_ms", "out_KB"));
        try {
            ConversationDriver member = bot.driver(MEMBER, GROUP);
            // warm-up (class loading, fonts) so the first measured action is not inflated
            act(member, () -> member.sendText("/rankplayers"));
            RecordedCall warm = fake.calls().stream().filter(RecordedCall::hasKeyboard).reduce((a, b) -> b).orElseThrow();
            act(member, () -> member.click(warm, "rankplayers_round_all"));

            measure(bot, member, table, "/rankplayers -> All rounds (report+image)", 5, () -> {
                member.sendText("/rankplayers");
                RecordedCall k = member.awaitKeyboard(STEP);
                member.click(k, "rankplayers_round_all");
            });
            RecordedCall stale = fake.calls().stream().filter(c -> c.hasKeyboard() && c.buttons().stream()
                    .anyMatch(b -> "rankplayers_round_all".equals(b.callbackData()))).reduce((a, b) -> b).orElseThrow();
            measure(bot, member, table, "re-tap stripped keyboard x10 at once", 1, () -> {
                for (int i = 0; i < 10; i++) {
                    member.clickData(stale, "rankplayers_round_all");
                }
            });
            measure(bot, member, table, "/infohall -> hall 1 -> All (report+image)", 3, () -> {
                member.sendText("/infohall");
                RecordedCall k = member.awaitKeyboard(STEP);
                member.clickFirst(k, b -> b.callbackData() != null && b.callbackData().startsWith("infohall_hall_"));
                RecordedCall r = member.awaitKeyboard(STEP);
                member.clickFirst(r, b -> b.callbackData() != null && b.callbackData().startsWith("infohall_round_"));
            });
            measure(bot, member, table, "upload while uploads disabled", 10, () -> member.upload("2001_round_2.csv", "x".getBytes(StandardCharsets.UTF_8)));
            measure(bot, member, table, "/settings (access denied)", 10, () -> member.sendText("/settings"));
            measure(bot, member, table, "/exportdatabase (access denied)", 5, () -> member.sendText("/exportdatabase"));
            measure(bot, member, table, "plain chat line", 20, () -> member.sendText("good game everyone"));
            measure(bot, member, table, "/help", 10, () -> member.sendText("/help"));

            // Same flood with rate: 20 report taps injected back to back - peak live threads and heap.
            int peakThreadsBefore = ManagementFactory.getThreadMXBean().getPeakThreadCount();
            ManagementFactory.getThreadMXBean().resetPeakThreadCount();
            long t0 = System.nanoTime();
            List<RecordedCall> burst = act(member, () -> {
                for (int i = 0; i < 20; i++) {
                    member.clickData(stale, "rankplayers_round_all");
                }
            });
            long wall = (System.nanoTime() - t0) / 1_000_000;
            int peak = ManagementFactory.getThreadMXBean().getPeakThreadCount();
            long photos = burst.stream().filter(c -> c.method.equalsIgnoreCase("sendPhoto")).count();
            table.append(String.format("burst: 20 report taps back to back -> %d photos, %d sends, wall %d ms, peak live threads %d (before: %d)%n",
                    photos, sends(burst).size(), wall, peak, peakThreadsBefore));
            Path out = bot.workDir().resolve("output");
            table.append(String.format("output/ after the run: %d files, %d KB (never cleaned)%n", dirCount(out), dirBytes(out) / 1024));
            note("abuse: cost per non-admin action (remote logging on; cpu = whole test JVM incl. the fake)", table.toString());
        } finally {
            writeTranscriptSafe(bot, "b4-abuse-cost");
            bot.close();
        }
    }

    private void measure(BotHarness bot, ConversationDriver d, StringBuilder table, String label, int n, Runnable action) throws Exception {
        com.sun.management.OperatingSystemMXBean os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long threads0 = ManagementFactory.getThreadMXBean().getTotalStartedThreadCount();
        long cpu0 = os.getProcessCpuTime();
        Path out = bot.workDir().resolve("output");
        long out0 = dirBytes(out);
        int from = fake.callCount();
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            d.mark();
            action.run();
            d.awaitQuiet(QUIET, Duration.ofSeconds(60));
        }
        long wall = (System.nanoTime() - t0) / 1_000_000 - (long) n * QUIET.toMillis();
        long cpu = (os.getProcessCpuTime() - cpu0) / 1_000_000;
        long threads = ManagementFactory.getThreadMXBean().getTotalStartedThreadCount() - threads0;
        List<RecordedCall> calls = fake.calls().subList(from, fake.callCount());
        long userSends = calls.stream().filter(c -> c.isSend() && !DEV_CHAT.equals(c.chatId())).count();
        long keyboards = calls.stream().filter(c -> c.isSend() && c.hasKeyboard()).count();
        long dev = calls.stream().filter(c -> c.method.equalsIgnoreCase("sendMessage") && DEV_CHAT.equals(c.chatId())).count();
        List<RecordedCall> discord = calls.stream().filter(c -> c.method.equals(FakeTelegramServer.DISCORD_CREATE_MESSAGE)).toList();
        long pings = discord.stream().filter(c -> String.valueOf(c.param("content")).contains("<@5550009>")).count()
                + calls.stream().filter(c -> DEV_CHAT.equals(c.chatId()) && String.valueOf(c.text()).contains("tg://user?id=910001")).count();
        long outKb = (dirBytes(out) - out0) / 1024;
        table.append(String.format("%-46s %4d %6.1f %6.1f %6.1f %7.1f %7.1f %8.1f %8.0f %7.0f %9.1f%n", label, n,
                (double) userSends / n, (double) keyboards / n, (double) dev / n, (double) discord.size() / n, (double) pings / n,
                (double) threads / n, (double) cpu / n, (double) Math.max(0, wall) / n, (double) outKb / n));
    }

    /** Copies everything written to {@code a} into {@code b} as well. */
    private static final class TeeStream extends OutputStream {
        private final OutputStream a;
        private final OutputStream b;

        TeeStream(OutputStream a, OutputStream b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public void write(int c) throws IOException {
            a.write(c);
            synchronized (b) {
                b.write(c);
            }
        }

        @Override
        public void write(byte[] buf, int off, int len) throws IOException {
            a.write(buf, off, len);
            synchronized (b) {
                b.write(buf, off, len);
            }
        }

        @Override
        public void flush() throws IOException {
            a.flush();
        }
    }

    @SuppressWarnings("unused")
    private static String read(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
}
