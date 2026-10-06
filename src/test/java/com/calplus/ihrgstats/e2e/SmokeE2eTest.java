package com.calplus.ihrgstats.e2e;

import com.calplus.ihrgstats.databasemanager.A1_Rounds;
import com.calplus.ihrgstats.utils.DatabaseHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.calplus.ihrgstats.e2e.ConversationDriver.ADMIN;
import static com.calplus.ihrgstats.e2e.ConversationDriver.GROUP;
import static com.calplus.ihrgstats.e2e.ConversationDriver.MEMBER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Five smoke scenarios through the real TelegramListener and the fake Bot API:
 * /help, a rank command with its image, a three-step picker wizard, a round CSV
 * upload with its dialogs answered by button clicks, and an admin-only command
 * refused for a member. Each writes a token-free transcript to
 * target/e2e-transcripts/ and fails on any request the fake rejects or any
 * request to a host other than the fake.
 */
public class SmokeE2eTest {

    private static final Duration STEP = Duration.ofSeconds(20);

    private FakeTelegramServer fake;

    @BeforeEach
    void startFake() throws Exception {
        fake = FakeTelegramServer.start();
    }

    @AfterEach
    void stopFake() {
        fake.close();
    }

    private static void assertNoRejections(FakeTelegramServer fake) {
        assertTrue(fake.rejections().isEmpty(), "the fake rejected requests Telegram would reject: " + fake.rejections());
    }

    @Test
    void help_showsTheCommandMenu(@TempDir Path tmp) throws Exception {
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"));
        try {
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            admin.sendText("/help");
            RecordedCall menu = admin.awaitKeyboard(STEP);

            assertTrue(menu.isOk());
            assertEquals(GROUP.idString(), menu.chatId(), "the menu goes back to the chat the command came from");
            assertTrue(menu.buttons().stream().anyMatch(b -> "help_category_commands".equals(b.callbackData())),
                    "help menu offers the command list: " + menu.buttons());
            assertTrue(fake.findCall(0, c -> c.method.equals("deleteWebhook")).isPresent(), "startup deletes the webhook");
            assertNoRejections(fake);
        } finally {
            bot.writeTranscript("smoke-01-help");
            bot.close();
        }
    }

    @Test
    void rankPlayers_allRounds_sendsReportPhotoAndDocument(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        try {
            ConversationDriver member = bot.driver(MEMBER, GROUP);
            member.sendText("/rankplayers");
            RecordedCall picker = member.awaitKeyboard(STEP);
            member.click(picker, "rankplayers_round_all");

            RecordedCall keyboardRemoved = member.awaitMethod("editMessageReplyMarkup", STEP);
            RecordedCall report = member.awaitMethod("sendMessage", STEP);
            RecordedCall photo = member.awaitMethod("sendPhoto", STEP);
            RecordedCall document = member.awaitMethod("sendDocument", STEP);

            assertTrue(keyboardRemoved.isOk(), "keyboard removal accepted: " + keyboardRemoved.responseBody);
            assertTrue(report.isOk() && report.text() != null && !report.text().isBlank());
            assertTrue(photo.isOk(), "photo accepted: " + photo.responseBody);
            assertNotNull(photo.file());
            assertTrue(photo.file().width() > 0 && photo.file().height() > 0, "photo is a readable image");
            assertTrue(document.isOk(), "document accepted: " + document.responseBody);
            assertTrue(document.file().fileName().endsWith(".png"), document.file().fileName());
            assertEquals(photo.file().size(), document.file().size(), "photo and document are the same PNG");
            assertTrue(fake.findCall(0, c -> c.method.equals("answerCallbackQuery") && c.isOk()).isPresent(),
                    "the click was answered");
            assertNoRejections(fake);
        } finally {
            bot.writeTranscript("smoke-02-rankplayers-image");
            bot.close();
        }
    }

