package com.calplus.ihrgstats.lifecycle;

import com.calplus.ihrgstats.databasemanager.DatabaseSchema;
import com.calplus.ihrgstats.utils.LogHelper;

import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Round-3 lane a4 probe for seed S1 (per-command log objects, HTTP clients
 * and shutdown hooks). Runs in a CHILD JVM launched by
 * {@link S1CommandLeakProbeTest} so that (a) the shutdown hooks it leaks die
 * with it instead of pinning objects in the Surefire JVM, and (b) host
 * resolution can be locked to a hosts file that maps the two log hosts to
 * 127.0.0.1 - nothing in this process can reach a real host.
 *
 * Args: mode (disabled | enabled | enabled-pending), perClass (constructions
 * per command class). Prints one "S1PROBE ..." summary line plus one
 * "S1CLASS ..." line per command class, then "S1PROBE_END epochMs".
 */
public final class S1LeakProbeChild {

    static final String[] COMMAND_CLASSES = {
            "CommandAbout", "CommandAdmins", "CommandCompareHalls", "CommandComparePlayers",
            "CommandExportDatabase", "CommandHelp", "CommandInfoHall", "CommandInfoMatch",
            "CommandInfoMatchHall", "CommandInfoPlayer", "CommandLineup", "CommandMatchTypes",
            "CommandModelStats", "CommandPredict", "CommandRankHalls", "CommandRankPlayers",
            "CommandRecalculate", "CommandSettings"};

    static final String FAKE_TELEGRAM_TOKEN = "000000:TEST";

    private S1LeakProbeChild() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "disabled";
        int perClass = args.length > 1 ? Integer.parseInt(args[1]) : 10;

        // --- Safety guards: refuse to run anywhere near real hosts or data. ---
        String hostsFile = System.getProperty("jdk.net.hosts.file");
        Path userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path tmp = Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath();
        if (hostsFile == null || !userDir.startsWith(tmp)) {
            System.out.println("S1PROBE_REFUSED hostsFile=" + hostsFile + " userDir=" + userDir);
            System.exit(3);
        }
        for (String host : new String[]{"api.telegram.org", "discord.com"}) {
            InetAddress resolved = InetAddress.getByName(host);
            if (!resolved.isLoopbackAddress()) {
                System.out.println("S1PROBE_REFUSED " + host + " resolves to " + resolved);
                System.exit(3);
            }
        }

        boolean enabled = mode.startsWith("enabled");
        if (enabled) {
            // Fake credentials only; the hosts file sends both hosts to 127.0.0.1.
            System.setProperty("TELEGRAM_BOT_TOKEN", FAKE_TELEGRAM_TOKEN);
            System.setProperty("TELEGRAM_DEV_CHATID", "-1000000000000");
            System.setProperty("DISCORD_BOT_TOKEN", "TEST.TOKEN.FAKE");
            System.setProperty("DISCORD_LOG_CHANNELID", "000000000000000000");
        }

        new DatabaseSchema().createDatabase("default.db");

        settle();
        int hooks0 = shutdownHookCount();
        int selectors0 = selectorThreadCount();
        int threads0 = ManagementFactory.getThreadMXBean().getThreadCount();
        long heap0 = usedHeapAfterGc();
        long fds0 = openFds();

        List<WeakReference<Object>> logRefs = new ArrayList<>();
        List<WeakReference<Object>> commandRefs = new ArrayList<>();
        Map<String, int[]> perClassHooks = new LinkedHashMap<>();
        int peakSelectors = selectors0;
        int constructions = 0;

        for (String simpleName : COMMAND_CLASSES) {
            Class<?> cls = Class.forName("com.calplus.ihrgstats.telegrambot.commands." + simpleName);
            int before = shutdownHookCount();
            int selBefore = selectorThreadCount();
            for (int i = 0; i < perClass; i++) {
                Object command = construct(cls);
                constructions++;
                commandRefs.add(new WeakReference<>(command));
                for (Object channelLog : channelLogsOf(command)) {
                    logRefs.add(new WeakReference<>(channelLog));
                }
                if (mode.equals("enabled-pending") && simpleName.equals("CommandRankHalls")) {
                    // The real first step of the /rankhalls wizard: logs one INFO line
                    // and returns the keyboard (or, with no current year, a notice) -
                    // no terminal log, so the INFO stays in THIS instance's buffer.
                    Method handle = cls.getMethod("handleCommand", String.class);
                    handle.invoke(command, "probe-user-" + i);
                }
            }
            peakSelectors = Math.max(peakSelectors, selectorThreadCount());
            perClassHooks.put(simpleName, new int[]{shutdownHookCount() - before, selectorThreadCount() - selBefore});
        }
        int selectorsBeforeGc = selectorThreadCount();

