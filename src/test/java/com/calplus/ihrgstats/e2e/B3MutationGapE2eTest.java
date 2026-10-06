package com.calplus.ihrgstats.e2e;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.calplus.ihrgstats.e2e.ConversationDriver.ADMIN;
import static com.calplus.ihrgstats.e2e.ConversationDriver.GROUP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lane b3: the smallest end-to-end tests that fail under a surviving
 * listener-level mutant and pass on today's code. Each method names the
 * mutant it kills (see review record b3_mutation_report.md).
 */
public class B3MutationGapE2eTest {

    private static final Duration FIRST = Duration.ofSeconds(20);
    private static final Duration QUIET = Duration.ofMillis(1200);

    private FakeTelegramServer fake;

    @BeforeEach
    void startFake() throws Exception {
        fake = FakeTelegramServer.start();
    }

    @AfterEach
    void stopFake() {
        fake.close();
    }

    private A1bSupport.Step send(ConversationDriver d, String text) {
        A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
        int from = fake.callCount();
        d.sendText(text);
        return A1bSupport.awaitStep(fake, from, null, null, FIRST, QUIET);
    }

    /**
     * M01: every picker command must open ITS OWN wizard - the routing table
     * matched only "replied / did not reply" before, so routing /infomatchhall
     * to the /infomatch handler (or any other swap) went unnoticed.
     */
    @Test
    void everyPickerCommand_opensItsOwnWizard(@TempDir Path tmp) throws Exception {
        Map<String, String> expectedPrefix = new LinkedHashMap<>();
        expectedPrefix.put("/rankplayers", "rankplayers_");
        expectedPrefix.put("/rankhalls", "rankhalls_");
        expectedPrefix.put("/comparehalls", "comparehalls_");
        expectedPrefix.put("/compareplayers", "compareplayers_");
        expectedPrefix.put("/infoplayer", "infoplayer_");
        expectedPrefix.put("/infohall", "infohall_");
        expectedPrefix.put("/infomatch", "infomatch_");
        expectedPrefix.put("/infomatchhall", "infomatchhall_");
        expectedPrefix.put("/predict", "predict_");
        expectedPrefix.put("/help", "help_");
        expectedPrefix.put("/matchtypes", "matchtypes_");
        expectedPrefix.put("/admins", "admins_");
        expectedPrefix.put("/exportdatabase", "export_db_");
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        List<String> wrong = new ArrayList<>();
        try {
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            for (Map.Entry<String, String> e : expectedPrefix.entrySet()) {
                A1bSupport.Step s = send(admin, e.getKey());
                if (s.keyboard() == null) {
                    wrong.add(e.getKey() + " opened no keyboard: " + s.summary(null));
                    continue;
                }
                for (RecordedCall.Button b : s.keyboard().buttons()) {
                    if (b.callbackData() != null && !b.callbackData().startsWith(e.getValue())) {
                        wrong.add(e.getKey() + " offered button " + b.text() + " {" + b.callbackData() + "}");
                    }
                }
            }
        } finally {
            bot.writeTranscript("b3-gap-routing");
            bot.close();
        }
        assertEquals(List.of(), wrong, "each command must answer with its own wizard");
    }

    /**
     * M03: with the commands and upload threads configured and allow-all off,
     * a wizard's text answer typed in the COMMANDS thread must reach the
     * wizard (plain text is accepted from both threads).
     */
    @Test
    void threadMode_wizardTextInTheCommandsThread_reachesTheWizard(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        o.publicChatId = GROUP.idString();
        o.commandsThreadId = "11";
        o.fileUploadThreadId = "12";
        o.allowAllChannelsProcessing = false;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        try {
            ConversationDriver admin = bot.driver(ADMIN, GROUP).inThread(11L);
            A1bSupport.Step menu = send(admin, "/settings");
            assertTrue(menu.keyboard() != null, "settings menu: " + menu.summary(null));
            A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
            int f = fake.callCount();
            admin.clickData(menu.keyboard(), "setting_currentYear_select");
            A1bSupport.Step prompt = A1bSupport.awaitStep(fake, f, null, null, FIRST, QUIET);
            assertTrue(prompt.summary(null).contains("enter the tournament year"), prompt.summary(null));
            A1bSupport.Step answer = send(admin, "2002");
            String s = answer.summary(null);
            assertTrue(s.contains("Successfully set current year"), "year typed in the commands thread: " + s);
            assertEquals("2002", System.getProperty("SETTINGS_CURRENTYEAR"));
        } finally {
            bot.writeTranscript("b3-gap-thread-wizard-text");
            bot.close();
        }
    }