    @Test
    void infoPlayer_hallPlayerRoundWizard_completesWithImage(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        try {
            ConversationDriver member = bot.driver(MEMBER, GROUP);
            member.sendText("/infoplayer");
            RecordedCall halls = member.awaitKeyboard(STEP);
            member.click(halls, "1");

            RecordedCall players = member.awaitKeyboard(STEP);
            member.clickFirst(players, b -> b.callbackData().startsWith("infoplayer_player_"));

            RecordedCall rounds = member.awaitKeyboard(STEP);
            member.click(rounds, "infoplayer_round_all");

            RecordedCall photo = member.awaitMethod("sendPhoto", STEP);
            RecordedCall document = member.awaitMethod("sendDocument", STEP);
            assertTrue(photo.isOk(), "photo accepted: " + photo.responseBody);
            assertTrue(document.isOk(), "document accepted: " + document.responseBody);
            assertTrue(fake.calls().stream().filter(c -> c.method.equals("editMessageReplyMarkup")).count() >= 3,
                    "each of the three picker messages lost its keyboard");
            assertNoRejections(fake);
        } finally {
            bot.writeTranscript("smoke-03-infoplayer-wizard");
            bot.close();
        }
    }

    @Test
    void roundCsvUpload_dialogsAnsweredByButtons_ingestsTheRound(@TempDir Path tmp) throws Exception {
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"));
        try {
            Path copy = tmp.resolve("upload").resolve("2001_round_1.csv");
            Files.createDirectories(copy.getParent());
            Files.copy(BotHarness.sampleFile("2001_round_1.csv"), copy);

            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            admin.upload(copy);

            List<String> dialogs = new ArrayList<>();
            RecordedCall done = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (done == null) {
                Duration left = Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
                RecordedCall c = admin.await(RecordedCall::isSend, left, "next upload message");
                List<RecordedCall.Button> choices = c.buttons().stream()
                        .filter(b -> b.callbackData() != null && b.callbackData().startsWith("choice_")).toList();
                if (!choices.isEmpty()) {
                    String[] labels = choices.stream().map(RecordedCall.Button::text).toArray(String[]::new);
                    int answer = BotHarness.autoAnswer(c.text(), labels);
                    dialogs.add(c.text().lines().findFirst().orElse("") + " -> " + labels[answer]);
                    admin.clickData(c, choices.get(answer).callbackData());
                } else if (c.text() != null && c.text().contains("processed successfully")) {
                    done = c;
                } else if (c.text() != null && (c.text().contains("ERROR") || c.text().contains("Failed"))) {
                    fail("upload failed: " + c.text());
                }
            }
            admin.awaitQuiet(Duration.ofMillis(800), Duration.ofSeconds(20));

            assertFalse(dialogs.isEmpty(), "the sample round raises at least one dialog");
            assertNotNull(new A1_Rounds().getRoundByYearAndOrder(2001, 1), "round 1 of 2001 exists after the upload");
            try (Connection conn = DatabaseHelper.getDefaultConnection();
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM matches")) {
                assertTrue(rs.next() && rs.getInt(1) > 0, "matches were stored");
            }
            Path tempRoot = bot.workDir().resolve("temp");
            if (Files.exists(tempRoot)) {
                try (Stream<Path> left = Files.walk(tempRoot)) {
                    assertEquals(List.of(), left.filter(Files::isRegularFile).toList(), "the downloaded copy is deleted");
                }
            }
            assertTrue(fake.findCall(0, c -> c.method.equals("getFile") && c.isOk()).isPresent());
            assertTrue(fake.findCall(0, c -> c.method.equals(FakeTelegramServer.FILE_DOWNLOAD) && c.isOk()).isPresent());
            assertNoRejections(fake);
            long removals = fake.calls().stream().filter(c -> c.method.equals("editMessageReplyMarkup")).count();
            System.out.println("[smoke-04] dialogs answered: " + dialogs.size() + ", keyboard removals after them: " + removals);
        } finally {
            bot.writeTranscript("smoke-04-round-upload");
            bot.close();
        }
    }

    @Test
    void settings_fromANonAdmin_isRefused(@TempDir Path tmp) throws Exception {
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"));
        try {
            ConversationDriver member = bot.driver(MEMBER, GROUP);
            member.sendText("/settings");
            RecordedCall refusal = member.awaitText("Access Denied", STEP);

            assertTrue(refusal.isOk());
            assertFalse(refusal.hasKeyboard(), "no settings keyboard for a non-admin");
            member.awaitQuiet(Duration.ofMillis(500), Duration.ofSeconds(5));
            assertTrue(fake.calls().stream().noneMatch(c -> c.isSend() && c.hasKeyboard()),
                    "no keyboard was ever offered to the non-admin");
            assertNoRejections(fake);
        } finally {
            bot.writeTranscript("smoke-05-admin-only-refused");
            bot.close();
        }
    }
}
