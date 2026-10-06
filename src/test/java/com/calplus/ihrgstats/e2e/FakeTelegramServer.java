package com.calplus.ihrgstats.e2e;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * A local, in-process stand-in for the Telegram Bot API (and the one Discord
 * endpoint the log channel uses), built on the JDK's {@code com.sun.net.httpserver}.
 *
 * <ul>
 *   <li>Binds 127.0.0.1 on port 0; {@link #baseUrl()} / {@link #discordBaseUrl()} are
 *       what the {@code ihrgstats.telegram.baseUrl} / {@code ihrgstats.discord.baseUrl}
 *       system properties must be set to.</li>
 *   <li>Handlers run on an explicit cached thread pool, so a getUpdates long poll held
 *       for up to 30 s never blocks a concurrent sendMessage.</li>
 *   <li>Every request is recorded as a {@link RecordedCall}, checked against
 *       {@link BotApiRules}, and - unless {@link #setRejectInvalid(boolean) disabled} -
 *       answered with the 400 Telegram would send when a rule is broken.</li>
 *   <li>Updates are queued with {@link #enqueueUpdate(JsonObject)} and served with
 *       Telegram's offset semantics; an enqueue wakes a held poll immediately.</li>
 *   <li>Scripted failures per method: {@link #failNext}, {@link #failTimes},
 *       {@link #failAlways}, {@link #failWhen}; see {@link Fault}.</li>
 *   <li>Unknown methods are recorded and answered {"ok":true,"result":true}
 *       (or 404 like Telegram after {@link #setRejectUnknownMethods(boolean)}).</li>
 * </ul>
 */
public final class FakeTelegramServer implements AutoCloseable {

    /** The only token the harness accepts. Never a real one. */
    public static final String FAKE_TOKEN = "000000:TEST";
    public static final long BOT_USER_ID = 999000L;
    public static final String BOT_USERNAME = "fake_ihrg_test_bot";

    /** Pseudo method names for non-Bot-API routes. */
    public static final String DISCORD_CREATE_MESSAGE = "discord.createMessage";
    public static final String FILE_DOWNLOAD = "file.download";
    public static final String UNKNOWN_PATH = "unknown-path";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final long MAX_DOWNLOAD_BYTES = 20L * 1024 * 1024;
    private static final Set<String> CHAT_ACTIONS = Set.of("typing", "upload_photo", "record_video", "upload_video",
            "record_voice", "upload_voice", "upload_document", "choose_sticker", "find_location",
            "record_video_note", "upload_video_note");

    /** One event the driver injected (for transcripts). */
    public record Inbound(long seq, Instant at, long updateId, JsonObject update) {
    }

    private record StoredFile(String fileId, String fileUniqueId, String fileName, String filePath, byte[] bytes) {
    }

    private static final class FaultRule {
        final String method;
        final Predicate<RecordedCall> when;
        final Fault fault;
        int remaining; // < 0 = unlimited

        FaultRule(String method, Predicate<RecordedCall> when, Fault fault, int remaining) {
            this.method = method;
            this.when = when;
            this.fault = fault;
            this.remaining = remaining;
        }
    }

    /** Answer the fake sends: status, JSON or raw body, and the Message created (if any). */
    private static final class Reply {
        int status;
        byte[] body;
        String contentType = "application/json";
        JsonObject result;
    }

    private final HttpServer server;
    private final ExecutorService pool;
    private final String token;
    private final AtomicLong seq = new AtomicLong();
    private final List<RecordedCall> calls = new ArrayList<>();
    private final List<Inbound> inbound = new CopyOnWriteArrayList<>();
    private final List<RecordedCall.Violation> violations = new CopyOnWriteArrayList<>();

    private final Object updateLock = new Object();
    private final List<JsonObject> updateQueue = new ArrayList<>();
    private long nextUpdateId = 100001;
    private long pollGeneration = 0;
    private int heldPolls = 0;
    private boolean pollsReleased = false;
    private volatile boolean conflictOnOverlappingPolls = true;
    private volatile long maxHoldMs = 30_000;
    private volatile String webhookUrl = "";

    private final AtomicLong nextMessageId = new AtomicLong(1);
    private final AtomicInteger nextFileNo = new AtomicInteger(1);
    private final Map<String, JsonObject> messages = new ConcurrentHashMap<>();
    private final Set<String> knownCallbackIds = ConcurrentHashMap.newKeySet();
    private final Set<String> answeredCallbackIds = ConcurrentHashMap.newKeySet();
    private final Map<String, StoredFile> filesById = new ConcurrentHashMap<>();
    private final Map<String, StoredFile> filesByPath = new ConcurrentHashMap<>();
    private final Map<String, JsonObject> chats = new ConcurrentHashMap<>();
    private volatile JsonArray myCommands = new JsonArray();
    private final List<FaultRule> faultRules = new ArrayList<>();

    private volatile boolean rejectInvalid = true;
    private volatile boolean rejectUnknownMethods = false;
    private volatile boolean closed = false;
    private final CountDownLatch closedLatch = new CountDownLatch(1);

    private FakeTelegramServer(String token) throws IOException {
        this.token = token;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicInteger threadNo = new AtomicInteger();
        this.pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-telegram-" + threadNo.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
    }

    /** Starts a fake that accepts {@link #FAKE_TOKEN}. */
    public static FakeTelegramServer start() throws IOException {
        return new FakeTelegramServer(FAKE_TOKEN);
    }

    // ------------------------------------------------------------------ addresses

    public int port() {
        return server.getAddress().getPort();
    }

    /** "host:port" of the fake, as the host guard allows it. */
    public String authority() {
        return server.getAddress().getAddress().getHostAddress() + ":" + port();
    }

    /** Value for {@code ihrgstats.telegram.baseUrl}. */
    public String baseUrl() {
        return "http://" + authority();
    }

    /** Value for {@code ihrgstats.discord.baseUrl}. */
    public String discordBaseUrl() {
        return baseUrl() + "/discord/api/v10";
    }

    public String token() {
        return token;
    }

    // ------------------------------------------------------------------ configuration

    /** When true (default) a broken rule is answered with Telegram's 400; when false it is only recorded. */
    public void setRejectInvalid(boolean reject) {
        this.rejectInvalid = reject;
    }

    /** When true unknown methods get Telegram's 404; default false (recorded, answered ok). */
    public void setRejectUnknownMethods(boolean reject) {
        this.rejectUnknownMethods = reject;
    }

    /** Upper bound on how long a getUpdates is held (default 30 s, i.e. the bot's own timeout=30). */
    public void setMaxLongPollHold(Duration hold) {
        this.maxHoldMs = hold.toMillis();
    }

    /** When true (default) a new poll arriving while another is held ends the older one with 409, like Telegram. */
    public void setConflictOnOverlappingPolls(boolean conflict) {
        this.conflictOnOverlappingPolls = conflict;
    }

    /** Ends every held poll now and answers all later polls immediately (used before stopping the bot). */
    public void releasePolls() {
        synchronized (updateLock) {
            pollsReleased = true;
            updateLock.notifyAll();
        }
    }

    /** Undoes {@link #releasePolls()}. */
    public void resumePolls() {
        synchronized (updateLock) {
            pollsReleased = false;
        }
    }

    // ------------------------------------------------------------------ faults

    public void failNext(String method, Fault fault) {
        failTimes(method, 1, fault);
    }

    public void failTimes(String method, int times, Fault fault) {
        failWhen(method, c -> true, fault, times);
    }

    public void failAlways(String method, Fault fault) {
        failWhen(method, c -> true, fault, -1);
    }

    /** Applies {@code fault} to the next {@code times} calls of {@code method} matching {@code when} (times &lt; 0 = always). */
    public void failWhen(String method, Predicate<RecordedCall> when, Fault fault, int times) {
        synchronized (faultRules) {
            faultRules.add(new FaultRule(method, when, fault, times));
        }
    }

    public void clearFaults() {
        synchronized (faultRules) {
            faultRules.clear();
        }
    }

    private Fault takeFault(RecordedCall probe) {
        synchronized (faultRules) {
            Iterator<FaultRule> it = faultRules.iterator();
            while (it.hasNext()) {
                FaultRule rule = it.next();
                if (!rule.method.equalsIgnoreCase(probe.method) || !rule.when.test(probe)) {
                    continue;
                }
                if (rule.remaining > 0 && --rule.remaining == 0) {
                    it.remove();
                }
                return rule.fault;
            }
            return null;
        }
    }

    // ------------------------------------------------------------------ driver side

    /** Queues one update (update_id is assigned here and returned) and wakes a held poll. */
    public long enqueueUpdate(JsonObject update) {
        JsonObject copy = update.deepCopy();
        long id;
        synchronized (updateLock) {
            id = nextUpdateId++;
            copy.addProperty("update_id", id);
            // Sequenced before any poll can deliver it, so transcripts show cause before effect.
            inbound.add(new Inbound(seq.incrementAndGet(), Instant.now(), id, copy));
            updateQueue.add(copy);
            updateLock.notifyAll();
        }
        return id;
    }

    /** Updates queued and not yet confirmed by a later offset. */
    public int pendingUpdates() {
        synchronized (updateLock) {
            return updateQueue.size();
        }
    }

    /** Stores a file the bot can fetch with getFile + download; returns its file_id. */
    public String registerFile(String fileName, byte[] bytes) {
        int no = nextFileNo.getAndIncrement();
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : "";
        StoredFile f = new StoredFile("FAKEFILE" + no, "FAKEUNIQ" + no, fileName, "documents/file_" + no + ext, bytes.clone());
        filesById.put(f.fileId(), f);
        filesByPath.put(f.filePath(), f);
        return f.fileId();
    }

    /** Marks a callback_query id as issued, so answerCallbackQuery accepts it once. */
    public void registerCallbackId(String id) {
        knownCallbackIds.add(id);
    }

    /** Overrides what getChat answers for one chat id. */
    public void registerChat(String chatId, JsonObject chat) {
        chats.put(chatId, chat.deepCopy());
    }

    /** The current state of a message the fake created (keyboard edits applied), or null. */
    public JsonObject message(String chatId, long messageId) {
        JsonObject m = messages.get(chatId + "/" + messageId);
        return m == null ? null : m.deepCopy();
    }

    public JsonArray myCommands() {
        return myCommands.deepCopy();
    }

    public String webhookUrl() {
        return webhookUrl;
    }

    // ------------------------------------------------------------------ inspection

    public List<RecordedCall> calls() {
        synchronized (calls) {
            return new ArrayList<>(calls);
        }
    }

    public int callCount() {
        synchronized (calls) {
            return calls.size();
        }
    }

    public List<Inbound> inbound() {
        return new ArrayList<>(inbound);
    }

    public List<RecordedCall.Violation> violations() {
        return new ArrayList<>(violations);
    }

    /** Violations Telegram would have rejected (excludes notes). */
    public List<RecordedCall.Violation> rejections() {
        return violations.stream().filter(RecordedCall.Violation::rejected).toList();
    }

    public List<RecordedCall> unknownMethodCalls() {
        return calls().stream().filter(c -> c.unknownMethod).toList();
    }

    public int heldPolls() {
        synchronized (updateLock) {
            return heldPolls;
        }
    }

    /** First call at index &gt;= {@code from} matching {@code p}, if already recorded. */
    public Optional<RecordedCall> findCall(int from, Predicate<RecordedCall> p) {
        synchronized (calls) {
            for (int i = Math.max(0, from); i < calls.size(); i++) {
                if (p.test(calls.get(i))) {
                    return Optional.of(calls.get(i));
                }
            }
            return Optional.empty();
        }
    }

    /** Index in {@link #calls()} of a recorded call. */
    public int indexOf(RecordedCall call) {
        synchronized (calls) {
            return calls.indexOf(call);
        }
    }

    /**
     * Waits for the first call at index &gt;= {@code from} matching {@code p}.
     * @throws AssertionError on timeout, listing the calls recorded since {@code from}
     */
    public RecordedCall awaitCall(int from, Predicate<RecordedCall> p, Duration timeout, String what) {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (calls) {
            while (true) {
                for (int i = Math.max(0, from); i < calls.size(); i++) {
                    if (p.test(calls.get(i))) {
                        return calls.get(i);
                    }
                }
                long waitNanos = deadline - System.nanoTime();
                if (waitNanos <= 0) {
                    StringBuilder sb = new StringBuilder("timed out after " + timeout.toMillis() + " ms waiting for " + what
                            + "; calls since index " + from + ":");
                    for (int i = Math.max(0, from); i < calls.size(); i++) {
                        RecordedCall c = calls.get(i);
                        if (!(c.method.equals("getUpdates") && c.isOk())) {
                            sb.append("\n  ").append(c);
                        }
                    }
                    throw new AssertionError(sb.toString());
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(calls, waitNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted waiting for " + what);
                }
            }
        }
    }

    /** Waits until a getUpdates is being held (the bot is idle and listening). */
    public boolean awaitPollHeld(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (updateLock) {
            while (heldPolls == 0) {
                long waitNanos = deadline - System.nanoTime();
                if (waitNanos <= 0) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(updateLock, waitNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ HTTP handling

    private void handle(HttpExchange ex) {
        try {
            handleExchange(ex);
        } catch (Throwable t) {
            System.err.println("[FakeTelegramServer] handler error: " + t);
            t.printStackTrace();
            try {
                byte[] b = "{\"ok\":false,\"error_code\":500,\"description\":\"fake server error\"}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(500, b.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(b);
                }
            } catch (Throwable ignored) {
                ex.close();
            }
        }
    }

    private void handleExchange(HttpExchange ex) throws IOException {
        Instant at = Instant.now();
        String rawPath = ex.getRequestURI().getRawPath();
        String rawQuery = ex.getRequestURI().getRawQuery();
        byte[] body = ex.getRequestBody().readAllBytes();
        Map<String, String> headers = pickHeaders(ex.getRequestHeaders());

        String method;
        String tokenInPath = null;
        String filePath = null;
        String discordChannel = null;
        if (rawPath.startsWith("/bot")) {
            String rest = rawPath.substring(4);
            int slash = rest.indexOf('/');
            tokenInPath = urlDecode(slash < 0 ? rest : rest.substring(0, slash));
            method = slash < 0 ? "" : rest.substring(slash + 1);
        } else if (rawPath.startsWith("/file/bot")) {
            String rest = rawPath.substring(9);
            int slash = rest.indexOf('/');
            tokenInPath = urlDecode(slash < 0 ? rest : rest.substring(0, slash));
            filePath = slash < 0 ? "" : urlDecode(rest.substring(slash + 1));
            method = FILE_DOWNLOAD;
        } else if (rawPath.startsWith("/discord/api/v10/channels/") && rawPath.endsWith("/messages")) {
            discordChannel = rawPath.substring("/discord/api/v10/channels/".length(), rawPath.length() - "/messages".length());
            method = DISCORD_CREATE_MESSAGE;
        } else {
            method = UNKNOWN_PATH;
        }
        String redactedPath = rawPath.replaceFirst("^/bot[^/]*", "/bot<TOKEN>").replaceFirst("^/file/bot[^/]*", "/file/bot<TOKEN>");

        // ---- parameters
        JsonObject params = new JsonObject();
        List<RecordedCall.FilePart> files = new ArrayList<>();
        List<RecordedCall.Violation> v = new ArrayList<>();
        RecordedCall.Source source = RecordedCall.Source.NONE;
        if (rawQuery != null && !rawQuery.isEmpty()) {
            parseForm(rawQuery, params);
            source = RecordedCall.Source.QUERY;
        }
        String contentType = headers.getOrDefault("Content-Type", "");
        String ctLower = contentType.toLowerCase(Locale.ROOT);
        if (body.length > 0) {
            try {
                if (ctLower.startsWith("application/json")) {
                    JsonElement parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
                    if (!parsed.isJsonObject()) {
                        throw new IllegalArgumentException("JSON body is not an object");
                    }
                    for (Map.Entry<String, JsonElement> e : parsed.getAsJsonObject().entrySet()) {
                        params.add(e.getKey(), e.getValue());
                    }
                    source = RecordedCall.Source.JSON;
                } else if (ctLower.startsWith("application/x-www-form-urlencoded")) {
                    parseForm(new String(body, StandardCharsets.UTF_8), params);
                    source = RecordedCall.Source.FORM;
                } else if (ctLower.startsWith("multipart/form-data")) {
                    parseMultipart(body, boundaryOf(contentType), params, files);
                    source = RecordedCall.Source.MULTIPART;
                } else {
                    v.add(new RecordedCall.Violation(method, "content-type", "body with unsupported Content-Type '" + contentType + "'", true));
                }
            } catch (RuntimeException e) {
                v.add(new RecordedCall.Violation(method, "malformed-body", e.getMessage(), true));
            }
        }
        if (discordChannel != null) {
            params.addProperty("channel_id", discordChannel);
        }

        RecordedCall probe = new RecordedCall(0, at, method, ex.getRequestMethod(), redactedPath, source, params, files,
                headers, 0, null, null, v, null, false, false);

        // ---- token
        if (tokenInPath != null && !token.equals(tokenInPath)) {
            Reply r = error(401, "Unauthorized");
            finish(ex, probe, r, null, false, true);
            return;
        }

        // ---- scripted faults
        Fault fault = takeFault(probe);
        if (fault != null) {
            if (fault.delayMs > 0) {
                sleepUnlessClosed(fault.delayMs);
            }
            switch (fault.kind) {
                case RESPOND -> {
                    Reply r = new Reply();
                    r.status = fault.status;
                    r.body = fault.body == null ? new byte[0] : fault.body.getBytes(StandardCharsets.UTF_8);
                    finish(ex, probe, r, fault, false, false);
                    return;
                }
                case DROP_CONNECTION -> {
                    record(probe, -1, null, null, fault, false, false);
                    ex.close();
                    return;
                }
                case HANG -> {
                    try {
                        closedLatch.await(10, TimeUnit.MINUTES);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    record(probe, -1, null, null, fault, false, false);
                    ex.close();
                    return;
                }
                case DELAY -> {
                    // fall through to normal handling
                }
            }
        }

        // ---- dispatch
        if (!v.isEmpty()) {
            finish(ex, probe, error(400, "Bad Request: " + v.get(0).detail()), fault, false, false);
            return;
        }
        boolean unknown = false;
        Reply reply;
        switch (method.toLowerCase(Locale.ROOT)) {
            case "getupdates" -> reply = getUpdates(params);
            case "sendmessage" -> reply = sendMessage(method, params, source, v);
            case "sendphoto" -> reply = sendMedia(method, "photo", params, source, files, v);
            case "senddocument" -> reply = sendMedia(method, "document", params, source, files, v);
            case "editmessagereplymarkup" -> reply = editMessageReplyMarkup(method, params, source, v);
            case "editmessagetext" -> reply = editMessageText(method, params, source, v);
            case "deletemessage" -> reply = deleteMessage(method, params, source, v);
            case "answercallbackquery" -> reply = answerCallbackQuery(method, params, v);
            case "getfile" -> reply = getFile(params);
            case "getchat" -> reply = getChat(params);
            case "getme" -> reply = ok(botUser());
            case "sendchataction" -> reply = sendChatAction(method, params, source, v);
            case "setmycommands" -> reply = setMyCommands(method, params, v);
            case "setwebhook" -> {
                webhookUrl = param(params, "url") == null ? "" : param(params, "url");
                reply = ok(new JsonPrimitive(true));
            }
            case "deletewebhook" -> {
                webhookUrl = "";
                reply = ok(new JsonPrimitive(true));
            }
            case "file.download" -> reply = download(filePath);
            case "discord.createmessage" -> reply = discordCreateMessage(method, params, headers, v);
            default -> {
                unknown = true;
                v.add(new RecordedCall.Violation(method, "unknown-method", "method '" + method + "' is not implemented by the fake", false));
                reply = rejectUnknownMethods ? error(404, "Not Found") : ok(new JsonPrimitive(true));
            }
        }
        finish(ex, probe, reply, fault, unknown, false);
    }

    private void finish(HttpExchange ex, RecordedCall probe, Reply r, Fault fault, boolean unknown, boolean wrongToken) throws IOException {
        record(probe, r.status, r.body, r.result, fault, unknown, wrongToken);
        ex.getResponseHeaders().set("Content-Type", r.contentType);
        if (r.body.length == 0) {
            ex.sendResponseHeaders(r.status, -1);
            ex.close();
            return;
        }
        ex.sendResponseHeaders(r.status, r.body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(r.body);
        }
    }

    private void record(RecordedCall probe, int status, byte[] body, JsonObject result, Fault fault, boolean unknown, boolean wrongToken) {
        String bodyText = body == null ? null
                : (probe.method.equals(FILE_DOWNLOAD) && status == 200 ? "<" + body.length + " bytes>" : new String(body, StandardCharsets.UTF_8));
        List<RecordedCall.Violation> v = new ArrayList<>(probe.violations);
        violations.addAll(v);
        synchronized (calls) {
            RecordedCall call = new RecordedCall(seq.incrementAndGet(), probe.at, probe.method, probe.httpMethod, probe.path,
                    probe.source, probe.params, new ArrayList<>(probe.files), new LinkedHashMap<>(probe.headers), status,
                    bodyText, result, v, fault, unknown, wrongToken);
            calls.add(call);
            calls.notifyAll();
        }
    }

    // ------------------------------------------------------------------ methods

    private Reply getUpdates(JsonObject params) {
        long offset = longParam(params, "offset", 0);
        int limit = (int) Math.max(1, Math.min(100, longParam(params, "limit", 100)));
        long timeoutSec = Math.max(0, longParam(params, "timeout", 0));
        if (!webhookUrl.isEmpty()) {
            return error(409, "Conflict: can't use getUpdates method while webhook is active; use deleteWebhook to delete the webhook first");
        }
        long holdMs = Math.min(timeoutSec * 1000, maxHoldMs);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(holdMs);
        synchronized (updateLock) {
            long myGeneration = ++pollGeneration;
            if (heldPolls > 0) {
                updateLock.notifyAll(); // an older held poll learns it was superseded
            }
            while (true) {
                if (offset < 0) {
                    int keep = (int) Math.min(Integer.MAX_VALUE, -offset);
                    while (updateQueue.size() > keep) {
                        updateQueue.remove(0);
                    }
                } else {
                    updateQueue.removeIf(u -> u.get("update_id").getAsLong() < offset);
                }
                JsonArray out = new JsonArray();
                for (JsonObject u : updateQueue) {
                    if (out.size() >= limit) {
                        break;
                    }
                    out.add(u.deepCopy());
                }
                if (out.size() > 0 || holdMs <= 0 || pollsReleased || closed) {
                    return ok(out);
                }
                if (conflictOnOverlappingPolls && myGeneration != pollGeneration) {
                    return error(409, "Conflict: terminated by other getUpdates request; make sure that only one bot instance is running");
                }
                long waitNanos = deadline - System.nanoTime();
                if (waitNanos <= 0) {
                    return ok(out);
                }
                heldPolls++;
                updateLock.notifyAll(); // wakes awaitPollHeld
                try {
                    TimeUnit.NANOSECONDS.timedWait(updateLock, waitNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ok(new JsonArray());
                } finally {
                    heldPolls--;
                }
            }
        }
    }

    private Reply sendMessage(String method, JsonObject params, RecordedCall.Source source, List<RecordedCall.Violation> v) {
        String chatId = param(params, "chat_id");
        if (chatId == null || chatId.isEmpty()) {
            v.add(new RecordedCall.Violation(method, "chat_id", "chat_id is empty", true));
        }
        String text = param(params, "text");
        checkText(method, "text", text, param(params, "parse_mode"), BotApiRules.MAX_TEXT, "message is too long", v, true);
        checkThreadId(method, params, source, v);
        checkKeyboard(method, params, v);
        Reply rejected = rejectionIfAny(v);
        if (rejected != null) {
            return rejected;
        }
        JsonObject m = newMessage(chatId, params);
        m.addProperty("text", text);
        return storeAndReturn(m);
    }

    private Reply sendMedia(String method, String field, JsonObject params, RecordedCall.Source source,
                            List<RecordedCall.FilePart> files, List<RecordedCall.Violation> v) {
        String chatId = param(params, "chat_id");
        if (chatId == null || chatId.isEmpty()) {
            v.add(new RecordedCall.Violation(method, "chat_id", "chat_id is empty", true));
        }
        RecordedCall.FilePart part = files.stream().filter(f -> f.field().equals(field)).findFirst().orElse(null);
        if (part == null && param(params, field) == null) {
            v.add(new RecordedCall.Violation(method, field, "there is no " + field + " in the request", true));
        }
        if (param(params, "caption") != null) {
            checkText(method, "caption", param(params, "caption"), param(params, "parse_mode"), BotApiRules.MAX_CAPTION,
                    "message caption is too long", v, false);
        }
        if (part != null && field.equals("photo")) {
            if (part.size() > BotApiRules.MAX_PHOTO_BYTES) {
                v.add(new RecordedCall.Violation(method, "photo-size", "photo is " + part.size() + " bytes > 10 MB", true));
            }
            if (part.width() <= 0) {
                v.add(new RecordedCall.Violation(method, "photo-decode", "IMAGE_PROCESS_FAILED (not a readable image)", true));
            } else {
                String problem = BotApiRules.photoDimensionProblem(part.width(), part.height());
                if (problem != null) {
                    v.add(new RecordedCall.Violation(method, "photo-dimensions", "PHOTO_INVALID_DIMENSIONS: " + problem
                            + " (" + part.width() + "x" + part.height() + ")", true));
                }
            }
        }
        if (part != null && field.equals("document") && part.size() > BotApiRules.MAX_DOCUMENT_BYTES) {
            v.add(new RecordedCall.Violation(method, "document-size", "document is " + part.size() + " bytes > 50 MB", true));
        }
        checkThreadId(method, params, source, v);
        checkKeyboard(method, params, v);
        Reply rejected = rejectionIfAny(v);
        if (rejected != null) {
            return rejected;
        }
        JsonObject m = newMessage(chatId, params);
        if (param(params, "caption") != null) {
            m.addProperty("caption", param(params, "caption"));
        }
        int no = nextFileNo.getAndIncrement();
        if (field.equals("photo")) {
            JsonArray sizes = new JsonArray();
            JsonObject size = new JsonObject();
            size.addProperty("file_id", "FAKEPHOTO" + no);
            size.addProperty("file_unique_id", "FAKEPHOTOU" + no);
            size.addProperty("width", part == null ? 0 : part.width());
            size.addProperty("height", part == null ? 0 : part.height());
            size.addProperty("file_size", part == null ? 0 : part.size());
            sizes.add(size);
            m.add("photo", sizes);
        } else {
            JsonObject doc = new JsonObject();
            doc.addProperty("file_id", "FAKEDOC" + no);
            doc.addProperty("file_unique_id", "FAKEDOCU" + no);
            doc.addProperty("file_name", part == null ? "" : part.fileName());
            doc.addProperty("file_size", part == null ? 0 : part.size());
            m.add("document", doc);
        }
        return storeAndReturn(m);
    }

    private Reply editMessageReplyMarkup(String method, JsonObject params, RecordedCall.Source source, List<RecordedCall.Violation> v) {
        JsonObject m = findTargetMessage(method, params, source, v);
        checkKeyboard(method, params, v);
        Reply rejected = rejectionIfAny(v);
        if (rejected != null) {
            return rejected;
        }
        if (m == null) {
            return error(400, "Bad Request: message to edit not found");
        }
        JsonObject newMarkup = markupOf(params);
        boolean newEmpty = isEmptyKeyboard(newMarkup);
        JsonObject oldMarkup = m.has("reply_markup") ? m.getAsJsonObject("reply_markup") : null;
        boolean oldEmpty = isEmptyKeyboard(oldMarkup);
        if ((newEmpty && oldEmpty) || (!newEmpty && newMarkup.equals(oldMarkup))) {
            return error(400, "Bad Request: message is not modified: specified new message content and reply markup are exactly the same as a current content and reply markup of the message");
        }
        if (newEmpty) {
            m.remove("reply_markup");
        } else {
            m.add("reply_markup", newMarkup);
        }
        m.addProperty("edit_date", Instant.now().getEpochSecond());
        return ok(m.deepCopy());
    }

    private Reply editMessageText(String method, JsonObject params, RecordedCall.Source source, List<RecordedCall.Violation> v) {
        JsonObject m = findTargetMessage(method, params, source, v);
        String text = param(params, "text");
        checkText(method, "text", text, param(params, "parse_mode"), BotApiRules.MAX_TEXT, "MESSAGE_TOO_LONG", v, true);
        checkKeyboard(method, params, v);
        Reply rejected = rejectionIfAny(v);
        if (rejected != null) {
            return rejected;
        }
        if (m == null) {
            return error(400, "Bad Request: message to edit not found");
        }
        JsonObject newMarkup = markupOf(params);
        if (text.equals(m.has("text") ? m.get("text").getAsString() : null)
                && (newMarkup == null ? !m.has("reply_markup") : newMarkup.equals(m.get("reply_markup")))) {
            return error(400, "Bad Request: message is not modified: specified new message content and reply markup are exactly the same as a current content and reply markup of the message");
        }
        m.addProperty("text", text);
        if (isEmptyKeyboard(newMarkup)) {
            m.remove("reply_markup");
        } else {
            m.add("reply_markup", newMarkup);
        }
        m.addProperty("edit_date", Instant.now().getEpochSecond());
        return ok(m.deepCopy());
    }

    private Reply deleteMessage(String method, JsonObject params, RecordedCall.Source source, List<RecordedCall.Violation> v) {
        JsonObject m = findTargetMessage(method, params, source, v);
        Reply rejected = rejectionIfAny(v);
        if (rejected != null) {
            return rejected;
        }
        if (m == null) {
            return error(400, "Bad Request: message to delete not found");
        }
        messages.remove(param(params, "chat_id") + "/" + longParam(params, "message_id", -1));
        return ok(new JsonPrimitive(true));
    }

    private Reply answerCallbackQuery(String method, JsonObject params, List<RecordedCall.Violation> v) {
        String id = param(params, "callback_query_id");
        String text = param(params, "text");
        if (text != null && BotApiRules.telegramLength(text) > BotApiRules.MAX_CALLBACK_ANSWER_TEXT) {
            v.add(new RecordedCall.Violation(method, "callback-answer-text", "answer text " + text.length() + " > 200", true));
        }
        Reply rejected = rejectionIfAny(v);
        if (rejected != null) {
            return rejected;
        }
        if (id == null || !knownCallbackIds.contains(id) || !answeredCallbackIds.add(id)) {
            return error(400, "Bad Request: query is too old and response timeout expired or query ID is invalid");
        }
        return ok(new JsonPrimitive(true));
    }

    private Reply getFile(JsonObject params) {
        StoredFile f = filesById.get(String.valueOf(param(params, "file_id")));
        if (f == null) {
            return error(400, "Bad Request: invalid file_id");
        }
        if (f.bytes().length > MAX_DOWNLOAD_BYTES) {
            return error(400, "Bad Request: file is too big");
        }
        JsonObject r = new JsonObject();
        r.addProperty("file_id", f.fileId());
        r.addProperty("file_unique_id", f.fileUniqueId());
        r.addProperty("file_size", f.bytes().length);
        r.addProperty("file_path", f.filePath());
        return ok(r);
    }

    private Reply download(String filePath) {
        StoredFile f = filePath == null ? null : filesByPath.get(filePath);
        if (f == null) {
            return error(404, "Not Found");
        }
        Reply r = new Reply();
        r.status = 200;
        r.body = f.bytes().clone();
        r.contentType = "application/octet-stream";
        return r;
    }

    private Reply getChat(JsonObject params) {
        String chatId = param(params, "chat_id");
        if (chatId == null || chatId.isEmpty()) {
            return error(400, "Bad Request: chat_id is empty");
        }
        JsonObject known = chats.get(chatId);
        if (known != null) {
            return ok(known.deepCopy());
        }
        JsonObject chat = new JsonObject();
        chat.add("id", chatIdValue(chatId));
        if (chatId.startsWith("-")) {
            chat.addProperty("type", "supergroup");
            chat.addProperty("title", "Fictional Test Group");
        } else {
            chat.addProperty("type", "private");
            chat.addProperty("first_name", "Testuser");
            chat.addProperty("last_name", "N" + chatId);
            chat.addProperty("username", "testuser_" + chatId);
        }
        return ok(chat);
    }

    private Reply sendChatAction(String method, JsonObject params, RecordedCall.Source source, List<RecordedCall.Violation> v) {
        String action = param(params, "action");
        if (action == null || !CHAT_ACTIONS.contains(action)) {
            v.add(new RecordedCall.Violation(method, "action", "wrong parameter action in request: " + action, true));
        }
        checkThreadId(method, params, source, v);
        Reply rejected = rejectionIfAny(v);
        return rejected != null ? rejected : ok(new JsonPrimitive(true));
    }

    private Reply setMyCommands(String method, JsonObject params, List<RecordedCall.Violation> v) {
        JsonElement e = params.get("commands");
        JsonArray commands = null;
        try {
            if (e != null && e.isJsonArray()) {
                commands = e.getAsJsonArray();
            } else if (e != null && e.isJsonPrimitive()) {
                commands = JsonParser.parseString(e.getAsString()).getAsJsonArray();
            }
        } catch (RuntimeException ex) {
            commands = null;
        }
        if (commands == null) {
            v.add(new RecordedCall.Violation(method, "commands", "commands is not a JSON array", true));
        } else {
            if (commands.size() > 100) {
                v.add(new RecordedCall.Violation(method, "commands", commands.size() + " commands > 100", true));
            }
            for (JsonElement c : commands) {
                JsonObject co = c.isJsonObject() ? c.getAsJsonObject() : new JsonObject();
                String name = co.has("command") ? co.get("command").getAsString() : "";
                String desc = co.has("description") ? co.get("description").getAsString() : "";
                if (!name.matches("[a-z0-9_]{1,32}")) {
                    v.add(new RecordedCall.Violation(method, "command-name", "invalid command name '" + name + "'", true));
                }
                if (desc.isEmpty() || desc.length() > 256) {
                    v.add(new RecordedCall.Violation(method, "command-description", "description length " + desc.length() + " not in 1..256", true));
                }
            }
        }
        Reply rejected = rejectionIfAny(v);
        if (rejected != null) {
            return rejected;
        }
        myCommands = commands.deepCopy();
        return ok(new JsonPrimitive(true));
    }

    private Reply discordCreateMessage(String method, JsonObject params, Map<String, String> headers, List<RecordedCall.Violation> v) {
        String auth = headers.get("Authorization");
        if (auth == null || !auth.startsWith("Bot ")) {
            Reply r = new Reply();
            r.status = 401;
            r.body = "{\"message\": \"401: Unauthorized\", \"code\": 0}".getBytes(StandardCharsets.UTF_8);
            return r;
        }
        String content = param(params, "content");
        if (content == null || content.isEmpty()) {
            v.add(new RecordedCall.Violation(method, "content", "empty content", true));
        } else if (content.length() > 2000) {
            v.add(new RecordedCall.Violation(method, "content", "content length " + content.length() + " > 2000", true));
        }
        if (!v.isEmpty() && rejectInvalid) {
            Reply r = new Reply();
            r.status = 400;
            r.body = "{\"message\": \"Invalid Form Body\", \"code\": 50035}".getBytes(StandardCharsets.UTF_8);
            return r;
        }
        JsonObject m = new JsonObject();
        m.addProperty("id", String.valueOf(1_000_000_000L + nextMessageId.getAndIncrement()));
        m.addProperty("channel_id", param(params, "channel_id"));
        m.addProperty("content", content);
        Reply r = new Reply();
        r.status = 200;
        r.body = GSON.toJson(m).getBytes(StandardCharsets.UTF_8);
        r.result = m;
        return r;
    }

    // ------------------------------------------------------------------ rule helpers

    private void checkText(String method, String field, String text, String parseMode, int max, String tooLongDescription,
                           List<RecordedCall.Violation> v, boolean required) {
        if (text == null) {
            if (required) {
                v.add(new RecordedCall.Violation(method, field, "message text is empty", true));
            }
            return;
        }
        String visible = text;
        if (parseMode != null && !parseMode.isEmpty()) {
            if (parseMode.equalsIgnoreCase("HTML")) {
                BotApiRules.HtmlCheck check = BotApiRules.checkHtml(text);
                for (String err : check.errors) {
                    v.add(new RecordedCall.Violation(method, "html", "can't parse entities: " + err, true));
                }
                for (String note : check.notes) {
                    v.add(new RecordedCall.Violation(method, "html-nesting", note, false));
                }
                visible = check.plainText;
            } else if (parseMode.equals("Markdown") || parseMode.equals("MarkdownV2")) {
                v.add(new RecordedCall.Violation(method, "parse_mode", parseMode + " is not validated by the fake", false));
            } else {
                v.add(new RecordedCall.Violation(method, "parse_mode", "unsupported parse_mode " + parseMode, true));
            }
        }
        if (required && visible.trim().isEmpty()) {
            v.add(new RecordedCall.Violation(method, field, "message text is empty", true));
        }
        int length = BotApiRules.telegramLength(visible);
        if (length > max) {
            v.add(new RecordedCall.Violation(method, field + "-length", tooLongDescription + " (" + length + " > " + max
                    + " after entity parsing; raw " + text.length() + ")", true));
        }
    }

    private void checkThreadId(String method, JsonObject params, RecordedCall.Source source, List<RecordedCall.Violation> v) {
        JsonElement t = params.get("message_thread_id");
        if (t == null || t.isJsonNull()) {
            return;
        }
        if (!t.isJsonPrimitive()) {
            v.add(new RecordedCall.Violation(method, "message_thread_id", "message_thread_id is not a number: " + t, true));
            return;
        }
        JsonPrimitive p = t.getAsJsonPrimitive();
        if (p.isNumber()) {
            return;
        }
        String s = p.getAsString();
        if (!s.matches("\\d{1,10}")) {
            v.add(new RecordedCall.Violation(method, "message_thread_id", "message_thread_id is not numeric: '" + s + "'", true));
        } else if (source == RecordedCall.Source.JSON) {
            v.add(new RecordedCall.Violation(method, "message_thread_id", "message_thread_id sent as a JSON string, not a number", false));
        }
    }

    private void checkKeyboard(String method, JsonObject params, List<RecordedCall.Violation> v) {
        JsonElement raw = params.get("reply_markup");
        if (raw == null || raw.isJsonNull()) {
            return;
        }
        JsonObject markup = markupOf(params);
        if (markup == null) {
            v.add(new RecordedCall.Violation(method, "reply_markup", "reply_markup is not a JSON object", true));
            return;
        }
        if (!markup.has("inline_keyboard")) {
            return; // {} (keyboard removal) or another markup type
        }
        JsonElement kb = markup.get("inline_keyboard");
        if (!kb.isJsonArray()) {
            v.add(new RecordedCall.Violation(method, "inline_keyboard", "inline_keyboard is not an array", true));
            return;
        }
        int total = 0;
        for (JsonElement row : kb.getAsJsonArray()) {
            if (!row.isJsonArray()) {
                v.add(new RecordedCall.Violation(method, "inline_keyboard", "keyboard row is not an array", true));
                continue;
            }
            for (JsonElement b : row.getAsJsonArray()) {
                total++;
                JsonObject bo = b.isJsonObject() ? b.getAsJsonObject() : new JsonObject();
                String text = bo.has("text") ? bo.get("text").getAsString() : "";
                if (text.isEmpty()) {
                    v.add(new RecordedCall.Violation(method, "button-text", "BUTTON_TEXT_EMPTY", true));
                }
                boolean hasAction = bo.has("callback_data") || bo.has("url") || bo.has("switch_inline_query")
                        || bo.has("switch_inline_query_current_chat") || bo.has("web_app") || bo.has("login_url")
                        || bo.has("pay") || bo.has("copy_text") || bo.has("callback_game");
                if (!hasAction) {
                    v.add(new RecordedCall.Violation(method, "button-action",
                            "can't parse inline keyboard button: Text buttons are unallowed in the inline keyboard ('" + text + "')", true));
                }
                if (bo.has("callback_data")) {
                    String data = bo.get("callback_data").getAsString();
                    int bytes = BotApiRules.utf8Bytes(data);
                    if (bytes < 1 || bytes > BotApiRules.MAX_CALLBACK_DATA_BYTES) {
                        v.add(new RecordedCall.Violation(method, "callback_data", "BUTTON_DATA_INVALID: '" + data + "' is "
                                + bytes + " bytes (allowed 1..64)", true));
                    }
                }
            }
        }
        if (total > BotApiRules.MAX_BUTTONS) {
            v.add(new RecordedCall.Violation(method, "button-count", "reply markup is too long: " + total + " buttons > 100", true));
        }
    }

    private JsonObject findTargetMessage(String method, JsonObject params, RecordedCall.Source source, List<RecordedCall.Violation> v) {
        String chatId = param(params, "chat_id");
        JsonElement mid = params.get("message_id");
        if (chatId == null || mid == null || mid.isJsonNull()) {
            v.add(new RecordedCall.Violation(method, "message_id", "chat_id and message_id are required", true));
            return null;
        }
        if (!mid.isJsonPrimitive() || !mid.getAsString().matches("\\d+")) {
            v.add(new RecordedCall.Violation(method, "message_id", "message_id is not numeric: " + mid, true));
            return null;
        }
        if (source == RecordedCall.Source.JSON && !mid.getAsJsonPrimitive().isNumber()) {
            v.add(new RecordedCall.Violation(method, "message_id", "message_id sent as a JSON string, not a number", false));
        }
        return messages.get(chatId + "/" + mid.getAsString());
    }

    private Reply rejectionIfAny(List<RecordedCall.Violation> v) {
        if (!rejectInvalid) {
            return null;
        }
        for (RecordedCall.Violation x : v) {
            if (x.rejected()) {
                return error(x.rule().endsWith("-size") && x.rule().startsWith("document") ? 413 : 400,
                        x.rule().startsWith("document-size") ? "Request Entity Too Large" : "Bad Request: " + x.detail());
            }
        }
        return null;
    }

    private JsonObject newMessage(String chatId, JsonObject params) {
        JsonObject m = new JsonObject();
        m.addProperty("message_id", nextMessageId.getAndIncrement());
        m.add("from", botUser());
        JsonObject chat = new JsonObject();
        chat.add("id", chatIdValue(chatId));
        chat.addProperty("type", chatId != null && chatId.startsWith("-") ? "supergroup" : "private");
        m.add("chat", chat);
        m.addProperty("date", Instant.now().getEpochSecond());
        String thread = param(params, "message_thread_id");
        if (thread != null && thread.matches("\\d+")) {
            m.addProperty("message_thread_id", Long.parseLong(thread));
            m.addProperty("is_topic_message", true);
        }
        JsonObject markup = markupOf(params);
        if (!isEmptyKeyboard(markup)) {
            m.add("reply_markup", markup);
        }
        return m;
    }

    private Reply storeAndReturn(JsonObject m) {
        String key = m.getAsJsonObject("chat").get("id").getAsString() + "/" + m.get("message_id").getAsLong();
        messages.put(key, m);
        Reply r = ok(m.deepCopy());
        r.result = m.deepCopy();
        return r;
    }

    private static JsonObject markupOf(JsonObject params) {
        JsonElement e = params.get("reply_markup");
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (e.isJsonObject()) {
            return e.getAsJsonObject().deepCopy();
        }
        try {
            JsonElement parsed = JsonParser.parseString(e.getAsString());
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean isEmptyKeyboard(JsonObject markup) {
        if (markup == null || markup.size() == 0) {
            return true;
        }
        JsonElement kb = markup.get("inline_keyboard");
        if (kb == null || !kb.isJsonArray()) {
            return false;
        }
        for (JsonElement row : kb.getAsJsonArray()) {
            if (row.isJsonArray() && row.getAsJsonArray().size() > 0) {
                return false;
            }
        }
        return true;
    }

    private static JsonObject botUser() {
        JsonObject u = new JsonObject();
        u.addProperty("id", BOT_USER_ID);
        u.addProperty("is_bot", true);
        u.addProperty("first_name", "Fake IHRG Bot");
        u.addProperty("username", BOT_USERNAME);
        return u;
    }

    private static JsonElement chatIdValue(String chatId) {
        if (chatId != null && chatId.matches("-?\\d{1,18}")) {
            return new JsonPrimitive(Long.parseLong(chatId));
        }
        return new JsonPrimitive(String.valueOf(chatId));
    }

    private static Reply ok(JsonElement result) {
        JsonObject body = new JsonObject();
        body.addProperty("ok", true);
        body.add("result", result);
        Reply r = new Reply();
        r.status = 200;
        r.body = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        return r;
    }

    private static Reply error(int code, String description) {
        JsonObject body = new JsonObject();
        body.addProperty("ok", false);
        body.addProperty("error_code", code);
        body.addProperty("description", description);
        Reply r = new Reply();
        r.status = code;
        r.body = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        return r;
    }

    private static String param(JsonObject params, String name) {
        JsonElement e = params.get(name);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    private static long longParam(JsonObject params, String name, long dflt) {
        String s = param(params, name);
        if (s == null || s.isEmpty()) {
            return dflt;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    // ------------------------------------------------------------------ parsing

    private static Map<String, String> pickHeaders(Headers h) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : List.of("Content-Type", "Upgrade", "Connection", "HTTP2-Settings", "User-Agent", "Authorization")) {
            String value = h.getFirst(name);
            if (value != null) {
                out.put(name, name.equals("Authorization") ? value.replaceAll("^(\\S+)\\s+.*$", "$1 <REDACTED>") : value);
            }
        }
        // Keep the real Authorization for the Discord check, then redact for the record.
        String auth = h.getFirst("Authorization");
        if (auth != null) {
            out.put("Authorization", auth.startsWith("Bot ") ? "Bot <REDACTED>" : "<REDACTED>");
        }
        return out;
    }

    private static void parseForm(String form, JsonObject params) {
        for (String pair : form.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = urlDecode(eq < 0 ? pair : pair.substring(0, eq));
            String val = eq < 0 ? "" : urlDecode(pair.substring(eq + 1));
            params.addProperty(k, val);
        }
    }

    private static String urlDecode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String boundaryOf(String contentType) {
        for (String piece : contentType.split(";")) {
            String p = piece.trim();
            if (p.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
                String b = p.substring("boundary=".length());
                if (b.startsWith("\"") && b.endsWith("\"") && b.length() >= 2) {
                    b = b.substring(1, b.length() - 1);
                }
                return b;
            }
        }
        throw new IllegalArgumentException("multipart body without boundary");
    }

    private static void parseMultipart(byte[] body, String boundary, JsonObject params, List<RecordedCall.FilePart> files) {
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] nextDelimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int pos = indexOf(body, delimiter, 0);
        if (pos < 0) {
            throw new IllegalArgumentException("multipart boundary not found");
        }
        while (true) {
            pos += delimiter.length;
            if (pos + 1 < body.length && body[pos] == '-' && body[pos + 1] == '-') {
                return; // closing delimiter
            }
            if (pos + 1 >= body.length || body[pos] != '\r' || body[pos + 1] != '\n') {
                throw new IllegalArgumentException("malformed multipart delimiter line");
            }
            pos += 2;
            int headerEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), pos);
            if (headerEnd < 0) {
                throw new IllegalArgumentException("multipart part without header end");
            }
            String partHeaders = new String(body, pos, headerEnd - pos, StandardCharsets.UTF_8);
            int contentStart = headerEnd + 4;
            int next = indexOf(body, nextDelimiter, contentStart);
            if (next < 0) {
                throw new IllegalArgumentException("multipart part without closing boundary");
            }
            byte[] content = Arrays.copyOfRange(body, contentStart, next);
            String name = null;
            String fileName = null;
            String partType = null;
            for (String line : partHeaders.split("\r\n")) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("content-disposition:")) {
                    name = dispositionValue(line, "name");
                    fileName = dispositionValue(line, "filename");
                } else if (lower.startsWith("content-type:")) {
                    partType = line.substring(line.indexOf(':') + 1).trim();
                }
            }
            if (name == null) {
                throw new IllegalArgumentException("multipart part without a name");
            }
            if (fileName != null) {
                int[] dims = imageSize(content);
                files.add(new RecordedCall.FilePart(name, fileName, partType, content, dims[0], dims[1]));
            } else {
                params.addProperty(name, new String(content, StandardCharsets.UTF_8));
            }
            pos = next + 2;
        }
    }

    private static String dispositionValue(String line, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(";\\s*" + key + "=\"([^\"]*)\"").matcher(line);
        return m.find() ? m.group(1) : null;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Width and height read from the image header without decoding pixels; {0,0} when unreadable. */
    static int[] imageSize(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) {
                return new int[]{0, 0};
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return new int[]{0, 0};
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return new int[]{0, 0};
        }
    }

    private void sleepUnlessClosed(long ms) {
        try {
            closedLatch.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closedLatch.countDown();
        synchronized (updateLock) {
            updateLock.notifyAll();
        }
        server.stop(0);
        pool.shutdownNow();
        try {
            pool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
