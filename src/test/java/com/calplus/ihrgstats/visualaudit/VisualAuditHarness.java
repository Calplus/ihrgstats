package com.calplus.ihrgstats.visualaudit;

import com.calplus.ihrgstats.databasemanager.*;
import com.calplus.ihrgstats.telegrambot.commands.*;
import com.calplus.ihrgstats.telegrambot.utils.CappedListProcessor;
import com.calplus.ihrgstats.telegrambot.utils.RoundCsvProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manually-run visual audit generator - NOT part of the regular suite
 * (property-gated like the perf harness). Renders every image-producing
 * command with at least three parameter variants each, into a single
 * flat export folder for inspection:
 *
 *   temp/visual-audit/exports/<nn>_<command>_<variant>.png  (+ coverage.txt)
 *
 * Two datasets, both fully fictional:
 *  - SYNTHETIC: 4 halls (one icon-less), 14 players covering every
 *    name-shape outlier, 10 rounds covering draws, both TIMEOUT side
 *    conventions (one with the blank-winner-score 0-0 form), stated-hall
 *    and blank-hall (unknown-hall fallback) walkovers incl. a
 *    WALKOVER-as-name1 row, a full-win-vs-0 board, a bye round, a
 *    multi-opponent round, a mid-season debut, a near-duplicate name
 *    pair, capped players and a dormant player. Single year -> no
 *    champion -> the ExpElo column renders its "-" placeholder.
 *  - CORPUS: the committed 4-year sample corpus (2001-2004, 11 halls),
 *    ingested through the real pipeline; it clears the ML burn-in so the
 *    ExpElo-populated variants come from a database with a real champion.
 *
 * Run with:
 *   mvn test -Dtest=VisualAuditHarness -Dvisual.audit=true -Dsurefire.failIfNoSpecifiedTests=false
 */
@EnabledIfSystemProperty(named = "visual.audit", matches = "true",
        disabledReason = "visual audit generator - run manually with -Dvisual.audit=true")
public class VisualAuditHarness {

    private static final int YEAR = 2026;
    private static final String NOW = "2026-01-01 00:00:00.000";
    private static final String ADMIN_USER_ID = "visual_audit_admin";

    private String originalUserDir;
    private Path exportsDir;
    private List<String> coverage;

    @BeforeEach
    void setUp() throws Exception {
        originalUserDir = System.getProperty("user.dir");
        exportsDir = Paths.get(originalUserDir).resolve("temp/visual-audit/exports");
        Files.createDirectories(exportsDir);
        coverage = new ArrayList<>();
        System.setProperty("TELEGRAM_ADMIN_USERID", ADMIN_USER_ID);
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
        System.clearProperty("SETTINGS_CURRENTYEAR");
        System.clearProperty("SETTINGS_HOMEHALL");
    }

    private void writeCoverage(String fileName) throws Exception {
        Files.write(exportsDir.resolve(fileName), coverage);
    }

    /** Makes reruns idempotent - each dataset rebuilds its home from scratch. */
    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    /** Copies a produced image into the flat exports folder under the matrix name. */
    private void export(String name, Path imagePath, String outliers) throws Exception {
        if (imagePath == null) {
            coverage.add(name + ".png -> NO IMAGE PRODUCED (text-only response) | " + outliers);
            return;
        }
        Files.copy(imagePath, exportsDir.resolve(name + ".png"), StandardCopyOption.REPLACE_EXISTING);
        coverage.add(name + ".png | " + outliers);
    }

    // =====================================================================
    // Test 1 - SYNTHETIC dataset: the full matrix minus ExpElo-populated.
    // =====================================================================
    @Test
    void synthetic_variantMatrix() throws Exception {
        Path baseDir = Paths.get(originalUserDir).resolve("temp/visual-audit/synthetic");
        deleteRecursively(baseDir);
        Files.createDirectories(baseDir);
        System.setProperty("user.dir", baseDir.toAbsolutePath().toString());
        System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(YEAR));
        System.setProperty("SETTINGS_HOMEHALL", "Binjai");

        new DatabaseSchema().createDatabase("default.db");
        new A3_Halls().seedDefaults(NOW);
        insertHall("MV", "Mysteryville");
        new B4_Players().seedDefaults(NOW);
        new D10_RatingTypes().seedDefaults(NOW);
        new F16_Admins().seedDefaults(NOW);
        new A2_MatchTypes().createMatchType("VisualAuditMatch", 21.0, null, "Visual audit match type", NOW);

