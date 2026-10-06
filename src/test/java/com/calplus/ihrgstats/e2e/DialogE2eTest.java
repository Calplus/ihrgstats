package com.calplus.ihrgstats.e2e;

import com.calplus.ihrgstats.databasemanager.A1_Rounds;
import com.calplus.ihrgstats.utils.DatabaseHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.calplus.ihrgstats.e2e.ConversationDriver.ADMIN;
import static com.calplus.ihrgstats.e2e.ConversationDriver.GROUP;
import static com.calplus.ihrgstats.e2e.ConversationDriver.MEMBER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Lane a1b, scope 2: every dialog outcome - upload rejections, the capped list,
 * non-admin yes/no, re-upload (reprocess) dialog, /recalculate, /exportdatabase,
 * the /settings, /admins and /matchtypes text wizards, stale / foreign / forged
 * choice_ clicks. The real 60 s / 120 s dialog timeouts have no override seam,
 * so the timeout scenarios are gated: -Dihrgstats.e2e.slow=true (about 2.5 min).
 */
public class DialogE2eTest {

    private static final Duration FIRST = Duration.ofSeconds(12);
    private static final Duration QUIET = Duration.ofMillis(1500);

    private FakeTelegramServer fake;

    @BeforeEach
    void startFake() throws Exception {
        fake = FakeTelegramServer.start();
    }

    @AfterEach
    void stopFake() {
        fake.close();
    }

    private A1bSupport.Step act(Runnable action) {
        A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
        int from = fake.callCount();
        action.run();
        return A1bSupport.awaitStep(fake, from, null, null, FIRST, QUIET);
    }

    private static String texts(A1bSupport.Step s) {
        StringBuilder sb = new StringBuilder();
        for (RecordedCall c : A1bSupport.userSends(s.calls(), null)) {
            sb.append(c.text() == null ? "<file " + (c.file() == null ? "?" : c.file().fileName()) + ">" : c.text()).append("\n---\n");
        }
        return sb.toString();
    }

    private static byte[] sample(String name) throws Exception {
        return Files.readAllBytes(BotHarness.sampleFile(name));
    }

    /** Answers an upload's choice_ dialogs like the corpus battery until a terminal message; returns all texts. */
    private List<String> driveUpload(ConversationDriver d, Duration max) {
        List<String> seen = new ArrayList<>();
        long deadline = System.nanoTime() + max.toNanos();
        while (System.nanoTime() < deadline) {
            A1bSupport.Step s = A1bSupport.awaitStep(fake, fake.callCount() - 0, null, null, Duration.ofSeconds(1), Duration.ofMillis(300));
            List<RecordedCall> sends = A1bSupport.userSends(A1bSupport.sliceFrom(fake, 0), null);
            RecordedCall last = sends.isEmpty() ? null : sends.get(sends.size() - 1);
            if (last != null && last.hasKeyboard() && last.buttons().stream().anyMatch(b -> b.callbackData().startsWith("choice_"))
                    && !seen.contains("#" + last.seq)) {
                seen.add("#" + last.seq);
                List<RecordedCall.Button> choices = last.buttons();
                String[] labels = choices.stream().map(RecordedCall.Button::text).toArray(String[]::new);
                int answer = BotHarness.autoAnswer(last.text(), labels);
                seen.add("DIALOG: " + last.text().lines().findFirst().orElse("") + " -> " + labels[answer]);
                d.clickData(last, choices.get(answer).callbackData());
                continue;
            }
            if (last != null && last.text() != null && (last.text().contains("processed successfully") || last.text().contains("ERROR")
                    || last.text().contains("Failed") || last.text().contains("❌"))) {
                d.awaitQuiet(Duration.ofMillis(1500), Duration.ofSeconds(20));
                break;
            }
            if (s.silent()) {
                A1bSupport.sleep(200);
            }
        }
        return seen;
    }

    // ------------------------------------------------------------------ uploads