        long heap1 = usedHeapAfterGc();
        // HttpClient selector threads exit a few seconds after their client is
        // collected - wait (bounded) for the count to settle.
        long deadline = System.currentTimeMillis() + 15_000;
        int selectorsAfterGc = selectorThreadCount();
        while (System.currentTimeMillis() < deadline) {
            System.gc();
            Thread.sleep(500);
            int now = selectorThreadCount();
            if (now <= selectors0) {
                selectorsAfterGc = now;
                break;
            }
            selectorsAfterGc = now;
        }
        long heap2 = usedHeapAfterGc();
        int retainedLogs = 0;
        for (WeakReference<Object> ref : logRefs) {
            if (ref.get() != null) retainedLogs++;
        }
        int retainedCommands = 0;
        for (WeakReference<Object> ref : commandRefs) {
            if (ref.get() != null) retainedCommands++;
        }

        for (Map.Entry<String, int[]> e : perClassHooks.entrySet()) {
            System.out.println(String.format(Locale.ROOT, "S1CLASS %s hooksDelta=%d selectorDeltaBeforeGc=%d",
                    e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        System.out.println(String.format(Locale.ROOT,
                "S1PROBE mode=%s perClass=%d constructions=%d hooks0=%d hooksDelta=%d selectors0=%d "
                        + "selectorsPeak=%d selectorsBeforeGc=%d selectorsAfterGc=%d threads0=%d threadsAfterGc=%d "
                        + "logObjects=%d retainedLogsAfterGc=%d retainedCommandsAfterGc=%d heapDeltaAfterGcBytes=%d heapDeltaSettledBytes=%d "
                        + "openFds0=%d openFdsAfterGc=%d",
                mode, perClass, constructions, hooks0, shutdownHookCount() - hooks0, selectors0,
                peakSelectors, selectorsBeforeGc, selectorsAfterGc, threads0,
                ManagementFactory.getThreadMXBean().getThreadCount(),
                logRefs.size(), retainedLogs, retainedCommands, heap1 - heap0, heap2 - heap0,
                fds0, openFds()));
        System.out.println("S1PROBE_END " + System.currentTimeMillis());
        System.out.flush();
        // Returning lets the JVM run every registered shutdown hook - the parent
        // measures how long that takes and how many sends it attempts.
    }

    private static Object construct(Class<?> cls) throws Exception {
        if (cls.getSimpleName().equals("CommandAbout")) {
            Constructor<?> c = cls.getConstructor(String.class);
            return c.newInstance(FAKE_TELEGRAM_TOKEN);
        }
        Constructor<?> c = cls.getDeclaredConstructor();
        c.setAccessible(true);
        return c.newInstance();
    }

    /** The DiscordLog/TelegramLog objects held by every LogHelper field of the command. */
    private static List<Object> channelLogsOf(Object command) throws IllegalAccessException {
        List<Object> out = new ArrayList<>();
        for (Class<?> c = command.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == LogHelper.class) {
                    f.setAccessible(true);
                    Object helper = f.get(command);
                    if (helper == null) continue;
                    for (Field lf : LogHelper.class.getDeclaredFields()) {
                        lf.setAccessible(true);
                        Object log = lf.get(helper);
                        if (log != null) out.add(log);
                    }
                }
            }
        }
        return out;
    }

    /** Count of registered application shutdown hooks (needs --add-opens java.base/java.lang). */
    static int shutdownHookCount() {
        try {
            Class<?> hooksClass = Class.forName("java.lang.ApplicationShutdownHooks");
            Field hooks = hooksClass.getDeclaredField("hooks");
            hooks.setAccessible(true);
            synchronized (hooksClass) {
                Map<?, ?> map = (Map<?, ?>) hooks.get(null);
                return map == null ? -1 : map.size();
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("cannot read shutdown hooks (run with --add-opens java.base/java.lang=ALL-UNNAMED)", e);
        }
    }

    static int selectorThreadCount() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().contains("SelectorManager")) n++;
        }
        return n;
    }

    /** Open file descriptors on Unix JVMs (the Linux rehearsal), -1 elsewhere (e.g. Windows). */
    static long openFds() {
        java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.UnixOperatingSystemMXBean unix) {
            return unix.getOpenFileDescriptorCount();
        }
        return -1;
    }

    private static long usedHeapAfterGc() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(100);
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static void settle() throws InterruptedException {
        usedHeapAfterGc();
    }
}