        Path csvDir = baseDir.resolve("csv");
        Files.createDirectories(csvDir);
        for (int round = 1; round <= 10; round++) {
            Path csv = csvDir.resolve("round_" + round + ".csv");
            Files.writeString(csv, roundCsv(round));
            RoundCsvProcessor processor = new RoundCsvProcessor();
            processor.setMultiChoiceCallback((message, options) -> {
                if (message.startsWith("⚠️ This round contains a WALKOVER")) {
                    return 0; // the single VisualAuditMatch type
                }
                if (message.contains("'Sam Le' may match existing player 'Sam Lee'")) {
                    return 1; // the deliberate near-duplicate pair - different people
                }
                // The historical "'Nightingale, Florence' may match 'Ng'"
                // dialog no longer fires: the containment matcher requires
                // the shorter side to be >=3 chars, so the 2-char name "Ng"
                // no longer partial-matches every debut containing it.
                throw new IllegalStateException("unexpected dialog: " + message);
            });
            if (!processor.processRound(csv.toString(), YEAR, round, NOW)) {
                throw new IllegalStateException("Round " + round + " failed to process - fix the CSV before continuing");
            }
        }

        markCapped("Kai");
        markCapped("Priyanka Chandrasekaran");

        A3_Halls halls = new A3_Halls();
        int hall1Id = halls.getHallByName("1").id;
        int hallBinjaiId = halls.getHallByName("Binjai").id;
        int hallCrescentId = halls.getHallByName("Crescent").id;
        int hallMysteryvilleId = halls.getHallByName("Mysteryville").id;

        // --- /rankplayers ---------------------------------------------------
        export("01_rankplayers_allrounds", rankPlayers("all"),
                "ExpElo '-' placeholder; every name shape incl. 42-char, comma, apostrophe-hyphen, 2-char; capped badges; dormant player last-played");
        export("02_rankplayers_round6", rankPlayers(roundOrder(YEAR, 6)),
                "single-round scope; round with blank-winner TIMEOUT and mid-season debuts");

        // --- /rankhalls -----------------------------------------------------
        export("03_rankhalls_allrounds", rankHalls("all"), "all-rounds; icon-less hall in table; home-hall marker");
        export("04_rankhalls_round6", rankHalls(roundOrder(YEAR, 6)), "single-round scope");
        export("05_rankhalls_round7", rankHalls(roundOrder(YEAR, 7)), "bye round - one hall absent that round");

        // --- /infohall ------------------------------------------------------
        export("06_infohall_binjai_allrounds", infoHall(hallBinjaiId, "all"),
                "hall WITH icon; TIMEOUT loss and walkover in member history");
        export("07_infohall_mysteryville_allrounds", infoHall(hallMysteryvilleId, "all"),
                "icon-less hall fallback; capped member; near-duplicate member (Sam Le)");
        export("08_infohall_hall1_round7", infoHall(hall1Id, roundOrder(YEAR, 7)), "single-round scope; multi-opponent round");

        // --- /infoplayer ----------------------------------------------------
        export("09_infoplayer_longname", infoPlayer(hallCrescentId, "Zara Zephyrine Quintessa Blackwood-Ashford", "all"),
                "42-char hyphenated name; full-win-vs-0 board; TIMEOUT win with blank winner score (0-0)");
        export("10_infoplayer_capped_dormant", infoPlayer(hallBinjaiId, "Kai", "all"),
                "capped badge; 2-char name; dormant since round 4; draw in history");
        export("11_infoplayer_draw_timeout_walkover", infoPlayer(hallBinjaiId, "Ravi Kumar", "all"),
                "one player with DRAW + TIMEOUT loss + WALKOVER win all in history");

        // --- /comparehalls --------------------------------------------------
        export("12_comparehalls_1v_crescent_allrounds", compareHalls(hall1Id, hallCrescentId, "all"),
                "both icons; victory icons row");
        export("13_comparehalls_1v_crescent_round6", compareHalls(hall1Id, hallCrescentId, roundOrder(YEAR, 6)),
                "round-scoped comparison");
        export("14_comparehalls_1v_mysteryville_allrounds", compareHalls(hall1Id, hallMysteryvilleId, "all"),
                "icon-less side fallback");

