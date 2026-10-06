package com.calplus.ihrgstats.e2e;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static com.calplus.ihrgstats.e2e.ConversationDriver.ADMIN;
import static com.calplus.ihrgstats.e2e.ConversationDriver.GROUP;
import static com.calplus.ihrgstats.e2e.ConversationDriver.MEMBER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Lane a1b, scope 5 (seed S1): 2,000 commands (default; -Dihrgstats.e2e.soak.commands=N)
 * with remote logging enabled against the fake (Telegram dev chat + Discord), sampling
 * heap after GC, live threads, JDK HttpClient selector threads and registered JVM
 * shutdown hooks every 200 commands; then 200 plain chat messages. A control run with
 * remote logging off runs first, so the fake's own call records can be told apart from
 * the bot's growth. Gated: -Dihrgstats.e2e.soak=true (about 8-10 minutes).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "ihrgstats.e2e.soak", matches = "true")
public class SoakE2eTest {

    static final String DEV_CHAT = "-1009990999";
    static final List<String> ROTATION = List.of("/help", "/rankplayers", "/rankhalls", "/infohall", "/about");
    static final int BATCH = 25;
    static final int EVERY = 200;

    private FakeTelegramServer fake;

    @BeforeEach
    void startFake() throws Exception {
        fake = FakeTelegramServer.start();
    }

    @AfterEach
    void stopFake() {
        fake.close();
    }

    record Sample(int commands, long heapAfterGcKb, int liveThreads, int httpClientThreads, int shutdownHooks, int fakeCalls, long elapsedMs) {
        @Override
        public String toString() {
            return String.format("%6d %12d %8d %8d %8d %9d %9d", commands, heapAfterGcKb, liveThreads, httpClientThreads,
                    shutdownHooks, fakeCalls, elapsedMs);
        }
    }

    static final String HEADER = String.format("%6s %12s %8s %8s %8s %9s %9s", "cmds", "heapGC(KB)", "threads", "httpSel", "hooks",
            "fakeCalls", "ms");

    /** Registered JVM shutdown hooks (reads java.lang.ApplicationShutdownHooks.hooks; -1 when unreadable). */
    @SuppressWarnings({"removal", "deprecation"})
    static int shutdownHooks() {
        try {
            Class<?> c = Class.forName("java.lang.ApplicationShutdownHooks");
            Field f = c.getDeclaredField("hooks");
            Object map;
            try {
                f.setAccessible(true);
                map = f.get(null);
            } catch (RuntimeException notOpen) {
                Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                uf.setAccessible(true);
                sun.misc.Unsafe u = (sun.misc.Unsafe) uf.get(null);
                map = u.getObject(u.staticFieldBase(f), u.staticFieldOffset(f));
            }
            synchronized (c) {
                return map instanceof IdentityHashMap<?, ?> m ? m.size() : map instanceof Map<?, ?> m2 ? m2.size() : -1;
            }
        } catch (Throwable t) {
            System.err.println("[a1b soak] cannot read shutdown hooks: " + t);
            return -1;
        }
    }