    /**
     * M29 (b3u28 R2-M3-2 lock): a callback that crashes mid-report must tell
     * the user (today: a status line in the chat), never leave them in silence
     * after the keyboard was stripped.
     */
    @Test
    void crashingCallback_isReportedInTheChat(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        try {
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            A1bSupport.Step picker = send(admin, "/infohall");
            assertTrue(picker.keyboard() != null, picker.summary(null));
            A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
            int f = fake.callCount();
            admin.clickData(picker.keyboard(), "infohall_hall_"); // empty id: NumberFormatException inside the route
            A1bSupport.Step after = A1bSupport.awaitStep(fake, f, null, null, Duration.ofSeconds(10), QUIET);
            boolean told = A1bSupport.userSends(after.calls(), null).stream()
                    .anyMatch(c -> c.text() != null && c.text().contains("Error processing"));
            assertTrue(told, "the user must be told the callback failed: " + after.summary(null));
        } finally {
            bot.writeTranscript("b3-gap-callback-crash");
            bot.close();
        }
    }

    /**
     * M25: when the file download itself fails (HTTP 500 from the file
     * server) the user is told the download failed - the error body must
     * never be ingested as if it were the uploaded CSV.
     */
    @Test
    void failedFileDownload_isReportedAsADownloadFailure(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        try {
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
            fake.failNext(FakeTelegramServer.FILE_DOWNLOAD, Fault.serverError());
            int f = fake.callCount();
            admin.upload("2001_round_2.csv", java.nio.file.Files.readAllBytes(BotHarness.sampleFile("2001_round_2.csv")));
            A1bSupport.Step s = A1bSupport.awaitStep(fake, f, null, null, Duration.ofSeconds(20), Duration.ofMillis(2500));
            // Full reply texts (Step.summary truncates each text to 70 characters).
            String texts = A1bSupport.userSends(s.calls(), null).stream()
                    .map(c -> c.text() == null ? "" : c.text()).reduce("", (a, b) -> a + "\n" + b);
            assertTrue(texts.contains("Failed to download"), "download failure reported: " + texts);
            assertTrue(!texts.contains("Invalid CSV"), "the error body must not be parsed as the CSV: " + texts);
        } finally {
            bot.writeTranscript("b3-gap-download-500");
            bot.close();
        }
    }

    /**
     * M04: in every keyboard built by the generic sender (the info pickers)
     * the Cancel button is alone on the last row - the choices come first,
     * never after Cancel. (The rank/compare pickers use the column sender,
     * which puts Cancel on the same row as the choices - not covered here.)
     */
    @Test
    void pickerKeyboards_cancelIsTheLastRow(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        List<String> wrong = new ArrayList<>();
        try {
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            for (String cmd : List.of("/infoplayer", "/infohall", "/infomatch", "/infomatchhall")) {
                A1bSupport.Step s = send(admin, cmd);
                if (s.keyboard() == null) {
                    wrong.add(cmd + " no keyboard");
                    continue;
                }
                List<RecordedCall.Button> bs = s.keyboard().buttons();
                RecordedCall.Button last = bs.get(bs.size() - 1);
                int maxRow = bs.stream().mapToInt(RecordedCall.Button::row).max().orElse(-1);
                if (last.callbackData() == null || !last.callbackData().endsWith("_cancel") || last.row() != maxRow
                        || bs.stream().filter(b -> b.row() == maxRow).count() != 1) {
                    wrong.add(cmd + " keyboard order: " + bs);
                }
            }
        } finally {
            bot.writeTranscript("b3-gap-keyboard-order");
            bot.close();
        }
        assertEquals(List.of(), wrong);
    }
}