        // --- /compareplayers ------------------------------------------------
        export("15_compareplayers_bartholomew_v_kai_allrounds",
                comparePlayers(hall1Id, "Bartholomew Alexander Krieger", hallBinjaiId, "Kai", "all"),
                "long vs 3-char name; capped vs uncapped; dormant side");
        export("16_compareplayers_bartholomew_v_kai_round4",
                comparePlayers(hall1Id, "Bartholomew Alexander Krieger", hallBinjaiId, "Kai", roundOrder(YEAR, 4)),
                "round-scoped; the draw round");
        export("17_compareplayers_zara_v_ng_allrounds",
                comparePlayers(hallCrescentId, "Zara Zephyrine Quintessa Blackwood-Ashford", hallCrescentId, "Ng", "all"),
                "extreme name-length asymmetry (42 chars vs 2)");

        // --- /infomatch -----------------------------------------------------
        export("18_infomatch_round4_draw", infoMatch(YEAR + "_4"), "draw icons");
        export("19_infomatch_round5_walkover", infoMatch(YEAR + "_5"), "stated-hall individual walkover");
        export("20_infomatch_round6_timeout", infoMatch(YEAR + "_6"), "TIMEOUT with blank winner score (0-0 rated win); comma-name debut");
        export("21_infomatch_round9_walkover_name1", infoMatch(YEAR + "_9"),
                "WALKOVER-as-name1 with blank hall - unknown-hall fallback rendering");

        // --- /infomatchhall -------------------------------------------------
        export("22_infomatchhall_hall1_round7", infoMatchHall(hall1Id, YEAR + "_7"), "multi-opponent round");
        export("23_infomatchhall_mysteryville_round7", infoMatchHall(hallMysteryvilleId, YEAR + "_7"),
                "the hall WITH the bye that round");
        export("24_infomatchhall_binjai_round6", infoMatchHall(hallBinjaiId, YEAR + "_6"), "normal round with a TIMEOUT board");

