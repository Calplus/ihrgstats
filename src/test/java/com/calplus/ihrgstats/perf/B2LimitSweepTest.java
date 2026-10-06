package com.calplus.ihrgstats.perf;

import com.calplus.ihrgstats.databasemanager.A3_Halls;
import com.calplus.ihrgstats.telegrambot.commands.CommandCompareHalls;
import com.calplus.ihrgstats.telegrambot.commands.CommandComparePlayers;
import com.calplus.ihrgstats.telegrambot.commands.CommandInfoHall;
import com.calplus.ihrgstats.telegrambot.commands.CommandInfoMatch;
import com.calplus.ihrgstats.telegrambot.commands.CommandInfoMatchHall;
import com.calplus.ihrgstats.telegrambot.commands.CommandInfoPlayer;
import com.calplus.ihrgstats.telegrambot.commands.CommandRankHalls;
import com.calplus.ihrgstats.telegrambot.commands.CommandRankPlayers;
import com.calplus.ihrgstats.telegrambot.utils.RoundCsvProcessor;
import com.calplus.ihrgstats.utils.DatabaseHelper;
import com.calplus.ihrgstats.utils.MessageChunker;
import com.calplus.ihrgstats.utils.TelegramCommandUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lane b2, section 3: measured breaking points of every image type against Telegram's photo
 * limits (width + height <= 10,000, ratio <= 20, 10 MB) and of the text replies against 4,096
 * characters - rows (players / halls in a ranking), rounds per season, name length. Databases
 * are built through the real {@code RoundCsvProcessor} in temp folders; images come from the
 * command classes' own entry points (the same calls the listener makes).
 *
 * <pre>mvn -o test -Dtest=B2LimitSweepTest -Db2.sweep=true</pre>
 */
@EnabledIfSystemProperty(named = "b2.sweep", matches = "true")
public class B2LimitSweepTest {

    static final String U = "b2_sweep_user";

    /** One measured render. */
    record Img(String kind, int w, int h, long bytes, int textLen, int chunks, String note) {
        String verdict() {
            List<String> breaks = new ArrayList<>();
            if (w > 0 && w + h > 10_000) {
                breaks.add("W+H>10000");
            }
            if (w > 0 && Math.max(w, h) > 20.0 * Math.min(w, h)) {
                breaks.add("RATIO>20");
            }
            if (bytes > 10L * 1024 * 1024) {
                breaks.add(">10MB");
            }
            return breaks.isEmpty() ? "ok" : String.join("+", breaks);
        }

        String tsv(String sweep, String x) {
            return String.format(Locale.ROOT, "%s\t%s\t%s\t%d\t%d\t%d\t%d\t%.2f\t%d\t%d\t%s\t%s", sweep, x, kind, w, h, w + h, bytes,
                    w > 0 ? (double) Math.max(w, h) / Math.min(w, h) : 0.0, textLen, chunks, verdict(), note == null ? "" : note);
        }
    }

    static Img measure(String kind, TelegramCommandUtils.CommandResponse r) throws Exception {
        int textLen = r.message == null ? 0 : r.message.length();
        int chunks = r.message == null ? 0 : MessageChunker.splitForTelegram(r.message).size();
        if (r.imagePath == null || !Files.exists(r.imagePath)) {
            String m = r.message == null ? "" : r.message.replace('\n', ' ');
            return new Img(kind, 0, 0, 0, textLen, chunks, "NO IMAGE: " + (m.length() > 80 ? m.substring(0, 80) : m));
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(r.imagePath.toFile())) {
            Iterator<ImageReader> it = ImageIO.getImageReaders(in);
            ImageReader reader = it.next();
            reader.setInput(in);
            int w = reader.getWidth(0);
            int h = reader.getHeight(0);
            reader.dispose();
            return new Img(kind, w, h, Files.size(r.imagePath), textLen, chunks, null);
        }
    }

    static Path out() throws Exception {
        Path p = Path.of(System.getProperty("basedir", System.getProperty("user.dir")), "target", "b2");
        Files.createDirectories(p);
        Path tsv = p.resolve("limits.tsv");
        if (!Files.exists(tsv)) {
            Files.writeString(tsv, "sweep\tx\timage\tw\th\tw+h\tbytes\tratio\ttext_len\ttext_chunks\tverdict\tnote\n");
        }
        return tsv;
    }

