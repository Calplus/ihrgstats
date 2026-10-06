package com.calplus.ihrgstats.e2e;

import com.calplus.ihrgstats.utils.DatabaseHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;

import static com.calplus.ihrgstats.e2e.ConversationDriver.ADMIN;
import static com.calplus.ihrgstats.e2e.ConversationDriver.GROUP;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Lane a1b, scope 6 (limits): 100 extra fictional halls (codes JA..NT) are inserted
 * at test time so every hall picker exceeds Telegram's 100-button keyboard limit.
 * Records what each picker command does when its keyboard is rejected.
 */
public class BigPickerE2eTest {

    private FakeTelegramServer fake;

    @BeforeEach
    void startFake() throws Exception {
        fake = FakeTelegramServer.start();
    }

    @AfterEach
    void stopFake() {
        fake.close();
    }

    /** Every hall-picker command walked into a hall with no players / no matches (fixture hall 12). */
    @Test
    void emptyHall_everyHallPicker(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        StringBuilder sb = new StringBuilder();
        try {
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            for (String cmd : List.of("/infohall", "/infoplayer", "/comparehalls", "/compareplayers", "/infomatchhall", "/predict", "/lineup")) {
                A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
                int from = fake.callCount();
                admin.sendText(cmd);
                A1bSupport.Step s = A1bSupport.awaitStep(fake, from, GROUP.idString(), null, Duration.ofSeconds(12), Duration.ofMillis(1500));
                List<String> path = new java.util.ArrayList<>();
                // pick hall "12" at the first hall step, then the first non-cancel button until a terminal
                while (s.keyboard() != null && path.size() < 6) {
                    RecordedCall.Button b = s.keyboard().buttons().stream().filter(x -> "12".equals(x.text())).findFirst()
                            .orElse(s.keyboard().buttons().stream().filter(x -> x.callbackData() != null && !x.callbackData().contains("cancel")
                                    && !x.callbackData().contains("back")).findFirst().orElse(null));
                    if (b == null) {
                        break;
                    }
                    path.add(b.text());
                    A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
                    int f = fake.callCount();
                    admin.clickData(s.keyboard(), b.callbackData());
                    s = A1bSupport.awaitStep(fake, f, GROUP.idString(), null, Duration.ofSeconds(12), Duration.ofMillis(1500));
                }
                String line = cmd + " " + path + " => " + (s.silent() ? "SILENT " : "") + s.summary(null);
                sb.append(line).append('\n');
                A1bSupport.cell("empty-hall", cmd, String.join(" > ", path), "admin", "group", (s.silent() ? "SILENT " : "") + s.summary(null));
                A1bSupport.ledger(cmd, A1bSupport.sliceFrom(fake, from));
            }
            A1bSupport.note("empty hall (12) through every hall picker", sb.toString());
            assertTrue(fake.rejections().isEmpty(), "rejections: " + fake.rejections());
        } finally {
            bot.writeTranscript("a1b-empty-hall");
            bot.close();
        }
    }

    @Test
    void hallPickerOver100Buttons_knownDefect_A1B_24(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        StringBuilder sb = new StringBuilder();
        try {
            String sql = "INSERT INTO halls (hall_code, hall_name, next_player_seq, created_dttm, updated_dttm) VALUES (?, ?, 1, ?, ?)";
            try (Connection c = DatabaseHelper.getDefaultConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                for (char a = 'J'; a <= 'N'; a++) {
                    for (char b = 'A'; b <= 'T'; b++) {
                        ps.setString(1, "" + a + b);
                        ps.setString(2, "Fictional Hall " + a + b);
                        ps.setString(3, BotHarness.NOW);
                        ps.setString(4, BotHarness.NOW);
                        ps.executeUpdate();
                    }
                }
            }
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            int silentOrRejected = 0;
            List<String> cmds = List.of("/infohall", "/infoplayer", "/comparehalls", "/compareplayers", "/infomatchhall", "/predict", "/lineup");
            for (String cmd : cmds) {
                A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
                int from = fake.callCount();
                admin.sendText(cmd);
                admin.awaitQuiet(Duration.ofMillis(2000), Duration.ofSeconds(20));
                List<RecordedCall> calls = A1bSupport.sliceFrom(fake, from);
                long okKeyboards = calls.stream().filter(c -> c.isSend() && c.isOk() && c.hasKeyboard()).count();
                long rejected = calls.stream().filter(c -> c.isSend() && c.status == 400).count();
                long okAny = calls.stream().filter(c -> c.isSend() && c.isOk()).count();
                int maxButtons = calls.stream().mapToInt(c -> c.buttons().size()).max().orElse(0);
                String line = String.format("%-16s sends=%d rejected(400)=%d okKeyboards=%d okSends=%d maxButtons=%d :: %s", cmd,
                        calls.stream().filter(RecordedCall::isSend).count(), rejected, okKeyboards, okAny, maxButtons,
                        A1bSupport.summarize(calls, null));
                sb.append(line).append('\n');
                A1bSupport.cell("limits", cmd, "119 halls in the picker", "admin", "group", line);
                A1bSupport.ledger("(119 halls) " + cmd, calls);
                if (okAny == 0) {
                    silentOrRejected++;
                }
            }
            sb.append("rejections: ").append(fake.rejections().size()).append('\n');
            for (RecordedCall.Violation v : fake.rejections().stream().limit(5).toList()) {
                sb.append("  ").append(v).append('\n');
            }
            A1bSupport.note("hall pickers with 119 halls (limit 100 buttons)", sb.toString());
            // Today: Telegram rejects the keyboard, the fallback resends the same keyboard, the user gets nothing.
            assertEquals(cmds.size(), silentOrRejected, "today no hall picker reaches the user when there are >100 halls (A1B-24):\n" + sb);
        } finally {
            bot.writeTranscript("a1b-limits-119-halls");
            bot.close();
        }
    }
}
