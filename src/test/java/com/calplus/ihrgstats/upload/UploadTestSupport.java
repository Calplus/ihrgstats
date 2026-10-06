package com.calplus.ihrgstats.upload;

import com.calplus.ihrgstats.databasemanager.A2_MatchTypes;
import com.calplus.ihrgstats.databasemanager.A3_Halls;
import com.calplus.ihrgstats.databasemanager.B4_Players;
import com.calplus.ihrgstats.databasemanager.D10_RatingTypes;
import com.calplus.ihrgstats.databasemanager.DatabaseSchema;
import com.calplus.ihrgstats.databasemanager.F16_Admins;
import com.calplus.ihrgstats.telegrambot.utils.CappedListProcessor;
import com.calplus.ihrgstats.telegrambot.utils.RoundCsvProcessor;
import com.calplus.ihrgstats.utils.DatabaseHelper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lane b1 test support: throwaway database homes (always under a JUnit
 * {@code @TempDir}), scripted upload dialogs, ingestion helpers and a
 * <em>semantic</em> database dump used as the "identical database" oracle.
 *
 * <p>The dump covers every table of the schema (an unknown table fails the
 * dump, so a new table can never be silently skipped). Surrogate keys are
 * replaced by natural keys (round_id -> year:round_order, hall_id ->
 * hall_code, match_id -> year:round:sorted participants, type ids -> names),
 * and the bookkeeping {@code *_dttm} columns are left out. Everything else -
 * every name, score, outcome, seat, capped flag, rating, snapshot,
 * prediction and model row - is compared exactly.
 */
public final class UploadTestSupport {

    public static final String NOW = "2026-01-01 00:00:00.000";
    public static final double MAX_SCORE = 370.0;

    /** Read-only fictional corpus; resolved before any test moves user.dir. */
    public static final Path SAMPLE_DIR = resolveSampleDir();

    /** The scripted identity dialogs the 2001 season fires (from CorpusIngestionTest). */
    public static final List<Script> CORPUS_2001_SCRIPT = List.of(
            new Script("'Paul Murphy' may match existing player 'Paul Morphy'.", 1),
            new Script("'Bobby Fischer' may match existing player 'Bob'.", 1),
            new Script("'Teddy Rosevelt' may match existing player 'Teddy Roosevelt'.", 0));

    private UploadTestSupport() {}

    private static Path resolveSampleDir() {
        String base = System.getProperty("basedir", System.getProperty("user.dir"));
        Path dir = Paths.get(base, "SAMPLE FILES").toAbsolutePath().normalize();
        if (!Files.isRegularFile(dir.resolve("2001_round_1.csv"))) {
            throw new IllegalStateException("fictional corpus not found at " + dir);
        }
        return dir;
    }

    public static Path sample(String fileName) {
        return SAMPLE_DIR.resolve(fileName);
    }

    // ---------------------------------------------------------------- homes

    /**
     * Points user.dir at {@code home} (must be inside java.io.tmpdir) and
     * creates a fresh database there with the production seeds, the corpus
     * match type and the fictional halls HallA/HallB/HallC.
     */
    public static void initHome(Path home) throws Exception {
        Path tmp = Paths.get(System.getProperty("java.io.tmpdir")).toRealPath();
        Files.createDirectories(home);
        if (!home.toRealPath().startsWith(tmp)) {
            throw new IllegalStateException("refusing a database home outside java.io.tmpdir: " + home);
        }
        System.setProperty("user.dir", home.toString());
        new DatabaseSchema().createDatabase("default.db");
        new A3_Halls().seedDefaults(NOW);
        new B4_Players().seedDefaults(NOW);
        new D10_RatingTypes().seedDefaults(NOW);
        new F16_Admins().seedDefaults(NOW);
        new A2_MatchTypes().createMatchType("Corpus", MAX_SCORE, null, "Corpus battery match type", NOW);
        insertHall("HA", "HallA");
        insertHall("HB", "HallB");
        insertHall("HC", "HallC");
    }

    public static void useHome(Path home) {
        System.setProperty("user.dir", home.toString());
    }

