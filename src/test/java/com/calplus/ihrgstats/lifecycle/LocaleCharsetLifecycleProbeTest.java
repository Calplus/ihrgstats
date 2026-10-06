package com.calplus.ihrgstats.lifecycle;

import com.calplus.ihrgstats.databasemanager.F16_Admins;
import com.calplus.ihrgstats.telegrambot.listener.TelegramListener;
import com.calplus.ihrgstats.telegrambot.logs.TelegramLog;
import com.calplus.ihrgstats.utils.EnvironmentManager;
import com.calplus.ihrgstats.utils.PropertyManager;
import com.calplus.ihrgstats.utils.TimezoneHelper;
import com.calplus.ihrgstats.utils.VictoryRecordCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-3 lane a4 sweep probes (locale, charset, shutdown flush). Each test
 * CHARACTERIZES today's behaviour at f115301 - it passes while the defect is
 * present and its message says what the fixed behaviour would be. Every
 * global it touches (default locale, user.dir, system properties) is
 * restored in finally; files live only in JUnit temp directories.
 */
public class LocaleCharsetLifecycleProbeTest {

    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    /**
     * EnvironmentManager writes .env.properties with Files.write (UTF-8) but
     * reads it back with Properties.load(InputStream), which decodes
     * ISO-8859-1 - a non-ASCII settings value (e.g. a home hall name) comes
     * back as mojibake after the next load (i.e. after every restart).
     */
    @Test
    void envFile_nonAsciiValue_doesNotRoundTrip(@TempDir Path tmp) throws Exception {
        Path env = tmp.resolve(".env.properties");
        String key = "SETTINGS_A4PROBE";
        String value = "Hällé 名";
        try {
            new EnvironmentManager(env.toString()).setProperty(key, value);
            String reloaded = new EnvironmentManager(env.toString()).getProperty(key);
            String onDisk = Files.readString(env, StandardCharsets.UTF_8);
            System.out.println("[a4-ENV] wrote '" + value + "', file has UTF-8 line '"
                    + onDisk.lines().filter(l -> l.startsWith(key)).findFirst().orElse("?") + "', reloaded '" + reloaded + "'");
            assertTrue(onDisk.contains(value), "the writer stores raw UTF-8 text");
            assertNotEquals(value, reloaded,
                    "characterization: today the reload decodes ISO-8859-1 (fixed: load via a UTF-8 Reader)");
            assertEquals(new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1), reloaded);
        } finally {
            System.clearProperty(key);
        }
    }

    /**
     * PropertyManager.updateEnvironmentProperty upper-cases the property key
     * with the DEFAULT locale (PropertyManager.java:76). Under a Turkish or
     * Azerbaijani default locale "settings.homeHall" becomes
     * "SETTİNGS_HOMEHALL" (dotted capital I), so /settings reports success
     * while the value lands under a key nothing reads.
     */
    @Test
    void turkishDefaultLocale_settingsUpdatesLandUnderAWrongKey(@TempDir Path tmp) throws Exception {
        String origUserDir = System.getProperty("user.dir");
        Locale origLocale = Locale.getDefault();
        String origHomeHall = System.getProperty("SETTINGS_HOMEHALL");
        String dottedKey = "SETTİNGS_HOMEHALL";
        try {
            System.setProperty("user.dir", tmp.toString());
            Locale.setDefault(TURKISH);
            assertTrue(PropertyManager.updateProperty("settings.homeHall", "ProbeHall"), "update reports success");
            Locale.setDefault(origLocale);

            String env = Files.readString(tmp.resolve(".env.properties"), StandardCharsets.UTF_8);
            System.out.println("[a4-LOCALE] tr-TR settings write: SETTINGS_HOMEHALL=" + System.getProperty("SETTINGS_HOMEHALL")
                    + " dotted=" + System.getProperty(dottedKey) + " fileHasDottedKey=" + env.contains(dottedKey));
            assertEquals(origHomeHall, System.getProperty("SETTINGS_HOMEHALL"),
                    "characterization: the real key is untouched under tr-TR (fixed: toUpperCase(Locale.ROOT))");
            assertEquals("ProbeHall", System.getProperty(dottedKey));
            assertTrue(env.contains(dottedKey));
        } finally {
            Locale.setDefault(origLocale);
            System.setProperty("user.dir", origUserDir);
            System.clearProperty(dottedKey);
            if (origHomeHall == null) System.clearProperty("SETTINGS_HOMEHALL");
            else System.setProperty("SETTINGS_HOMEHALL", origHomeHall);
        }
    }

    /**
     * TelegramListener.processFile lower-cases the uploaded file name with the
     * default locale (TelegramListener.java:1257) before matching it; the
     * CASE_INSENSITIVE pattern is ASCII-only, so under tr-TR an upload named
     * "CAPPEDLIST.CSV" becomes "cappedlıst.csv" and is rejected as an
     * unknown file type. Same class of defect: CommandAdmins.java:201
     * upper-cases the platform, so "discord" becomes "DİSCORD" and is refused.
     */
    @Test
    void turkishDefaultLocale_uploadNameAndAdminPlatformCaseFolding() throws Exception {
        Field f = TelegramListener.class.getDeclaredField("CAPPEDLIST_FILENAME");
        f.setAccessible(true);
        Pattern cappedList = (Pattern) f.get(null);

        String upload = "CAPPEDLIST.CSV";
        assertTrue(cappedList.matcher(upload.toLowerCase(Locale.ROOT)).matches());
        String turkishLowered = upload.toLowerCase(TURKISH); // what line 1257 does under tr-TR
        System.out.println("[a4-LOCALE] tr-TR lowercases '" + upload + "' to '" + turkishLowered + "'");
        assertFalse(cappedList.matcher(turkishLowered).matches(),
                "characterization: rejected under tr-TR (fixed: toLowerCase(Locale.ROOT))");

        assertNotEquals(F16_Admins.PLATFORM_DISCORD, "discord".toUpperCase(TURKISH));
        assertEquals(F16_Admins.PLATFORM_DISCORD, "discord".toUpperCase(Locale.ROOT));
    }

    /**
     * Timestamps stored in every *_dttm column come from
     * TimezoneHelper.formatNow -> new SimpleDateFormat(pattern) with the
     * DEFAULT locale, and the 417 locale-less String.format calls render
     * numbers with the default locale's digits and separators.
     */
    @Test
    void defaultLocale_leaksIntoStoredTimestampsAndScoreText() {
        Locale orig = Locale.getDefault();
        try {
            Locale.setDefault(Locale.ROOT);
            String rootTs = TimezoneHelper.formatNow("yyyy-MM-dd HH:mm:ss.SSS");
            Locale.setDefault(Locale.forLanguageTag("th-TH-u-nu-thai"));
            String ts = TimezoneHelper.formatNow("yyyy-MM-dd HH:mm:ss.SSS");
            Locale.setDefault(Locale.forLanguageTag("th-TH"));
            String thaiTs = TimezoneHelper.formatNow("yyyy-MM-dd HH:mm:ss.SSS");
            Locale.setDefault(Locale.GERMANY);
            String score = VictoryRecordCalculator.formatScorePair(2.5, 1.0);
            Locale.setDefault(orig);
            System.out.println("[a4-LOCALE] ROOT timestamp='" + rootTs + "' th-TH-u-nu-thai timestamp='" + ts
                    + "' th-TH timestamp='" + thaiTs + "' de-DE score='" + score + "'");
            assertFalse(ts.chars().allMatch(c -> c < 128),
                    "characterization: non-ASCII digits in a stored timestamp (fixed: Locale.ROOT formatter)");
            assertNotEquals(rootTs.substring(0, 4), thaiTs.substring(0, 4),
                    "characterization: th-TH default locale selects the Buddhist calendar -> year +543 in stored timestamps");
            assertEquals("2,5-1,0", score, "characterization: decimal comma in score text (fixed: Locale.ROOT)");
        } finally {
            Locale.setDefault(orig);
        }
    }

    /**
     * TelegramLog re-queues a rate-limited (429) message with no retry cap
     * (TelegramLog.java:188-201) and ChannelLog.flush() - what every shutdown
     * hook calls - waits with no deadline (ChannelLog.java:141-150). Under a
     * sustained 429 the flush, and therefore JVM shutdown, never completes.
     * Uses a disabled (no hook, no HTTP) TelegramLog subclass whose send
     * always answers "retry after 50 ms" until released.
     */
    @Test
    void telegramLogFlush_neverReturnsWhileRateLimited() throws Exception {
        RateLimitedTelegramLog log = new RateLimitedTelegramLog();
        log.enqueue("probe message");
        Thread flusher = new Thread(log::flush, "a4-flush-probe");
        flusher.setDaemon(true);
        flusher.start();
        flusher.join(2_000);
        boolean stillFlushing = flusher.isAlive();
        int attempts = log.attempts.get();
        log.release = true;
        flusher.join(5_000);
        System.out.println("[a4-FLUSH] after 2 s: flushing=" + stillFlushing + " sendAttempts=" + attempts
                + "; after release: flushing=" + flusher.isAlive());
        assertTrue(stillFlushing, "characterization: flush() has no deadline (fixed: bounded flush)");
        assertTrue(attempts >= 10, "characterization: no retry cap on 429 (got " + attempts + " attempts in 2 s)");
        assertFalse(flusher.isAlive(), "flush returns once sends succeed");
    }

    /** Disabled (enabled=false -> no shutdown hook) TelegramLog whose sends are always rate limited until released. */
    static final class RateLimitedTelegramLog extends TelegramLog {
        volatile boolean release;
        final AtomicInteger attempts = new AtomicInteger();

        @Override
        protected boolean loadConfig() {
            return false;
        }

        @Override
        protected long sendMessage(String message) {
            attempts.incrementAndGet();
            return release ? 0 : 50;
        }

        void enqueue(String message) {
            messageQueue.add(new QueuedMessage(message, new CompletableFuture<>()));
            processQueue();
        }
    }
}
