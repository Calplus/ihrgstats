package com.calplus.ihrgstats.perf;

import com.calplus.ihrgstats.databasemanager.A3_Halls;
import com.calplus.ihrgstats.telegrambot.commands.CommandCompareHalls;
import com.calplus.ihrgstats.telegrambot.commands.CommandComparePlayers;
import com.calplus.ihrgstats.telegrambot.commands.CommandInfoHall;
import com.calplus.ihrgstats.telegrambot.commands.CommandLineup;
import com.calplus.ihrgstats.telegrambot.commands.CommandRankPlayers;
import com.calplus.ihrgstats.utils.DatabaseHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Lane b2, section 4: JFR recordings ("profile" settings) of the hot read paths on a stored
 * scale database, each command called through its own entry points (the calls the listener
 * makes) once to warm up and then N times inside the recording.
 *
 * <pre>mvn -o test -Dtest=B2JfrProfileTest -Db2.jfrDb=&lt;default.db&gt; -Db2.size=3x -Db2.year=2007 [-Db2.jfrIters=5]</pre>
 *
 * Writes target/b2/jfr/&lt;size&gt;_&lt;profile&gt;.jfr and one line per profile to target/b2/jfr/profiles.tsv.
 */
@EnabledIfSystemProperty(named = "b2.jfrDb", matches = ".+")
public class B2JfrProfileTest {

    interface Call {
        Object run() throws Exception;
    }

    @Test
    void profiles() throws Exception {
        Path src = Path.of(System.getProperty("b2.jfrDb"));
        String size = System.getProperty("b2.size", "?");
        int iters = Integer.getInteger("b2.jfrIters", 5);
        Path work = B2ScaleHarness.root().resolve("jfr-work-" + size);
        Path db = work.resolve("database").resolve("core").resolve("default.db");
        Files.createDirectories(db.getParent());
        Files.copy(src, db, StandardCopyOption.REPLACE_EXISTING);
        String userDir = System.getProperty("user.dir");
        Path dir = B2ScaleHarness.targetDir().resolve("jfr");
        Files.createDirectories(dir);
        Path tsv = dir.resolve("profiles.tsv");
        if (!Files.exists(tsv)) {
            Files.writeString(tsv, "# mvn -o test -Dtest=B2JfrProfileTest -Db2.jfrDb=<db> -Db2.size=<size> -Db2.year=<year> -Db2.jfrIters=<n>\n"
                    + "size\tprofile\titers\tmedian_ms\tmin_ms\tmax_ms\tjfr\tjava\tcpus\n", StandardCharsets.UTF_8);
        }
        try {
            System.setProperty("user.dir", work.toString());
            System.setProperty("SETTINGS_CURRENTYEAR", System.getProperty("b2.year", "2004"));
            System.setProperty("SETTINGS_HOMEHALL", "1");
            String admin = ScaleDbBuilder.ADMIN_ID;
            int h1 = new A3_Halls().getHallByName("1").id;
            int h2 = new A3_Halls().getHallByName("2").id;
            String[] p = firstTwoPlayers(h1, h2);
            Map<String, Call> calls = new LinkedHashMap<>();
            calls.put("infohall_all", () -> {
                CommandInfoHall c = new CommandInfoHall();
                c.handleHallSelection(admin, h1);
                return c.handleRoundSelection(admin, "all");
            });
            calls.put("rankplayers_allyears", () -> new CommandRankPlayers().handleRoundSelection(admin, "allyears"));
            calls.put("compareplayers_all", () -> {
                CommandComparePlayers c = new CommandComparePlayers();
                c.handleFirstHallSelection(admin, h1);
                c.handleFirstPlayerSelection(admin, p[0]);
                c.handleSecondHallSelection(admin, h2);
                c.handleSecondPlayerSelection(admin, p[1]);
                return c.handleRoundSelection(admin, "all");
            });
            calls.put("lineup_vs_hall2", () -> new CommandLineup().handleOpponentHallSelection(admin, h2));
            calls.put("render_comparehalls_all", () -> {
                CommandCompareHalls c = new CommandCompareHalls();
                c.handleFirstHallSelection(admin, h1);
                c.handleSecondHallSelection(admin, h2);
                return c.handleRoundSelection(admin, "all");
            });
            String only = System.getProperty("b2.jfrOnly", "");
            for (Map.Entry<String, Call> e : calls.entrySet()) {
                if (!only.isEmpty() && !only.contains(e.getKey())) {
                    continue;
                }
                assertNotNull(e.getValue().run(), "warm-up " + e.getKey());
                jdk.jfr.Recording rec = B2ScaleHarness.startJfr();
                List<Long> ms = new ArrayList<>();
                for (int i = 0; i < iters; i++) {
                    long t0 = System.nanoTime();
                    e.getValue().run();
                    ms.add((System.nanoTime() - t0) / 1_000_000);
                }
                Path f = B2ScaleHarness.stopJfr(rec, size + "_" + e.getKey());
                List<Long> sorted = new ArrayList<>(ms);
                Collections.sort(sorted);
                String line = String.format(Locale.ROOT, "%s\t%s\t%d\t%d\t%d\t%d\t%s\t%s\t%d", size, e.getKey(), iters, sorted.get(sorted.size() / 2),
                        sorted.get(0), sorted.get(sorted.size() - 1), f.getFileName(), System.getProperty("java.version"),
                        Runtime.getRuntime().availableProcessors());
                System.out.println("[b2-jfr] " + line + " " + ms);
                Files.writeString(tsv, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            }
        } finally {
            System.setProperty("user.dir", userDir);
            System.clearProperty("SETTINGS_CURRENTYEAR");
            System.clearProperty("SETTINGS_HOMEHALL");
        }
    }

    /** The first player (by id) of each hall in the current year. */
    private static String[] firstTwoPlayers(int h1, int h2) throws Exception {
        String[] out = new String[2];
        int year = Integer.parseInt(System.getProperty("SETTINGS_CURRENTYEAR"));
        try (Connection c = DatabaseHelper.getDefaultConnection(); Statement st = c.createStatement()) {
            for (int i = 0; i < 2; i++) {
                try (ResultSet rs = st.executeQuery("SELECT player_id FROM player_year_status WHERE year = " + year + " AND hall_id = "
                        + (i == 0 ? h1 : h2) + " ORDER BY player_id LIMIT 1")) {
                    out[i] = rs.next() ? rs.getString(1) : null;
                }
            }
        }
        return out;
    }
}
