package com.calplus.ihrgstats.perf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

/**
 * Lane b2 - plain {@code main} for container runs (no Maven or JUnit in the measured JVM, so the
 * cgroup's memory.peak is this process alone). Test classpath only; refuses to run outside a temp
 * folder.
 *
 * <pre>
 * java -cp target/test-classes:target/classes:&lt;deps&gt; com.calplus.ihrgstats.perf.B2ScaleMain build &lt;k&gt; &lt;root&gt; &lt;out.tsv&gt; [defer]
 * java -cp ... com.calplus.ihrgstats.perf.B2ScaleMain retrain &lt;db&gt; &lt;runs&gt; &lt;root&gt; &lt;out.tsv&gt; &lt;label&gt;
 * </pre>
 */
public final class B2ScaleMain {

    private B2ScaleMain() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        System.setProperty("b2.cmd", ProcessHandle.current().info().commandLine().orElse("B2ScaleMain " + String.join(" ", args)));
        switch (mode) {
            case "build" -> build(Double.parseDouble(args[1]), Path.of(args[2]), Path.of(args[3]), args.length > 4 && args[4].equals("defer"));
            case "retrain" -> retrain(Path.of(args[1]), Integer.parseInt(args[2]), Path.of(args[3]), Path.of(args[4]), args[5]);
            case "export" -> export(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), args[4]);
            default -> throw new IllegalArgumentException("mode " + mode);
        }
        System.out.println("[b2] done " + String.join(" ", args));
        System.exit(0);
    }

    private static void requireTemp(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        if (!dir.toRealPath().startsWith(tmp)) {
            throw new IllegalStateException("REFUSING: " + dir + " is not under java.io.tmpdir " + tmp);
        }
    }

    static void build(double k, Path root, Path tsv, boolean defer) throws Exception {
        ScaleCorpus.Spec spec = ScaleCorpus.ofScale(k);
        Path base = root.resolve(spec.label() + (defer ? "-deferml" : ""));
        Path work = base.resolve("work");
        requireTemp(work);
        Files.deleteIfExists(work.resolve("database").resolve("core").resolve("default.db"));
        ScaleCorpus.Generated g = ScaleCorpus.generate(spec, base.resolve("csv"));
        System.out.println("[b2] " + g.summary());
        if (defer) {
            System.setProperty("ihrgstats.b2.deferMlTail", "true");
        }
        System.setProperty("user.dir", work.toString());
        ScaleDbBuilder.createEmptyDatabase(spec);
        long t0 = System.nanoTime();
        List<ScaleDbBuilder.UploadTiming> timings = ScaleDbBuilder.ingest(g, tsv, null);
        long ingestMs = (System.nanoTime() - t0) / 1_000_000;
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "# %s ingest wall %d ms over %d uploads (java %s, %d cpus, max heap %d MB)%n",
                base.getFileName(), ingestMs, timings.size(), System.getProperty("java.version"), Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() / (1024 * 1024)));
        if (defer) {
            System.clearProperty("ihrgstats.b2.deferMlTail");
            long[] tail = ScaleDbBuilder.runMlTail();
            sb.append(String.format(Locale.ROOT, "# deferred ML tail once at the end: retrain %d ms, distil %d ms, rolling cache %d ms, trained=%d%n",
                    tail[0], tail[1], tail[2], tail[3]));
            sb.append(String.format(Locale.ROOT, "# total with one retrain: %d ms%n", ingestMs + tail[0] + tail[1] + tail[2]));
        }
        sb.append("# counts ").append(ScaleDbBuilder.counts()).append('\n');
        sb.append("# heap peak ").append(ScaleDbBuilder.heapPeakMb()).append(" MB\n");
        Files.writeString(tsv, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        System.out.print(sb);
    }

    /** The admin's full .xlsx export (the in-memory workbook path of /exportdatabase) on a copy of a stored database. */
    static void export(Path src, Path root, Path tsv, String label) throws Exception {
        Path work = root.resolve("export-work");
        requireTemp(work);
        Path db = work.resolve("database").resolve("core").resolve("default.db");
        Files.createDirectories(db.getParent());
        Files.copy(src, db, StandardCopyOption.REPLACE_EXISTING);
        System.setProperty("user.dir", work.toString());
        if (!Files.exists(tsv)) {
            Files.writeString(tsv, "# java [flags] -cp ... com.calplus.ihrgstats.perf.B2ScaleMain export <db> <root> <tsv> <label>\n"
                    + "label\tok\tms\tbytes\theap_peak_mb\tmax_heap_mb\tmessage\n");
        }
        ScaleDbBuilder.resetHeapPeak();
        long t0 = System.nanoTime();
        String line;
        try {
            var r = new com.calplus.ihrgstats.telegrambot.commands.CommandExportDatabase().executeXlsxExport(ScaleDbBuilder.ADMIN_ID);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            long bytes = r.exportedFilePath != null && Files.exists(r.exportedFilePath) ? Files.size(r.exportedFilePath) : -1;
            line = String.format(Locale.ROOT, "%s\t%s\t%d\t%d\t%d\t%d\t%s", label, r.success, ms, bytes, ScaleDbBuilder.heapPeakMb(),
                    Runtime.getRuntime().maxMemory() >> 20, r.message.replace('\n', ' ').replace('\t', ' '));
        } catch (Throwable t) {
            line = String.format(Locale.ROOT, "%s\tTHROWN\t%d\t-1\t%d\t%d\t%s", label, (System.nanoTime() - t0) / 1_000_000, ScaleDbBuilder.heapPeakMb(),
                    Runtime.getRuntime().maxMemory() >> 20, t.toString().replace('\n', ' '));
        }
        System.out.println("[b2] " + line);
        Files.writeString(tsv, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    static void retrain(Path src, int runs, Path root, Path tsv, String label) throws Exception {
        Path work = root.resolve("retrain-work");
        requireTemp(work);
        Path db = work.resolve("database").resolve("core").resolve("default.db");
        Files.createDirectories(db.getParent());
        System.setProperty("user.dir", work.toString());
        if (!Files.exists(tsv)) {
            Files.writeString(tsv, "# java [flags] -cp target/test-classes:target/classes:target/b2/lib/* com.calplus.ihrgstats.perf.B2ScaleMain retrain <db> <runs> <root> <tsv> <label>"
                    + " (one row per run; flags and cgroup limits per run in the matching .mem file)\n"
                    + "label\trounds\tmatches\tretrain_ms\tdistil_ms\tcache_ms\ttrained\theap_peak_mb\tjava\tcpus\tmax_heap_mb\n");
        }
        System.out.println("[b2] cmd " + System.getProperty("b2.cmd"));
        for (int i = 0; i < runs; i++) {
            Files.copy(src, db, StandardCopyOption.REPLACE_EXISTING);
            var counts = ScaleDbBuilder.counts();
            ScaleDbBuilder.resetHeapPeak();
            long[] tail = ScaleDbBuilder.runMlTail();
            String line = String.format(Locale.ROOT, "%s\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%s\t%d\t%d", label, counts.get("rounds"), counts.get("matches"),
                    tail[0], tail[1], tail[2], tail[3], ScaleDbBuilder.heapPeakMb(), System.getProperty("java.version"),
                    Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() / (1024 * 1024));
            System.out.println("[b2] " + line);
            Files.writeString(tsv, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
    }
}
