package com.calplus.ihrgstats.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The URL and timeout seams must not change production behaviour: with the
 * override properties unset, every URL the 14 call sites build must be
 * character-identical to the hardcoded literal it replaced (the expressions on
 * the left below are copied verbatim from f115301), and every timeout must be
 * the old constant.
 */
public class ApiEndpointsTest {

    private static final String[] PROPS = {
            ApiEndpoints.TELEGRAM_BASE_URL_PROPERTY, ApiEndpoints.DISCORD_BASE_URL_PROPERTY,
            HttpClientFactory.CONNECT_TIMEOUT_PROPERTY, HttpClientFactory.REQUEST_TIMEOUT_PROPERTY,
            HttpClientFactory.LONG_POLL_TIMEOUT_PROPERTY, HttpClientFactory.FILE_TRANSFER_TIMEOUT_PROPERTY};

    private final Map<String, String> saved = new LinkedHashMap<>();

    @BeforeEach
    void clearOverrides() {
        for (String p : PROPS) {
            saved.put(p, System.getProperty(p));
            System.clearProperty(p);
        }
    }

    @AfterEach
    void restoreOverrides() {
        for (Map.Entry<String, String> e : saved.entrySet()) {
            if (e.getValue() == null) {
                System.clearProperty(e.getKey());
            } else {
                System.setProperty(e.getKey(), e.getValue());
            }
        }
    }

    @Test
    void defaultUrls_areCharacterIdenticalToTheReplacedLiterals() {
        for (String botToken : new String[]{"123456789:AAbbCC_dd-EE", "000000:TEST", "", null}) {
            String webhookUrl = "https://example.invalid/hook?x=1";
            long lastUpdateId = 41;
            String endpoint = "sendPhoto";
            String fileId = "BQACAgIAAxk";
            String filePath = "documents/file_7.csv";
            String channelId = "1234567890";

            Map<String, String[]> sites = new LinkedHashMap<>();
            // TelegramListener:395 setWebhook
            sites.put("TL setWebhook", new String[]{
                    "https://api.telegram.org/bot" + botToken + "/setWebhook?url=" + webhookUrl,
                    ApiEndpoints.telegramMethodUrl(botToken, "setWebhook") + "?url=" + webhookUrl});
            // TelegramListener:460 deleteWebhook
            sites.put("TL deleteWebhook", new String[]{
                    "https://api.telegram.org/bot" + botToken + "/deleteWebhook",
                    ApiEndpoints.telegramMethodUrl(botToken, "deleteWebhook")});
            // TelegramListener:479 initial offset
            sites.put("TL getUpdates init", new String[]{
                    "https://api.telegram.org/bot" + botToken + "/getUpdates?offset=-1&limit=1",
                    ApiEndpoints.telegramMethodUrl(botToken, "getUpdates") + "?offset=-1&limit=1"});
            // TelegramListener:509 long poll
            sites.put("TL getUpdates poll", new String[]{
                    "https://api.telegram.org/bot" + botToken + "/getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=30",
                    ApiEndpoints.telegramMethodUrl(botToken, "getUpdates") + "?offset=" + (lastUpdateId + 1) + "&timeout=30"});
            // TelegramListener:973 answerCallbackQuery (was String.format)
            sites.put("TL answerCallbackQuery", new String[]{
                    String.format("https://api.telegram.org/bot%s/answerCallbackQuery", botToken),
                    ApiEndpoints.telegramMethodUrl(botToken, "answerCallbackQuery")});
            // TelegramListener:1619 sendMessage
            sites.put("TL sendMessage", new String[]{
                    "https://api.telegram.org/bot" + botToken + "/sendMessage",
                    ApiEndpoints.telegramMethodUrl(botToken, "sendMessage")});
            // TelegramListener:2918 multipart image endpoint
            sites.put("TL image endpoint", new String[]{
                    "https://api.telegram.org/bot" + botToken + "/" + endpoint,
                    ApiEndpoints.telegramMethodUrl(botToken, endpoint)});
            // TelegramListener:3195 sendDocument to DM
            sites.put("TL sendDocument DM", new String[]{
                    "https://api.telegram.org/bot" + botToken + "/sendDocument",
                    ApiEndpoints.telegramMethodUrl(botToken, "sendDocument")});
            // TelegramListener:3220 editMessageReplyMarkup
            sites.put("TL editMessageReplyMarkup", new String[]{
                    "https://api.telegram.org/bot" + botToken + "/editMessageReplyMarkup",
                    ApiEndpoints.telegramMethodUrl(botToken, "editMessageReplyMarkup")});
            // TelegramLog:63
            sites.put("TelegramLog sendMessage", new String[]{
                    "https://api.telegram.org/bot" + botToken + "/sendMessage",
                    ApiEndpoints.telegramMethodUrl(botToken, "sendMessage")});
            // CommandAbout:66
            sites.put("CommandAbout getChat", new String[]{
                    "https://api.telegram.org/bot" + botToken + "/getChat",
                    ApiEndpoints.telegramMethodUrl(botToken, "getChat")});
            // TelegramFileDownloader:34
            sites.put("Downloader getFile", new String[]{
                    "https://api.telegram.org/bot" + botToken + "/getFile?file_id=" + fileId,
                    ApiEndpoints.telegramMethodUrl(botToken, "getFile") + "?file_id=" + fileId});
            // TelegramFileDownloader:59
            sites.put("Downloader file", new String[]{
                    "https://api.telegram.org/file/bot" + botToken + "/" + filePath,
                    ApiEndpoints.telegramFileUrl(botToken, filePath)});
            // DiscordLog:60
            sites.put("DiscordLog channel", new String[]{
                    "https://discord.com/api/v10/channels/" + channelId + "/messages",
                    ApiEndpoints.discordChannelMessagesUrl(channelId)});

            assertEquals(14, sites.size(), "13 Telegram sites + 1 Discord site");
            for (Map.Entry<String, String[]> e : sites.entrySet()) {
                assertEquals(e.getValue()[0], e.getValue()[1], e.getKey() + " (token=" + botToken + ")");
            }
        }
    }