    private static void insertHall(String code, String name) throws SQLException {
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

    // -------------------------------------------------------------- dialogs

    /** One scripted dialog: matched by substring, answered by option index. */
    public record Script(String needle, int answer) {}

    /**
     * Dialog policy for an upload: the walkover match-type dialog is answered
     * with the single corpus type, the reprocess confirmation with
     * {@link #reprocessAnswer}, scripted identity dialogs by their script,
     * and anything else with {@link #fallbackAnswer} (default -1 = cancel,
     * so an unexpected dialog fails the ingest loudly). Every message is kept.
     */
    public static final class Dialogs implements RoundCsvProcessor.MultiChoiceCallback {
        public final List<String> seen = new ArrayList<>();
        public final List<String> unexpected = new ArrayList<>();
        private final List<Script> script;
        public int reprocessAnswer = 0;
        public int fallbackAnswer = -1;

        public Dialogs(List<Script> script) {
            this.script = script;
        }

        @Override
        public int requestChoice(String message, String[] options) {
            seen.add(message);
            if (message.startsWith("\u26A0\uFE0F This round contains a WALKOVER")) return 0;
            if (message.startsWith("\u26A0\uFE0F Round Already Processed")) return reprocessAnswer;
            for (Script s : script) {
                if (message.contains(s.needle())) return s.answer();
            }
            unexpected.add(message);
            return fallbackAnswer;
        }

        public long identityDialogCount() {
            return seen.stream().filter(m -> m.contains("Name Mismatch") || m.contains("Hall Mismatch")).count();
        }
    }

    // ------------------------------------------------------------ ingestion

    /** Result of one processor call: its boolean plus every chat notification it sent. */
    public record Outcome(boolean ok, List<String> messages) {
        public String joined() {
            return String.join("\n", messages);
        }
    }

    public static Outcome round(Path csv, int year, int roundOrder, Dialogs dialogs) {
        List<String> messages = new ArrayList<>();
        RoundCsvProcessor processor = new RoundCsvProcessor();
        processor.setMultiChoiceCallback(dialogs);
        processor.setUploadChatCallback(messages::add);
        System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(year));
        boolean ok = processor.processRound(csv.toString(), year, roundOrder, NOW);
        return new Outcome(ok, messages);
    }

    public static Outcome capped(Path csv, int year) {
        List<String> messages = new ArrayList<>();
        CappedListProcessor processor = new CappedListProcessor();
        processor.setUploadChatCallback(messages::add);
        System.setProperty("SETTINGS_CURRENTYEAR", String.valueOf(year));
        boolean ok = processor.processCappedList(csv.toString(), year, NOW);
        return new Outcome(ok, messages);
    }

    /**
     * Ingests capped list + rounds 1..lastRound of {@code year}, taking each
     * file from {@code fileFor} (given the corpus file name). Asserts every
     * step succeeded and no unexpected dialog fired.
     */
    public static void ingestSeason(int year, int lastRound, Function<String, Path> fileFor, Dialogs dialogs) {
        Outcome c = capped(fileFor.apply(year + "_cappedlist.csv"), year);
        assertTrue(c.ok(), year + "_cappedlist.csv failed: " + c.joined());
        for (int r = 1; r <= lastRound; r++) {
            String name = year + "_round_" + r + ".csv";
            Outcome o = round(fileFor.apply(name), year, r, dialogs);
            assertTrue(o.ok(), name + " failed: " + o.joined() + " / unexpected dialogs " + dialogs.unexpected);
        }
        assertTrue(dialogs.unexpected.isEmpty(), "unexpected dialogs: " + dialogs.unexpected);
    }

    // ------------------------------------------------------------------ dump

    private static final Set<String> KNOWN_TABLES = Set.of(
            "rounds", "match_types", "halls", "players", "player_names", "player_year_status",
            "capped_imports", "matches", "match_participants", "rating_types", "player_ratings",
            "player_ratings_snapshot", "player_profiles", "player_rolling_cache", "ai_predictions",
            "ml_models", "admins", "sqlite_sequence");

    /** Semantic dump with player ids kept as-is. */
    public static Map<String, List<String>> dump() throws Exception {
        return dump(Function.identity());
    }

