package com.calplus.ihrgstats.utils;

/**
 * Single source of the remote API base URLs (Telegram Bot API and Discord).
 *
 * Production never sets the two system properties below, so every URL built
 * here is character-identical to the literals it replaced. The properties
 * exist so a test harness (or a rehearsal container with no network) can point
 * the bot at a local fake server - e.g.
 * {@code -Dihrgstats.telegram.baseUrl=http://127.0.0.1:8081}. Read on every
 * call (cheap: one System.getProperty), never cached, so a test can switch the
 * target between scenarios in one JVM.
 */
public final class ApiEndpoints {

    /** System property overriding the Telegram Bot API base URL. */
    public static final String TELEGRAM_BASE_URL_PROPERTY = "ihrgstats.telegram.baseUrl";

    /** System property overriding the Discord API base URL (including the API version path). */
    public static final String DISCORD_BASE_URL_PROPERTY = "ihrgstats.discord.baseUrl";

    /** Telegram Bot API base URL used when the property is unset or blank. */
    public static final String DEFAULT_TELEGRAM_BASE_URL = "https://api.telegram.org";

    /** Discord API base URL used when the property is unset or blank. */
    public static final String DEFAULT_DISCORD_BASE_URL = "https://discord.com/api/v10";

    private ApiEndpoints() {
    }

    /** The Telegram Bot API base URL, without a trailing slash. */
    public static String telegramBaseUrl() {
        return resolve(TELEGRAM_BASE_URL_PROPERTY, DEFAULT_TELEGRAM_BASE_URL);
    }

    /** The Discord API base URL (with version path), without a trailing slash. */
    public static String discordBaseUrl() {
        return resolve(DISCORD_BASE_URL_PROPERTY, DEFAULT_DISCORD_BASE_URL);
    }

    /**
     * URL of one Bot API method: {@code <base>/bot<token>/<method>}. Any query
     * string is appended by the caller.
     */
    public static String telegramMethodUrl(String botToken, String method) {
        return telegramBaseUrl() + "/bot" + botToken + "/" + method;
    }

    /** Download URL of a file returned by getFile: {@code <base>/file/bot<token>/<filePath>}. */
    public static String telegramFileUrl(String botToken, String filePath) {
        return telegramBaseUrl() + "/file/bot" + botToken + "/" + filePath;
    }

    /** Discord "create message" URL for one channel: {@code <base>/channels/<id>/messages}. */
    public static String discordChannelMessagesUrl(String channelId) {
        return discordBaseUrl() + "/channels/" + channelId + "/messages";
    }

    private static String resolve(String property, String defaultValue) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        value = value.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isEmpty() ? defaultValue : value;
    }
}
