package com.calplus.ihrgstats.b3;

import com.calplus.ihrgstats.calculations.EloCalculator;
import com.calplus.ihrgstats.databasemanager.A3_Halls;
import com.calplus.ihrgstats.databasemanager.B4_Players;
import com.calplus.ihrgstats.databasemanager.B5_PlayerNames;
import com.calplus.ihrgstats.databasemanager.B6_PlayerYearStatus;
import com.calplus.ihrgstats.databasemanager.B7_CappedImports;
import com.calplus.ihrgstats.databasemanager.DatabaseSchema;
import com.calplus.ihrgstats.databasemanager.F16_Admins;
import com.calplus.ihrgstats.ml.FeatureExtractor;
import com.calplus.ihrgstats.ml.GlickoBaseline;
import com.calplus.ihrgstats.ml.MatchupPredictor;
import com.calplus.ihrgstats.ml.lineup.LineupOptimizer;
import com.calplus.ihrgstats.ml.lineup.OpponentModel;
import com.calplus.ihrgstats.telegrambot.commands.CommandExportDatabase;
import com.calplus.ihrgstats.telegrambot.commands.CommandSettings;
import com.calplus.ihrgstats.telegrambot.commands.CommandMatchTypes;
import com.calplus.ihrgstats.telegrambot.commands.CommandRankHalls;
import com.calplus.ihrgstats.databasemanager.D10_RatingTypes;
import com.calplus.ihrgstats.utils.DatabaseHelper;
import com.calplus.ihrgstats.utils.OutcomeIconRenderer;
import com.calplus.ihrgstats.telegrambot.utils.CappedListProcessor;
import com.calplus.ihrgstats.telegrambot.utils.PlayerIdentityResolver;
import com.calplus.ihrgstats.telegrambot.utils.RoundCsvProcessor;
import com.calplus.ihrgstats.utils.TimezoneHelper;
import com.calplus.ihrgstats.utils.YearContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lane b3: the smallest unit tests that fail under a surviving mutant and
 * pass on today's code. Each method names the mutant it kills (see
 * review record b3_mutation_report.md). Fictional data only.
 */
public class B3MutationGapTest {

    private final Map<String, String> savedProps = new HashMap<>();

