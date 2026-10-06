package com.calplus.ihrgstats.perf;

import com.calplus.ihrgstats.databasemanager.A2_MatchTypes;
import com.calplus.ihrgstats.databasemanager.A3_Halls;
import com.calplus.ihrgstats.databasemanager.B4_Players;
import com.calplus.ihrgstats.databasemanager.D10_RatingTypes;
import com.calplus.ihrgstats.databasemanager.DatabaseSchema;
import com.calplus.ihrgstats.databasemanager.F16_Admins;
import com.calplus.ihrgstats.ml.ExpEloDistiller;
import com.calplus.ihrgstats.ml.MatchupPredictor;
import com.calplus.ihrgstats.ml.ModelTrainer;
import com.calplus.ihrgstats.ml.PredictionService;
import com.calplus.ihrgstats.ml.RollingCacheUpdater;
import com.calplus.ihrgstats.telegrambot.utils.CappedListProcessor;
import com.calplus.ihrgstats.telegrambot.utils.RoundCsvProcessor;
import com.calplus.ihrgstats.utils.DatabaseHelper;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lane b2 - builds a {@link ScaleCorpus} league into a fresh database through the
 * real upload pipeline ({@code CappedListProcessor.processCappedList} and
 * {@code RoundCsvProcessor.processRound}, dialogs auto-answered like BotHarness),
 * timing every upload and splitting it into phases from the processor's own
 * status messages. The caller must have pointed {@code user.dir} at a temp folder.
 */
public final class ScaleDbBuilder {

    public static final String NOW = "2026-01-01 00:00:00.000";
    public static final String ADMIN_ID = "910001";

    /** One timed upload. Phase ends are ms since the upload started; -1 = that status message never came. */
    public record UploadTiming(int index, int year, int round, boolean capped, int rows, long totalMs,
                               long dataMs, long recalcMs, long retrainMs, long distillMs, long heapUsedAfterMb,
                               boolean ok, String retrainNote) {
        static String header() {
            return "idx\tyear\tround\tcapped\trows\ttotal_ms\tdata_done_ms\trecalc_done_ms\tretrain_done_ms\tdistill_done_ms\theap_used_after_mb\tok\tnote";
        }

        String tsv() {
            return String.format(Locale.ROOT, "%d\t%d\t%d\t%s\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%s\t%s", index, year, round, capped, rows, totalMs,
                    dataMs, recalcMs, retrainMs, distillMs, heapUsedAfterMb, ok, retrainNote == null ? "" : retrainNote.replace('\t', ' '));
        }
    }

    private ScaleDbBuilder() {
    }

    /** Fresh schema + reference data; the 7 named seed halls get generated names; the league's extra halls are added. */
    public static void createEmptyDatabase(ScaleCorpus.Spec spec) throws SQLException {
        System.setProperty("TELEGRAM_ADMIN_USERID", ADMIN_ID);
        new DatabaseSchema().createDatabase("default.db");
        new A3_Halls().seedDefaults(NOW);
        String[] namedCodes = {"BY", "BJ", "CS", "PR", "SC", "TM", "TJ"};
        try (Connection c = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE halls SET hall_name = ? WHERE hall_code = ?")) {
            for (int i = 0; i < namedCodes.length; i++) {
                ps.setString(1, ScaleCorpus.FICTIONAL_NAMED_HALLS[i]);
                ps.setString(2, namedCodes[i]);
                ps.executeUpdate();
            }
        }
        for (String hall : ScaleCorpus.hallNames(spec.halls())) {
            if (new A3_Halls().getHallByName(hall) == null) {
                insertHall(String.format("%02d", Integer.parseInt(hall)), hall);
            }
        }
        new B4_Players().seedDefaults(NOW);
        new D10_RatingTypes().seedDefaults(NOW);
        new F16_Admins().seedDefaults(NOW);
        new A2_MatchTypes().createMatchType("Standard", ScaleCorpus.MAX_SCORE, null, "Synthetic scale match type", NOW);
    }