        writeCoverage("coverage_synthetic.txt");
        System.out.println("=== VISUAL AUDIT (synthetic) exports written to " + exportsDir + " ===");
    }

    // =====================================================================
    // Test 2 - CORPUS dataset: ExpElo-populated variants from the committed
    // 4-year sample corpus (slow: full ingestion incl. per-round retraining).
    // =====================================================================
    @Test
    void corpus_expEloPopulatedVariants() throws Exception {
        Path sampleDir = Paths.get(originalUserDir).resolve("SAMPLE FILES");
        Path baseDir = Paths.get(originalUserDir).resolve("temp/visual-audit/corpus");
        deleteRecursively(baseDir);
        Files.createDirectories(baseDir);
        System.setProperty("user.dir", baseDir.toAbsolutePath().toString());

        new DatabaseSchema().createDatabase("default.db");
        new A3_Halls().seedDefaults(NOW);
        insertHall("HA", "HallA");
        insertHall("HB", "HallB");
        insertHall("HC", "HallC");
        new B4_Players().seedDefaults(NOW);
        new D10_RatingTypes().seedDefaults(NOW);
        new F16_Admins().seedDefaults(NOW);
        new A2_MatchTypes().createMatchType("Corpus", 370.0, null, "Corpus match type", NOW);

        // The corpus's scripted identity dialogs (same answers as
        // CorpusIngestionTest); anything unexpected fails loudly.
        // The four historical "'...' may match existing player 'X'" dialogs
        // are gone: the containment matcher now requires the shorter side to
        // be >=3 chars (all four were answered "different people", so the
        // resulting rosters are identical - only the dialog noise is gone).
        Map<String, Integer> dialogAnswers = Map.ofEntries(
                Map.entry("'Paul Murphy' may match existing player 'Paul Morphy'.", 1),
                Map.entry("'Bobby Fischer' may match existing player 'Bob'.", 1),
                Map.entry("'Teddy Rosevelt' may match existing player 'Teddy Roosevelt'.", 0),
                Map.entry("Player: Joyce Byers", 1),
                Map.entry("'Jessie Pinkman' may match existing player 'Jesse Pinkman'.", 0),
                Map.entry("'Margarey Tyrell' may match existing player 'Margaery Tyrell'.", 0),
                Map.entry("Player: Jim Hopper", 1),
                Map.entry("Player: Coral Reeves", 2),
                Map.entry("'Elven' may match existing player 'Eleven'.", 0),
                Map.entry("'Dominique' may match existing player 'Dominique DiPierro'.", 0),
                Map.entry("'Aniya Forger' may match existing player 'Anya Forger'.", 0),
                Map.entry("'Hermoine Granger' may match existing player 'Hermione Granger'.", 0),
                Map.entry("'Baracuda' may match existing player 'Barracuda'.", 0),
                Map.entry("'Tigran Petrosyan' may match existing player 'Tigran Petrosian'.", 0));

        int[][] seasons = {{2001, 10}, {2002, 10}, {2003, 9}, {2004, 10}};
        CappedListProcessor cappedProcessor = new CappedListProcessor();
        RoundCsvProcessor processor = new RoundCsvProcessor();
        processor.setMultiChoiceCallback((message, options) -> {
            if (message.startsWith("⚠️ This round contains a WALKOVER")) {
                return 0;
            }
            for (Map.Entry<String, Integer> e : dialogAnswers.entrySet()) {
                if (message.contains(e.getKey())) {
                    return e.getValue();
                }
            }
            throw new IllegalStateException("unexpected dialog: " + message);
        });
        for (int[] season : seasons) {
            int year = season[0];
            System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(year));
            if (!cappedProcessor.processCappedList(sampleDir.resolve(year + "_cappedlist.csv").toString(), year, NOW)) {
                throw new IllegalStateException(year + " capped list failed to process");
            }
            for (int round = 1; round <= season[1]; round++) {
                Path csv = sampleDir.resolve(year + "_round_" + round + ".csv");
                if (!processor.processRound(csv.toString(), year, round, NOW)) {
                    throw new IllegalStateException(year + " round " + round + " failed to process");
                }
            }
        }
        // Render against the final season.
        System.setProperty("SETTINGS_CURRENTYEAR", "2004");

        A3_Halls halls = new A3_Halls();
        int hall2Id = halls.getHallByName("2").id;
        int hall3Id = halls.getHallByName("3").id;
        int hall4Id = halls.getHallByName("4").id;
        int hallBId = halls.getHallByName("HallB").id;

        export("25_corpus_rankplayers_allrounds", rankPlayers("all"),
                "ExpElo column POPULATED (trained champion); 11-hall field; capped badges");
        export("26_corpus_rankhalls_allrounds", rankHalls("all"), "ExpElo-era hall ranking, 11 halls");
        export("27_corpus_infoplayer_bubblegum", infoPlayer(hall2Id, "Princess Bubblegum", "all"),
                "capped 3 straight years; multi-year history; ExpElo populated");
        export("28_corpus_infoplayer_hermione", infoPlayer(hall4Id, "Hermione Granger", "all"),
                "year-long misspelling merged into one identity; sweepout boards in history");
        export("29_corpus_comparehalls_3_v_hallB", compareHalls(hall3Id, hallBId, "all"),
                "strongest vs weakest hall across 4 seasons");
        export("30_corpus_infomatch_2004r8", infoMatch("2004_8"),
                "corpus TIMEOUT round; quoted comma-name player on the timed-out side");

        writeCoverage("coverage_corpus.txt");
        System.out.println("=== VISUAL AUDIT (corpus) exports written to " + exportsDir + " ===");
    }

    // --- command wrappers (each returns the produced image path) -------------

    private Path rankPlayers(String roundSel) throws Exception {
        CommandRankPlayers cmd = new CommandRankPlayers();
        cmd.handleCommand(ADMIN_USER_ID);
        return cmd.handleRoundSelection(ADMIN_USER_ID, roundSel).imagePath;
    }

    private Path rankHalls(String roundSel) throws Exception {
        CommandRankHalls cmd = new CommandRankHalls();
        cmd.handleCommand(ADMIN_USER_ID);
        return cmd.handleRoundSelection(ADMIN_USER_ID, roundSel).imagePath;
    }

    private Path infoHall(int hallId, String roundSel) throws Exception {
        CommandInfoHall cmd = new CommandInfoHall();
        cmd.handleCommand(ADMIN_USER_ID);
        cmd.handleHallSelection(ADMIN_USER_ID, hallId);
        return cmd.handleRoundSelection(ADMIN_USER_ID, roundSel).imagePath;
    }

    private Path infoPlayer(int hallId, String playerName, String roundSel) throws Exception {
        CommandInfoPlayer cmd = new CommandInfoPlayer();
        cmd.handleCommand(ADMIN_USER_ID);
        cmd.handleHallSelection(ADMIN_USER_ID, hallId);
        cmd.handlePlayerSelection(ADMIN_USER_ID, findPlayerId(playerName));
        return cmd.handleRoundSelection(ADMIN_USER_ID, roundSel).imagePath;
    }

    private Path compareHalls(int hallAId, int hallBId, String roundSel) throws Exception {
        CommandCompareHalls cmd = new CommandCompareHalls();
        cmd.handleCommand(ADMIN_USER_ID);
        cmd.handleFirstHallSelection(ADMIN_USER_ID, hallAId);
        cmd.handleSecondHallSelection(ADMIN_USER_ID, hallBId);
        return cmd.handleRoundSelection(ADMIN_USER_ID, roundSel).imagePath;
    }

    private Path comparePlayers(int hallAId, String playerA, int hallBId, String playerB, String roundSel) throws Exception {
        CommandComparePlayers cmd = new CommandComparePlayers();
        cmd.handleCommand(ADMIN_USER_ID);
        cmd.handleFirstHallSelection(ADMIN_USER_ID, hallAId);
        cmd.handleFirstPlayerSelection(ADMIN_USER_ID, findPlayerId(playerA));
        cmd.handleSecondHallSelection(ADMIN_USER_ID, hallBId);
        cmd.handleSecondPlayerSelection(ADMIN_USER_ID, findPlayerId(playerB));
        return cmd.handleRoundSelection(ADMIN_USER_ID, roundSel).imagePath;
    }

    private Path infoMatch(String roundSel) throws Exception {
        CommandInfoMatch cmd = new CommandInfoMatch();
        cmd.handleCommand(ADMIN_USER_ID);
        return cmd.handleRoundSelection(ADMIN_USER_ID, roundSel).imagePath;
    }

    private Path infoMatchHall(int hallId, String roundSel) throws Exception {
        CommandInfoMatchHall cmd = new CommandInfoMatchHall();
        cmd.handleCommand(ADMIN_USER_ID);
        cmd.handleHallSelection(ADMIN_USER_ID, hallId);
        return cmd.handleRoundSelection(ADMIN_USER_ID, roundSel).imagePath;
    }

    // --- data helpers --------------------------------------------------------

    private void insertHall(String code, String name) throws Exception {
        try (Connection conn = com.calplus.ihrgstats.utils.DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO halls (hall_code, hall_name, next_player_seq, created_dttm, updated_dttm) VALUES (?, ?, 1, ?, ?)")) {
            ps.setString(1, code);
            ps.setString(2, name);
            ps.setString(3, NOW);
            ps.setString(4, NOW);
            ps.executeUpdate();
        }
    }

    private void markCapped(String playerName) throws Exception {
        new B6_PlayerYearStatus().setCapped(findPlayerId(playerName), YEAR, true, NOW);
    }

    /**
     * The rank/info/compare wizards take the round's database id (or
     * "all"); only /infomatch and /infomatchhall use the "year_order" form.
     */
    private static String roundOrder(int year, int order) throws Exception {
        return String.valueOf(new A1_Rounds().getRoundByYearAndOrder(year, order).roundOrder);
    }

    private String findPlayerId(String name) throws Exception {
        List<B5_PlayerNames.NameRecord> candidates = new B5_PlayerNames().findCandidatesByExactName(name);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No player found with name: " + name);
        }
        return candidates.get(0).playerId;
    }

    private static String row(String name1, String hall1, String score1, String name2, String hall2, String score2) {
        return name1 + "," + hall1 + "," + score1 + "," + name2 + "," + hall2 + "," + score2 + "\n";
    }

    /**
     * Synthetic season. Outlier map:
     *  R4 draw (5-5); R5 stated-hall individual walkover; R6 side-1 TIMEOUT
     *  with BLANK winner score (the 0-0 rated-win convention) + mid-season
     *  debuts of the quoted comma-name and the near-duplicate "Sam Le";
     *  R7 multi-opponent round + Mysteryville bye; R8 full-win-vs-0 (21-0);
     *  R9 WALKOVER-as-name1 with blank hall (unknown-hall fallback);
     *  R10 side-2 TIMEOUT with a numeric winner score + a second draw.
     *  Ravi Kumar collects draw + timeout + walkover in one history.
     */
    private static String roundCsv(int round) {
        StringBuilder sb = new StringBuilder("name1,hall1,score1,name2,hall2,score2\n");
        switch (round) {
            case 1:
                sb.append(row("Bartholomew Alexander Krieger", "1", "10", "Kai", "Binjai", "4"));
                sb.append(row("Marcus Villanueva", "1", "10", "Anne-Marie O'Brien-Smith", "Binjai", "6"));
                sb.append(row("Sam Lee", "1", "10", "Ravi Kumar", "Binjai", "7"));
                sb.append(row("Zara Zephyrine Quintessa Blackwood-Ashford", "Crescent", "10", "Priyanka Chandrasekaran", "Mysteryville", "3"));
                sb.append(row("Ng", "Crescent", "10", "Jean-Luc", "Mysteryville", "8"));
                sb.append(row("Fatimah Zahra", "Crescent", "10", "Ah Huat", "Mysteryville", "2"));
                break;
            case 2:
                sb.append(row("Bartholomew Alexander Krieger", "1", "10", "Zara Zephyrine Quintessa Blackwood-Ashford", "Crescent", "8"));
                sb.append(row("Marcus Villanueva", "1", "10", "Ng", "Crescent", "5"));
                sb.append(row("Sam Lee", "1", "10", "Fatimah Zahra", "Crescent", "6"));
                sb.append(row("Kai", "Binjai", "10", "Priyanka Chandrasekaran", "Mysteryville", "7"));
                sb.append(row("Anne-Marie O'Brien-Smith", "Binjai", "10", "Jean-Luc", "Mysteryville", "9"));
                sb.append(row("Ravi Kumar", "Binjai", "10", "Ah Huat", "Mysteryville", "4"));
                break;
            case 3:
                sb.append(row("Bartholomew Alexander Krieger", "1", "10", "Priyanka Chandrasekaran", "Mysteryville", "6"));
                sb.append(row("Marcus Villanueva", "1", "10", "Jean-Luc", "Mysteryville", "7"));
                sb.append(row("Sam Lee", "1", "10", "Ah Huat", "Mysteryville", "3"));
                sb.append(row("Zara Zephyrine Quintessa Blackwood-Ashford", "Crescent", "10", "Anne-Marie O'Brien-Smith", "Binjai", "5"));
                sb.append(row("Ravi Kumar", "Binjai", "10", "Ng", "Crescent", "8"));
                // Kai (Binjai) and Fatimah (Crescent) both sit out this round.
                break;
            case 4:
                sb.append(row("Marcus Villanueva", "1", "5", "Kai", "Binjai", "5")); // DRAW - Kai's last rated round
                sb.append(row("Bartholomew Alexander Krieger", "1", "10", "Anne-Marie O'Brien-Smith", "Binjai", "6"));
                sb.append(row("Sam Lee", "1", "10", "Ravi Kumar", "Binjai", "7"));
                sb.append(row("Zara Zephyrine Quintessa Blackwood-Ashford", "Crescent", "10", "Jean-Luc", "Mysteryville", "4"));
                sb.append(row("Ng", "Crescent", "10", "Ah Huat", "Mysteryville", "6"));
                sb.append(row("Priyanka Chandrasekaran", "Mysteryville", "10", "Fatimah Zahra", "Crescent", "9"));
                break;
            case 5:
                // Kai sits out from here on (last played round 4).
                sb.append(row("Bartholomew Alexander Krieger", "1", "10", "Jean-Luc", "Mysteryville", "5"));
                sb.append(row("Marcus Villanueva", "1", "10", "Ah Huat", "Mysteryville", "6"));
                sb.append(row("Priyanka Chandrasekaran", "Mysteryville", "", "WALKOVER", "1", "")); // WALKOVER (forfeiting side's hall supplied)
                sb.append(row("Anne-Marie O'Brien-Smith", "Binjai", "10", "Zara Zephyrine Quintessa Blackwood-Ashford", "Crescent", "8"));
                sb.append(row("Fatimah Zahra", "Crescent", "10", "Ravi Kumar", "Binjai", "7"));
                // Ng (Crescent) sits out this round.
                break;
            case 6:
                // Side-1 TIMEOUT with the winner score left BLANK (stored as a rated 0-0 win).
                sb.append(row("Ravi Kumar", "Binjai", "TIMEOUT", "Zara Zephyrine Quintessa Blackwood-Ashford", "Crescent", ""));
                sb.append(row("Anne-Marie O'Brien-Smith", "Binjai", "10", "Ng", "Crescent", "6"));
                sb.append(row("Bartholomew Alexander Krieger", "1", "10", "Priyanka Chandrasekaran", "Mysteryville", "7"));
                sb.append(row("Marcus Villanueva", "1", "10", "Ah Huat", "Mysteryville", "5"));
                sb.append(row("Sam Lee", "1", "10", "Jean-Luc", "Mysteryville", "8"));
                // Mid-season debuts: the quoted comma-name and the near-duplicate of Sam Lee.
                sb.append(row("\"Nightingale, Florence\"", "Crescent", "10", "Sam Le", "Mysteryville", "7"));
                // Fatimah (Crescent) sits out this round.
                break;
            case 7:
                // Multi-opponent round: Hall 1 plays 2 boards vs Binjai AND 1 board vs Crescent.
                sb.append(row("Bartholomew Alexander Krieger", "1", "10", "Anne-Marie O'Brien-Smith", "Binjai", "6"));
                sb.append(row("Marcus Villanueva", "1", "10", "Ravi Kumar", "Binjai", "8"));
                sb.append(row("Sam Lee", "1", "10", "Ng", "Crescent", "9"));
                // Mysteryville has a bye; Zara/Fatimah/Nightingale (Crescent) also sit out.
                break;
            case 8:
                sb.append(row("Priyanka Chandrasekaran", "Mysteryville", "10", "Fatimah Zahra", "Crescent", "7"));
                sb.append(row("Jean-Luc", "Mysteryville", "10", "Ng", "Crescent", "6"));
                sb.append(row("Zara Zephyrine Quintessa Blackwood-Ashford", "Crescent", "21", "Ah Huat", "Mysteryville", "0")); // full win vs 0
                sb.append(row("\"Nightingale, Florence\"", "Crescent", "14", "Sam Le", "Mysteryville", "11"));
                sb.append(row("Bartholomew Alexander Krieger", "1", "10", "Anne-Marie O'Brien-Smith", "Binjai", "5"));
                sb.append(row("Marcus Villanueva", "1", "10", "Ravi Kumar", "Binjai", "7"));
                // Sam Lee (Hall 1) sits out this round.
                break;
            case 9:
                sb.append(row("Bartholomew Alexander Krieger", "1", "10", "Priyanka Chandrasekaran", "Mysteryville", "6"));
                sb.append(row("Marcus Villanueva", "1", "10", "Jean-Luc", "Mysteryville", "8"));
                sb.append(row("Sam Lee", "1", "10", "Ah Huat", "Mysteryville", "5"));
                sb.append(row("Anne-Marie O'Brien-Smith", "Binjai", "10", "Fatimah Zahra", "Crescent", "7"));
                // WALKOVER as name1 with a BLANK hall - the unknown-hall fallback path.
                sb.append(row("WALKOVER", "", "", "Ravi Kumar", "Binjai", ""));
                // Ng/Zara/Nightingale (Crescent) sit out this round.
                break;
            case 10:
                sb.append(row("Bartholomew Alexander Krieger", "1", "10", "Zara Zephyrine Quintessa Blackwood-Ashford", "Crescent", "9"));
                sb.append(row("Marcus Villanueva", "1", "10", "Ng", "Crescent", "7"));
                // Side-2 TIMEOUT with a NUMERIC winner score.
                sb.append(row("Sam Lee", "1", "15", "Ah Huat", "Mysteryville", "TIMEOUT"));
                sb.append(row("Anne-Marie O'Brien-Smith", "Binjai", "10", "Priyanka Chandrasekaran", "Mysteryville", "6"));
                sb.append(row("Ravi Kumar", "Binjai", "8", "Jean-Luc", "Mysteryville", "8")); // second draw - completes Ravi's draw+timeout+walkover set
                // Fatimah (Crescent) sits out this round; Kai still absent.
                break;
            default:
                throw new IllegalArgumentException("No CSV designed for round " + round);
        }
        return sb.toString();
    }
}
