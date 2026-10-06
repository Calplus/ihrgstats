package com.calplus.ihrgstats.e2e;

import com.calplus.ihrgstats.discordbot.logs.DiscordLog;
import com.calplus.ihrgstats.telegrambot.listener.TelegramListener;
import com.calplus.ihrgstats.telegrambot.logs.TelegramLog;
import com.calplus.ihrgstats.utils.ApiEndpoints;
import com.calplus.ihrgstats.utils.HttpClientFactory;
import com.calplus.ihrgstats.utils.TelegramFileDownloader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The harness's own safety nets: it refuses to run outside a temp folder or with
 * a non-fake token, fails a scenario that reaches for another host, and puts
 * system properties and static wizard state back. Also captures - without any
 * network - the URLs the real code builds when no override is set, proving the
 * defaults are today's.
 */
public class HarnessSafetyTest {

    private FakeTelegramServer fake;

    @BeforeEach
    void startFake() throws Exception {
        fake = FakeTelegramServer.start();
    }

    @AfterEach
    void stopFake() {
        fake.close();
    }

    @Test
    void refusesAWorkDirectoryOutsideTheTempFolder() {
        Path notTemp = BotHarness.projectDir().resolve("target").resolve("not-a-temp-dir");
        String userDir = System.getProperty("user.dir");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> BotHarness.start(fake, notTemp));
        assertTrue(e.getMessage().startsWith("REFUSING"), e.getMessage());
        assertEquals(userDir, System.getProperty("user.dir"), "nothing was changed");
    }

    @Test
    void refusesANonFakeToken_andLeavesNoTrace(@TempDir Path tmp) {
        BotHarness.Options o = new BotHarness.Options();
        o.botToken = "123456789:NOT-THE-FAKE";
        Set<String> before = System.getProperties().stringPropertyNames();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> BotHarness.start(fake, tmp.resolve("w"), o));
        assertTrue(e.getMessage().startsWith("REFUSING"), e.getMessage());
        assertEquals(before, System.getProperties().stringPropertyNames(), "system properties restored");
        assertSame(HostGuard.lockdown(), java.net.ProxySelector.getDefault(),
                "the scenario guard is gone; only the permanent loopback-only lockdown remains");
        assertEquals(0, fake.callCount());
    }

    @Test
    void aRequestToAnotherHost_failsTheScenarioAtClose(@TempDir Path tmp) throws Exception {
        BotHarness.Options o = new BotHarness.Options();
        o.startListener = false;
        BotHarness bot = BotHarness.start(fake, tmp.resolve("w"), o);
        HttpClient c = HttpClientFactory.newClient();
        assertThrows(Exception.class, () -> c.send(HttpRequest.newBuilder(URI.create("http://unreachable.invalid/x"))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString()));
        AssertionError e = assertThrows(AssertionError.class, bot::close);
        assertTrue(e.getMessage().contains("unreachable.invalid"), e.getMessage());
    }

    @Test
    void staticWizardStateAndSystemProperties_areRestoredAfterAScenario(@TempDir Path tmp) throws Exception {
        Field f = Class.forName("com.calplus.ihrgstats.telegrambot.commands.CommandHelp").getDeclaredField("userSelectionStates");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Object, Object> states = (Map<Object, Object>) f.get(null);
        states.put("outside-user", "outside-state");
        System.setProperty("TELEGRAM_PUBLIC_CHATID", "leftover-from-another-test");
        try {
            BotHarness bot = BotHarness.start(fake, tmp.resolve("w"));
            try {
                assertFalse(states.containsKey("outside-user"), "maps are cleared for the scenario");
                assertNotEquals("leftover-from-another-test", System.getProperty("TELEGRAM_PUBLIC_CHATID"), "leftover config cleared");
                bot.driver(ConversationDriver.ADMIN, ConversationDriver.GROUP).sendText("/help");
                bot.driver(ConversationDriver.ADMIN, ConversationDriver.GROUP).awaitKeyboard();
                assertTrue(states.containsKey(ConversationDriver.ADMIN.idString()), "the scenario created wizard state");
                System.setProperty("scenario.only.property", "x");
            } finally {
                bot.close();
            }
            assertEquals(Map.of("outside-user", "outside-state"), states, "maps restored to the snapshot");
            assertNull(System.getProperty("scenario.only.property"));
            assertEquals("leftover-from-another-test", System.getProperty("TELEGRAM_PUBLIC_CHATID"));
            assertNull(System.getProperty(ApiEndpoints.TELEGRAM_BASE_URL_PROPERTY));
            List<String> covered = StaticState.coveredMapFields();
            System.out.println("[A1A-PROBE] static maps saved/restored: " + covered.size() + " " + covered);
            assertTrue(covered.size() >= 12, "11 command maps + TelegramListener.userNameCache: " + covered);
        } finally {
            states.remove("outside-user");
            System.clearProperty("TELEGRAM_PUBLIC_CHATID");
        }
    }

    /**
     * With no override set, the real call sites build exactly today's URLs. The
     * guard allows no host, so every request is routed to the loopback sinkhole
     * and fails there: nothing is resolved or sent anywhere. A precheck proves the
     * guard is consulted and that no DNS lookup happens before any default URL
     * is touched.
     */
    @Test
    void withoutOverrides_theRealCallSitesBuildTodaysUrls_capturedWithoutNetwork(@TempDir Path tmp) throws Exception {
        String userDir = System.getProperty("user.dir");
        Map<String, String> saved = System.getProperties().stringPropertyNames().stream()
                .filter(k -> k.startsWith("TELEGRAM_") || k.startsWith("DISCORD_") || k.startsWith("SETTINGS_")
                        || k.startsWith("INTERNET_") || k.startsWith("ihrgstats."))
                .collect(Collectors.toMap(k -> k, System::getProperty));
        saved.keySet().forEach(System::clearProperty);
        HostGuard guard = HostGuard.install(); // nothing allowed
        TelegramListener listener = null;
        try {
            // Precheck: the guard sees the request and the failure is not a DNS failure.
            Exception pre = assertThrows(Exception.class, () -> HttpClientFactory.newClient().send(
                    HttpRequest.newBuilder(URI.create("http://precheck.invalid/x")).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()));
            for (Throwable t = pre; t != null; t = t.getCause()) {
                assertFalse(t instanceof UnknownHostException, "a DNS lookup happened - aborting before any real URL");
            }
            assertEquals(1, guard.violations().size(), "the guard was consulted");

            Path work = tmp.resolve("w");
            Files.createDirectories(work);
            Files.writeString(work.resolve(".env.properties"), "TELEGRAM_BOT_TOKEN=000000:TEST\n");
            System.setProperty("user.dir", work.toString());
            System.setProperty("TELEGRAM_BOT_TOKEN", FakeTelegramServer.FAKE_TOKEN);
            System.setProperty("TELEGRAM_DEV_CHATID", "-1009990099");
            System.setProperty("DISCORD_BOT_TOKEN", BotHarness.FAKE_DISCORD_TOKEN);
            System.setProperty("DISCORD_LOG_CHANNELID", "5550001");

            assertFalse(new TelegramFileDownloader(FakeTelegramServer.FAKE_TOKEN).downloadFile("FILEID", work.resolve("x").toString()));
            new TelegramLog().logSuccess("capture");
            new DiscordLog().logSuccess("capture");
            listener = new TelegramListener();
            listener.start();

            List<String> expected = List.of(
                    "https://api.telegram.org/bot000000:TEST/getFile?file_id=FILEID",
                    "https://api.telegram.org/bot000000:TEST/sendMessage",
                    "https://discord.com/api/v10/channels/5550001/messages",
                    "https://api.telegram.org/bot000000:TEST/deleteWebhook",
                    "https://api.telegram.org/bot000000:TEST/getUpdates?offset=-1&limit=1",
                    "https://api.telegram.org/bot000000:TEST/getUpdates?offset=1&timeout=30");
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline
                    && !guard.violations().stream().map(URI::toString).collect(Collectors.toSet()).containsAll(expected)) {
                Thread.sleep(50);
            }
            Set<String> seen = guard.violations().stream().map(URI::toString).collect(Collectors.toSet());
            for (String url : expected) {
                assertTrue(seen.contains(url), "expected default URL " + TranscriptWriter.redact(url) + " among "
                        + seen.stream().map(TranscriptWriter::redact).toList());
            }
            System.out.println("[A1A-PROBE] default URLs captured without network: " + expected.size());
        } finally {
            if (listener != null) {
                listener.stop();
            }
            guard.close();
            System.setProperty("user.dir", userDir);
            for (String k : List.of("TELEGRAM_BOT_TOKEN", "TELEGRAM_DEV_CHATID", "DISCORD_BOT_TOKEN", "DISCORD_LOG_CHANNELID")) {
                System.clearProperty(k);
            }
            saved.forEach(System::setProperty);
        }
    }
}
