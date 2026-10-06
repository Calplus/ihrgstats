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
        RecordedVariants.start();
    }

    @AfterEach
    void tearDown() {
        RecordedVariants.stop();
        System.setProperty("user.dir", originalUserDir);
        System.clearProperty("SETTINGS_CURRENTYEAR");
        System.clearProperty("SETTINGS_HOMEHALL");
    }

    private void writeCoverage(String fileName) throws Exception {
        Files.write(exportsDir.resolve(fileName), coverage);
        RecordedVariants.writeReports(exportsDir);
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

    /**
     * Renders one variant TWICE with the draw-call recorder on: the first
     * render is exported (PNG + record JSON) and mechanically checked, the
     * second proves the render deterministic (pixels equal outside the
     * "Generated:" timestamp text, draw record equal apart from it).
     */
    private void export(String name, java.util.concurrent.Callable<Path> render, String outliers) throws Exception {
        RecordedVariants.export(exportsDir, name, render, outliers, coverage);
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
        export("01_rankplayers_allrounds", () -> rankPlayers("all"),
                "DENSE: 14 rows crossing the 10-row color-band boundary; every name shape at once (42-char hyphenated,"
                        + " quoted comma-name, apostrophe-hyphen, single-word hyphenated, 2-char, 3-char); capped badges x2;"
                        + " dormant player's frozen LR (R4) vs active LRs; ExpElo column all '-' (no champion, single year);"
                        + " zero-loss records both flavors (all-wins Bartholomew, wins+draw Marcus); icon-less hall code in hall column");
        export("02_rankplayers_round6", () -> rankPlayers(roundOrder(YEAR, 6)),
                "single-round point-in-time scope; the blank-winner TIMEOUT (0-0) round; BOTH mid-season debuts"
                        + " (comma-name + near-duplicate Sam Le) first appearing; dormant Kai's frozen LR (the"
                        + " renamed-round header case is unit-pinned in CommandRankPlayersTest)");

        // --- /rankhalls -----------------------------------------------------
        export("03_rankhalls_allrounds", () -> rankHalls("all"),
                "DENSE: icon-less hall row ('??' fallback) alongside icon halls; home-hall marker; 4-hall field");
        export("04_rankhalls_round7", () -> rankHalls(roundOrder(YEAR, 7)),
                "single-round scope AND the bye round in one image - one hall absent that round entirely");

        // --- /infohall ------------------------------------------------------
        export("06_infohall_binjai_allrounds", () -> infoHall(hallBinjaiId, "all"),
                "DENSE: hall WITH icon; roster carries capped+dormant Kai (3-char), apostrophe-hyphen name, and Ravi's"
                        + " DRAW + TIMEOUT loss + WALKOVER win all in one member history; victory record mixes wins/losses/draw");
        export("07_infohall_mysteryville_allrounds", () -> infoHall(hallMysteryvilleId, "all"),
                "DENSE: icon-less hall fallback; capped member; near-duplicate member (Sam Le); timed-out member (Ah Huat);"
                        + " single-word hyphenated name (Jean-Luc); bye round gap in the round columns (R7)");
        export("08_infohall_hall1_round7", () -> infoHall(hall1Id, roundOrder(YEAR, 7)),
                "single-round scope + multi-opponent round (2 boards vs Binjai + 1 vs Crescent shown against primary opponent)");

        // --- /infoplayer ----------------------------------------------------
        export("09_infoplayer_longname", () -> infoPlayer(hallCrescentId, "Zara Zephyrine Quintessa Blackwood-Ashford", "all"),
                "DENSE: 42-char hyphenated name (initials shortening); 21-0 full-win board; TIMEOUT win with blank winner"
                        + " score (0-0 rated win); loss rounds; sat-out round gaps");
        export("10_infoplayer_capped_dormant", () -> infoPlayer(hallBinjaiId, "Kai", "all"),
                "capped badge context; 3-char name; dormant since R4 (history stops mid-season); draw in final played round");
        export("11_infoplayer_draw_timeout_walkover", () -> infoPlayer(hallBinjaiId, "Ravi Kumar", "all"),
                "DRAW + TIMEOUT loss + WALKOVER win all in ONE history; walkover opponent renders as WALKOVER/unknown hall");

        // --- /comparehalls --------------------------------------------------
        export("12_comparehalls_1v_crescent_allrounds", () -> compareHalls(hall1Id, hallCrescentId, "all"),
                "DENSE: both icons; mirror-consistency anchor vs 06/07 victory records; INDEPENDENTLY computed"
                        + " win-probability pair (the tie-permutation non-sum case has no reachable render here and is"
                        + " pinned by CommandCompareHallsTest); seating grid with sit-out '-' gaps and comma-name row");
        export("13_comparehalls_1v_mysteryville_round6", () -> compareHalls(hall1Id, hallMysteryvilleId, roundOrder(YEAR, 6)),
                "round-scoped comparison AND icon-less side fallback in one image");

        // --- /compareplayers ------------------------------------------------
        export("15_compareplayers_bartholomew_v_kai_allrounds",
                () -> comparePlayers(hall1Id, "Bartholomew Alexander Krieger", hallBinjaiId, "Kai", "all"),
                "DENSE: long vs 3-char name; capped vs uncapped; dormant side's frozen history vs full-season side;"
                        + " undefeated record vs mixed record");
        export("16_compareplayers_bartholomew_v_kai_round4",
                () -> comparePlayers(hall1Id, "Bartholomew Alexander Krieger", hallBinjaiId, "Kai", roundOrder(YEAR, 4)),
                "round-scoped + the draw round (Kai's draw vs Marcus visible in scope)");
        export("17_compareplayers_zara_v_ng_allrounds",
                () -> comparePlayers(hallCrescentId, "Zara Zephyrine Quintessa Blackwood-Ashford", hallCrescentId, "Ng", "all"),
                "extreme name-length asymmetry (42 chars vs 2); same-hall comparison; Ng's new R9 draw in history");

        // --- /infomatch -----------------------------------------------------
        export("18_infomatch_round4_draw", () -> infoMatch(YEAR + "_4"), "draw icons; cumulative scores with a drawn board");
        export("19_infomatch_round5_walkover", () -> infoMatch(YEAR + "_5"),
                "stated-hall individual walkover: the matchup row renders the sentinel side as WALKOVER (established"
                        + " b3u24 behavior); the stated forfeiting hall is attributed in the hall-level records (see 06)");
        export("20_infomatch_round6_timeout", () -> infoMatch(YEAR + "_6"),
                "TIMEOUT with blank winner score (0-0 rated win); comma-name debut board; near-duplicate debut board");
        export("21_infomatch_round9_walkover_name1", () -> infoMatch(YEAR + "_9"),
                "DENSE: WALKOVER-as-name1 with blank hall (unknown-hall '??' fallback) AND a 9-9 draw AND the"
                        + " 2-char-vs-near-duplicate pairing, all in one round view");

        // --- /infomatchhall -------------------------------------------------
        export("22_infomatchhall_hall1_round7", () -> infoMatchHall(hall1Id, YEAR + "_7"),
                "multi-opponent round: primary-opponent score + per-board detail incl. the minority opponent");
        export("23_infomatchhall_mysteryville_round7", () -> infoMatchHall(hallMysteryvilleId, YEAR + "_7"),
                "the hall WITH the bye that round - text-only response by design (documented, no image)");
        export("24_infomatchhall_binjai_round6", () -> infoMatchHall(hallBinjaiId, YEAR + "_6"),
                "TIMEOUT board inside a normal round; 0-0 rated win attribution");

        // --- single-year All-Years (this dataset holds 2026 only) -------------
        export("36_rankplayers_allyears_single_year", () -> rankPlayers("allyears"), "All-Years view over a single year");
        export("37_infoplayer_allyears_single_year", () -> infoPlayer(hallBinjaiId, "Ravi Kumar", "allyears"), "All-Years player summary, one year");
        export("38_infohall_allyears_single_year", () -> infoHall(hallBinjaiId, "allyears"), "All-Years hall summary, one year");
        export("39_comparehalls_allyears_single_year", () -> compareHalls(hall1Id, hallCrescentId, "allyears"), "All-Years hall comparison, one year");
        export("40_compareplayers_allyears_single_year",
                () -> comparePlayers(hall1Id, "Bartholomew Alexander Krieger", hallBinjaiId, "Kai", "allyears"), "All-Years player comparison, one year");

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
        int hallAId = halls.getHallByName("HallA").id;

        export("25_corpus_rankplayers_allrounds", () -> rankPlayers("all"),
                "DENSE: ExpElo column POPULATED (trained champion) - the synthetic 01 shows the '-' form; 11-hall field;"
                        + " capped badges; long table many band boundaries; final-season (2004) scope");
        export("26_corpus_rankhalls_allrounds", () -> rankHalls("all"), "ExpElo-era hall ranking, 11 halls, mixed icons");
        export("27_corpus_infoplayer_bubblegum", () -> infoPlayer(hall2Id, "Princess Bubblegum", "all"),
                "capped multiple straight years; multi-year veteran; ExpElo populated");
        export("28_corpus_infoplayer_hermione", () -> infoPlayer(hall4Id, "Hermione Granger", "all"),
                "year-long misspelling merged into one identity; sweepout boards in history");
        export("29_corpus_comparehalls_3_v_hallB", () -> compareHalls(hall3Id, hallBId, "all"),
                "strongest vs weakest hall across 4 seasons; fictional-hall (HallB) side");
        export("30_corpus_infomatch_2004r8", () -> infoMatch("2004_8"),
                "corpus TIMEOUT round; quoted comma-name player on the timed-out side");

        // --- All-Years family (first rendered in this audit; the b3u24 matrix
        // predated it as an audited surface, and the b3u25 C-4 fix changed
        // the /comparehalls seating grid's keying) -------------------------
        export("31_corpus_rankplayers_allyears", () -> rankPlayers("allyears"),
                "ALL-YEARS ranking: players whose only activity is a PAST year appear alongside 2004 actives;"
                        + " ExpElo populated; cross-year LR labels");
        export("32_corpus_infoplayer_allyears_hopper", () -> infoPlayer(hallAId, "Jim Hopper", "allyears"),
                "ALL-YEARS player summary: HALL MOVER (hall 5 -> 5 -> HallA -> HallA) one row per year;"
                        + " per-year W/L + final elo/rank");
        export("33_corpus_infohall_allyears_hall2", () -> infoHall(hall2Id, "allyears"),
                "ALL-YEARS hall summary: one row per season, rank/elo deltas year over year");
        export("34_corpus_comparehalls_allyears_4_v_2", () -> compareHalls(hall4Id, hall2Id, "allyears"),
                "DENSE + INTENDED NEW RENDERING (b3u25 C-4): the year-long renamed player (Hermione's 2004 variant"
                        + " spelling) appears as ONE seating row under the LAST-KNOWN name with all year columns filled -"
                        + " b3u24 rendered two disjoint rows; per-year season records both sides; win probability from the"
                        + " most recent year's rosters");
        export("35_corpus_compareplayers_allyears_bubblegum_v_hermione",
                () -> comparePlayers(hall2Id, "Princess Bubblegum", hall4Id, "Hermione Granger", "allyears"),
                "ALL-YEARS player comparison: multi-year on both sides; a renamed identity on one side; ExpElo era");

        // --- text-only ML reports (for the <pre> text-table lint) ---------------
        textOnly("41_corpus_modelstats", () -> RecordedVariants.keep(new CommandModelStats().handleCommand(ADMIN_USER_ID)),
                "/modelstats text tables");
        textOnly("42_corpus_lineup_vs_hall3", () -> {
            CommandLineup cmd = new CommandLineup();
            cmd.handleCommand(ADMIN_USER_ID);
            return RecordedVariants.keep(cmd.handleOpponentHallSelection(ADMIN_USER_ID, hall3Id));
        }, "/lineup text tables");

        writeCoverage("coverage_corpus.txt");
        System.out.println("=== VISUAL AUDIT (corpus) exports written to " + exportsDir + " ===");
    }

    // =====================================================================
    // Test 3 - MATRIX dataset: the never-rendered cases (plan A2 / A.4).
    // Script matrix in player AND hall names for all eight image commands,
    // 21 rounds, a one-round season, custom short/long round labels, a
    // walkover-as-primary-opponent hall (dual header icons), a never-played
    // player, a single-word long name, an oversize score, and a home hall
    // configured in different case. All names fictional.
    // =====================================================================
    private static final int MATRIX_YEAR = 2026;
    private static final int MATRIX_ONE_ROUND_YEAR = 2025;

    /** hall name -> its two players. Order matters (circle-method pairing). */
    private static final String[][] MATRIX_HALLS = {
            {"Tanjong", "José Ñúñez-Peña", "Élodie Brontë"},
            {"Binjai", "Priya Nair", "Tomasz Kowalczyk"},
            {"Banyan", "Iris Bloom", "Omar Quist"},
            {"7", "Grace Hopperfield", "Linus Ward"},
            {"Ñandú Plaza", "Søren Æblegård", "Inés Muñoz"},
            {"東京会館", "王小明", "佐藤 花子"},
            {"சென்னை மன்றம்", "முருகன் செல்வம்", "Kavya Raman"},
            {"دار القاهرة", "محمد الفارسي", "Layla Haddad"},
            {"Dice 🎲 Hall", "Ana 😀 Lima", "Maximilianoalexandrovichsson"},
            {"Zoé Arena", "Zoé Mélanie", "Noël Fão"},
    };
    private static final String[] MATRIX_NEW_HALL_CODES = {null, null, null, null, "NP", "TK", "CH", "QA", "DI", "ZA"};

    @Test
    void matrix_neverRenderedCases() throws Exception {
        Path baseDir = Paths.get(originalUserDir).resolve("temp/visual-audit/matrix");
        deleteRecursively(baseDir);
        Files.createDirectories(baseDir);
        System.setProperty("user.dir", baseDir.toAbsolutePath().toString());
        System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(MATRIX_ONE_ROUND_YEAR));
        // Home hall configured in a DIFFERENT CASE than the hall's stored name.
        System.setProperty("SETTINGS_HOMEHALL", "TANJONG");

        new DatabaseSchema().createDatabase("default.db");
        new A3_Halls().seedDefaults(NOW);
        for (int i = 0; i < MATRIX_HALLS.length; i++) {
            if (MATRIX_NEW_HALL_CODES[i] != null) insertHall(MATRIX_NEW_HALL_CODES[i], MATRIX_HALLS[i][0]);
        }
        new B4_Players().seedDefaults(NOW);
        new D10_RatingTypes().seedDefaults(NOW);
        new F16_Admins().seedDefaults(NOW);
        new A2_MatchTypes().createMatchType("MatrixMatch", 21.0, null, "Visual audit matrix match type", NOW);

        Path csvDir = baseDir.resolve("csv");
        Files.createDirectories(csvDir);
        RoundCsvProcessor processor = new RoundCsvProcessor();
        processor.setMultiChoiceCallback((message, options) -> {
            if (message.startsWith("⚠️ This round contains a WALKOVER")) return 0;
            if (message.contains("may match existing player")) return 1; // all matrix names are different people
            throw new IllegalStateException("unexpected dialog: " + message);
        });
        // A one-round season first (single-round + two-year All-Years cases).
        Path one = csvDir.resolve(MATRIX_ONE_ROUND_YEAR + "_round_1.csv");
        Files.writeString(one, matrixRoundCsv(1, true));
        if (!processor.processRound(one.toString(), MATRIX_ONE_ROUND_YEAR, 1, NOW)) throw new IllegalStateException("2025 round 1 failed");
        System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(MATRIX_YEAR));
        for (int round = 1; round <= 21; round++) {
            Path csv = csvDir.resolve(MATRIX_YEAR + "_round_" + round + ".csv");
            Files.writeString(csv, matrixRoundCsv(round, false));
            if (!processor.processRound(csv.toString(), MATRIX_YEAR, round, NOW)) {
                throw new IllegalStateException("matrix round " + round + " failed");
            }
        }
        // Custom round labels: one shorter than "Round N", one far longer.
        A1_Rounds rounds = new A1_Rounds();
        rounds.updateRoundMetadata(rounds.getRoundByYearAndOrder(MATRIX_YEAR, 3).id, "QF", null, NOW);
        rounds.updateRoundMetadata(rounds.getRoundByYearAndOrder(MATRIX_YEAR, 4).id, "Grand Final Championship Decider", null, NOW);
        // A never-played player on Banyan's 2026 roster.
        A3_Halls halls = new A3_Halls();
        int banyanId = halls.getHallByName("Banyan").id;
        B4_Players players = new B4_Players();
        String neverId = players.generateNewPlayerId("BY", NOW);
        if (!players.playerExists(neverId)) players.createPlayer(neverId, NOW);
        new B5_PlayerNames().addOrUpdateName(neverId, "Quinn Neverplayed", MATRIX_YEAR, NOW);
        new B6_PlayerYearStatus().upsertStatus(neverId, MATRIX_YEAR, banyanId, false, true, NOW);
        markCappedIn("王小明", MATRIX_YEAR);

        int tokyo = halls.getHallByName("東京会館").id;
        int chennai = halls.getHallByName("சென்னை மன்றம்").id;
        int cairo = halls.getHallByName("دار القاهرة").id;
        int dice = halls.getHallByName("Dice 🎲 Hall").id;
        int zoe = halls.getHallByName("Zoé Arena").id;
        int seven = halls.getHallByName("7").id;
        int tanjong = halls.getHallByName("Tanjong").id;
        int nandu = halls.getHallByName("Ñandú Plaza").id;

        export("m01_rankplayers_allrounds_scripts", () -> rankPlayers("all"),
                "20 players, every script (accented, CJK, Tamil, Arabic, emoji, combining); home hall configured as 'TANJONG' (case differs); 21 rounds; capped CJK player");
        export("m02_rankplayers_round21", () -> rankPlayers(roundOrder(MATRIX_YEAR, 21)), "round 21 point-in-time (> 10 rounds)");
        export("m03_rankhalls_allrounds_scripts", () -> rankHalls("all"), "10 halls incl. 6 script-named halls (icon fallback, 2-letter code from non-Latin names), Tanjong icon 256x288");
        export("m04_infohall_tokyo_allrounds", () -> infoHall(tokyo, "all"), "CJK hall + CJK players; 21-round seating grid and hall-elo table; custom labels QF / long label");
        export("m05_infohall_chennai_round4_longlabel", () -> infoHall(chennai, roundOrder(MATRIX_YEAR, 4)), "Tamil hall, round scope with a 32-char custom round label");
        export("m06_infohall_7_walkover_primary", () -> infoHall(seven, "all"), "hall whose round-6 primary opponent is WALKOVER");
        export("m07_infoplayer_cjk", () -> infoPlayer(tokyo, "王小明", "all"), "CJK player name (capped)");
        export("m08_infoplayer_tamil", () -> infoPlayer(chennai, "முருகன் செல்வம்", "all"), "Tamil player name");
        export("m09_infoplayer_arabic", () -> infoPlayer(cairo, "محمد الفارسي", "all"), "Arabic (RTL) player name");
        export("m10_infoplayer_emoji", () -> infoPlayer(dice, "Ana 😀 Lima", "all"), "emoji in player and hall name");
        export("m11_infoplayer_single_word_long", () -> infoPlayer(dice, "Maximilianoalexandrovichsson", "all"), "single-word 28-char name (truncation, not initials)");
        export("m12_infoplayer_combining", () -> infoPlayer(zoe, "Zoé Mélanie", "all"), "combining acute accents (NFD) in player and hall name");
        export("m13_infoplayer_never_played", () -> infoPlayer(banyanId, "Quinn Neverplayed", "all"), "player on the roster with no games at all");
        export("m14_infoplayer_accented_round3_shortlabel", () -> infoPlayer(tanjong, "José Ñúñez-Peña", roundOrder(MATRIX_YEAR, 3)), "accented Latin; round scope with custom short label 'QF'; home hall in other case");
        export("m15_comparehalls_tamil_v_arabic_allrounds", () -> compareHalls(chennai, cairo, "all"), "21 rounds side by side (width), Tamil vs Arabic hall labels");
        export("m16_comparehalls_dice_v_zoe_round4", () -> compareHalls(dice, zoe, roundOrder(MATRIX_YEAR, 4)), "emoji vs combining hall labels, long custom round label");
        export("m17_compareplayers_cjk_v_emoji_allrounds", () -> comparePlayers(tokyo, "王小明", dice, "Ana 😀 Lima", "all"), "CJK vs emoji player names, 21 rounds");
        export("m18_compareplayers_longword_v_combining_round5", () -> comparePlayers(dice, "Maximilianoalexandrovichsson", zoe, "Zoé Mélanie", roundOrder(MATRIX_YEAR, 5)),
                "round 5 carries the oversize score board");
        export("m19_infomatch_round5_oversize_score", () -> infoMatch(MATRIX_YEAR + "_5"), "a 123456789.5-0.5 board (score wider than the 200.5-100.5 template; name space may drop <= 20 px)");
        export("m20_infomatch_round6_walkover_hall", () -> infoMatch(MATRIX_YEAR + "_6"), "hall 7's two boards are WALKOVERs; home hall 'TANJONG' case-differs (case-sensitive highlight here)");
        export("m21_infomatch_round4_longlabel", () -> infoMatch(MATRIX_YEAR + "_4"), "long custom round label in title/metadata; script names on every board");
        export("m22_infomatchhall_7_round6_walkover_dual_icon", () -> infoMatchHall(seven, MATRIX_YEAR + "_6"), "walkover as PRIMARY opponent -> dual header icons with 'unknown'");
        export("m23_infomatchhall_tokyo_round21", () -> infoMatchHall(tokyo, MATRIX_YEAR + "_21"), "CJK hall, round 21");
        export("m24_infomatchhall_cairo_round5", () -> infoMatchHall(cairo, MATRIX_YEAR + "_5"), "Arabic hall");
        export("m25_infomatchhall_nandu_round2", () -> infoMatchHall(nandu, MATRIX_YEAR + "_2"), "accented Latin hall");
        export("m26_rankplayers_allyears_two_years", () -> rankPlayers("allyears"), "All-Years over a one-round season + a 21-round season");
        export("m27_infoplayer_allyears", () -> infoPlayer(tokyo, "王小明", "allyears"), "All-Years player summary (2 seasons)");
        export("m28_infohall_allyears", () -> infoHall(chennai, "allyears"), "All-Years hall summary (2 seasons)");
        export("m29_comparehalls_allyears", () -> compareHalls(tokyo, dice, "allyears"), "All-Years hall comparison");
        export("m30_compareplayers_allyears", () -> comparePlayers(tokyo, "王小明", cairo, "محمد الفارسي", "allyears"), "All-Years player comparison");
        System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(MATRIX_ONE_ROUND_YEAR));
        export("m31_rankplayers_single_round_season", () -> rankPlayers("all"), "a season with exactly one round");
        export("m32_infoplayer_single_round_season", () -> infoPlayer(tanjong, "José Ñúñez-Peña", "all"), "single-round season player view (accented Latin)");
        export("m33_comparehalls_single_round_season", () -> compareHalls(tanjong, halls.getHallByName("Binjai").id, "all"), "single-round season comparison");
        System.setProperty("SETTINGS_CURRENTYEAR", "2027");
        export("m34_rankplayers_year_without_rounds", () -> rankPlayers("all"), "current year has no rounds at all (T3 / no-rounds metadata)");
        export("m35_rankhalls_year_without_rounds", () -> rankHalls("all"), "current year has no rounds at all");
        System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(MATRIX_YEAR));

        writeCoverage("coverage_matrix.txt");
        System.out.println("=== VISUAL AUDIT (matrix) exports written to " + exportsDir + " ===");
    }

    // =====================================================================
    // Test 4 - generator-level boundary cases (no database).
    // =====================================================================
    @Test
    void generator_boundaryCases() throws Exception {
        Path baseDir = Paths.get(originalUserDir).resolve("temp/visual-audit/generator");
        deleteRecursively(baseDir);
        Files.createDirectories(baseDir);
        System.setProperty("user.dir", baseDir.toAbsolutePath().toString());
        for (GeneratorBoundaryCases.Case c : GeneratorBoundaryCases.cases()) {
            export(c.name(), c.render(), c.outliers());
        }
        writeCoverage("coverage_generator.txt");
    }

    private void markCappedIn(String playerName, int year) throws Exception {
        new B6_PlayerYearStatus().setCapped(findPlayerId(playerName), year, true, NOW);
    }

    /**
     * Circle-method pairing of the 10 matrix halls, two boards per pairing.
     * Round 5 carries an oversize score; round 6 makes hall "7" forfeit both
     * boards (WALKOVER, blank hall) so its primary opponent is WALKOVER.
     */
    private static String matrixRoundCsv(int round, boolean oneRoundSeason) {
        StringBuilder sb = new StringBuilder("name1,hall1,score1,name2,hall2,score2\n");
        int n = MATRIX_HALLS.length;
        int k = (round - 1) % (n - 1);
        List<int[]> pairs = new ArrayList<>();
        pairs.add(new int[]{0, 1 + (k % (n - 1))});
        for (int j = 1; j < n / 2; j++) {
            pairs.add(new int[]{1 + (k + j) % (n - 1), 1 + (k + n - 1 - j) % (n - 1)});
        }
        if (oneRoundSeason) pairs = pairs.subList(0, 3);
        int p = 0;
        for (int[] pair : pairs) {
            String[] a = MATRIX_HALLS[pair[0]], b = MATRIX_HALLS[pair[1]];
            for (int board = 1; board <= 2; board++) {
                int seed = round * 7 + p * 3 + board;
                String sa = "10", sb2 = String.valueOf(seed % 10);
                if (seed % 3 == 0) { sa = sb2; sb2 = "10"; }
                if (seed % 11 == 0) { sa = "5"; sb2 = "5"; }
                if (round == 5 && p == 0 && board == 1) { sa = "123456789.5"; sb2 = "0.5"; }
                if (round == 6 && (a[0].equals("7") || b[0].equals("7"))) {
                    String[] seven = a[0].equals("7") ? a : b;
                    sb.append(row("WALKOVER", "", "", seven[board], "7", ""));
                    continue;
                }
                sb.append(row(a[board], a[0], sa, b[board], b[0], sb2));
            }
            p++;
        }
        return sb.toString();
    }

    /** A variant that answers with text only (its message is kept for the text-table lint). */
    private void textOnly(String name, java.util.concurrent.Callable<Path> render, String outliers) throws Exception {
        RecordedVariants.export(exportsDir, name, render, outliers, coverage);
    }

    // --- command wrappers (each returns the produced image path) -------------

    private Path rankPlayers(String roundSel) throws Exception {
        CommandRankPlayers cmd = new CommandRankPlayers();
        cmd.handleCommand(ADMIN_USER_ID);
        return RecordedVariants.keep(cmd.handleRoundSelection(ADMIN_USER_ID, roundSel));
    }

    private Path rankHalls(String roundSel) throws Exception {
        CommandRankHalls cmd = new CommandRankHalls();
        cmd.handleCommand(ADMIN_USER_ID);
        return RecordedVariants.keep(cmd.handleRoundSelection(ADMIN_USER_ID, roundSel));
    }

    private Path infoHall(int hallId, String roundSel) throws Exception {
        CommandInfoHall cmd = new CommandInfoHall();
        cmd.handleCommand(ADMIN_USER_ID);
        cmd.handleHallSelection(ADMIN_USER_ID, hallId);
        return RecordedVariants.keep(cmd.handleRoundSelection(ADMIN_USER_ID, roundSel));
    }

    private Path infoPlayer(int hallId, String playerName, String roundSel) throws Exception {
        CommandInfoPlayer cmd = new CommandInfoPlayer();
        cmd.handleCommand(ADMIN_USER_ID);
        cmd.handleHallSelection(ADMIN_USER_ID, hallId);
        cmd.handlePlayerSelection(ADMIN_USER_ID, findPlayerId(playerName));
        return RecordedVariants.keep(cmd.handleRoundSelection(ADMIN_USER_ID, roundSel));
    }

    private Path compareHalls(int hallAId, int hallBId, String roundSel) throws Exception {
        CommandCompareHalls cmd = new CommandCompareHalls();
        cmd.handleCommand(ADMIN_USER_ID);
        cmd.handleFirstHallSelection(ADMIN_USER_ID, hallAId);
        cmd.handleSecondHallSelection(ADMIN_USER_ID, hallBId);
        return RecordedVariants.keep(cmd.handleRoundSelection(ADMIN_USER_ID, roundSel));
    }

    private Path comparePlayers(int hallAId, String playerA, int hallBId, String playerB, String roundSel) throws Exception {
        CommandComparePlayers cmd = new CommandComparePlayers();
        cmd.handleCommand(ADMIN_USER_ID);
        cmd.handleFirstHallSelection(ADMIN_USER_ID, hallAId);
        cmd.handleFirstPlayerSelection(ADMIN_USER_ID, findPlayerId(playerA));
        cmd.handleSecondHallSelection(ADMIN_USER_ID, hallBId);
        cmd.handleSecondPlayerSelection(ADMIN_USER_ID, findPlayerId(playerB));
        return RecordedVariants.keep(cmd.handleRoundSelection(ADMIN_USER_ID, roundSel));
    }

    private Path infoMatch(String roundSel) throws Exception {
        CommandInfoMatch cmd = new CommandInfoMatch();
        cmd.handleCommand(ADMIN_USER_ID);
        return RecordedVariants.keep(cmd.handleRoundSelection(ADMIN_USER_ID, roundSel));
    }

    private Path infoMatchHall(int hallId, String roundSel) throws Exception {
        CommandInfoMatchHall cmd = new CommandInfoMatchHall();
        cmd.handleCommand(ADMIN_USER_ID);
        cmd.handleHallSelection(ADMIN_USER_ID, hallId);
        return RecordedVariants.keep(cmd.handleRoundSelection(ADMIN_USER_ID, roundSel));
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
                // Density: the same round view also carries a DRAW between the
                // 2-char name and the near-duplicate debutant.
                sb.append(row("Ng", "Crescent", "9", "Sam Le", "Mysteryville", "9"));
                // Zara/Nightingale (Crescent) sit out this round.
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