    @Test
    void blankOverrides_fallBackToTheDefaults() {
        System.setProperty(ApiEndpoints.TELEGRAM_BASE_URL_PROPERTY, "   ");
        System.setProperty(ApiEndpoints.DISCORD_BASE_URL_PROPERTY, "");
        assertEquals("https://api.telegram.org", ApiEndpoints.telegramBaseUrl());
        assertEquals("https://discord.com/api/v10", ApiEndpoints.discordBaseUrl());
    }

    @Test
    void overrides_redirectEveryUrl_andTrailingSlashesAreDropped() {
        System.setProperty(ApiEndpoints.TELEGRAM_BASE_URL_PROPERTY, "http://127.0.0.1:8081/");
        System.setProperty(ApiEndpoints.DISCORD_BASE_URL_PROPERTY, "http://127.0.0.1:8081/discord/api/v10//");
        assertEquals("http://127.0.0.1:8081/bot1:A/getMe", ApiEndpoints.telegramMethodUrl("1:A", "getMe"));
        assertEquals("http://127.0.0.1:8081/file/bot1:A/documents/x.csv", ApiEndpoints.telegramFileUrl("1:A", "documents/x.csv"));
        assertEquals("http://127.0.0.1:8081/discord/api/v10/channels/9/messages", ApiEndpoints.discordChannelMessagesUrl("9"));
    }

    @Test
    void timeouts_defaultToTheOldConstants_andOverridesApplyOnlyWhenValid() {
        assertEquals(Duration.ofSeconds(10), HttpClientFactory.connectTimeout());
        assertEquals(Duration.ofSeconds(30), HttpClientFactory.requestTimeout());
        assertEquals(Duration.ofSeconds(45), HttpClientFactory.longPollTimeout());
        assertEquals(Duration.ofSeconds(120), HttpClientFactory.fileTransferTimeout());
        assertEquals(Duration.ofSeconds(10), HttpClientFactory.newClient().connectTimeout().orElseThrow());

        System.setProperty(HttpClientFactory.REQUEST_TIMEOUT_PROPERTY, "250");
        System.setProperty(HttpClientFactory.LONG_POLL_TIMEOUT_PROPERTY, "1500");
        System.setProperty(HttpClientFactory.FILE_TRANSFER_TIMEOUT_PROPERTY, "abc");
        System.setProperty(HttpClientFactory.CONNECT_TIMEOUT_PROPERTY, "-5");
        assertEquals(Duration.ofMillis(250), HttpClientFactory.requestTimeout());
        assertEquals(Duration.ofMillis(1500), HttpClientFactory.longPollTimeout());
        assertEquals(Duration.ofSeconds(120), HttpClientFactory.fileTransferTimeout(), "unparseable value -> default");
        assertEquals(Duration.ofSeconds(10), HttpClientFactory.connectTimeout(), "non-positive value -> default");
    }
}