    private void setProp(String key, String value) {
        if (!savedProps.containsKey(key)) {
            savedProps.put(key, System.getProperty(key));
        }
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @BeforeEach
    void clean() {
        savedProps.clear();
    }

    @AfterEach
    void restore() {
        savedProps.forEach((k, v) -> {
            if (v == null) System.clearProperty(k);
            else System.setProperty(k, v);
        });
    }

    private static final String NOW = "2026-01-01 00:00:00.000";

    /** Fresh schema + default halls/players in a temp working directory (user.dir restored by restore()). */
    private void freshDb(Path dir) throws Exception {
        setProp("user.dir", dir.toString());
        new DatabaseSchema().createDatabase("default.db");
        new A3_Halls().seedDefaults(NOW);
        new B4_Players().seedDefaults(NOW);
    }

    /**
     * M07 (b3u28 R2-M2-3 lock): a capped list naming two same-named people
     * while only one of them is active must map exactly ONE row - the other
     * stays unmapped for the second person to claim when they debut.
     */
    @Test
    void cappedList_twoSameNameRows_oneActivePlayer_claimsOnlyOneRow(@TempDir Path dir) throws Exception {
        freshDb(dir);
        A3_Halls halls = new A3_Halls();
        String id = new B4_Players().generateNewPlayerId(halls.getHallByName("4").hallCode, NOW);
        new B5_PlayerNames().addOrUpdateName(id, "Twin Fictional", 2026, NOW);
        new B6_PlayerYearStatus().upsertStatus(id, 2026, halls.getHallByName("4").id, false, true, NOW);
        Path csv = dir.resolve("cappedlist.csv");
        Files.writeString(csv, "name,hall\nTwin Fictional,4\nTwin Fictional,5\n");
        assertTrue(new CappedListProcessor().processCappedList(csv.toString(), 2026, NOW));
        List<B7_CappedImports.ImportRow> rows = new B7_CappedImports().getImportsForYear(2026);
        assertEquals(2, rows.size());
        assertEquals(1, rows.stream().filter(r -> r.mapped).count(),
                "one active player may claim only one of the two same-name rows: " + rows.stream().map(r -> r.mapped + "/" + r.playerId).toList());
    }

    /** Calls the package-private RoundCsvProcessor.parseAndValidateCSV exactly as production does. */
    private static Exception roundCsvRejection(Path csv) throws Exception {
        java.lang.reflect.Method m = RoundCsvProcessor.class.getDeclaredMethod("parseAndValidateCSV", String.class);
        m.setAccessible(true);
        try {
            m.invoke(new RoundCsvProcessor(), csv.toString());
            return null;
        } catch (java.lang.reflect.InvocationTargetException e) {
            return (Exception) e.getCause();
        }
    }

    /** M08: a negative score in the SECOND score column is rejected too (only score1 was tested). */
    @Test
    void roundCsv_negativeSecondScore_isRejected(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("2026_round_1.csv");
        Files.writeString(csv, "name1,hall1,score1,name2,hall2,score2\nIona Fictional,1,50,Pell Fictional,2,-5\n");
        Exception ex = roundCsvRejection(csv);
        assertTrue(ex != null && ex.getMessage().contains("negative"), "score2 = -5 must be rejected: " + ex);
    }

    /**
     * M10 (b3u28 R2-M2-2 lock): two fuzzy candidates of equal strength and
     * recency - the dialog offers the A-Z first name, whatever the scan order.
     */
    @Test
    void fuzzyMatch_exactTie_offersTheAlphabeticallyFirstCandidate(@TempDir Path dir) throws Exception {
        freshDb(dir);
        A3_Halls halls = new A3_Halls();
        A3_Halls.Hall hall4 = halls.getHallByName("4");
        // Inserted in reverse alphabetical order so scan order and the tie-break disagree.
        for (String n : List.of("Alma Fictional", "Alba Fictional")) {
            String id = new B4_Players().generateNewPlayerId(hall4.hallCode, NOW);
            new B5_PlayerNames().addOrUpdateName(id, n, 2025, NOW);
            new B6_PlayerYearStatus().upsertStatus(id, 2025, hall4.id, false, true, NOW);
        }
        List<String> dialogs = new ArrayList<>();
        PlayerIdentityResolver resolver = new PlayerIdentityResolver();
        resolver.setMultiChoiceCallback((message, options) -> {
            dialogs.add(message);
            return 1; // treat as different people
        });
        resolver.resolvePlayer("Alda Fictional", hall4, 2026, NOW);
        assertEquals(1, dialogs.size(), "one fuzzy dialog: " + dialogs);
        assertTrue(dialogs.get(0).contains("'Alba Fictional'"), "tie broken A-Z: " + dialogs.get(0));
    }

    /**
     * M09: exact-name returning player whose CSV hall differs from last
     * season - answering "Keep old hall" keeps the prior hall for this year.
     */
    @Test
    void exactName_hallChange_keepOldHall_keepsThePriorHall(@TempDir Path dir) throws Exception {
        freshDb(dir);
        A3_Halls halls = new A3_Halls();
        A3_Halls.Hall hall4 = halls.getHallByName("4");
        A3_Halls.Hall hall5 = halls.getHallByName("5");
        String id = new B4_Players().generateNewPlayerId(hall4.hallCode, NOW);
        new B5_PlayerNames().addOrUpdateName(id, "Rho Fictional", 2025, NOW);
        new B6_PlayerYearStatus().upsertStatus(id, 2025, hall4.id, false, true, NOW);
        List<String> dialogs = new ArrayList<>();
        PlayerIdentityResolver resolver = new PlayerIdentityResolver();
        resolver.setMultiChoiceCallback((message, options) -> {
            dialogs.add(message);
            return 0; // Keep old hall - same player
        });
        PlayerIdentityResolver.ResolutionResult r = resolver.resolvePlayer("Rho Fictional", hall5, 2026, NOW);
        assertEquals(1, dialogs.size(), "hall-mismatch dialog shown: " + dialogs);
        assertEquals(id, r.playerId);
        assertEquals(hall4.id, r.hallId, "keep old hall");
        assertEquals(hall4.id, new B6_PlayerYearStatus().getStatus(id, 2026).hallId);
    }

    /** M11: every data row of the .xlsx export carries every column, the last one included. */
    @Test
    void xlsxExport_everyRowHasEveryColumn(@TempDir Path dir) throws Exception {
        freshDb(dir);
        setProp("TELEGRAM_ADMIN_USERID", "990001");
        new F16_Admins().seedDefaults(NOW);
        A3_Halls.Hall hall4 = new A3_Halls().getHallByName("4");
        String id = new B4_Players().generateNewPlayerId(hall4.hallCode, NOW);
        new B5_PlayerNames().addOrUpdateName(id, "Export Fictional", 2026, NOW);
        new B6_PlayerYearStatus().upsertStatus(id, 2026, hall4.id, true, true, NOW);
        CommandExportDatabase.ExportResponse r = new CommandExportDatabase().executeXlsxExport("990001");
        assertTrue(r.success, r.message);
        List<String> short_ = new ArrayList<>();
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook(Files.newInputStream(r.exportedFilePath))) {
            for (org.apache.poi.ss.usermodel.Sheet sheet : wb) {
                int columns = sheet.getRow(0).getLastCellNum();
                for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                    if (sheet.getRow(i).getLastCellNum() != columns) {
                        short_.add(sheet.getSheetName() + " row " + i + ": " + sheet.getRow(i).getLastCellNum() + " of " + columns);
                    }
                }
            }
        }
        assertEquals(List.of(), short_);
    }

    /** Deterministic stub: our rank i beats their rank j iff j > i (fictional ids O1..O5 / T1..T5). */
    private static final class TierStub implements MatchupPredictor {
        @Override
        public Probs predict(FeatureExtractor.RawBoard board) {
            String a = board.a.playerId;
            String b = board.b.playerId;
            if (a.startsWith("O") && b.startsWith("T")) {
                return a.charAt(1) < b.charAt(1) ? new Probs(1.0, 0.0, 0.0) : new Probs(0.0, 0.0, 1.0);
            }
            if (a.startsWith("T") && b.startsWith("O")) {
                return b.charAt(1) < a.charAt(1) ? new Probs(0.0, 0.0, 1.0) : new Probs(1.0, 0.0, 0.0);
            }
            return new Probs(0.5, 0.0, 0.5);
        }

        @Override
        public String family() {
            return "STUB";
        }
    }

    /**
     * M17: the "Double sacrifice" archetype puts our two weakest (ranks 3, 4)
     * on the seats of their two strongest (ranks 1, 0) and shifts our top
     * three down - not a copy of the single-sacrifice rotation.
     */
    @Test
    void lineup_doubleSacrifice_seatsOurTwoWeakestAgainstTheirTwoStrongest(@TempDir Path dir) throws Exception {
        setProp("user.dir", dir.toString());
        new DatabaseSchema().createDatabase("default.db");
        List<String> our = List.of("O1", "O2", "O3", "O4", "O5");
        List<String> their = List.of("T1", "T2", "T3", "T4", "T5");
        OpponentModel.Profile profile = new OpponentModel.Profile(1, their, Map.of(),
                List.of(new OpponentModel.Ordering(their, 1.0)), new OpponentModel.CaptainProfile(0.0, null, 0));
        LineupOptimizer.Result r = new LineupOptimizer().optimize(our, profile, new TierStub(), new GlickoBaseline(0.05), 2025);
        Map<String, List<String>> seats = new HashMap<>();
        for (LineupOptimizer.ArchetypeResult a : r.archetypes) {
            seats.put(a.name, a.playerIdsBySeat);
        }
        assertEquals(List.of("O5", "O1", "O2", "O3", "O4"), seats.get("Single sacrifice"));
        assertEquals(List.of("O5", "O4", "O1", "O2", "O3"), seats.get("Double sacrifice"),
                "seat of their #1 gets our #5, seat of their #2 gets our #4, our top three shift down");
    }

    /**
     * M18 (b3u28 R2-M2-8 lock): every hypothetical /lineup board carries the
     * same next-round order - the seat must never leak into the round slot.
     */
    @Test
    void lineup_hypotheticalBoards_allCarryOneRoundOrder(@TempDir Path dir) throws Exception {
        setProp("user.dir", dir.toString());
        new DatabaseSchema().createDatabase("default.db");
        List<String> our = List.of("O1", "O2", "O3", "O4", "O5");
        List<String> their = List.of("T1", "T2", "T3", "T4", "T5");
        OpponentModel.Profile profile = new OpponentModel.Profile(1, their, Map.of(),
                List.of(new OpponentModel.Ordering(their, 1.0)), new OpponentModel.CaptainProfile(0.0, null, 0));
        Set<Integer> roundOrders = new java.util.TreeSet<>();
        TierStub tiers = new TierStub();
        MatchupPredictor recording = new MatchupPredictor() {
            @Override
            public Probs predict(FeatureExtractor.RawBoard board) {
                roundOrders.add(board.roundOrder);
                return tiers.predict(board);
            }

            @Override
            public String family() {
                return "STUB";
            }
        };
        new LineupOptimizer().optimize(our, profile, recording, new GlickoBaseline(0.05), 2025);
        assertEquals(1, roundOrders.size(), "one round order for every seat: " + roundOrders);
    }

    /** M23: the /settings current-year wizard refuses years outside 2000-2100 and keeps the old value. */
    @Test
    void settings_currentYearOutOfRange_isRefused(@TempDir Path dir) throws Exception {
        freshDb(dir);
        setProp("TELEGRAM_ADMIN_USERID", "990001");
        setProp("SETTINGS_CURRENTYEAR", "2026");
        new F16_Admins().seedDefaults(NOW);
        CommandSettings settings = new CommandSettings();
        for (String bad : List.of("2101", "1999", "99999")) {
            settings.handleCurrentYearSelection("990001");
            String reply = settings.handleTextInput("990001", bad);
            assertTrue(reply != null && reply.contains("between 2000 and 2100"), bad + " -> " + reply);
            assertEquals("2026", System.getProperty("SETTINGS_CURRENTYEAR"), bad + " must not be stored");
        }
        settings.handleCurrentYearSelection("990001");
        assertTrue(settings.handleTextInput("990001", "2100").contains("Successfully"));
        settings.handleCancel("990001");
    }

    /** M15: each outcome draws its own icon resource - a win/draw/loss swap must not go unnoticed. */
    @Test
    void outcomeIcons_eachOutcomeUsesItsOwnResource() throws Exception {
        java.lang.reflect.Method read = OutcomeIconRenderer.class.getDeclaredMethod("readAndResizeIcon", String.class, int.class);
        read.setAccessible(true);
        String[] files = {"win.png", "draw.png", "lose.png"};
        int[] outcomes = {1, 0, -1};
        for (int i = 0; i < 3; i++) {
            java.awt.image.BufferedImage expected = (java.awt.image.BufferedImage) read.invoke(null, files[i], 24);
            java.awt.image.BufferedImage actual = OutcomeIconRenderer.loadOutcomeIcon(outcomes[i], 24);
            assertTrue(actual != null && expected.getWidth() > 1, "icon for outcome " + outcomes[i]);
            int diff = 0;
            for (int y = 0; y < 24; y++) {
                for (int x = 0; x < 24; x++) {
                    if (expected.getRGB(x, y) != actual.getRGB(x, y)) diff++;
                }
            }
            assertEquals(0, diff, "outcome " + outcomes[i] + " must render " + files[i]);
        }
    }

    /** M26: two halls with exactly equal average rating are listed A-Z by name, whatever the input order. */
    @Test
    void rankHalls_equalAverages_breakTiesAToZ(@TempDir Path dir) throws Exception {
        freshDb(dir);
        setProp("TELEGRAM_ADMIN_USERID", "990001");
        setProp("SETTINGS_CURRENTYEAR", "2026");
        new D10_RatingTypes().seedDefaults(NOW);
        new F16_Admins().seedDefaults(NOW);
        try (java.sql.Connection c = DatabaseHelper.getDefaultConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO halls (hall_code, hall_name, next_player_seq, created_dttm, updated_dttm) VALUES (?, ?, 1, ?, ?)")) {
            for (String[] h : new String[][]{{"ZQ", "Zed"}, {"AQ", "Abe"}}) { // fictional; inserted Z first
                ps.setString(1, h[0]);
                ps.setString(2, h[1]);
                ps.setString(3, NOW);
                ps.setString(4, NOW);
                ps.executeUpdate();
            }
        }
        Path csv = dir.resolve("2026_round_1.csv");
        // A draw between two fresh players leaves both at exactly the same rating.
        Files.writeString(csv, "name1,hall1,score1,name2,hall2,score2\nYves Fictional,Zed,10,Ulla Fictional,Abe,10\n");
        RoundCsvProcessor p = new RoundCsvProcessor();
        p.setMultiChoiceCallback((message, options) -> 0);
        assertTrue(p.processRound(csv.toString(), 2026, 1, NOW));
        CommandRankHalls rank = new CommandRankHalls();
        rank.handleCommand("990001");
        String msg = rank.handleRoundSelection("990001", "all").message;
        int abe = msg.indexOf("Abe");
        int zed = msg.indexOf("Zed");
        assertTrue(abe >= 0 && zed >= 0, msg);
        assertTrue(abe < zed, "equal averages must list Abe before Zed:\n" + msg);
    }

    /** M31 (b3u29 R2-M5-1 lock): the exact-name lookup is served by the LOWER(name) expression index. */
    @Test
    void schema_exactNameLookup_usesTheLowerNameIndex(@TempDir Path dir) throws Exception {
        freshDb(dir);
        StringBuilder plan = new StringBuilder();
        try (java.sql.Connection c = DatabaseHelper.getDefaultConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "EXPLAIN QUERY PLAN SELECT * FROM player_names WHERE LOWER(name) = LOWER(?) ORDER BY last_seen_year DESC")) {
            ps.setString(1, "Some Fictional");
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) plan.append(rs.getString("detail")).append('\n');
            }
        }
        assertTrue(plan.toString().contains("idx_player_names_name_lower"), "plan:\n" + plan);
    }

    /** M27 (b3u28 R2-M2-5 lock): the match-type wizard refuses non-finite max scores. */
    @Test
    void matchTypes_nonFiniteMaxScore_isRefused(@TempDir Path dir) throws Exception {
        freshDb(dir);
        setProp("TELEGRAM_ADMIN_USERID", "990001");
        new F16_Admins().seedDefaults(NOW);
        CommandMatchTypes cmd = new CommandMatchTypes();
        try {
            cmd.handleCreateNew("990001");
            cmd.handleTextInput("990001", "Fictional Blitz");
            for (String bad : List.of("NaN", "Infinity", "1e999")) {
                String reply = cmd.handleTextInput("990001", bad).message;
                assertTrue(reply.contains("❌"), bad + " -> " + reply);
            }
        } finally {
            cmd.handleCancel("990001");
        }
    }

    /** M30 (b3u28 R2-M1-1 lock): equal boards and equal own score - the primary opponent is the A-Z first name. */
    @Test
    void primaryOpponent_fullTie_isTheAlphabeticallyFirst() {
        Map<String, Integer> boards = new java.util.LinkedHashMap<>();
        boards.put("Bea Hall", 2);
        boards.put("Abe Hall", 2);
        Map<String, Double> score = new java.util.LinkedHashMap<>();
        score.put("Bea Hall", 1.5);
        score.put("Abe Hall", 1.5);
        assertEquals("Abe Hall", com.calplus.ihrgstats.utils.VictoryRecordCalculator.primaryOpponent(boards, score));
        boards.put("Cid Hall", 3); // more boards still wins outright
        assertEquals("Cid Hall", com.calplus.ihrgstats.utils.VictoryRecordCalculator.primaryOpponent(boards, score));
    }

    /** M19: a brand-new player who sits out a round must not drift above the 350 RD cap. */
    @Test
    void elo_newPlayerIdleInTheirFirstRound_rdStaysAtTheCap() {
        Set<String> players = new HashSet<>(List.of("p-active-a", "p-active-b", "p-idle"));
        List<EloCalculator.Game> games = new ArrayList<>();
        games.add(new EloCalculator.Game("p-active-a", "p-active-b", 1.0, 1));
        Map<String, EloCalculator.Glicko2Rating> initial = new HashMap<>();
        for (String p : players) initial.put(p, new EloCalculator.Glicko2Rating());
        EloCalculator.Glicko2Result r = EloCalculator.calculateGlicko2TrueElo(games, players, initial, List.of(1, 2, 3));
        for (int round = 1; round <= 3; round++) {
            double rd = r.ratingsByRound.get(round).get("p-idle").rd;
            assertTrue(rd <= 350.0 + 1e-9, "idle new player's RD must stay capped at 350 (round " + round + "): " + rd);
        }
    }

    /** M20: negative half-hour offsets (e.g. -3.5) must build a valid zone, not throw. */
    @Test
    void timezone_negativeHalfHourOffset_buildsTheRightZone() {
        assertEquals(ZoneId.ofOffset("UTC", ZoneOffset.ofHoursMinutes(-3, -30)), TimezoneHelper.getZoneIdFromOffset(-3.5));
        assertEquals(ZoneId.ofOffset("UTC", ZoneOffset.ofHoursMinutes(5, 30)), TimezoneHelper.getZoneIdFromOffset(5.5));
    }

    /** M21 (b3u28 R2-M2-4 lock): a hand-corrupted out-of-range offset falls back to the default instead of throwing. */
    @Test
    void timezone_outOfRangeSetting_fallsBackToTheDefault() {
        setProp("SETTINGS_TIMEZONE", "99");
        ZonedDateTime now = TimezoneHelper.now();
        assertEquals(ZoneId.of("Asia/Singapore"), now.getZone());
        assertEquals(8.0, TimezoneHelper.getTimezoneOffset());
        assertEquals("UTC+8", TimezoneHelper.getFormattedTimezone());
    }

    /** M22: an unset current year must stay unset - never silently default to the calendar year. */
    @Test
    void currentYear_unset_isNull_neverTheCalendarYear() {
        setProp("SETTINGS_CURRENTYEAR", null);
        assertNull(YearContext.getCurrentYear());
        setProp("SETTINGS_CURRENTYEAR", "");
        assertNull(YearContext.getCurrentYear());
        setProp("SETTINGS_CURRENTYEAR", "2001");
        assertEquals(2001, YearContext.getCurrentYear());
    }
}
