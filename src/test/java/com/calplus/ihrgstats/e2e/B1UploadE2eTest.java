package com.calplus.ihrgstats.e2e;

import com.calplus.ihrgstats.databasemanager.A1_Rounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.calplus.ihrgstats.e2e.ConversationDriver.ADMIN;
import static com.calplus.ihrgstats.e2e.ConversationDriver.GROUP;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Lane b1: what the uploader actually sees in the chat, through the fake
 * Telegram server, for two upload-format problems found at processor level.
 * <ul>
 *   <li>B1-1: the corpus round 1 saved as Excel "CSV UTF-8" (with a BOM);</li>
 *   <li>B1-13: a capped list and a multi-round workbook sent as .xlsx.</li>
 * </ul>
 */
public class B1UploadE2eTest {

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

    /** Collects every message the bot sends until it has been quiet for a moment. */
    private static List<String> repliesUntilQuiet(ConversationDriver d) {
        List<String> out = new ArrayList<>();
        d.awaitQuiet(Duration.ofMillis(1500), Duration.ofSeconds(30));
        for (RecordedCall c : d.callsSinceCursor()) {
            if (c.isSend() && c.text() != null) out.add(c.text());
        }
        return out;
    }

    @Test
    void utf8BomRoundFile_userSeesAHeaderErrorNamingTheVisibleHeader_knownDefect_B1_1(@TempDir Path tmp) throws Exception {
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"));
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.writeBytes(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            bytes.writeBytes(Files.readAllBytes(BotHarness.sampleFile("2001_round_1.csv")));
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            admin.mark();
            admin.upload("2001_round_1.csv", bytes.toByteArray());
            RecordedCall first = admin.awaitText("CSV validation failed", STEP);
            List<String> replies = new ArrayList<>();
            replies.add(first.text());
            replies.addAll(repliesUntilQuiet(admin));
            String joined = String.join("\n", replies);
            assertTrue(first.text().contains("Invalid CSV header: Expected 'name1' at column 1, found '\uFEFFname1'"), first.text());
            assertTrue(joined.contains("Failed to process round_1.csv for 2001"), joined);
            assertNull(new A1_Rounds().getRoundByYearAndOrder(2001, 1), "nothing stored");
            assertTrue(fake.rejections().isEmpty(), fake.rejections().toString());
            System.out.println("[b1-e2e] BOM upload replies:\n  " + String.join("\n  ", replies).replace("\uFEFF", "<U+FEFF>"));
        } finally {
            bot.writeTranscript("b1-bom-round-upload");
            bot.close();
        }
    }

    @Test
    void xlsxUploads_todayAreUnknownFileTypes_evenThoughHelpOffersExcel_knownDefect_B1_13(@TempDir Path tmp) throws Exception {
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"));
        try {
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            byte[] notReallyRead = {0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}; // empty zip
            for (String name : new String[]{"2001_cappedlist.xlsx", "2001_rounds.xlsx"}) {
                admin.mark();
                admin.upload(name, notReallyRead);
                RecordedCall reply = admin.awaitText("Unknown file type", STEP);
                assertTrue(reply.text().contains(name), reply.text());
                System.out.println("[b1-e2e] " + name + " -> " + reply.text().replace('\n', ' '));
            }
            assertTrue(fake.findCall(0, c -> c.method.equals(FakeTelegramServer.FILE_DOWNLOAD)).isPresent(),
                    "today the file is downloaded before its name is checked");
            assertTrue(fake.rejections().isEmpty(), fake.rejections().toString());
        } finally {
            bot.writeTranscript("b1-xlsx-unknown-type");
            bot.close();
        }
    }
}