    /**
     * Semantic dump of the current database (see class javadoc). {@code pid}
     * maps every player id before it is written, so two databases whose ids
     * differ only by creation order can still be compared.
     */
    public static Map<String, List<String>> dump(Function<String, String> pid) throws Exception {
        Map<String, List<String>> out = new TreeMap<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection()) {
            Set<String> tables = new TreeSet<>(column(conn, "SELECT name FROM sqlite_master WHERE type='table'"));
            for (String t : tables) {
                if (!KNOWN_TABLES.contains(t)) {
                    throw new IllegalStateException("semantic dump does not know table '" + t + "' - extend it");
                }
            }
            Map<Integer, String> roundKey = new HashMap<>();
            for (String[] r : rows(conn, "SELECT id, year, round_order FROM rounds")) roundKey.put(Integer.parseInt(r[0]), r[1] + ":" + r[2]);
            Map<Integer, String> hallKey = new HashMap<>();
            for (String[] r : rows(conn, "SELECT id, hall_code FROM halls")) hallKey.put(Integer.parseInt(r[0]), r[1]);
            Map<Integer, String> typeKey = new HashMap<>();
            for (String[] r : rows(conn, "SELECT id, type_name FROM match_types")) typeKey.put(Integer.parseInt(r[0]), r[1]);
            Map<Integer, String> ratingKey = new HashMap<>();
            for (String[] r : rows(conn, "SELECT id, rating_name FROM rating_types")) ratingKey.put(Integer.parseInt(r[0]), r[1]);

            // match natural key: round + sorted participant signatures
            Map<Integer, List<String>> sides = new HashMap<>();
            for (String[] r : rows(conn, "SELECT match_id, player_id, hall_id, hall_seat_number, participation_type, score, outcome FROM match_participants")) {
                sides.computeIfAbsent(Integer.parseInt(r[0]), k -> new ArrayList<>()).add(
                        pid.apply(r[1]) + "/" + hallKey.get(Integer.parseInt(r[2])) + "/seat" + r[3] + "/" + r[4] + "/" + r[5] + "/" + r[6]);
            }
            Map<Integer, String> matchKey = new HashMap<>();
            List<String> matchRows = new ArrayList<>();
            for (String[] r : rows(conn, "SELECT id, round_id, match_type_id, table_number, match_timestamp FROM matches")) {
                int id = Integer.parseInt(r[0]);
                List<String> s = new ArrayList<>(sides.getOrDefault(id, List.of()));
                Collections.sort(s);
                String key = roundKey.get(Integer.parseInt(r[1])) + "#" + String.join(" | ", s);
                matchKey.put(id, key);
                matchRows.add(key + " type=" + (r[2] == null ? null : typeKey.get(Integer.parseInt(r[2]))) + " table=" + r[3] + " ts=" + r[4]);
            }
            out.put("matches+participants", sorted(matchRows));

            out.put("halls", sorted(map(rows(conn, "SELECT hall_code, hall_name, next_player_seq FROM halls"), r -> String.join("|", r))));
            out.put("match_types", sorted(map(rows(conn, "SELECT type_name, max_score, time_limit_minutes, description FROM match_types"), r -> String.join("|", r))));
            out.put("rating_types", sorted(map(rows(conn, "SELECT rating_name FROM rating_types"), r -> r[0])));
            out.put("rounds", sorted(map(rows(conn, "SELECT year, round_order, round_label, round_datetime FROM rounds"), r -> String.join("|", r))));
            out.put("players", sorted(map(rows(conn, "SELECT player_id FROM players"), r -> pid.apply(r[0]))));
            out.put("player_names", sorted(map(rows(conn, "SELECT player_id, name, first_seen_year, last_seen_year FROM player_names"),
                    r -> pid.apply(r[0]) + "|" + r[1] + "|" + r[2] + "|" + r[3])));
            out.put("player_year_status", sorted(map(rows(conn, "SELECT player_id, year, hall_id, capped, active FROM player_year_status"),
                    r -> pid.apply(r[0]) + "|" + r[1] + "|" + hallKey.get(Integer.parseInt(r[2])) + "|capped=" + r[3] + "|active=" + r[4])));
            out.put("capped_imports", sorted(map(rows(conn, "SELECT year, name, prev_hall, player_id, mapped FROM capped_imports"),
                    r -> r[0] + "|" + r[1] + "|" + r[2] + "|" + (r[3] == null ? null : pid.apply(r[3])) + "|mapped=" + r[4])));
            for (String table : new String[]{"player_ratings", "player_ratings_snapshot"}) {
                out.put(table, sorted(map(rows(conn, "SELECT player_id, round_id, rating_type_id, rating_value, rating_deviation, volatility FROM " + table),
                        r -> pid.apply(r[0]) + "|" + roundKey.get(Integer.parseInt(r[1])) + "|" + ratingKey.get(Integer.parseInt(r[2]))
                                + "|" + r[3] + "|" + r[4] + "|" + r[5])));
            }
            out.put("player_profiles", sorted(map(rows(conn, "SELECT player_id, playstyle_vector, last_calculated_year FROM player_profiles"),
                    r -> pid.apply(r[0]) + "|" + r[1] + "|" + r[2])));
            out.put("player_rolling_cache", sorted(map(rows(conn, "SELECT player_id, current_streak, avg_margin_last_5_matches, matches_played_today FROM player_rolling_cache"),
                    r -> pid.apply(r[0]) + "|" + r[1] + "|" + r[2] + "|" + r[3])));
            out.put("ai_predictions", sorted(map(rows(conn, "SELECT match_id, predicted_winner_player_id, predicted_win_probability, model_version FROM ai_predictions"),
                    r -> matchKey.get(Integer.parseInt(r[0])) + " -> " + (r[1] == null ? null : pid.apply(r[1])) + "|" + r[2] + "|" + r[3])));
            out.put("ml_models", sorted(map(rows(conn, "SELECT model_version, family, params_json, metrics_json, trained_boards, is_champion FROM ml_models"),
                    r -> r[0] + "|" + r[1] + "|" + sha(r[2]) + "|" + sha(r[3]) + "|" + r[4] + "|" + r[5])));
            out.put("admins", sorted(map(rows(conn, "SELECT platform, platform_user_id, display_name FROM admins"), r -> String.join("|", r))));
        }
        return out;
    }

    /** Asserts two dumps are equal table by table, naming the first differing rows. */
    public static void assertSameDatabase(Map<String, List<String>> expected, Map<String, List<String>> actual, String what) {
        assertEquals(expected.keySet(), actual.keySet(), what + ": table sets differ");
        List<String> diffs = new ArrayList<>();
        for (String table : expected.keySet()) {
            List<String> e = expected.get(table);
            List<String> a = actual.get(table);
            if (!e.equals(a)) {
                List<String> missing = new ArrayList<>(e);
                missing.removeAll(a);
                List<String> extra = new ArrayList<>(a);
                extra.removeAll(e);
                diffs.add(table + " (" + e.size() + " vs " + a.size() + " rows): missing " + head(missing) + " extra " + head(extra));
            }
        }
        assertTrue(diffs.isEmpty(), what + ": databases differ:\n" + String.join("\n", diffs));
    }

    /** Names of the tables whose dumps differ (empty when identical). */
    public static List<String> differingTables(Map<String, List<String>> a, Map<String, List<String>> b) {
        List<String> out = new ArrayList<>();
        for (String t : a.keySet()) if (!a.get(t).equals(b.get(t))) out.add(t);
        return out;
    }

    public static int rowCount(Map<String, List<String>> dump) {
        return dump.values().stream().mapToInt(List::size).sum();
    }

    private static List<String> head(List<String> rows) {
        return rows.size() <= 4 ? rows : new ArrayList<>(rows.subList(0, 4)) {{ add("... (" + (rows.size() - 4) + " more)"); }};
    }

    private static List<String> sorted(List<String> rows) {
        Collections.sort(rows);
        return rows;
    }

    private static List<String> map(List<String[]> rows, Function<String[], String> f) {
        List<String> out = new ArrayList<>(rows.size());
        for (String[] r : rows) out.add(f.apply(r));
        return out;
    }

    private static List<String> column(Connection conn, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        for (String[] r : rows(conn, sql)) out.add(r[0]);
        return out;
    }

    /** Every value as text; REAL values with full round-trip precision. */
    static List<String[]> rows(Connection conn, String sql) throws SQLException {
        List<String[]> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            while (rs.next()) {
                String[] row = new String[n];
                for (int i = 1; i <= n; i++) {
                    Object v = rs.getObject(i);
                    if (v == null) row[i - 1] = null;
                    else if (v instanceof Double d) row[i - 1] = Double.toString(d);
                    else if (v instanceof Float f) row[i - 1] = Double.toString(f.doubleValue());
                    else if (v instanceof byte[] b) row[i - 1] = "blob:" + sha(new String(b, StandardCharsets.ISO_8859_1));
                    else row[i - 1] = v.toString();
                    if (md.getColumnType(i) == Types.REAL && v instanceof Integer iv) row[i - 1] = Double.toString(iv.doubleValue());
                }
                out.add(row);
            }
        }
        return out;
    }

    static String sha(String s) {
        if (s == null) return "null";
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** player id -> the alphabetically first name it ever used (for order-independent comparisons). */
    public static Map<String, String> playerIdToFirstName() throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (Connection conn = DatabaseHelper.getDefaultConnection()) {
            for (String[] r : rows(conn, "SELECT player_id, MIN(name) FROM player_names GROUP BY player_id")) {
                out.put(r[0], "P<" + r[1] + ">");
            }
        }
        return out;
    }

    /** Counts players (excluding the WALKOVER sentinel). */
    public static int playerCount() throws Exception {
        try (Connection conn = DatabaseHelper.getDefaultConnection()) {
            return Integer.parseInt(rows(conn, "SELECT COUNT(*) FROM players WHERE player_id != '" + B4_Players.WALKOVER_PLAYER_ID + "'").get(0)[0]);
        }
    }

    /** Runs one SQL query against the current database and returns its rows as text. */
    public static List<String[]> query(String sql) throws Exception {
        try (Connection conn = DatabaseHelper.getDefaultConnection()) {
            return rows(conn, sql);
        }
    }
}