    static void emit(Path tsv, String sweep, String x, Img img) throws Exception {
        String line = img.tsv(sweep, x);
        System.out.println("[b2-limit] " + line);
        Files.writeString(tsv, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    /** Fresh database with {@code halls} playing halls, user.dir switched to {@code dir}. */
    static void freshDb(Path dir, int halls) throws Exception {
        Files.createDirectories(dir);
        System.setProperty("user.dir", dir.toString());
        System.setProperty("SETTINGS_CURRENTYEAR", "2001");
        ScaleCorpus.Spec spec = new ScaleCorpus.Spec("sweep", 1, new int[]{1}, halls, 5, 10, 0, 1L);
        ScaleDbBuilder.createEmptyDatabase(spec);
    }

    static void ingest(Path csv, int round) throws Exception {
        RoundCsvProcessor p = new RoundCsvProcessor();
        p.setMultiChoiceCallback(ScaleDbBuilder::autoAnswer);
        p.setUploadChatCallback(m -> { });
        assertTrue(p.processRound(csv.toString(), 2001, round, ScaleDbBuilder.NOW), "ingest " + csv);
    }

    static String playerId(String name) throws Exception {
        try (Connection c = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = c.prepareStatement("SELECT player_id FROM player_names WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    static List<String> names(int n, long seed) {
        Random rnd = new Random(seed);
        Set<String> used = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(ScaleCorpus.generatedName(rnd, used));
        }
        return out;
    }

    static String board(String a, String ha, String b, String hb, int i) {
        boolean aWins = i % 3 != 0;
        return a + "," + ha + "," + (aWins ? "250" : "120") + "," + b + "," + hb + "," + (aWins ? "120" : "250") + "\n";
    }

    // ------------------------------------------------------------------ rows

    @Test
    void rowsSweep_rankPlayersAndRankHalls(@TempDir Path tmp) throws Exception {
        Path tsv = out();
        String userDir = System.getProperty("user.dir");
        try {
            for (int n : new int[]{100, 200, 280, 288, 290, 292, 294, 296, 300, 400}) {
                freshDb(tmp.resolve("rows" + n), 2);
                List<String> nm = names(n, n);
                StringBuilder sb = new StringBuilder("name1,hall1,score1,name2,hall2,score2\n");
                for (int i = 0; i < n / 2; i++) {
                    sb.append(board(nm.get(2 * i), "1", nm.get(2 * i + 1), "2", i));
                }
                Path csv = tmp.resolve("rows" + n + ".csv");
                Files.writeString(csv, sb.toString());
                ingest(csv, 1);
                emit(tsv, "players-in-season", String.valueOf(n), measure("rankplayers-all", new CommandRankPlayers().handleRoundSelection(U, "all")));
            }
            for (int halls : new int[]{50, 100, 200, 290, 294, 300}) {
                freshDb(tmp.resolve("halls" + halls), halls);
                List<String> hn = ScaleCorpus.hallNames(halls);
                List<String> nm = names(halls, 7000 + halls);
                StringBuilder sb = new StringBuilder("name1,hall1,score1,name2,hall2,score2\n");
                for (int i = 0; i + 1 < halls; i += 2) {
                    sb.append(board(nm.get(i), hn.get(i), nm.get(i + 1), hn.get(i + 1), i));
                }
                Path csv = tmp.resolve("halls" + halls + ".csv");
                Files.writeString(csv, sb.toString());
                ingest(csv, 1);
                emit(tsv, "halls-in-season", String.valueOf(halls), measure("rankhalls-all", new CommandRankHalls().handleRoundSelection(U, "all")));
            }
        } finally {
            System.setProperty("user.dir", userDir);
            System.clearProperty("SETTINGS_CURRENTYEAR");
        }
    }

    // ------------------------------------------------------------------ rounds per season

    @Test
    void roundsSweep_infoAndCompareWidths(@TempDir Path tmp) throws Exception {
        Path tsv = out();
        String userDir = System.getProperty("user.dir");
        int maxRounds = Integer.getInteger("b2.maxRounds", 40);
        try {
            freshDb(tmp.resolve("rounds"), 3);
            List<String> a = names(5, 11);
            List<String> b = names(5, 22).stream().map(s -> s + "son").toList();
            for (int r = 1; r <= maxRounds; r++) {
                StringBuilder sb = new StringBuilder("name1,hall1,score1,name2,hall2,score2\n");
                for (int i = 0; i < 5; i++) {
                    sb.append(board(a.get(i), "1", b.get((i + r) % 5), "2", i + r));
                }
                Path csv = tmp.resolve("r" + r + ".csv");
                Files.writeString(csv, sb.toString());
                ingest(csv, r);
                if (r < 10 || (r % 2 == 1 && r < 20)) {
                    continue;
                }
                int h1 = new A3_Halls().getHallByName("1").id;
                int h2 = new A3_Halls().getHallByName("2").id;
                String pa = playerId(a.get(0));
                String pb = playerId(b.get(0));
                String x = String.valueOf(r);
                CommandInfoHall ih = new CommandInfoHall();
                ih.handleHallSelection(U, h1);
                emit(tsv, "rounds-in-season", x, measure("infohall-all", ih.handleRoundSelection(U, "all")));
                CommandInfoPlayer ip = new CommandInfoPlayer();
                ip.handleHallSelection(U, h1);
                ip.handlePlayerSelection(U, pa);
                emit(tsv, "rounds-in-season", x, measure("infoplayer-all", ip.handleRoundSelection(U, "all")));
                CommandCompareHalls ch = new CommandCompareHalls();
                ch.handleFirstHallSelection(U, h1);
                ch.handleSecondHallSelection(U, h2);
                emit(tsv, "rounds-in-season", x, measure("comparehalls-all", ch.handleRoundSelection(U, "all")));
                CommandComparePlayers cp = new CommandComparePlayers();
                cp.handleFirstHallSelection(U, h1);
                cp.handleFirstPlayerSelection(U, pa);
                cp.handleSecondHallSelection(U, h2);
                cp.handleSecondPlayerSelection(U, pb);
                emit(tsv, "rounds-in-season", x, measure("compareplayers-all", cp.handleRoundSelection(U, "all")));
                emit(tsv, "rounds-in-season", x, measure("rankplayers-all", new CommandRankPlayers().handleRoundSelection(U, "all")));
            }
        } finally {
            System.setProperty("user.dir", userDir);
            System.clearProperty("SETTINGS_CURRENTYEAR");
        }
    }

    // ------------------------------------------------------------------ name length

    @Test
    void nameLengthSweep(@TempDir Path tmp) throws Exception {
        Path tsv = out();
        String userDir = System.getProperty("user.dir");
        try {
            for (int len : new int[]{20, 40, 80, 160, 320, 640}) {
                freshDb(tmp.resolve("name" + len), 2);
                // a long generated name of whole words ("Kalo Renvo Quizel ...") cut to len characters
                StringBuilder ln = new StringBuilder();
                Random rnd = new Random(len);
                Set<String> used = new HashSet<>();
                while (ln.length() < len) {
                    ln.append(ln.length() == 0 ? "" : " ").append(ScaleCorpus.generatedName(rnd, used).replace(" ", ""));
                }
                String longName = ln.substring(0, len).trim();
                List<String> a = names(5, 31);
                List<String> b = names(5, 32).stream().map(s -> s + "ley").toList();
                StringBuilder sb = new StringBuilder("name1,hall1,score1,name2,hall2,score2\n");
                sb.append(board(longName, "1", b.get(0), "2", 1));
                for (int i = 1; i < 5; i++) {
                    sb.append(board(a.get(i), "1", b.get(i), "2", i));
                }
                Path csv = tmp.resolve("name" + len + ".csv");
                Files.writeString(csv, sb.toString());
                ingest(csv, 1);
                String pl = playerId(longName);
                String pb = playerId(b.get(0));
                int h1 = new A3_Halls().getHallByName("1").id;
                int h2 = new A3_Halls().getHallByName("2").id;
                String x = String.valueOf(longName.length());
                CommandInfoPlayer ip = new CommandInfoPlayer();
                ip.handleHallSelection(U, h1);
                ip.handlePlayerSelection(U, pl);
                emit(tsv, "name-length", x, measure("infoplayer-all", ip.handleRoundSelection(U, "all")));
                CommandComparePlayers cp = new CommandComparePlayers();
                cp.handleFirstHallSelection(U, h1);
                cp.handleFirstPlayerSelection(U, pl);
                cp.handleSecondHallSelection(U, h2);
                cp.handleSecondPlayerSelection(U, pb);
                emit(tsv, "name-length", x, measure("compareplayers-all", cp.handleRoundSelection(U, "all")));
                emit(tsv, "name-length", x, measure("infomatch-latest", new CommandInfoMatch().handleRoundSelection(U, "latest")));
                CommandInfoMatchHall imh = new CommandInfoMatchHall();
                imh.handleHallSelection(U, h1);
                emit(tsv, "name-length", x, measure("infomatchhall-r1", imh.handleRoundSelection(U, "2001_1")));
                emit(tsv, "name-length", x, measure("rankplayers-all", new CommandRankPlayers().handleRoundSelection(U, "all")));
                CommandInfoHall ih = new CommandInfoHall();
                ih.handleHallSelection(U, h1);
                emit(tsv, "name-length", x, measure("infohall-all", ih.handleRoundSelection(U, "all")));
            }
        } finally {
            System.setProperty("user.dir", userDir);
            System.clearProperty("SETTINGS_CURRENTYEAR");
        }
    }
}
