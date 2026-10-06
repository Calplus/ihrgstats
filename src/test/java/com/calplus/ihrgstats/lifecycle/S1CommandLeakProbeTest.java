package com.calplus.ihrgstats.lifecycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-3 lane a4 probe for seed S1: every command construction builds a
 * LogHelper -> new DiscordLog + new TelegramLog, each with its own
 * HttpClient (selector thread) and, when remote logging is enabled, its own
 * JVM shutdown hook that pins the object for the life of the process.
 *
 * Runs {@link S1LeakProbeChild} in a child JVM per mode (the leaked hooks
 * die with the child; a hosts file maps api.telegram.org and discord.com to
 * 127.0.0.1, so no real host is reachable; fake tokens only; temp working
 * directory).
 *
 * Today's code (f115301) is CHARACTERIZED: the assertions hold while S1 is
 * present. Run with -Dihrg.probe.s1.expectFixed=true after a fix to assert
 * the fixed invariants instead (no per-construction hooks, nothing retained).
 * A mistyped flag can never pass silently: the default assertions fail once
 * S1 is fixed, and the fixed assertions fail while it is present.
 */
public class S1CommandLeakProbeTest {

    private static final int PER_CLASS = 20;
    private static final int COMMAND_TYPES = S1LeakProbeChild.COMMAND_CLASSES.length; // 18
    private static final boolean EXPECT_FIXED = Boolean.getBoolean("ihrg.probe.s1.expectFixed");

    record ChildRun(Map<String, Long> summary, Map<String, Integer> hooksByClass, long shutdownMillis,
                    int telegramSendErrors, int discordSendErrors, String output) {}

    @Test
    void disabledRemoteLogging_noHooks_andEverythingCollectable(@TempDir Path tmp) throws Exception {
        ChildRun run = runChild(tmp, "disabled");
        Map<String, Long> s = run.summary();
        System.out.println("[a4-S1] disabled: " + s + " shutdownMs=" + run.shutdownMillis());

        assertEquals(COMMAND_TYPES * PER_CLASS, s.get("constructions"));
        assertEquals(0L, s.get("hooksDelta"), "no shutdown hooks may be registered with remote logging disabled");
        // Two log objects per construction either way (S1 part 1: allocation churn).
        assertEquals(2L * COMMAND_TYPES * PER_CLASS, s.get("logObjects"));
        assertEquals(0L, s.get("retainedLogsAfterGc"), "with logging disabled nothing pins the log objects");
        // Each construction still starts two HttpClient selector threads; they
        // only die after a GC collects the client (S1 part 2: thread churn until
        // GC). The peak depends on when the JVM happens to collect, so it is
        // reported, not asserted exactly; after forced GC it must be back to
        // the baseline.
        assertTrue(s.get("selectorsPeak") > s.get("selectors0"),
                "expected live selector threads before GC, got " + s);
        assertEquals(s.get("selectors0"), s.get("selectorsAfterGc"), "selector threads must drain after GC when disabled");
    }

    @Test
    void enabledRemoteLogging_twoHooksPerConstruction_andLogsPinned(@TempDir Path tmp) throws Exception {
        ChildRun run = runChild(tmp, "enabled");
        Map<String, Long> s = run.summary();
        System.out.println("[a4-S1] enabled: " + s + " shutdownMs=" + run.shutdownMillis()
                + " perClassHooks=" + run.hooksByClass());

        assertEquals(COMMAND_TYPES * PER_CLASS, s.get("constructions"));
        long constructions = s.get("constructions");
        if (EXPECT_FIXED) {
            assertEquals(0L, s.get("hooksDelta"), "after the S1 fix no hook may be added per command");
            assertEquals(0L, s.get("retainedLogsAfterGc"), "after the S1 fix no per-command log object may be pinned");
        } else {
            assertEquals(2L * constructions, s.get("hooksDelta"), "S1: two shutdown hooks per command construction");
            assertEquals(2L * constructions, s.get("retainedLogsAfterGc"), "S1: every log object pinned by its hook");
            for (Map.Entry<String, Integer> e : run.hooksByClass().entrySet()) {
                assertEquals(2 * PER_CLASS, e.getValue(), e.getKey() + ": hooks per " + PER_CLASS + " constructions");
            }
        }
    }

