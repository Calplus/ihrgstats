package com.calplus.ihrgstats.e2e;

import com.calplus.ihrgstats.telegrambot.utils.RoundCsvProcessor;
import com.calplus.ihrgstats.utils.DatabaseHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static com.calplus.ihrgstats.e2e.ConversationDriver.ADMIN;
import static com.calplus.ihrgstats.e2e.ConversationDriver.GROUP;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lane b2, section 3: the hall and player counts at which each picker first exceeds
 * Telegram's 100-button keyboard limit, measured through the rig (the fake answers 400 like
 * Telegram). Halls are added one at a time with fictional names; players are added by
 * uploading rounds through the real processor.
 *
 * <pre>mvn -o test -Dtest=B2PickerLimitE2eTest -Db2.sweep=true</pre>
 */
@EnabledIfSystemProperty(named = "b2.sweep", matches = "true")
public class B2PickerLimitE2eTest {

    static Path tsv() throws Exception {
        Path p = BotHarness.projectDir().resolve("target").resolve("b2");
        Files.createDirectories(p);
        Path t = p.resolve("pickers.tsv");
        if (!Files.exists(t)) {
            Files.writeString(t, "sweep\tcount\tcommand\tstep\tbuttons_attempted\trows\tok_keyboards\trejected_400\treached_user\tsummary\n");
        }
        return t;
    }