    @Test
    void uploadRejections_everyPath(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        Map<String, String> out = new LinkedHashMap<>();
        try {
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            Map<String, byte[]> cases = new LinkedHashMap<>();
            cases.put("notes.txt", "hello".getBytes(StandardCharsets.UTF_8));
            cases.put("round_x.csv", sample("2001_round_2.csv"));
            cases.put("2001_round_0.csv", sample("2001_round_2.csv"));
            cases.put("2001_round_2.xlsx", new byte[]{0x50, 0x4b, 3, 4, 0, 0});
            cases.put("2001_round_3.csv", new byte[0]);
            cases.put("2001_round_4.csv", "only,a,header\n".getBytes(StandardCharsets.UTF_8));
            cases.put("2001_round_5.csv", new byte[]{(byte) 0xff, (byte) 0xfe, 0, 0, 1, 2, 3, (byte) 0x89, 0x50, 0x4e, 0x47});
            String good = new String(sample("2001_round_2.csv"), StandardCharsets.UTF_8);
            String[] goodLines = good.split("\r?\n");
            cases.put("2001_round_6.csv", (goodLines[0] + "\n" + goodLines[1].replaceAll("\\d+", "x") + "\n").getBytes(StandardCharsets.UTF_8));
            cases.put("2001_round_7.csv", (goodLines[0] + "\n" + goodLines[1] + ",extra,cells,here\n").getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> e : cases.entrySet()) {
                A1bSupport.Step s = act(() -> admin.upload(e.getKey(), e.getValue()));
                // validation replies may come as several messages; gather a little longer
                admin.awaitQuiet(Duration.ofMillis(1500), Duration.ofSeconds(15));
                List<RecordedCall> all = A1bSupport.sliceFrom(fake, s.fromIndex());
                String sum = A1bSupport.summarize(all, null);
                out.put(e.getKey(), sum);
                A1bSupport.cell("upload-reject", e.getKey(), e.getValue().length + " bytes", "admin", "group", sum);
                A1bSupport.ledger("(upload)", all);
                // a choice_ dialog may have opened (e.g. reprocess): cancel by answering its last option
                for (RecordedCall c : A1bSupport.userSends(all, null)) {
                    if (c.hasKeyboard() && c.buttons().stream().anyMatch(b -> b.callbackData().startsWith("choice_"))) {
                        List<RecordedCall.Button> b = c.buttons();
                        act(() -> admin.clickData(c, b.get(b.size() - 1).callbackData()));
                        admin.awaitQuiet(Duration.ofMillis(1500), Duration.ofSeconds(15));
                    }
                }
            }
            A1bSupport.note("upload rejections", out.entrySet().stream().map(x -> x.getKey() + " => " + x.getValue())
                    .reduce("", (a, b) -> a + b + "\n"));
            // Every rejected upload gets some reply (none silent) - asserted for the clearly invalid names.
            assertTrue(out.get("notes.txt").contains("Unknown file ty"), out.get("notes.txt"));
            // Every rejection here is answered; content errors come as two messages (detail + "[TelegramListener] ERROR").
            for (Map.Entry<String, String> e : out.entrySet()) {
                assertTrue(e.getValue().contains("sendMessage->"), "rejection answered: " + e.getKey() + " " + e.getValue());
            }
            assertTrue(fake.rejections().isEmpty(), "rejections: " + fake.rejections());
        } finally {
            bot.writeTranscript("a1b-dialog-upload-rejections");
            bot.close();
        }
    }

    @Test
    void upload_noCurrentYear_andDownloadFailures(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.REFERENCE;
        o.currentYear = "";
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        try {
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            A1bSupport.Step noYear = act(() -> admin.upload("round_1.csv", new byte[]{'a'}));
            A1bSupport.cell("upload-reject", "round_1.csv", "no current year", "admin", "group", noYear.summary(null));
            A1bSupport.Step noYearCapped = act(() -> admin.upload("cappedlist.csv", new byte[]{'a'}));
            A1bSupport.cell("upload-reject", "cappedlist.csv", "no current year", "admin", "group", noYearCapped.summary(null));
            assertTrue(texts(noYear).contains("no current year"), texts(noYear));
            assertTrue(texts(noYearCapped).contains("no current year"), texts(noYearCapped));

            fake.failNext("getFile", Fault.serverError());
            A1bSupport.Step getFile500 = act(() -> admin.upload("2001_round_1.csv", new byte[]{'a'}));
            A1bSupport.cell("upload-reject", "2001_round_1.csv", "getFile answers 500", "admin", "group", getFile500.summary(null));
            fake.failNext(FakeTelegramServer.FILE_DOWNLOAD, Fault.http(404, "Not Found"));
            A1bSupport.Step dl404 = act(() -> admin.upload("2001_round_1.csv", new byte[]{'a'}));
            A1bSupport.cell("upload-reject", "2001_round_1.csv", "file download answers 404", "admin", "group", dl404.summary(null));
            fake.failNext(FakeTelegramServer.FILE_DOWNLOAD, Fault.dropConnection());
            A1bSupport.Step dlDrop = act(() -> admin.upload("2001_round_1.csv", new byte[]{'a'}));
            A1bSupport.cell("upload-reject", "2001_round_1.csv", "file download connection dropped", "admin", "group", dlDrop.summary(null));
            A1bSupport.note("upload no-year / download failures", "noYear: " + noYear.summary(null) + "\ncapped: " + noYearCapped.summary(null)
                    + "\ngetFile500: " + getFile500.summary(null) + "\ndl404: " + dl404.summary(null) + "\ndlDrop: " + dlDrop.summary(null));
            assertTrue(texts(getFile500).contains("Failed to download"), texts(getFile500));
            assertTrue(fake.rejections().isEmpty(), "rejections: " + fake.rejections());
        } finally {
            bot.writeTranscript("a1b-dialog-upload-noyear-download");
            bot.close();
        }
    }

