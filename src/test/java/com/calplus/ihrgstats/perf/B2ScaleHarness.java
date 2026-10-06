package com.calplus.ihrgstats.perf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lane b2 - gated entry points for the scalability probe (never part of a plain {@code mvn test}).
 *
 * <pre>
 * build a league DB through the real upload pipeline, timing every upload:
 *   mvn -o test -Dtest=B2ScaleHarness#build -Db2.scale=1 [-Db2.deferMl=true] [-Db2.root=...]
 * time the ML tail (retrain + distil + rolling cache) on a stored DB, N runs:
 *   mvn -o test -Dtest=B2ScaleHarness#retrain -Db2.db=&lt;path to default.db&gt; -Db2.runs=3
 * </pre>
 *
 * Everything is written under {@code b2.root} (default {@code java.io.tmpdir/b2-scale}); timings
 * TSVs also go to {@code target/b2/}. {@code -Db2.deferMl=true} only has an effect with the
 * candidate-5 measurement prototype applied (it skips the per-upload ML tail; the tail then
 * runs once at the end) - on today's code the property is ignored.
 */
public class B2ScaleHarness {

    static Path root() {
        return Path.of(System.getProperty("b2.root", Path.of(System.getProperty("java.io.tmpdir"), "b2-scale").toString()));
    }

    static Path targetDir() throws Exception {
        String basedir = System.getProperty("basedir", System.getProperty("user.dir"));
        Path p = Path.of(basedir, "target", "b2");
        Files.createDirectories(p);
        return p;
    }

    @Test
    @EnabledIfSystemProperty(named = "b2.scale", matches = "[0-9.]+")
    void build() throws Exception {
        double k = Double.parseDouble(System.getProperty("b2.scale"));
        ScaleCorpus.Spec spec = ScaleCorpus.ofScale(k);
        boolean defer = Boolean.getBoolean("b2.deferMl");
        boolean jfr = Boolean.getBoolean("b2.jfr");
        String tag = spec.label() + (defer ? "-deferml" : "") + System.getProperty("b2.tag", "");
        Path base = root().resolve(tag);
        Path csvDir = base.resolve("csv");
        Path work = base.resolve("work");
        Path snaps = base.resolve("snap");
        Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        Files.createDirectories(work);
        assertTrue(work.toRealPath().startsWith(tmp) || Boolean.getBoolean("b2.allowNonTemp"), "work dir must be under java.io.tmpdir: " + work);
        // fresh database every build
        Files.deleteIfExists(work.resolve("database").resolve("core").resolve("default.db"));

        ScaleCorpus.Generated g = ScaleCorpus.generate(spec, csvDir);
        Path sampleDir = Path.of(System.getProperty("basedir", System.getProperty("user.dir")), "SAMPLE FILES");
        if (Files.isDirectory(sampleDir)) {
            ScaleCorpus.assertNoSampleNames(g, sampleDir);
        }
        System.out.println("[b2] " + g.summary());

        String userDir = System.getProperty("user.dir");
        if (defer) {
            System.setProperty("ihrgstats.b2.deferMlTail", "true");
        }
        try {
            System.setProperty("user.dir", work.toString());
            ScaleDbBuilder.createEmptyDatabase(spec);
            Path tsv = targetDir().resolve("ingest_" + tag + ".tsv");
            System.setProperty("b2.cmd", "mvn -o test -Dtest=B2ScaleHarness#build -Db2.scale=" + System.getProperty("b2.scale")
                    + (defer ? " -Db2.deferMl=true" : "") + (jfr ? " -Db2.jfr=true" : "") + " -Dsurefire.failIfNoSpecifiedTests=false");
            jdk.jfr.Recording ingestRec = jfr ? startJfr() : null;
            long t0 = System.nanoTime();
            int[] season = {0};
            List<ScaleDbBuilder.UploadTiming> timings = ScaleDbBuilder.ingest(g, tsv, (u, t) -> {
                int seasonRounds = spec.roundsPerSeason()[u.year() - ScaleCorpus.FIRST_YEAR];
                if (!u.cappedList() && u.round() == seasonRounds) {
                    season[0]++;
                    ScaleDbBuilder.snapshot(snaps.resolve("season_" + season[0] + ".db"));
                }
            });
            long ingestMs = (System.nanoTime() - t0) / 1_000_000;
            if (ingestRec != null) {
                stopJfr(ingestRec, "ingest_" + tag);
            }
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "# %s ingest wall %d ms over %d uploads (java %s, %d cpus, max heap %d MB)%n", tag, ingestMs,
                    timings.size(), System.getProperty("java.version"), Runtime.getRuntime().availableProcessors(),
                    Runtime.getRuntime().maxMemory() / (1024 * 1024)));
            if (defer) {
                System.clearProperty("ihrgstats.b2.deferMlTail");
                jdk.jfr.Recording tailRec = jfr ? startJfr() : null;
                long[] tail = ScaleDbBuilder.runMlTail();
                if (tailRec != null) {
                    stopJfr(tailRec, "retrain_" + tag);
                }
                sb.append(String.format(Locale.ROOT, "# deferred ML tail once at the end: retrain %d ms, distil %d ms, rolling cache %d ms, trained=%d%n",
                        tail[0], tail[1], tail[2], tail[3]));
                ScaleDbBuilder.snapshot(snaps.resolve("final.db"));
            }
            sb.append("# counts ").append(ScaleDbBuilder.counts()).append('\n');
            sb.append("# heap peak during build ").append(ScaleDbBuilder.heapPeakMb()).append(" MB\n");
            Files.writeString(tsv, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            Files.copy(work.resolve("database").resolve("core").resolve("default.db"), base.resolve("final.db"), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[b2] " + sb);
        } finally {
            System.setProperty("user.dir", userDir);
            System.clearProperty("SETTINGS_CURRENTYEAR");
            System.clearProperty("TELEGRAM_ADMIN_USERID");
            System.clearProperty("ihrgstats.b2.deferMlTail");
        }
    }

