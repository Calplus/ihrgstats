package com.calplus.ihrgstats.e2e;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.calplus.ihrgstats.e2e.ConversationDriver.ADMIN;
import static com.calplus.ihrgstats.e2e.ConversationDriver.GROUP;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Lane a1b (plan: "B4's hostile names"): a generated fictional round whose player
 * names carry HTML, Markdown, quotes, CJK, RTL, emoji, zero-width and very long
 * text is uploaded through the bot; then the report commands are crawled so every
 * message, keyboard and image built from those names passes the fake's validators.
 */
public class HostileNamesE2eTest {

    static final List<String> HOSTILE = List.of(
            "<b>Bold Intruder", "Tom & Jerry", "</pre><i>Escaper", "Quote\"Mark'Er", "名字测试员", "مرحبا لاعب",
            "🀄 Tile Master 🎲", "Zero​Width‍Join", "@everyone", "/help", "*Star* _Under_", "`Tick` ```Fence```",
            "Long" + "abcdefghij".repeat(9), "Combining Á̂̃", "Tab\tIn Name", "<a href=\"x\">Link</a>");

    private FakeTelegramServer fake;

    @BeforeEach
    void startFake() throws Exception {
        fake = FakeTelegramServer.start();
    }

    @AfterEach
    void stopFake() {
        fake.close();
    }

    static String csv() {
        StringBuilder sb = new StringBuilder("name1,hall1,score1,name2,hall2,score2\n");
        String[] opp = {"Opponent Alpha", "Opponent Beta", "Opponent Gamma", "Opponent Delta", "Opponent Epsilon", "Opponent Zeta",
                "Opponent Eta", "Opponent Theta", "Opponent Iota", "Opponent Kappa", "Opponent Lambda", "Opponent Mu",
                "Opponent Nu", "Opponent Xi", "Opponent Omicron", "Opponent Pi"};
        for (int i = 0; i < HOSTILE.size(); i++) {
            String n = HOSTILE.get(i);
            String quoted = n.contains(",") || n.contains("\"") ? "\"" + n.replace("\"", "\"\"") + "\"" : n;
            sb.append(quoted).append(",3,").append(200 + i).append(".5,").append(opp[i]).append(",4,").append(150 + i).append(".25\n");
        }
        return sb.toString();
    }

    @Test
    void hostileNames_uploadThenEveryReport(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.REFERENCE;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        List<String> outcomes = new ArrayList<>();
        try {
            ConversationDriver admin = bot.driver(ADMIN, GROUP);
            int u0 = fake.callCount();
            admin.upload("2001_round_1.csv", csv().getBytes(StandardCharsets.UTF_8));
            // answer every dialog (new-player identity questions etc.)
            long end = System.nanoTime() + Duration.ofSeconds(120).toNanos();
            java.util.Set<Long> seen = new java.util.HashSet<>();
            String terminal = null;
            while (System.nanoTime() < end && terminal == null) {
                for (RecordedCall c : A1bSupport.userSends(A1bSupport.sliceFrom(fake, u0), null)) {
                    if (!seen.add(c.seq)) {
                        continue;
                    }
                    if (c.hasKeyboard() && c.buttons().stream().anyMatch(b -> b.callbackData().startsWith("choice_"))) {
                        String[] labels = c.buttons().stream().map(RecordedCall.Button::text).toArray(String[]::new);
                        admin.clickData(c, c.buttons().get(BotHarness.autoAnswer(c.text(), labels)).callbackData());
                    } else if (c.text() != null && (c.text().contains("processed successfully") || c.text().contains("ERROR")
                            || c.text().contains("validation failed"))) {
                        terminal = c.text();
                    }
                }
                A1bSupport.sleep(250);
            }
            admin.awaitQuiet(Duration.ofMillis(1500), Duration.ofSeconds(20));
            A1bSupport.ledger("(hostile upload)", A1bSupport.sliceFrom(fake, u0));
            outcomes.add("upload: " + (terminal == null ? "no terminal message" : terminal.lines().findFirst().orElse("")));
            A1bSupport.cell("hostile", "upload", "16 hostile names", "admin", "group", String.valueOf(terminal).replace("\n", "/"));
            assertNotNull(terminal, "upload finished");

            for (String cmd : List.of("/rankplayers", "/infoplayer", "/compareplayers", "/infohall", "/infomatch", "/infomatchhall", "/lineup", "/predict")) {
                int from = fake.callCount();
                A1bSupport.Crawler c = new A1bSupport.Crawler(fake, admin, cmd, null);
                c.maxPaths = cmd.equals("/infoplayer") ? 24 : 10;
                c.maxDepth = 5;
                if (cmd.equals("/infoplayer")) {
                    // visit hall 3 (all hostile players) and every player button in it
                    c.skip = b -> b.callbackData().startsWith("infoplayer_hall_") && !b.callbackData().equals("infoplayer_hall_3");
                }
                List<A1bSupport.PathResult> rs = c.crawl();
                if (cmd.equals("/infoplayer")) {
                    // the kind dedup visits one player; add every hostile player explicitly
                    A1bSupport.PathResult hallStep = rs.stream().filter(r -> r.clicks().size() == 1 && r.last().keyboard() != null)
                            .findFirst().orElse(null);
                    if (hallStep != null) {
                        for (RecordedCall.Button b : hallStep.last().keyboard().buttons()) {
                            if (b.callbackData().startsWith("infoplayer_player_")) {
                                List<RecordedCall.Button> path = new ArrayList<>();
                                path.add(new RecordedCall.Button("3", "infoplayer_hall_3", 0));
                                path.add(b);
                                path.add(new RecordedCall.Button("All Rounds", "infoplayer_round_all", 0));
                                A1bSupport.PathResult pr = c.replay(path);
                                A1bSupport.cell("hostile", cmd, b.text(), "admin", "group", pr.last().summary(null));
                            }
                        }
                    }
                }
                List<RecordedCall> calls = A1bSupport.sliceFrom(fake, from);
                A1bSupport.ledger("(hostile) " + cmd, calls);
                long rej = calls.stream().mapToLong(x -> x.violations.stream().filter(RecordedCall.Violation::rejected).count()).sum();
                long failed = calls.stream().filter(x -> !x.isOk() && !x.method.equals("getUpdates")).count();
                outcomes.add(cmd + ": paths=" + rs.size() + " calls=" + calls.size() + " rejected=" + rej + " non-200=" + failed);
                for (A1bSupport.PathResult r : rs) {
                    A1bSupport.cell("hostile", cmd, r.pathString(), "admin", "group", r.last().summary(null));
                }
            }
            StringBuilder v = new StringBuilder();
            for (RecordedCall.Violation x : fake.violations()) {
                v.append("  ").append(x).append('\n');
            }
            A1bSupport.note("hostile names", String.join("\n", outcomes) + "\nviolations:\n" + v);
        } finally {
            bot.writeTranscript("a1b-hostile-names");
            bot.close();
        }
        assertTrue(fake.rejections().isEmpty(), "Telegram would reject: " + fake.rejections());
    }
}
