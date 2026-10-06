package com.calplus.ihrgstats.e2e;

import com.calplus.ihrgstats.databasemanager.A2_MatchTypes;
import com.calplus.ihrgstats.databasemanager.A3_Halls;
import com.calplus.ihrgstats.databasemanager.B4_Players;
import com.calplus.ihrgstats.databasemanager.D10_RatingTypes;
import com.calplus.ihrgstats.databasemanager.DatabaseSchema;
import com.calplus.ihrgstats.databasemanager.F16_Admins;
import com.calplus.ihrgstats.telegrambot.listener.TelegramListener;
import com.calplus.ihrgstats.telegrambot.utils.RoundCsvProcessor;
import com.calplus.ihrgstats.utils.ApiEndpoints;
import com.calplus.ihrgstats.utils.DatabaseHelper;
import com.calplus.ihrgstats.utils.HttpClientFactory;
import com.calplus.ihrgstats.utils.PropertyResolver;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs the real {@link TelegramListener} against a {@link FakeTelegramServer}
 * inside a sandbox, and puts everything back afterwards.
 *
 * <p>Start: refuses unless the work directory is under java.io.tmpdir; snapshots
 * system properties and static wizard state ({@link StaticState}); installs a
 * {@link HostGuard} that only lets requests reach the fake; points both base-URL
 * properties at the fake; writes a {@code .env.properties} with the fake token
 * into the work directory and makes it {@code user.dir}; copies a template
 * database ({@link Seed}); re-checks every guard; constructs and starts the
 * listener and waits until its first long poll is being held.
 *
 * <p>Close: stops the listener, releases held polls, waits for the polling thread
 * and the workers it spawned to finish, fails on any request to another host,
 * then restores everything. A thread still running at the end is reported in
 * {@link #lingeringThreads()}; the network lockdown stays installed regardless,
 * so nothing started here can reach the internet later.
 */
public final class BotHarness implements AutoCloseable {

    public static final String NOW = "2026-01-01 00:00:00.000";
    /** Fictional Discord bot token used when Discord logging is enabled against the fake. */
    public static final String FAKE_DISCORD_TOKEN = "FAKE-DISCORD-TOKEN";

    /** What the work directory's database starts with. */
    public enum Seed {
        /** No database file at all. */
        NONE,
        /** Schema, numeric halls 1-16 plus fictional HallA/HallB/HallC, rating types, one match type "Standard". */
        REFERENCE,
        /** REFERENCE plus the fictional sample {@code 2001_round_1.csv} ingested (dialogs auto-answered). */
        ROUND_ONE
    }

    /** Scenario configuration; every field has a working default. */
    public static final class Options {
        /** Only {@link FakeTelegramServer#FAKE_TOKEN} or "" (blank, to probe the unconfigured start) are accepted. */
        public String botToken = FakeTelegramServer.FAKE_TOKEN;
        public String adminUserId = "910001";
        /** Empty = the bot accepts any chat (allowAllChannelsProcessing forced on). */
        public String publicChatId = "";
        public String commandsThreadId = "";
        public String fileUploadThreadId = "";
        /** Empty = no status heartbeat and no Telegram log channel. */
        public String devChatId = "";
        public String devLogThreadId = "";
        public String devStatusThreadId = "";
        /** When true, Discord logging is enabled with a fake token and posts to the fake. */
        public boolean discordLogging = false;
        public String discordChannelId = "5550001";
        public String currentYear = "2001";
        public Boolean allowNonAdminUploads = null;
        public Boolean allowAllChannelsProcessing = null;
        public Seed seed = Seed.REFERENCE;
        /** Extra .env.properties entries (KEY=value). */
        public final Map<String, String> extraEnv = new LinkedHashMap<>();
        /** Extra system properties, e.g. HttpClientFactory timeout overrides. */
        public final Map<String, String> systemProperties = new LinkedHashMap<>();
        public Duration startupTimeout = Duration.ofSeconds(15);
        /** Wait for the first held long poll before returning from start(). */
        public boolean awaitFirstPoll = true;
        /** Start the listener at all (false = sandbox + fake only). */
        public boolean startListener = true;
        /** How long close() waits for the polling thread and workers. */
        public Duration shutdownTimeout = Duration.ofSeconds(15);
    }

    private static final Object TEMPLATE_LOCK = new Object();
    private static final Map<Seed, Path> TEMPLATES = new EnumMap<>(Seed.class);

    private final FakeTelegramServer fake;
    private final Path workDir;
    private final Options options;
    private final StaticState saved;
    private final HostGuard guard;
    private final Path projectDir;
    private TelegramListener listener;
    private Set<Thread> threadsBefore = Set.of();
    private final List<Thread> scenarioThreads = new ArrayList<>();
    private final List<String> lingering = new ArrayList<>();
    private boolean closed;

    private BotHarness(FakeTelegramServer fake, Path workDir, Options options) throws Exception {
        this.fake = fake;
        this.workDir = workDir;
        this.options = options;
        this.projectDir = projectDir();
        requireTempFolder(workDir);
        this.saved = StaticState.saveAndClear();
        HostGuard g = null;
        try {
            g = HostGuard.install(fake.authority());
            this.guard = g;
            prepare();
        } catch (Throwable t) {
            if (g != null) {
                g.close();
            }
            saved.restore();
            throw t;
        }
    }

    /** Builds the sandbox and (by default) starts the listener. Close it in a finally / try-with-resources. */
    public static BotHarness start(FakeTelegramServer fake, Path workDir, Options options) throws Exception {
        BotHarness h = new BotHarness(fake, workDir, options);
        try {
            if (options.startListener) {
                h.startListener();
            }
            return h;
        } catch (Throwable t) {
            h.close();
            throw t;
        }
    }

    public static BotHarness start(FakeTelegramServer fake, Path workDir) throws Exception {
        return start(fake, workDir, new Options());
    }

    private void prepare() throws Exception {
        // Leftover config from other tests must not leak in.
        for (String key : new ArrayList<>(System.getProperties().stringPropertyNames())) {
            if (key.startsWith("TELEGRAM_") || key.startsWith("DISCORD_") || key.startsWith("SETTINGS_")
                    || key.startsWith("INTERNET_") || key.startsWith("ihrgstats.")) {
                System.clearProperty(key);
            }
        }
        System.setProperty(ApiEndpoints.TELEGRAM_BASE_URL_PROPERTY, fake.baseUrl());
        System.setProperty(ApiEndpoints.DISCORD_BASE_URL_PROPERTY, fake.discordBaseUrl());
        for (Map.Entry<String, String> e : options.systemProperties.entrySet()) {
            System.setProperty(e.getKey(), e.getValue());
        }

        Map<String, String> env = new LinkedHashMap<>();
        if (!options.botToken.isEmpty() && !options.botToken.equals(fake.token())) {
            throw new IllegalStateException("REFUSING to run: Options.botToken must be the fake token or blank");
        }
        env.put("TELEGRAM_BOT_TOKEN", options.botToken);
        env.put("TELEGRAM_ADMIN_USERID", options.adminUserId);
        env.put("TELEGRAM_PUBLIC_CHATID", options.publicChatId);
        env.put("TELEGRAM_PUBLIC_CHATID_COMMANDS", options.commandsThreadId);
        env.put("TELEGRAM_PUBLIC_CHATID_FILEUPLOAD", options.fileUploadThreadId);
        env.put("TELEGRAM_DEV_CHATID", options.devChatId);
        env.put("TELEGRAM_DEV_CHATID_LOG", options.devLogThreadId);
        env.put("TELEGRAM_DEV_CHATID_STATUS", options.devStatusThreadId);
        env.put("DISCORD_BOT_TOKEN", options.discordLogging ? FAKE_DISCORD_TOKEN : "");
        env.put("DISCORD_LOG_CHANNELID", options.discordLogging ? options.discordChannelId : "");
        env.put("DISCORD_ADMIN_USERID", "");
        env.put("SETTINGS_CURRENTYEAR", options.currentYear == null ? "" : options.currentYear);
        if (options.allowNonAdminUploads != null) {
            env.put("SETTINGS_ALLOWNONADMINUPLOADS", options.allowNonAdminUploads.toString());
        }
        if (options.allowAllChannelsProcessing != null) {
            env.put("SETTINGS_ALLOWALLCHANNELSPROCESSING", options.allowAllChannelsProcessing.toString());
        }
        env.putAll(options.extraEnv);
        StringBuilder envFile = new StringBuilder("# written by BotHarness - fake token only\n");
        for (Map.Entry<String, String> e : env.entrySet()) {
            envFile.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        Files.createDirectories(workDir);
        Files.writeString(workDir.resolve(".env.properties"), envFile.toString(), StandardCharsets.UTF_8);
        System.setProperty("user.dir", workDir.toString());
        // The listener constructor loads the env file into system properties;
        // values are also set here so DAO seeding below sees the same config.
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (!e.getValue().isEmpty()) {
                System.setProperty(e.getKey(), e.getValue());
            }
        }

        if (options.seed != Seed.NONE) {
            Path template = template(options.seed);
            Path db = DatabaseHelper.getDefaultDatabasePath();
            Files.createDirectories(db.getParent());
            Files.copy(template, db, StandardCopyOption.REPLACE_EXISTING);
            if (!options.adminUserId.isEmpty()) {
                new F16_Admins().addAdmin(F16_Admins.PLATFORM_TELEGRAM, options.adminUserId, null, NOW);
            }
        }
        requireSandbox();
    }

    /** Throws unless every safety condition holds. Called before the listener exists and again before start(). */
    public void requireSandbox() {
        requireTempFolder(workDir);
        if (!workDir.toString().equals(System.getProperty("user.dir"))) {
            throw new IllegalStateException("REFUSING to run: user.dir is not the sandbox work directory");
        }
        String token = PropertyResolver.getProperty("telegram.bot.token", "");
        boolean blankAllowed = token.isEmpty() && options.botToken.isEmpty();
        if (!FakeTelegramServer.FAKE_TOKEN.equals(token) && !blankAllowed) {
            throw new IllegalStateException("REFUSING to run: the resolved bot token is not the known fake token");
        }
        String discordToken = PropertyResolver.getProperty("discord.bot.token", "");
        if (!discordToken.isEmpty() && !FAKE_DISCORD_TOKEN.equals(discordToken)) {
            throw new IllegalStateException("REFUSING to run: the resolved Discord token is not the known fake token");
        }
        URI tg = URI.create(ApiEndpoints.telegramBaseUrl());
        URI dc = URI.create(ApiEndpoints.discordBaseUrl());
        if (!ApiEndpoints.telegramBaseUrl().equals(fake.baseUrl()) || !isLoopback(tg)) {
            throw new IllegalStateException("REFUSING to run: Telegram base URL is not the fake (" + tg + ")");
        }
        if (!ApiEndpoints.discordBaseUrl().equals(fake.discordBaseUrl()) || !isLoopback(dc)) {
            throw new IllegalStateException("REFUSING to run: Discord base URL is not the fake (" + dc + ")");
        }
        if (!(ProxySelector.getDefault() instanceof HostGuard)) {
            throw new IllegalStateException("REFUSING to run: the host guard is not installed");
        }
    }

    private static boolean isLoopback(URI uri) {
        String h = uri.getHost();
        return h != null && (h.equals("127.0.0.1") || h.equals("localhost") || h.equals("[::1]") || h.equals("::1"));
    }

    /** Throws unless {@code dir} is inside java.io.tmpdir. */
    public static void requireTempFolder(Path dir) {
        try {
            Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            Files.createDirectories(dir);
            Path real = dir.toRealPath();
            if (!real.startsWith(tmp) || real.equals(tmp)) {
                throw new IllegalStateException("REFUSING to run: work directory " + real + " is not inside java.io.tmpdir " + tmp);
            }
        } catch (IOException e) {
            throw new IllegalStateException("REFUSING to run: cannot resolve work directory", e);
        }
    }

    private void startListener() {
        threadsBefore = new HashSet<>(Thread.getAllStackTraces().keySet());
        listener = new TelegramListener();
        requireSandbox();
        listener.start();
        if (options.awaitFirstPoll && !fake.awaitPollHeld(options.startupTimeout)) {
            throw new AssertionError("the listener never started long polling within " + options.startupTimeout.toMillis()
                    + " ms; calls: " + fake.calls());
        }
    }

    // ------------------------------------------------------------------ accessors

    public FakeTelegramServer fake() {
        return fake;
    }

    public TelegramListener listener() {
        return listener;
    }

    public Path workDir() {
        return workDir;
    }

    public Options options() {
        return options;
    }

    public HostGuard guard() {
        return guard;
    }

    /** The project directory (where SAMPLE FILES and target live). */
    public Path projectDirectory() {
        return projectDir;
    }

    /** Threads started during the scenario that were still alive after close() waited for them. */
    public List<String> lingeringThreads() {
        return new ArrayList<>(lingering);
    }

    /** A driver for one user in one chat. */
    public ConversationDriver driver(ConversationDriver.User user, ConversationDriver.Chat chat) {
        return new ConversationDriver(fake, user, chat);
    }

    /** Writes the transcript to target/e2e-transcripts/&lt;name&gt;.txt and returns the path. */
    public Path writeTranscript(String name) throws IOException {
        return TranscriptWriter.write(projectDir.resolve("target").resolve("e2e-transcripts"), name, fake);
    }

    // ------------------------------------------------------------------ lifecycle

    /** Stops the listener and waits for its polling thread and workers (does not restore anything). */
    public void stopListener() {
        if (listener == null) {
            return;
        }
        listener.stop();
        fake.releasePolls();
        long deadline = System.nanoTime() + options.shutdownTimeout.toNanos();
        for (Thread t : newThreads()) {
            long left = deadline - System.nanoTime();
            if (left > 0) {
                try {
                    t.join(Math.max(1, left / 1_000_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (t.isAlive()) {
                lingering.add(t.getName() + " (" + t.getState() + ", daemon=" + t.isDaemon() + ")");
            }
        }
        listener = null;
    }

    /** Threads created since the listener was constructed, excluding JDK/infrastructure ones. */
    private List<Thread> newThreads() {
        List<Thread> out = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (threadsBefore.contains(t) || t == Thread.currentThread()) {
                continue;
            }
            String n = t.getName();
            if (n.startsWith("HttpClient-") || n.startsWith("ForkJoinPool") || n.startsWith("fake-telegram-")
                    || n.startsWith("host-guard") || n.startsWith("process reaper") || n.startsWith("Common-Cleaner")
                    || n.startsWith("Java2D") || n.startsWith("AWT-") || n.startsWith("Image")) {
                continue;
            }
            out.add(t);
        }
        scenarioThreads.addAll(out);
        return out;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        AssertionError guardFailure = null;
        try {
            stopListener();
            try {
                guard.assertNoViolations();
            } catch (AssertionError e) {
                guardFailure = e;
            }
        } finally {
            boolean anyAlive = scenarioThreads.stream().anyMatch(Thread::isAlive);
            guard.close();
            saved.restore();
            if (anyAlive) {
                // Something from this scenario still runs: keep its URLs pointed at a dead
                // loopback port so it can never fall back to the real API hosts.
                System.setProperty(ApiEndpoints.TELEGRAM_BASE_URL_PROPERTY, "http://127.0.0.1:9");
                System.setProperty(ApiEndpoints.DISCORD_BASE_URL_PROPERTY, "http://127.0.0.1:9");
                System.err.println("[BotHarness] threads still running after close: " + lingering);
            }
        }
        if (guardFailure != null) {
            throw guardFailure;
        }
    }

    // ------------------------------------------------------------------ templates

    /** The project directory: Maven's basedir, else the user.dir the JVM started with. */
    static Path projectDir() {
        String basedir = System.getProperty("basedir");
        return Path.of(basedir != null ? basedir : System.getProperty("user.dir")).toAbsolutePath();
    }

    /** A fictional sample file from SAMPLE FILES/ (read-only; copy it before changing anything). */
    public static Path sampleFile(String name) {
        Path p = projectDir().resolve("SAMPLE FILES").resolve(name);
        if (!Files.isRegularFile(p)) {
            throw new IllegalStateException("sample file not found: " + p);
        }
        return p;
    }

    private static Path template(Seed seed) throws Exception {
        synchronized (TEMPLATE_LOCK) {
            Path cached = TEMPLATES.get(seed);
            if (cached != null && Files.exists(cached)) {
                return cached;
            }
            Path dir = Files.createTempDirectory("ihrg-e2e-template-" + seed.name().toLowerCase());
            String userDir = System.getProperty("user.dir");
            String admin = System.getProperty("TELEGRAM_ADMIN_USERID");
            System.clearProperty("TELEGRAM_ADMIN_USERID");
            Path sample = seed == Seed.ROUND_ONE ? sampleFile("2001_round_1.csv") : null;
            try {
                System.setProperty("user.dir", dir.toString());
                new DatabaseSchema().createDatabase("default.db");
                new A3_Halls().seedDefaults(NOW);
                // Only numeric and fictional halls: drop the seeded named halls.
                execute("DELETE FROM halls WHERE hall_code NOT GLOB '[0-9][0-9]' AND hall_code <> ?", A3_Halls.UNKNOWN_HALL_CODE);
                insertHall("HA", "HallA");
                insertHall("HB", "HallB");
                insertHall("HC", "HallC");
                new B4_Players().seedDefaults(NOW);
                new D10_RatingTypes().seedDefaults(NOW);
                new A2_MatchTypes().createMatchType("Standard", 370.0, null, "Fictional test match type", NOW);
                if (seed == Seed.ROUND_ONE) {
                    Path copy = dir.resolve("2001_round_1.csv");
                    Files.copy(sample, copy);
                    RoundCsvProcessor processor = new RoundCsvProcessor();
                    processor.setMultiChoiceCallback((message, options) -> autoAnswer(message, options));
                    if (!processor.processRound(copy.toString(), 2001, 1, NOW)) {
                        throw new IllegalStateException("template ingest of 2001_round_1.csv failed");
                    }
                    Files.deleteIfExists(copy);
                }
            } finally {
                System.setProperty("user.dir", userDir);
                if (admin != null) {
                    System.setProperty("TELEGRAM_ADMIN_USERID", admin);
                }
            }
            Path db = dir.resolve("database").resolve("core").resolve("default.db");
            TEMPLATES.put(seed, db);
            return db;
        }
    }

    /** Answers ingest dialogs the way the corpus battery does: walkover type 0, identity questions "different". */
    static int autoAnswer(String message, String[] options) {
        if (message.startsWith("⚠️ This round contains a WALKOVER")) {
            return 0;
        }
        for (int i = 0; i < options.length; i++) {
            String o = options[i].toLowerCase();
            if (o.contains("different") || o.contains("reprocess")) {
                return i;
            }
        }
        return 0;
    }

    private static void execute(String sql, String arg) throws Exception {
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, arg);
            ps.executeUpdate();
        }
    }

    private static void insertHall(String code, String name) throws Exception {
        String sql = "INSERT INTO halls (hall_code, hall_name, next_player_seq, created_dttm, updated_dttm) VALUES (?, ?, 1, ?, ?)";
        try (Connection conn = DatabaseHelper.getDefaultConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            ps.setString(2, name);
            ps.setString(3, NOW);
            ps.setString(4, NOW);
            ps.executeUpdate();
        }
    }

    /** Convenience: the four HttpClientFactory timeout override property names. */
    public static final List<String> TIMEOUT_PROPERTIES = List.of(
            HttpClientFactory.CONNECT_TIMEOUT_PROPERTY, HttpClientFactory.REQUEST_TIMEOUT_PROPERTY,
            HttpClientFactory.LONG_POLL_TIMEOUT_PROPERTY, HttpClientFactory.FILE_TRANSFER_TIMEOUT_PROPERTY);
}