    /**
     * A crafted file name: the downloader saves temp/&lt;uuid&gt;/ + the raw name and
     * deletes it afterwards. A sentinel placed where "../../sentinel.csv" resolves
     * shows whether the bot writes and deletes outside its temp folder (seed S7).
     */
    @Test
    void upload_traversalFileName_knownDefect_A1B_5(@TempDir Path tmp) throws Exception {
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), new BotHarness.Options());
        try {
            Path sentinel = bot.workDir().resolve("sentinel_round_1.csv");
            Files.writeString(sentinel, "SENTINEL - must survive", StandardCharsets.UTF_8);
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            A1bSupport.Step s = act(() -> admin.upload("../../sentinel_round_1.csv", "x".getBytes(StandardCharsets.UTF_8)));
            admin.awaitQuiet(Duration.ofMillis(1500), Duration.ofSeconds(15));
            boolean survived = Files.exists(sentinel);
            A1bSupport.cell("upload-reject", "../../sentinel_round_1.csv", "path traversal name", "admin", "group",
                    "sentinel survived=" + survived + " :: " + s.summary(null));
            A1bSupport.note("traversal file name", "sentinel at workDir/sentinel_round_1.csv survived=" + survived + "\n" + A1bSupport.lines(s.calls()));
            // Today: the sentinel outside temp/<uuid>/ is overwritten by the download and then deleted (A1B-5).
            assertFalse(survived, "today the crafted name writes and deletes outside the temp folder (A1B-5)");
        } finally {
            bot.writeTranscript("a1b-dialog-upload-traversal");
            bot.close();
        }
    }

    @Test
    void nonAdminUpload_disabled_yes_no_other(@TempDir Path tmp) throws Exception {
        // disabled (default): silent
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        o.allowNonAdminUploads = false;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        try {
            ConversationDriver member = bot.driver(MEMBER, GROUP);
            A1bSupport.Step s = act(() -> member.upload("2001_round_2.csv", new byte[]{'a'}));
            A1bSupport.cell("non-admin-upload", "2001_round_2.csv", "uploads disabled", "member", "group",
                    (s.silent() ? "SILENT " : "") + s.summary(null));
            assertTrue(s.silent(), "today a non-admin upload is ignored without a reply (A1B-6): " + s.summary(null));
        } finally {
            bot.writeTranscript("a1b-dialog-nonadmin-disabled");
            bot.close();
        }
    }

    @Test
    void nonAdminUpload_enabled_yes_no_otherText_foreignAnswer(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        o.allowNonAdminUploads = true;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        try {
            ConversationDriver member = bot.driver(MEMBER, GROUP);
            ConversationDriver admin = bot.driver(ADMIN, GROUP);

            // "no" -> no reply at all
            A1bSupport.Step ask1 = act(() -> member.upload("notes.txt", "x".getBytes(StandardCharsets.UTF_8)));
            assertTrue(texts(ask1).contains("Reply with 'yes' or 'no'"), texts(ask1));
            A1bSupport.Step no = act(() -> member.sendText("no"));
            A1bSupport.cell("non-admin-upload", "notes.txt", "member answers no", "member", "group", (no.silent() ? "SILENT " : "") + no.summary(null));

            // admin answers "yes" for the member -> not consumed; member says "maybe" -> not consumed; member "Y" -> proceeds
            A1bSupport.Step ask2 = act(() -> member.upload("notes.txt", "x".getBytes(StandardCharsets.UTF_8)));
            A1bSupport.Step adminYes = act(() -> admin.sendText("yes"));
            A1bSupport.cell("non-admin-upload", "notes.txt", "admin answers yes to the member's question", "admin", "group",
                    (adminYes.silent() ? "SILENT " : "") + adminYes.summary(null));
            A1bSupport.Step maybe = act(() -> member.sendText("maybe"));
            A1bSupport.cell("non-admin-upload", "notes.txt", "member answers maybe", "member", "group",
                    (maybe.silent() ? "SILENT " : "") + maybe.summary(null));
            // a second upload while the first question is pending
            A1bSupport.Step second = act(() -> member.upload("notes2.txt", "x".getBytes(StandardCharsets.UTF_8)));
            A1bSupport.cell("non-admin-upload", "notes2.txt", "second upload while a question is pending", "member", "group", second.summary(null));
            A1bSupport.Step yes = act(() -> member.sendText("Y"));
            A1bSupport.cell("non-admin-upload", "notes.txt", "member answers Y", "member", "group", yes.summary(null));
            A1bSupport.note("non-admin upload", "ask1: " + ask1.summary(null) + "\nno: " + no.summary(null) + "\nask2: " + ask2.summary(null)
                    + "\nadminYes: " + adminYes.summary(null) + "\nmaybe: " + maybe.summary(null) + "\nsecond: " + second.summary(null)
                    + "\nyes: " + yes.summary(null));
            assertTrue(no.silent(), "today an explicit 'no' gets no reply (A1B-6)");
            assertTrue(adminYes.silent(), "another user's 'yes' does not answer the member's question");
            assertTrue(texts(yes).contains("Unknown file type"), "after Y the file is processed: " + texts(yes));
        } finally {
            bot.writeTranscript("a1b-dialog-nonadmin-enabled");
            bot.close();
        }
    }

    @Test
    void reupload_reprocessDialog_cancelAndAccept_staleAndForeignChoice(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        try {
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            ConversationDriver member = bot.driver(MEMBER, GROUP);
            // round 1 is already in the seed: re-upload asks to reprocess
            A1bSupport.Step ask = act(() -> admin.upload("2001_round_1.csv", sampleQuiet("2001_round_1.csv")));
            assertNotNull(ask.keyboard(), "reprocess dialog: " + ask.summary(null));
            RecordedCall dialog = ask.keyboard();
            A1bSupport.cell("reprocess", "2001_round_1.csv", "dialog", "admin", "group", ask.summary(null));
            // foreign: the member clicks the admin's dialog
            A1bSupport.Step foreign = act(() -> member.clickData(dialog, dialog.buttons().get(0).callbackData()));
            A1bSupport.cell("reprocess", "2001_round_1.csv", "member clicks admin's dialog", "member", "group",
                    (foreign.silent() ? "SILENT " : "") + foreign.summary(null));
            // forged index and malformed data with the right nonce
            String nonce = dialog.buttons().get(0).callbackData().substring("choice_0_".length());
            A1bSupport.Step forged = act(() -> admin.clickData(dialog, "choice_7_" + nonce));
            A1bSupport.cell("reprocess", "choice_7_<nonce>", "index out of range", "admin", "group", forged.summary(null));
            A1bSupport.Step malformed = act(() -> admin.clickData(dialog, "choice_x_" + nonce));
            A1bSupport.cell("reprocess", "choice_x_<nonce>", "non-numeric index", "admin", "group", malformed.summary(null));
            A1bSupport.Step wrongNonce = act(() -> admin.clickData(dialog, "choice_0_deadbeef"));
            A1bSupport.cell("reprocess", "choice_0_deadbeef", "wrong nonce", "admin", "group", wrongNonce.summary(null));
            // cancel = the last option
            List<RecordedCall.Button> b = dialog.buttons();
            A1bSupport.Step cancel = act(() -> admin.clickData(dialog, b.get(b.size() - 1).callbackData()));
            admin.awaitQuiet(Duration.ofMillis(1500), Duration.ofSeconds(15));
            A1bSupport.cell("reprocess", "2001_round_1.csv", "admin picks '" + b.get(b.size() - 1).text() + "'", "admin", "group",
                    A1bSupport.summarize(A1bSupport.sliceFrom(fake, cancel.fromIndex()), null));
            // stale: the same dialog clicked again after it resolved
            A1bSupport.Step stale = act(() -> admin.clickData(dialog, b.get(0).callbackData()));
            A1bSupport.cell("reprocess", "2001_round_1.csv", "stale click after resolution", "admin", "group", stale.summary(null));
            // double-click on a fresh dialog's first option
            A1bSupport.Step ask2 = act(() -> admin.upload("2001_round_1.csv", sampleQuiet("2001_round_1.csv")));
            RecordedCall d2 = ask2.keyboard();
            assertNotNull(d2);
            A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
            int f = fake.callCount();
            admin.clickData(d2, d2.buttons().get(0).callbackData());
            admin.clickData(d2, d2.buttons().get(0).callbackData());
            List<String> rest = driveUpload(admin, Duration.ofSeconds(90));
            A1bSupport.cell("reprocess", "2001_round_1.csv", "double-click '" + d2.buttons().get(0).text() + "' then drive", "admin", "group",
                    rest + " :: " + A1bSupport.summarize(A1bSupport.sliceFrom(fake, f), null));
            A1bSupport.ledger("(upload)", A1bSupport.sliceFrom(fake, 0));
            A1bSupport.note("reprocess dialog", "ask: " + ask.summary(null) + "\nforeign: " + foreign.summary(null) + "\nforged: " + forged.summary(null)
                    + "\nmalformed: " + malformed.summary(null) + "\nwrongNonce: " + wrongNonce.summary(null) + "\ncancel: " + cancel.summary(null)
                    + "\nstale: " + stale.summary(null) + "\ndouble+drive: " + rest);
            assertNotNull(new A1_Rounds().getRoundByYearAndOrder(2001, 1));
            assertTrue(fake.rejections().isEmpty(), "rejections: " + fake.rejections());
        } finally {
            bot.writeTranscript("a1b-dialog-reprocess");
            bot.close();
        }
    }

    private static byte[] sampleQuiet(String name) {
        try {
            return sample(name);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void cappedList_valid_invalid_yearPrefixed(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        try {
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            byte[] valid = sample("2001_cappedlist.csv");
            String validText = new String(valid, StandardCharsets.UTF_8);
            String header = validText.split("\r?\n")[0];
            Map<String, byte[]> cases = new LinkedHashMap<>();
            cases.put("2001_cappedlist.csv", valid);
            cases.put("cappedlist.csv", valid);
            cases.put("2001_cappedlist.csv#unknown-hall", (header + "\nNo Such Player,ZZ\n").getBytes(StandardCharsets.UTF_8));
            cases.put("2001_cappedlist.csv#empty", new byte[0]);
            cases.put("2001_cappedlist.csv#header-only", (header + "\n").getBytes(StandardCharsets.UTF_8));
            cases.put("2001_CappedList.CSV", valid);
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> e : cases.entrySet()) {
                String name = e.getKey().contains("#") ? e.getKey().substring(0, e.getKey().indexOf('#')) : e.getKey();
                A1bSupport.Step s = act(() -> admin.upload(name, e.getValue()));
                admin.awaitQuiet(Duration.ofMillis(1500), Duration.ofSeconds(15));
                String sum = A1bSupport.summarize(A1bSupport.sliceFrom(fake, s.fromIndex()), null);
                out.put(e.getKey(), sum);
                A1bSupport.cell("capped-list", e.getKey(), e.getValue().length + " bytes", "admin", "group", sum);
                A1bSupport.ledger("(capped list)", A1bSupport.sliceFrom(fake, s.fromIndex()));
            }
            int capped;
            try (Connection c = DatabaseHelper.getDefaultConnection(); Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM player_year_status WHERE is_capped = 1")) {
                capped = rs.next() ? rs.getInt(1) : -1;
            } catch (Exception ex) {
                capped = -2;
            }
            A1bSupport.note("capped list", out.entrySet().stream().map(x -> x.getKey() + " => " + x.getValue())
                    .reduce("", (a, b) -> a + b + "\n") + "capped rows after the sequence: " + capped);
            assertTrue(fake.rejections().isEmpty(), "rejections: " + fake.rejections());
        } finally {
            bot.writeTranscript("a1b-dialog-capped-list");
            bot.close();
        }
    }

    // ------------------------------------------------------------------ admin dialogs

    @Test
    void recalculate_start_cancel_foreign_stale(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        try {
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            ConversationDriver member = bot.driver(MEMBER, GROUP);
            A1bSupport.Step ask = act(() -> admin.sendText("/recalculate"));
            RecordedCall dlg = ask.keyboard();
            assertNotNull(dlg, ask.summary(null));
            A1bSupport.cell("recalculate", "/recalculate", "dialog", "admin", "group", ask.summary(null));
            A1bSupport.Step again = act(() -> admin.sendText("/recalculate"));
            A1bSupport.cell("recalculate", "/recalculate", "second /recalculate while pending", "admin", "group", again.summary(null));
            A1bSupport.Step foreign = act(() -> member.clickData(dlg, dlg.buttons().get(0).callbackData()));
            A1bSupport.cell("recalculate", "/recalculate", "member clicks Start", "member", "group", foreign.summary(null));
            A1bSupport.Step cancel = act(() -> admin.clickData(dlg, dlg.buttons().get(1).callbackData()));
            A1bSupport.cell("recalculate", "/recalculate", "admin clicks Cancel", "admin", "group", cancel.summary(null));
            A1bSupport.Step stale = act(() -> admin.clickData(dlg, dlg.buttons().get(0).callbackData()));
            A1bSupport.cell("recalculate", "/recalculate", "stale Start after Cancel", "admin", "group", stale.summary(null));
            A1bSupport.Step ask2 = act(() -> admin.sendText("/recalculate"));
            A1bSupport.Step start = act(() -> admin.clickData(ask2.keyboard(), ask2.keyboard().buttons().get(0).callbackData()));
            admin.awaitQuiet(Duration.ofMillis(2500), Duration.ofSeconds(60));
            String startSum = A1bSupport.summarize(A1bSupport.sliceFrom(fake, start.fromIndex()), null);
            A1bSupport.cell("recalculate", "/recalculate", "admin clicks Start", "admin", "group", startSum);
            A1bSupport.ledger("/recalculate", A1bSupport.sliceFrom(fake, 0));
            A1bSupport.note("recalculate", "ask: " + ask.summary(null) + "\nagain: " + again.summary(null) + "\nforeign: " + foreign.summary(null)
                    + "\ncancel: " + cancel.summary(null) + "\nstale: " + stale.summary(null) + "\nstart: " + startSum);
            assertTrue(texts(again).contains("already have a pending"), texts(again));
            assertTrue(texts(foreign).contains("not for you"), "foreign click is answered publicly: " + texts(foreign));
            assertTrue(fake.rejections().isEmpty(), "rejections: " + fake.rejections());
        } finally {
            bot.writeTranscript("a1b-dialog-recalculate");
            bot.close();
        }
    }

    @Test
    void exportDatabase_xlsx_db_cancel_foreign_privateDeliveryFails(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        try {
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            ConversationDriver member = bot.driver(MEMBER, GROUP);
            Map<String, String> out = new LinkedHashMap<>();
            for (String choice : List.of("export_db_xlsx_", "export_db_confirm_", "export_db_cancel_")) {
                A1bSupport.Step ask = act(() -> admin.sendText("/exportdatabase"));
                RecordedCall kb = ask.keyboard();
                assertNotNull(kb, ask.summary(null));
                A1bSupport.Step s = act(() -> admin.clickData(kb, choice + ADMIN.id()));
                admin.awaitQuiet(Duration.ofMillis(2000), Duration.ofSeconds(60));
                String sum = A1bSupport.summarize(A1bSupport.sliceFrom(fake, s.fromIndex()), null);
                out.put(choice, sum);
                A1bSupport.cell("export", "/exportdatabase", choice + "<own id>", "admin", "group", sum);
            }
            // foreign: the member presses the admin's export button
            A1bSupport.Step ask = act(() -> admin.sendText("/exportdatabase"));
            A1bSupport.Step foreign = act(() -> member.clickData(ask.keyboard(), "export_db_xlsx_" + ADMIN.id()));
            out.put("foreign", foreign.summary(null));
            A1bSupport.cell("export", "/exportdatabase", "member presses admin's xlsx", "member", "group", foreign.summary(null));
            // the private delivery fails (user never started the bot): 403 on sendDocument to the admin's private chat
            fake.failWhen("sendDocument", c -> ADMIN.idString().equals(c.chatId()),
                    Fault.apiError(403, "Forbidden: bot can't initiate conversation with a user"), 1);
            A1bSupport.Step ask2 = act(() -> admin.sendText("/exportdatabase"));
            A1bSupport.Step fails = act(() -> admin.clickData(ask2.keyboard(), "export_db_confirm_" + ADMIN.id()));
            admin.awaitQuiet(Duration.ofMillis(2000), Duration.ofSeconds(60));
            String failSum = A1bSupport.summarize(A1bSupport.sliceFrom(fake, fails.fromIndex()), null);
            out.put("private-403", failSum);
            A1bSupport.cell("export", "/exportdatabase", "private delivery answered 403", "admin", "group", failSum);
            A1bSupport.ledger("/exportdatabase", A1bSupport.sliceFrom(fake, 0));
            A1bSupport.note("export", out.entrySet().stream().map(x -> x.getKey() + " => " + x.getValue()).reduce("", (a, b) -> a + b + "\n"));
            List<RecordedCall> after = A1bSupport.userSends(A1bSupport.sliceFrom(fake, fails.fromIndex()), null);
            long successTexts = after.stream().filter(c -> c.text() != null && GROUP.idString().equals(c.chatId())).count();
            assertTrue(successTexts >= 1, "today success is announced although the private delivery failed (A1B-7): " + failSum);
            assertTrue(fake.rejections().isEmpty(), "rejections: " + fake.rejections());
        } finally {
            bot.writeTranscript("a1b-dialog-export");
            bot.close();
        }
    }

    @Test
    void textWizards_settingsYear_admins_matchtypes(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        Map<String, String> out = new LinkedHashMap<>();
        try {
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            ConversationDriver member = bot.driver(MEMBER, GROUP);
            // /settings -> current year -> junk, then a valid year
            A1bSupport.Step st = act(() -> admin.sendText("/settings"));
            A1bSupport.Step prompt = act(() -> admin.clickData(st.keyboard(), "setting_currentYear_select"));
            out.put("year prompt", prompt.summary(null));
            out.put("member types 2002 meanwhile", act(() -> member.sendText("2002")).summary(null));
            out.put("admin chats 'good game everyone'", act(() -> admin.sendText("good game everyone")).summary(null));
            out.put("admin types 2001", act(() -> admin.sendText("2001")).summary(null));
            out.put("admin types 2001 again (wizard over?)", act(() -> admin.sendText("2001")).summary(null));
            // home hall manual + timezone manual
            A1bSupport.Step st2 = act(() -> admin.sendText("/settings"));
            A1bSupport.Step hh = act(() -> admin.clickData(st2.keyboard(), "setting_homeHall_select"));
            out.put("home hall picker", hh.summary(null));
            if (hh.keyboard() != null && hh.keyboard().buttons().stream().anyMatch(b -> "setting_homeHall_manual".equals(b.callbackData()))) {
                out.put("home hall manual prompt", act(() -> admin.clickData(hh.keyboard(), "setting_homeHall_manual")).summary(null));
                out.put("home hall typed <b>HallZ</b>", act(() -> admin.sendText("<b>HallZ</b>")).summary(null));
            }
            A1bSupport.Step st3 = act(() -> admin.sendText("/settings"));
            A1bSupport.Step tz = act(() -> admin.clickData(st3.keyboard(), "setting_timezone_select"));
            out.put("timezone picker", tz.summary(null));
            if (tz.keyboard() != null && tz.keyboard().buttons().stream().anyMatch(b -> "setting_timezone_manual".equals(b.callbackData()))) {
                out.put("timezone manual prompt", act(() -> admin.clickData(tz.keyboard(), "setting_timezone_manual")).summary(null));
                out.put("timezone typed Mars/Olympus", act(() -> admin.sendText("Mars/Olympus")).summary(null));
                out.put("timezone typed UTC", act(() -> admin.sendText("UTC")).summary(null));
            }
            // forged settings callbacks from a member (callbacks are not admin-checked before routing?)
            out.put("member forges setting_toggle allowAllChannels", act(() -> member.clickData(st3.keyboard(),
                    "setting_toggle_settings.allowAllChannelsProcessing")).summary(null));
            out.put("member forges setting_timezone_UTC", act(() -> member.clickData(st3.keyboard(), "setting_timezone_UTC")).summary(null));

            // /admins add: junk id, then a member id; then the member runs an admin command
            A1bSupport.Step ad = act(() -> admin.sendText("/admins"));
            out.put("/admins", ad.summary(null));
            A1bSupport.Step add = act(() -> admin.clickData(ad.keyboard(), "admins_addstart"));
            out.put("admins add prompt", add.summary(null));
            out.put("admins typed 'abc'", act(() -> admin.sendText("abc")).summary(null));
            out.put("admins typed member id", act(() -> admin.sendText(MEMBER.idString())).summary(null));
            out.put("member /settings after being added", act(() -> member.sendText("/settings")).summary(null));
            A1bSupport.Step ad2 = act(() -> admin.sendText("/admins"));
            A1bSupport.Step rs = act(() -> admin.clickData(ad2.keyboard(), "admins_removeselect"));
            out.put("admins remove picker", rs.summary(null));
            if (rs.keyboard() != null) {
                for (RecordedCall.Button b : rs.keyboard().buttons()) {
                    if (b.callbackData().startsWith("admins_remove_") && b.text().contains(MEMBER.idString())) {
                        out.put("remove member admin", act(() -> admin.clickData(rs.keyboard(), b.callbackData())).summary(null));
                    }
                }
                out.put("forged admins_remove_999999", act(() -> admin.clickData(rs.keyboard(), "admins_remove_999999")).summary(null));
            }
            out.put("member forges admins_addstart", act(() -> member.clickData(ad2.keyboard(), "admins_addstart")).summary(null));

            // /matchtypes create: walk prompts with junk then valid values
            A1bSupport.Step mt = act(() -> admin.sendText("/matchtypes"));
            A1bSupport.Step create = act(() -> admin.clickData(mt.keyboard(), "matchtypes_create"));
            out.put("matchtypes create prompt", create.summary(null));
            for (String input : List.of("Friendly", "abc", "250", "x", "A fictional friendly type")) {
                out.put("matchtypes typed '" + input + "'", act(() -> admin.sendText(input)).summary(null));
            }
            // the only admin removes themself
            A1bSupport.Step ad3 = act(() -> admin.sendText("/admins"));
            A1bSupport.Step rs3 = act(() -> admin.clickData(ad3.keyboard(), "admins_removeselect"));
            if (rs3.keyboard() != null) {
                for (RecordedCall.Button b : rs3.keyboard().buttons()) {
                    if (b.callbackData().startsWith("admins_remove_") && b.text().contains(ADMIN.idString())) {
                        out.put("only admin removes self", act(() -> admin.clickData(rs3.keyboard(), b.callbackData())).summary(null));
                        out.put("ex-admin /settings afterwards", act(() -> admin.sendText("/settings")).summary(null));
                        break;
                    }
                }
            }
            A1bSupport.ledger("(text wizards)", A1bSupport.sliceFrom(fake, 0));
            for (Map.Entry<String, String> e : out.entrySet()) {
                A1bSupport.cell("text-wizard", e.getKey().split(" ")[0], e.getKey(), e.getKey().startsWith("member") ? "member" : "admin", "group", e.getValue());
            }
            A1bSupport.note("text wizards", out.entrySet().stream().map(x -> x.getKey() + " => " + x.getValue()).reduce("", (a, b) -> a + b + "\n"));
            assertTrue(fake.rejections().isEmpty(), "rejections: " + fake.rejections());
        } finally {
            bot.writeTranscript("a1b-dialog-text-wizards");
            bot.close();
        }
    }

    // ------------------------------------------------------------------ real timeouts (gated)

    /**
     * The three dialog timeouts, run concurrently in one bot so the whole scenario
     * takes ~125 s: a non-admin yes/no question (60 s), /recalculate's Start/Cancel
     * (60 s) and a re-upload's reprocess dialog (120 s).
     */
    @Test
    @EnabledIfSystemProperty(named = "ihrgstats.e2e.slow", matches = "true")
    void dialogTimeouts_realClock(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        o.allowNonAdminUploads = true;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        ConversationDriver.User admin2 = new ConversationDriver.User(910003L, "Robin", "fake_admin_two");
        try {
            new com.calplus.ihrgstats.databasemanager.F16_Admins().addAdmin(
                    com.calplus.ihrgstats.databasemanager.F16_Admins.PLATFORM_TELEGRAM, admin2.idString(), null, BotHarness.NOW);
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            ConversationDriver second = bot.driver(admin2, GROUP);
            ConversationDriver member = bot.driver(MEMBER, GROUP);
            long t0 = System.nanoTime();
            int from = fake.callCount();
            member.upload("notes.txt", "x".getBytes(StandardCharsets.UTF_8));
            second.sendText("/recalculate");
            admin.upload("2001_round_1.csv", sample("2001_round_1.csv"));
            // the admin's upload holds INGESTION_LOCK while its dialog waits; a later upload queues silently
            A1bSupport.sleep(3000);
            int queuedFrom = fake.callCount();
            second.upload("2001_round_2.csv", sample("2001_round_2.csv"));
            A1bSupport.sleep(10_000);
            List<RecordedCall> queuedReplies = A1bSupport.userSends(A1bSupport.sliceFrom(fake, queuedFrom), null);
            // wait past the 120 s dialog timeout
            while (System.nanoTime() - t0 < Duration.ofSeconds(130).toNanos()) {
                A1bSupport.sleep(1000);
            }
            admin.awaitQuiet(Duration.ofSeconds(3), Duration.ofSeconds(120));
            List<RecordedCall> all = A1bSupport.sliceFrom(fake, from);
            StringBuilder sb = new StringBuilder();
            for (RecordedCall c : A1bSupport.userSends(all, null)) {
                sb.append(String.format("  +%6d ms %s%n", Duration.between(fake.calls().get(from).at, c.at).toMillis(), c));
            }
            sb.append("replies to the queued second upload during its first 10 s: ").append(queuedReplies.size()).append('\n');
            A1bSupport.note("dialog timeouts (real clock)", sb.toString());
            A1bSupport.cell("timeout", "non-admin yes/no", "60 s", "member", "group",
                    texts(new A1bSupport.Step(from, 0, null, all, false, 0)).contains("Confirmation timeout") ? "timeout message sent" : "NO timeout message");
            A1bSupport.ledger("(timeouts)", all);
            String everything = texts(new A1bSupport.Step(from, 0, null, all, false, 0));
            assertTrue(everything.contains("Confirmation timeout"), everything);
            assertTrue(everything.contains("Button selection timeout") || everything.contains("timeout"), everything);
            assertEquals(0, queuedReplies.size(), "today a queued upload gets no 'queued' message (A1B-8)");
        } finally {
            bot.writeTranscript("a1b-dialog-timeouts");
            bot.close();
        }
    }

    /** Wizard state left behind: a hall picked, then nothing; 10+ minutes later is simulated by back-dating. */
    @Test
    void abandonedWizard_stateStaysUntilSameCommandRunsAgain(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        try {
            ConversationDriver member = bot.driver(MEMBER, GROUP);
            A1bSupport.Step k1 = act(() -> member.sendText("/infoplayer"));
            act(() -> member.clickData(k1.keyboard(), k1.keyboard().buttons().get(0).callbackData()));
            int sizeBefore = StaticStateProbe.totalEntries();
            // other commands do not purge it
            act(() -> member.sendText("/rankhalls"));
            act(() -> member.sendText("/help"));
            int sizeAfter = StaticStateProbe.totalEntries();
            A1bSupport.note("abandoned wizard state", "static wizard entries after an abandoned /infoplayer: " + sizeBefore
                    + "; after /rankhalls + /help: " + sizeAfter + "\n" + StaticStateProbe.describe());
            A1bSupport.cell("state", "/infoplayer", "abandoned after hall pick", "member", "group",
                    "static wizard entries " + sizeBefore + " -> " + sizeAfter);
            assertTrue(sizeAfter >= sizeBefore && sizeBefore > 0, "abandoned state stays: " + sizeBefore + " -> " + sizeAfter);
        } finally {
            bot.writeTranscript("a1b-dialog-abandoned-wizard");
            bot.close();
        }
    }

    static Stream<String> unused() {
        return Stream.empty();
    }
}