    /** Starts a JFR recording with the JDK's "profile" settings (10/20 ms execution sampling, allocation samples). */
    static jdk.jfr.Recording startJfr() throws Exception {
        jdk.jfr.Recording r = new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));
        r.start();
        return r;
    }

    static Path stopJfr(jdk.jfr.Recording r, String name) throws Exception {
        r.stop();
        Path dir = targetDir().resolve("jfr");
        Files.createDirectories(dir);
        Path f = dir.resolve(name + ".jfr");
        r.dump(f);
        r.close();
        System.out.println("[b2] JFR written: " + f);
        return f;
    }

    /** Times the ML tail N times on a copy of a stored database (each run on a fresh copy). */
    @Test
    @EnabledIfSystemProperty(named = "b2.db", matches = ".+")
    void retrain() throws Exception {
        Path src = Path.of(System.getProperty("b2.db"));
        int runs = Integer.getInteger("b2.runs", 3);
        String label = System.getProperty("b2.label", src.getFileName().toString());
        Path work = root().resolve("retrain-work");
        Path db = work.resolve("database").resolve("core").resolve("default.db");
        Files.createDirectories(db.getParent());
        String userDir = System.getProperty("user.dir");
        List<long[]> results = new ArrayList<>();
        try {
            System.setProperty("user.dir", work.toString());
            for (int i = 0; i < runs; i++) {
                Files.copy(src, db, StandardCopyOption.REPLACE_EXISTING);
                Map<String, Long> counts = ScaleDbBuilder.counts();
                ScaleDbBuilder.resetHeapPeak();
                jdk.jfr.Recording rec = Boolean.getBoolean("b2.jfr") && i == 0 ? startJfr() : null;
                long[] tail = ScaleDbBuilder.runMlTail();
                if (rec != null) {
                    stopJfr(rec, "retrain_" + label);
                }
                long[] row = {tail[0], tail[1], tail[2], tail[3], ScaleDbBuilder.heapPeakMb(), counts.get("matches"), counts.get("rounds")};
                results.add(row);
                System.out.printf(Locale.ROOT, "[b2] retrain %s run %d: retrain %d ms, distil %d ms, cache %d ms, trained=%d, heap peak %d MB, matches %d, rounds %d%n",
                        label, i + 1, row[0], row[1], row[2], row[3], row[4], row[5], row[6]);
            }
        } finally {
            System.setProperty("user.dir", userDir);
        }
        StringBuilder sb = new StringBuilder();
        for (long[] r : results) {
            sb.append(String.format(Locale.ROOT, "%s\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%s\t%d%n", label, r[6], r[5], r[0], r[1], r[2], r[3], r[4],
                    System.getProperty("java.version"), Runtime.getRuntime().availableProcessors()));
        }
        Path tsv = targetDir().resolve("retrain.tsv");
        if (!Files.exists(tsv)) {
            Files.writeString(tsv, "# mvn -o test -Dtest=B2ScaleHarness#retrain -Db2.db=<snapshot> -Db2.runs=<n> -Db2.label=<label> (one row per run)\n" + "label\trounds\tmatches\tretrain_ms\tdistil_ms\tcache_ms\ttrained\theap_peak_mb\tjava\tcpus\n");
        }
        Files.writeString(tsv, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }
}
