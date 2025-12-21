package com.calplus.ihrgstats.discordbot.profile;

import com.calplus.ihrgstats.discordbot.logs.DiscordLog;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.io.IOException;

/**
 * Discord bot online status manager.
 * Sets the bot's presence and handles connection lifecycle.
 */
public class DiscordOnlineStatus {
    private final DiscordLog discordLogger;
    private JDA jda;
    private String botToken;

    public DiscordOnlineStatus() {
        this.discordLogger = new DiscordLog();
        loadConfig();
    }

    /**
     * Loads the Discord bot token from application.properties
     */
    private void loadConfig() {
        try (var inputStream = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            if (inputStream == null) {
                discordLogger.logError("application.properties file not found");
                throw new RuntimeException("application.properties file not found");
            }

            java.util.Properties properties = new java.util.Properties();
            properties.load(inputStream);

            this.botToken = properties.getProperty("discord.bot.token");

            if (this.botToken == null || this.botToken.isEmpty()) {
                discordLogger.logError("discord.bot.token not found in application.properties");
                throw new RuntimeException("discord.bot.token not found in application.properties");
            }

        } catch (IOException e) {
            discordLogger.logError("Failed to read application.properties: " + e.getMessage());
            throw new RuntimeException("Failed to read application.properties", e);
        }
    }

    /**
     * Starts the Discord bot and sets its online status
     */
    public void start() {
        try {
            discordLogger.logInfo("Starting Discord bot...");

            // Build JDA instance with necessary intents
            jda = JDABuilder.createDefault(botToken)
                    .enableIntents(GatewayIntent.GUILD_MESSAGES)
                    .setStatus(OnlineStatus.ONLINE)
                    .setActivity(Activity.playing("Playing with BMO :D"))
                    .build();

            // Wait for JDA to be ready
            jda.awaitReady();

            discordLogger.logSuccess("Discord bot is now online!");
            discordLogger.logInfo("Logged in as: " + jda.getSelfUser().getAsTag());
            discordLogger.logInfo("Bot ID: " + jda.getSelfUser().getId());
            discordLogger.logInfo("Serving " + jda.getGuilds().size() + " server(s)");

        } catch (InterruptedException e) {
            discordLogger.logError("Bot startup interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            discordLogger.logError("Failed to login to Discord: " + e.getMessage());
            throw new RuntimeException("Failed to start Discord bot", e);
        }
    }

    /**
     * Shuts down the Discord bot
     */
    public void shutdown() {
        if (jda != null) {
            discordLogger.logInfo("Shutting down Discord bot...");
            jda.shutdown();
        }
    }

    /**
     * Gets the JDA instance
     * @return The JDA instance
     */
    public JDA getJDA() {
        return jda;
    }

    /**
     * Main method for standalone execution
     */
    public static void main(String[] args) {
        DiscordOnlineStatus bot = new DiscordOnlineStatus();

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            bot.shutdown();
        }));

        // Start the bot
        bot.start();

        // Keep the application running
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