    static int hallCountShown() throws Exception {
        try (Connection c = DatabaseHelper.getDefaultConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM halls")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    /** Runs the command, optionally clicks through to the step with the given index, records that step's keyboard. */
    static String probe(FakeTelegramServer fake, ConversationDriver admin, String command, List<String> clickLabels, String sweep, int count)
            throws Exception {
        A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
        int from = fake.callCount();
        admin.sendText(command);
        A1bSupport.Step s = A1bSupport.awaitStep(fake, from, GROUP.idString(), null, Duration.ofSeconds(20), Duration.ofMillis(2500));
        int stepNo = 0;
        for (String label : clickLabels) {
            if (s.keyboard() == null) {
                break;
            }
            RecordedCall.Button b = s.keyboard().buttons().stream().filter(x -> label.equals(x.text()) || (x.callbackData() != null && x.callbackData().contains(label)))
                    .findFirst().orElse(null);
            if (b == null) {
                break;
            }
            A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
            int f = fake.callCount();
            admin.clickData(s.keyboard(), b.callbackData());
            s = A1bSupport.awaitStep(fake, f, GROUP.idString(), null, Duration.ofSeconds(20), Duration.ofMillis(2500));
            stepNo++;
        }
        List<RecordedCall> calls = s.calls();
        int attempted = calls.stream().filter(RecordedCall::hasKeyboard).mapToInt(c -> c.buttons().size()).max().orElse(0);
        int rows = calls.stream().filter(RecordedCall::hasKeyboard)
                .mapToInt(c -> c.buttons().stream().mapToInt(RecordedCall.Button::row).max().orElse(-1) + 1).max().orElse(0);
        long ok = calls.stream().filter(c -> c.isSend() && c.isOk() && c.hasKeyboard()).count();
        long rejected = calls.stream().filter(c -> c.isSend() && c.status == 400).count();
        long reached = calls.stream().filter(c -> c.isSend() && c.isOk()).count();
        String line = String.format(Locale.ROOT, "%s\t%d\t%s\t%d\t%d\t%d\t%d\t%d\t%d\t%s", sweep, count, command, stepNo, attempted, rows, ok, rejected, reached,
                A1bSupport.summarize(calls, null).replace('\t', ' '));
        System.out.println("[b2-picker] " + line);
        Files.writeString(tsv(), line + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        // leave no wizard state behind
        A1bSupport.awaitIdle(fake, Duration.ofSeconds(10));
        if (s.keyboard() != null) {
            RecordedCall.Button cancel = s.keyboard().buttons().stream().filter(x -> x.callbackData() != null && x.callbackData().contains("cancel")).findFirst().orElse(null);
            if (cancel != null) {
                int f = fake.callCount();
                admin.clickData(s.keyboard(), cancel.callbackData());
                A1bSupport.awaitStep(fake, f, GROUP.idString(), null, Duration.ofSeconds(10), Duration.ofMillis(800));
            }
        }
        return line;
    }

    @Test
    void hallPickers_breakingCount(@TempDir Path tmp) throws Exception {
        FakeTelegramServer fake = FakeTelegramServer.start();
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        try {
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            int next = 0;
            for (int target : new int[]{97, 98, 99, 100, 101, 102}) {
                String sql = "INSERT INTO halls (hall_code, hall_name, next_player_seq, created_dttm, updated_dttm) VALUES (?, ?, 1, ?, ?)";
                try (Connection c = DatabaseHelper.getDefaultConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                    while (hallCountShown() < target) {
                        String code = "Q" + (char) ('A' + next / 26) + (char) ('A' + next % 26);
                        ps.setString(1, code);
                        ps.setString(2, "Fictional " + code);
                        ps.setString(3, BotHarness.NOW);
                        ps.setString(4, BotHarness.NOW);
                        ps.executeUpdate();
                        next++;
                    }
                }
                int rowsInTable = hallCountShown();
                for (String cmd : List.of("/infohall", "/infoplayer", "/comparehalls", "/compareplayers", "/infomatchhall", "/predict", "/lineup")) {
                    probe(fake, admin, cmd, List.of(), "halls-table-rows", rowsInTable);
                }
                probe(fake, admin, "/settings", List.of("homeHall"), "halls-table-rows", rowsInTable);
            }
            assertTrue(true);
        } finally {
            bot.writeTranscript("b2-picker-halls");
            bot.close();
            fake.close();
        }
    }

    @Test
    void playerPickers_breakingCount(@TempDir Path tmp) throws Exception {
        FakeTelegramServer fake = FakeTelegramServer.start();
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.REFERENCE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        try {
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            List<String> a = new java.util.ArrayList<>();
            List<String> b = new java.util.ArrayList<>();
            java.util.Random rnd = new java.util.Random(5);
            java.util.Set<String> used = new java.util.HashSet<>();
            for (int i = 0; i < 110; i++) {
                a.add(com.calplus.ihrgstats.perf.ScaleCorpus.generatedName(rnd, used));
                b.add(com.calplus.ihrgstats.perf.ScaleCorpus.generatedName(rnd, used));
            }
            int have = 0;
            int round = 0;
            for (int target : new int[]{96, 97, 98, 99, 100, 101}) {
                StringBuilder sb = new StringBuilder("name1,hall1,score1,name2,hall2,score2\n");
                // round 1 brings 96 players per hall; every later round brings the next new player per hall
                // plus the first 4 old ones so the round stays a normal 5-board match
                int start = have;
                while (have < target) {
                    sb.append(a.get(have)).append(",1,250,").append(b.get(have)).append(",2,120\n");
                    have++;
                }
                for (int i = 0; start > 0 && i < 4; i++) {
                    sb.append(a.get(i)).append(",1,250,").append(b.get(i)).append(",2,120\n");
                }
                Path csv = tmp.resolve("p" + target + ".csv");
                Files.writeString(csv, sb.toString());
                RoundCsvProcessor p = new RoundCsvProcessor();
                p.setMultiChoiceCallback(BotHarness::autoAnswer);
                p.setUploadChatCallback(m -> { });
                round++;
                assertTrue(p.processRound(csv.toString(), 2001, round, BotHarness.NOW), "ingest " + target);
                probe(fake, admin, "/infoplayer", List.of("1"), "players-in-hall-1", target);
                probe(fake, admin, "/compareplayers", List.of("1"), "players-in-hall-1", target);
                probe(fake, admin, "/predict", List.of("1"), "players-in-hall-1", target);
            }
        } finally {
            bot.writeTranscript("b2-picker-players");
            bot.close();
            fake.close();
        }
    }
}
