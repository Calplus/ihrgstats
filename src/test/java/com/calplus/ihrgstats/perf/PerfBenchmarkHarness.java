package com.calplus.ihrgstats.perf;

import com.calplus.ihrgstats.calculations.RatingRecalculator;
import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.ml.ExpEloDistiller;
import com.calplus.ihrgstats.ml.MatchupPredictor;
import com.calplus.ihrgstats.ml.ModelTrainer;
import com.calplus.ihrgstats.ml.PredictionService;
import com.calplus.ihrgstats.ml.RollingCacheUpdater;
import com.calplus.ihrgstats.telegrambot.commands.CommandInfoHall;
import com.calplus.ihrgstats.telegrambot.commands.CommandRankPlayers;
import com.calplus.ihrgstats.telegrambot.utils.RoundCsvProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Manual performance benchmark over a synthetic 12-round, 3-hall, 24-player
 * season driven through the REAL ingest pipeline (per-round recalc + ML
 * hooks included). Not part of the normal suite - run with:
 *
 *   mvn test -Dtest=PerfBenchmarkHarness -Dperf.benchmark=true
 *
 * Prints wall times for: full ingest, whole-history recalc, one
 * retrain+distill+cache cycle, and the /infohall + /rankplayers read paths.
 */
public class PerfBenchmarkHarness {

    private static final int YEAR = 2026;
    private static final String NOW = "2026-01-01 00:00:00.000";
    private static final int ROUNDS = 12;
    private static final int PLAYERS_PER_HALL = 8;

    @Test
    @EnabledIfSystemProperty(named = "perf.benchmark", matches = "true")
    void benchmark(@TempDir Path tempDir, @TempDir Path csvDir) throws Exception {
        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(YEAR));
        try {
            new DatabaseSchema().createDatabase("default.db");
            new A3_Halls().seedDefaults(NOW);
            new B4_Players().seedDefaults(NOW);
            new D10_RatingTypes().seedDefaults(NOW);
            new F16_Admins().seedDefaults(NOW);
            new A2_MatchTypes().createMatchType("Bench", 10.0, null, "Benchmark type", NOW);

            long ingestNs = time(() -> {
                for (int r = 1; r <= ROUNDS; r++) {
                    Path csv = writeRound(csvDir, r);
                    RoundCsvProcessor processor = new RoundCsvProcessor();
                    processor.setMultiChoiceCallback((message, options) -> {
                        for (int i = 0; i < options.length; i++) {
                            if (options[i].startsWith("Continue and reprocess") || options[i].startsWith("Treat as different people")) {
                                return i;
                            }
                        }
                        return 0;
                    });
                    assertTrue(processor.processRound(csv.toString(), YEAR, r, NOW), "round " + r + " must ingest");
                }
            });

            long recalcNs = timeAvg(3, () -> new RatingRecalculator().recalculateAll(NOW));

            long retrainNs = time(() -> {
                new ModelTrainer().retrainAndSelect(NOW);
                MatchupPredictor champion = new PredictionService().loadChampion();
                new ExpEloDistiller().distillAndWrite(champion, NOW);
                new RollingCacheUpdater().updateAll(NOW);
            });

            int hall1Id = new A3_Halls().getHallByName("1").id;
            long infoHallNs = timeAvg(3, () -> {
                CommandInfoHall infoHall = new CommandInfoHall();
                infoHall.handleHallSelection("bench_user", hall1Id);
                CommandInfoHall.InfoResponse r = infoHall.handleRoundSelection("bench_user", "all");
                assertTrue(r.message.contains("Victory Record"), "info hall view must render: " + r.message);
            });

            long rankPlayersNs = timeAvg(3, () ->
                assertTrue(new CommandRankPlayers().handleRoundSelection("bench_user", "all").message.contains("Player Rankings"),
                        "rankplayers view must render"));

            System.out.println("==== PERF BENCHMARK (" + ROUNDS + " rounds, 3 halls, " + (3 * PLAYERS_PER_HALL) + " players) ====");
            System.out.printf("ingest %d rounds (incl. per-round recalc + ML): %d ms%n", ROUNDS, ingestNs / 1_000_000);
            System.out.printf("recalculateAll (avg of 3):                     %d ms%n", recalcNs / 1_000_000);
            System.out.printf("retrain+distill+cache cycle:                   %d ms%n", retrainNs / 1_000_000);
            System.out.printf("/infohall all-rounds view (avg of 3):          %d ms%n", infoHallNs / 1_000_000);
            System.out.printf("/rankplayers all-rounds view (avg of 3):       %d ms%n", rankPlayersNs / 1_000_000);
            System.out.println("=============================================================");
        } finally {
            System.setProperty("user.dir", originalUserDir);
            System.clearProperty("SETTINGS_CURRENTYEAR");
        }
    }

    /** Deterministic synthetic round: halls A(1)/B(2) pair cross-hall with rotation; hall C(3) pairs internally. */
    private static Path writeRound(Path dir, int round) throws Exception {
        StringBuilder sb = new StringBuilder("name1,hall1,score1,name2,hall2,score2\n");
        for (int i = 0; i < PLAYERS_PER_HALL; i++) {
            int j = (i + round) % PLAYERS_PER_HALL;
            boolean aWins = (i + round) % 3 != 0;
            sb.append(String.format("%s,1,%d,%s,2,%d%n", name('A', i), aWins ? 7 : 3, name('B', j), aWins ? 3 : 7));
        }
        for (int i = 0; i < PLAYERS_PER_HALL; i += 2) {
            boolean draw = (i + round) % 4 == 0;
            int s1 = draw ? 5 : ((i + round) % 2 == 0 ? 6 : 4);
            int s2 = draw ? 5 : (10 - s1);
            sb.append(String.format("%s,3,%d,%s,3,%d%n", name('C', i), s1, name('C', i + 1), s2));
        }
        Path csv = dir.resolve("round_" + round + ".csv");
        Files.writeString(csv, sb.toString());
        return csv;
    }

    private static final String[] FIRSTS = {
        "Aurelia", "Bartholomew", "Cassandra", "Dmitri",
        "Evangeline", "Fitzgerald", "Guinevere", "Hieronymus"
    };

    private static String name(char hall, int idx) {
        String last = switch (hall) {
            case 'A' -> "Nightshade";
            case 'B' -> "Krieger";
            default -> "Vale";
        };
        return FIRSTS[idx] + " " + last;
    }

    private interface TimedBlock {
        void run() throws Exception;
    }

    private static long time(TimedBlock block) throws Exception {
        long start = System.nanoTime();
        block.run();
        return System.nanoTime() - start;
    }

    private static long timeAvg(int runs, TimedBlock block) throws Exception {
        long total = 0;
        for (int i = 0; i < runs; i++) {
            total += time(block);
        }
        return total / runs;
    }
}
