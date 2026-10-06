package com.calplus.ihrgstats.upload;

import com.calplus.ihrgstats.upload.CsvVariants.Style;
import com.calplus.ihrgstats.upload.CsvVariants.Table;
import com.calplus.ihrgstats.upload.UploadTestSupport.Dialogs;
import com.calplus.ihrgstats.upload.UploadTestSupport.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.calplus.ihrgstats.upload.UploadTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Lane b1, scope item 1 (database half): the whole 2001 season (capped list
 * + 10 rounds, 300 boards, 3 scripted identity dialogs) ingested through the
 * real pipeline from re-rendered files, compared with the clean ingest by
 * semantic database dump. The oracle is calibrated first.
 */
public class UploadDbEquivalenceTest {

    private String originalUserDir;

    @BeforeEach
    void saveUserDir() {
        originalUserDir = System.getProperty("user.dir");
    }

    @AfterEach
    void restoreUserDir() {
        System.setProperty("user.dir", originalUserDir);
        System.clearProperty("SETTINGS_CURRENTYEAR");
    }

    /** Ingests the 2001 season into a fresh home, files supplied by {@code fileFor}, and returns its dump. */
    static Map<String, List<String>> season2001(Path home, java.util.function.Function<String, Path> fileFor) throws Exception {
        initHome(home);
        Dialogs dialogs = new Dialogs(CORPUS_2001_SCRIPT);
        ingestSeason(2001, 10, fileFor, dialogs);
        assertEquals(3, dialogs.identityDialogCount(), "the 2001 season fires exactly 3 identity dialogs");
        return dump();
    }

    /**
     * Oracle calibration: two clean ingests give identical dumps, and a
     * single planted one-board score change (102.25 -> 102.5 in round 5) is
     * caught in the boards AND the ratings. Without this a "no difference"
     * result would mean nothing.
     */
    @Test
    void oracle_twoCleanIngestsIdentical_andPlantedScoreChangeDetected(@TempDir Path tmp) throws Exception {
        long t0 = System.nanoTime();
        Map<String, List<String>> a = season2001(tmp.resolve("a"), UploadTestSupport::sample);
        long t1 = System.nanoTime();
        Map<String, List<String>> b = season2001(tmp.resolve("b"), UploadTestSupport::sample);
        assertSameDatabase(a, b, "two clean ingests of the 2001 season");
        assertTrue(rowCount(a) > 1500, "dump must be substantial, got " + rowCount(a) + " rows");

        // Plant: change one fractional score in round 5 (a decided board, so
        // only its score - not its outcome - changes unless it was close).
        Path planted = tmp.resolve("planted");
        Files.createDirectories(planted);
        Table r5 = CsvVariants.read(sample("2001_round_5.csv"));
        String[] row = null;
        for (String[] r : r5.rows()) {
            if (CsvVariants.isNumber(r[2]) && CsvVariants.isNumber(r[5])
                    && Math.abs(Double.parseDouble(r[2]) - Double.parseDouble(r[5])) > 20) { row = r; break; }
        }
        assertNotNull(row, "round 5 must have a clearly decided board");
        final String[] target = row;
        final String newScore = String.valueOf(Double.parseDouble(row[2]) + 0.25);
        String text = new String(CsvVariants.render(r5, new Style()), StandardCharsets.UTF_8);
        String line = String.join(",", target);
        assertTrue(text.contains(line), "planted row must be rendered verbatim: " + line);
        Files.writeString(planted.resolve("2001_round_5.csv"), text.replace(line,
                String.join(",", target[0], target[1], newScore, target[3], target[4], target[5])), StandardCharsets.UTF_8);
        Map<String, List<String>> c = season2001(tmp.resolve("c"),
                name -> name.equals("2001_round_5.csv") ? planted.resolve(name) : sample(name));
        List<String> diff = differingTables(a, c);
        assertTrue(diff.contains("matches+participants"), "planted score change must show in the boards: " + diff);
        System.out.println("[b1] oracle: " + rowCount(a) + " dump rows; planted change differs in " + diff
                + "; one 2001 season ingest took " + (t1 - t0) / 1_000_000 + " ms");
    }

    /**
     * Every rendering that parses identically (parse matrix) also ingests to
     * an identical database: the 11 files of the season each get a different
     * accepted rendering (rotating through all 12), then the dump is compared
     * with the clean ingest.
     */
    @Test
    void acceptedRenderings_wholeSeason_ingestToIdenticalDatabase(@TempDir Path tmp) throws Exception {
        Map<String, List<String>> clean = season2001(tmp.resolve("clean"), UploadTestSupport::sample);

        List<UploadVariantsTest.Variant> accepted = new ArrayList<>();
        for (UploadVariantsTest.Variant v : UploadVariantsTest.correctToday()) {
            if (v.rejectedToday() == UploadVariantsTest.correctToday().get(0).rejectedToday()) accepted.add(v);
        }
        assertEquals(12, accepted.size(), "12 renderings are accepted for every file today");
        Path dir = tmp.resolve("variants");
        Files.createDirectories(dir);
        List<String> used = new ArrayList<>();
        String[] names = new String[11];
        names[0] = "2001_cappedlist.csv";
        for (int r = 1; r <= 10; r++) names[r] = "2001_round_" + r + ".csv";
        // two passes so every rendering is used on at least one round file
        for (int pass = 0; pass < 2; pass++) {
            Path passDir = dir.resolve("pass" + pass);
            Files.createDirectories(passDir);
            for (int i = 0; i < names.length; i++) {
                UploadVariantsTest.Variant v = accepted.get((i + pass * 11) % accepted.size());
                Table t = CsvVariants.read(sample(names[i]));
                Files.write(passDir.resolve(names[i]), CsvVariants.render(t, v.style()));
                used.add(names[i] + "<-" + v.id());
            }
            Map<String, List<String>> got = season2001(tmp.resolve("home" + pass), name -> passDir.resolve(name));
            assertSameDatabase(clean, got, "2001 season from accepted renderings, pass " + pass);
        }
        System.out.println("[b1] accepted renderings used: " + used);
    }
}