    static Sample sample(int commands, FakeTelegramServer fake, long t0) {
        for (int i = 0; i < 3; i++) {
            System.gc();
            A1bSupport.sleep(150);
        }
        long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1024;
        int live = ManagementFactory.getThreadMXBean().getThreadCount();
        int http = (int) Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("HttpClient-") && t.getName().contains("SelectorManager")).count();
        return new Sample(commands, heap, live, http, shutdownHooks(), fake.callCount(), (System.nanoTime() - t0) / 1_000_000);
    }

    private List<Sample> soak(Path tmp, boolean remoteLogging, String label) throws Exception {
        int total = Integer.getInteger("ihrgstats.e2e.soak.commands", 2000);
        BotHarness.Options o = new BotHarness.Options();
        o.seed = BotHarness.Seed.ROUND_ONE;
        if (remoteLogging) {
            o.devChatId = DEV_CHAT;
            o.discordLogging = true;
        }
        BotHarness bot = BotHarness.start(fake, tmp.resolve("work"), o);
        List<Sample> samples = new ArrayList<>();
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        try {
            ConversationDriver a = bot.driver(ADMIN, GROUP);
            ConversationDriver m = bot.driver(MEMBER, GROUP);
            long t0 = System.nanoTime();
            Sample base = sample(0, fake, t0);
            samples.add(base);
            sb.append(base).append('\n');
            int sent = 0;
            int expectedReplies = 0;
            int from = fake.callCount();
            while (sent < total) {
                int n = Math.min(BATCH, total - sent);
                for (int i = 0; i < n; i++) {
                    (sent % 2 == 0 ? a : m).sendText(ROTATION.get(sent % ROTATION.size()));
                    sent++;
                }
                expectedReplies += n;
                long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
                int got = 0;
                while (System.nanoTime() < end) {
                    got = A1bSupport.userSends(A1bSupport.sliceFrom(fake, from), DEV_CHAT).size();
                    if (got >= expectedReplies) {
                        break;
                    }
                    A1bSupport.sleep(50);
                }
                assertTrue(got >= expectedReplies, label + ": replies " + got + " < " + expectedReplies + " after " + sent + " commands");
                if (sent % EVERY == 0) {
                    Sample s = sample(sent, fake, t0);
                    samples.add(s);
                    sb.append(s).append('\n');
                    System.out.println("[A1B soak " + label + "] " + s);
                }
            }
            // 200 plain chat messages (no command): each builds CommandSettings + CommandAdmins + CommandMatchTypes
            if (remoteLogging) {
                for (int i = 0; i < 200; i++) {
                    (i % 2 == 0 ? a : m).sendText("good game " + i);
                }
                long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
                while (System.nanoTime() < end && fake.pendingUpdates() > 0) {
                    A1bSupport.sleep(100);
                }
                A1bSupport.sleep(2000);
                Sample chat = sample(sent, fake, t0);
                samples.add(chat);
                sb.append(chat).append("   <- after 200 plain chat messages\n");
            }
            A1bSupport.note("soak " + label + " (" + total + " commands, rotation " + ROTATION + ")", sb.toString());
            A1bSupport.cell("soak", label, total + " commands", "admin+member", "group", samples.get(0) + " -> " + samples.get(samples.size() - 1));
        } finally {
            bot.writeTranscript("a1b-soak-" + label);
            bot.close();
        }
        return samples;
    }

    @Test
    @Order(1)
    void soak_control_remoteLoggingOff(@TempDir Path tmp) throws Exception {
        List<Sample> s = soak(tmp, false, "control-logging-off");
        Sample first = s.get(0);
        Sample last = s.get(s.size() - 1);
        assertEquals(first.shutdownHooks(), last.shutdownHooks(), "no hooks registered with remote logging off");
    }

    @Test
    @Order(2)
    void soak_remoteLoggingOn_knownDefect_S1(@TempDir Path tmp) throws Exception {
        List<Sample> s = soak(tmp, true, "remote-logging-on");
        Sample first = s.get(0);
        Sample afterCommands = s.stream().filter(x -> x.commands() == Integer.getInteger("ihrgstats.e2e.soak.commands", 2000))
                .findFirst().orElse(s.get(s.size() - 1));
        int total = afterCommands.commands();
        int hookGrowth = afterCommands.shutdownHooks() - first.shutdownHooks();
        int threadGrowth = afterCommands.liveThreads() - first.liveThreads();
        // S1 today: two log objects (Discord + Telegram), each with a shutdown hook and an HttpClient, per command.
        assertTrue(hookGrowth >= total, "hooks grow at least one per command: +" + hookGrowth + " over " + total);
        assertTrue(threadGrowth >= total, "live threads grow at least one per command: +" + threadGrowth + " over " + total);
    }
}