    public static void insertHall(String code, String name) throws SQLException {
        String sql = "INSERT INTO halls (hall_code, hall_name, next_player_seq, created_dttm, updated_dttm) VALUES (?, ?, 1, ?, ?)";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            ps.setString(2, name);
            ps.setString(3, NOW);
            ps.setString(4, NOW);
            ps.executeUpdate();
        }
    }

    /** Callback for snapshots: called after every round upload with the 1-based upload count. */
    public interface AfterUpload {
        void after(ScaleCorpus.Upload upload, UploadTiming timing) throws Exception;
    }

    /** Ingests every upload in order; appends one TSV line per upload to {@code timingsTsv}. */
    public static List<UploadTiming> ingest(ScaleCorpus.Generated g, Path timingsTsv, AfterUpload after) throws Exception {
        List<UploadTiming> out = new ArrayList<>();
        Files.createDirectories(timingsTsv.getParent());
        Files.writeString(timingsTsv, "# " + System.getProperty("b2.cmd", "(command line not recorded)") + " | java " + System.getProperty("java.version")
                + " | " + Runtime.getRuntime().availableProcessors() + " cpus | max heap " + (Runtime.getRuntime().maxMemory() >> 20) + " MB\n# "
                + g.summary() + "\n" + UploadTiming.header() + "\n", StandardCharsets.UTF_8);
        List<String> unexpected = new ArrayList<>();
        int idx = 0;
        for (ScaleCorpus.Upload u : g.uploads) {
            idx++;
            System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(u.year()));
            Map<String, Long> marks = new LinkedHashMap<>();
            String[] note = {null};
            long start = System.nanoTime();
            boolean ok;
            if (u.cappedList()) {
                CappedListProcessor cp = new CappedListProcessor();
                cp.setUploadChatCallback(m -> { });
                ok = cp.processCappedList(u.file().toString(), u.year(), NOW);
            } else {
                RoundCsvProcessor p = new RoundCsvProcessor();
                p.setMultiChoiceCallback((message, options) -> {
                    if (!message.startsWith("⚠️ This round contains a WALKOVER")) {
                        unexpected.add(u.year() + "/" + u.round() + ": " + message.split("\n")[0]);
                    }
                    return autoAnswer(message, options);
                });
                p.setUploadChatCallback(m -> {
                    long t = (System.nanoTime() - start) / 1_000_000;
                    if (m.contains("processed successfully")) {
                        marks.put("data", t);
                    } else if (m.contains("Whole-history recalculation complete")) {
                        marks.put("recalc", t);
                    } else if (m.contains("AI model retraining complete")) {
                        marks.put("retrain", t);
                        note[0] = m;
                    } else if (m.contains("ExpElo distillation complete")) {
                        marks.put("distill", t);
                    }
                });
                ok = p.processRound(u.file().toString(), u.year(), u.round(), NOW);
            }
            long total = (System.nanoTime() - start) / 1_000_000;
            UploadTiming t = new UploadTiming(idx, u.year(), u.round(), u.cappedList(), u.dataRows(), total,
                    marks.getOrDefault("data", -1L), marks.getOrDefault("recalc", -1L), marks.getOrDefault("retrain", -1L),
                    marks.getOrDefault("distill", -1L), heapUsedMb(), ok, note[0]);
            out.add(t);
            Files.writeString(timingsTsv, t.tsv() + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            if (!ok) {
                throw new AssertionError("upload failed: " + u + " (dialogs: " + unexpected + ")");
            }
            if (after != null) {
                after.after(u, t);
            }
        }
        if (!unexpected.isEmpty()) {
            Files.writeString(timingsTsv, "# identity dialogs auto-answered 'different': " + unexpected.size() + " " + unexpected + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
        return out;
    }

    /** The ML tail an upload runs after recalculation (retrain + distil + rolling cache), timed in ms per phase. */
    public static long[] runMlTail() throws Exception {
        long t0 = System.nanoTime();
        ModelTrainer.TrainOutcome outcome = new ModelTrainer().retrainAndSelect(NOW);
        long t1 = System.nanoTime();
        MatchupPredictor champion = new PredictionService().loadChampion();
        new ExpEloDistiller().distillAndWrite(champion, outcome.extractedBoards, NOW);
        long t2 = System.nanoTime();
        new RollingCacheUpdater().updateAll(NOW);
        long t3 = System.nanoTime();
        return new long[]{(t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000, outcome.trained ? 1 : 0};
    }

    /** Same answers as BotHarness.autoAnswer: walkover type 0, identity questions "different", reprocess yes. */
    static int autoAnswer(String message, String[] options) {
        if (message.startsWith("⚠️ This round contains a WALKOVER")) {
            return 0;
        }
        for (int i = 0; i < options.length; i++) {
            String o = options[i].toLowerCase(Locale.ROOT);
            if (o.contains("different") || o.contains("reprocess")) {
                return i;
            }
        }
        return 0;
    }

    public static long heapUsedMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }

    /** Sum of the heap pools' peak usage since the last {@link #resetHeapPeak()}, in MB. */
    public static long heapPeakMb() {
        long sum = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP && pool.getPeakUsage() != null) {
                sum += pool.getPeakUsage().getUsed();
            }
        }
        return sum / (1024 * 1024);
    }

    public static void resetHeapPeak() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                pool.resetPeakUsage();
            }
        }
    }

    /** Row counts of the main tables (for the size table in the report). */
    public static Map<String, Long> counts() throws SQLException {
        Map<String, Long> m = new LinkedHashMap<>();
        try (Connection c = DatabaseHelper.getDefaultConnection(); Statement st = c.createStatement()) {
            for (String t : List.of("rounds", "halls", "players", "player_names", "matches", "match_participants", "player_ratings",
                    "player_ratings_snapshot", "ml_models", "ai_predictions", "player_year_status")) {
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + t)) {
                    m.put(t, rs.next() ? rs.getLong(1) : -1);
                }
            }
        }
        return m;
    }

    /** Copies the live database file (no connection is held between statements). */
    public static void snapshot(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.copy(DatabaseHelper.getDefaultDatabasePath(), target, StandardCopyOption.REPLACE_EXISTING);
    }
}