    @Test
    void enabledRemoteLogging_wizardFirstStepInfoIsStrandedUntilShutdown(@TempDir Path tmp) throws Exception {
        ChildRun run = runChild(tmp, "enabled-pending");
        Map<String, Long> s = run.summary();
        System.out.println("[a4-S1] enabled-pending: " + s + " shutdownMs=" + run.shutdownMillis()
                + " telegramSendErrorsAtShutdown=" + run.telegramSendErrors()
                + " discordSendErrorsAtShutdown=" + run.discordSendErrors());
        if (EXPECT_FIXED) {
            assertTrue(run.telegramSendErrors() <= 1, "after the fix INFO must not wait for one hook per instance");
        } else {
            // Each of the PER_CLASS /rankhalls first steps left one INFO line in its
            // own instance's buffer; nothing sends it until that instance's hook
            // runs at JVM exit -> one (here: refused, 127.0.0.1) send per instance
            // per platform, all fired at shutdown.
            assertTrue(run.telegramSendErrors() >= PER_CLASS,
                    "expected >= " + PER_CLASS + " Telegram sends at shutdown, got " + run.telegramSendErrors() + "\n" + tail(run.output()));
            assertTrue(run.discordSendErrors() >= PER_CLASS,
                    "expected >= " + PER_CLASS + " Discord sends at shutdown, got " + run.discordSendErrors() + "\n" + tail(run.output()));
        }
    }

    private static ChildRun runChild(Path tmp, String mode) throws Exception {
        Path hosts = tmp.resolve("hosts");
        Files.writeString(hosts, "127.0.0.1 api.telegram.org\n127.0.0.1 discord.com\n", StandardCharsets.US_ASCII);
        Path work = Files.createDirectories(tmp.resolve("work"));

        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("surefire.test.class.path");
        if (cp == null || cp.isBlank()) cp = System.getProperty("java.class.path");

        List<String> cmd = new ArrayList<>(List.of(javaBin,
                "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                "-Xmx256m", "-Djava.awt.headless=true",
                "-Djdk.net.hosts.file=" + hosts.toAbsolutePath(),
                "-Djava.io.tmpdir=" + tmp.toAbsolutePath(),
                "-Duser.dir=" + work.toAbsolutePath(),
                "-cp", cp,
                S1LeakProbeChild.class.getName(), mode, String.valueOf(PER_CLASS)));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(work.toFile()).redirectErrorStream(true);
        // Never let a real token from the parent environment reach the child.
        pb.environment().keySet().removeIf(k -> k.startsWith("TELEGRAM_") || k.startsWith("DISCORD_"));
        Process p = pb.start();

        StringBuilder out = new StringBuilder();
        long[] endPrinted = {-1};
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("S1PROBE_END")) endPrinted[0] = System.currentTimeMillis();
                    synchronized (out) {
                        out.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
                // process ended
            }
        });
        reader.start();
        boolean finished = p.waitFor(300, TimeUnit.SECONDS);
        long exitAt = System.currentTimeMillis();
        if (!finished) {
            p.destroyForcibly();
        }
        reader.join(10_000);
        String text;
        synchronized (out) {
            text = out.toString();
        }
        assertTrue(finished, "child JVM did not exit within 300 s (shutdown hooks hung?)\n" + tail(text));
        assertFalse(text.contains("S1PROBE_REFUSED"), "child refused to run (safety guard)\n" + tail(text));
        assertEquals(0, p.exitValue(), "child failed\n" + tail(text));

        Map<String, Long> summary = new HashMap<>();
        Map<String, Integer> hooksByClass = new HashMap<>();
        Pattern kv = Pattern.compile("(\\w+)=(-?\\d+)");
        for (String line : text.split("\n")) {
            if (line.startsWith("S1PROBE ")) {
                Matcher m = kv.matcher(line);
                while (m.find()) summary.put(m.group(1), Long.parseLong(m.group(2)));
            } else if (line.startsWith("S1CLASS ")) {
                String[] parts = line.split(" ");
                Matcher m = Pattern.compile("hooksDelta=(-?\\d+)").matcher(line);
                if (m.find()) hooksByClass.put(parts[1], Integer.parseInt(m.group(1)));
            }
        }
        assertFalse(summary.isEmpty(), "no S1PROBE line\n" + tail(text));
        int tgErrors = 0;
        int dcErrors = 0;
        boolean afterEnd = false;
        for (String line : text.split("\n")) {
            if (line.startsWith("S1PROBE_END")) afterEnd = true;
            if (!afterEnd) continue;
            if (line.startsWith("Error sending message to Telegram")) tgErrors++;
            if (line.startsWith("Error sending message to Discord")) dcErrors++;
        }
        long shutdownMs = endPrinted[0] > 0 ? exitAt - endPrinted[0] : -1;
        return new ChildRun(summary, hooksByClass, shutdownMs, tgErrors, dcErrors, text);
    }

    private static String tail(String text) {
        return text.length() <= 4000 ? text : text.substring(text.length() - 4000);
    }
}
