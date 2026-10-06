package com.calplus.ihrgstats.e2e;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Plays one user in one chat against the {@link FakeTelegramServer}: injects
 * text messages, button clicks and document uploads as Telegram updates, and
 * waits for the bot's outgoing calls.
 *
 * <p>Every inject first moves the driver's cursor to the end of the call log;
 * every {@code await*} searches from the cursor and moves it past the call it
 * returns - so a scenario reads top to bottom: send, await reply, click,
 * await next reply. All waits take an explicit timeout and fail with the list
 * of calls seen so far.
 */
public final class ConversationDriver {

    /** A fictional Telegram user. */
    public record User(long id, String firstName, String username) {
        public String idString() {
            return String.valueOf(id);
        }
    }

    /** A chat: a supergroup (negative id) or a private chat (the user's id). */
    public record Chat(long id, String type) {
        public static Chat group(long id) {
            return new Chat(id, "supergroup");
        }

        public static Chat privateWith(User user) {
            return new Chat(user.id(), "private");
        }

        public String idString() {
            return String.valueOf(id);
        }
    }

    /** Default fictional admin (BotHarness.Options.adminUserId is 910001). */
    public static final User ADMIN = new User(910001L, "Avery", "fake_admin");
    /** Default fictional non-admin member. */
    public static final User MEMBER = new User(920002L, "Morgan", "fake_member");
    /** Default fictional group chat. */
    public static final Chat GROUP = Chat.group(-1009990001L);

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    private static final AtomicLong NEXT_MESSAGE_ID = new AtomicLong(700_000);
    private static final AtomicLong NEXT_CALLBACK_ID = new AtomicLong(1);

    private final FakeTelegramServer fake;
    private final User user;
    private final Chat chat;
    private final Long threadId;
    private int cursor;

    ConversationDriver(FakeTelegramServer fake, User user, Chat chat) {
        this(fake, user, chat, null, fake.callCount());
    }

    private ConversationDriver(FakeTelegramServer fake, User user, Chat chat, Long threadId, int cursor) {
        this.fake = fake;
        this.user = user;
        this.chat = chat;
        this.threadId = threadId;
        this.cursor = cursor;
    }

    /** Same chat and cursor position, another user. */
    public ConversationDriver as(User other) {
        return new ConversationDriver(fake, other, chat, threadId, cursor);
    }

    /** Same user and cursor, messages carry this forum topic id (null = none). */
    public ConversationDriver inThread(Long topicId) {
        return new ConversationDriver(fake, user, chat, topicId, cursor);
    }

    /** Same user, another chat. */
    public ConversationDriver inChat(Chat other) {
        return new ConversationDriver(fake, user, other, threadId, cursor);
    }

    public User user() {
        return user;
    }

    public Chat chat() {
        return chat;
    }

    public FakeTelegramServer fake() {
        return fake;
    }

    // ------------------------------------------------------------------ inject

    /** Moves the cursor to the end of the call log (later awaits only see newer calls). */
    public void mark() {
        cursor = fake.callCount();
    }

    /** Injects a text message (a command when it starts with '/'). Returns the update_id. */
    public long sendText(String text) {
        JsonObject m = baseMessage();
        m.addProperty("text", text);
        if (text.startsWith("/")) {
            int len = text.indexOf(' ') < 0 ? text.length() : text.indexOf(' ');
            JsonArray entities = new JsonArray();
            JsonObject e = new JsonObject();
            e.addProperty("offset", 0);
            e.addProperty("length", len);
            e.addProperty("type", "bot_command");
            entities.add(e);
            m.add("entities", entities);
        }
        return inject("message", m);
    }

    /** Clicks the button whose label equals {@code labelOrData} or whose callback_data equals it. */
    public long click(RecordedCall keyboardMessage, String labelOrData) {
        for (RecordedCall.Button b : keyboardMessage.buttons()) {
            if (labelOrData.equals(b.text()) || labelOrData.equals(b.callbackData())) {
                return clickData(keyboardMessage, b.callbackData());
            }
        }
        throw new AssertionError("no button '" + labelOrData + "' on " + keyboardMessage + "; buttons: " + keyboardMessage.buttons());
    }

    /** Clicks the first button matching {@code p}. */
    public long clickFirst(RecordedCall keyboardMessage, Predicate<RecordedCall.Button> p) {
        for (RecordedCall.Button b : keyboardMessage.buttons()) {
            if (p.test(b)) {
                return clickData(keyboardMessage, b.callbackData());
            }
        }
        throw new AssertionError("no matching button on " + keyboardMessage + "; buttons: " + keyboardMessage.buttons());
    }

    /**
     * Injects a callback_query carrying {@code data} on the message the bot sent
     * in {@code sent} - whether or not such a button exists (stale, forged or
     * malformed clicks are allowed on purpose).
     */
    public long clickData(RecordedCall sent, String data) {
        if (sent.result == null) {
            throw new AssertionError("cannot click on a call the fake did not turn into a message: " + sent);
        }
        String chatId = sent.result.getAsJsonObject("chat").get("id").getAsString();
        JsonObject current = fake.message(chatId, sent.messageId());
        return clickOnMessage(current != null ? current : sent.result, data);
    }

    /** Injects a callback_query on an arbitrary message object. */
    public long clickOnMessage(JsonObject message, String data) {
        String id = "cbq-" + NEXT_CALLBACK_ID.getAndIncrement();
        fake.registerCallbackId(id);
        JsonObject cq = new JsonObject();
        cq.addProperty("id", id);
        cq.add("from", from());
        cq.add("message", message.deepCopy());
        cq.addProperty("chat_instance", "ci-" + Math.abs(chat.id()));
        cq.addProperty("data", data);
        return inject("callback_query", cq);
    }

    /** Uploads {@code bytes} as a document named {@code fileName}. */
    public long upload(String fileName, byte[] bytes) {
        String fileId = fake.registerFile(fileName, bytes);
        JsonObject m = baseMessage();
        JsonObject doc = new JsonObject();
        doc.addProperty("file_name", fileName);
        doc.addProperty("mime_type", fileName.toLowerCase().endsWith(".csv") ? "text/csv" : "application/octet-stream");
        doc.addProperty("file_id", fileId);
        doc.addProperty("file_unique_id", fileId + "U");
        doc.addProperty("file_size", bytes.length);
        m.add("document", doc);
        return inject("message", m);
    }

    /** Uploads a file from disk under its own name. */
    public long upload(Path file) throws IOException {
        return upload(file.getFileName().toString(), Files.readAllBytes(file));
    }

    private long inject(String kind, JsonObject payload) {
        mark();
        JsonObject update = new JsonObject();
        update.add(kind, payload);
        return fake.enqueueUpdate(update);
    }

    private JsonObject baseMessage() {
        JsonObject m = new JsonObject();
        m.addProperty("message_id", NEXT_MESSAGE_ID.getAndIncrement());
        m.add("from", from());
        JsonObject c = new JsonObject();
        c.addProperty("id", chat.id());
        c.addProperty("type", chat.type());
        if (chat.type().equals("private")) {
            c.addProperty("first_name", user.firstName());
            if (user.username() != null) {
                c.addProperty("username", user.username());
            }
        } else {
            c.addProperty("title", "Fictional Test Group");
            c.addProperty("is_forum", threadId != null);
        }
        m.add("chat", c);
        m.addProperty("date", Instant.now().getEpochSecond());
        if (threadId != null) {
            m.addProperty("message_thread_id", threadId);
            m.addProperty("is_topic_message", true);
        }
        return m;
    }

    private JsonObject from() {
        JsonObject f = new JsonObject();
        f.addProperty("id", user.id());
        f.addProperty("is_bot", false);
        f.addProperty("first_name", user.firstName());
        if (user.username() != null) {
            f.addProperty("username", user.username());
        }
        f.addProperty("language_code", "en");
        return f;
    }

    // ------------------------------------------------------------------ await

    /** First call after the cursor matching {@code p}; moves the cursor past it. */
    public RecordedCall await(Predicate<RecordedCall> p, Duration timeout, String what) {
        RecordedCall c = fake.awaitCall(cursor, p, timeout, what);
        cursor = Math.max(cursor, fake.indexOf(c) + 1);
        return c;
    }

    /** Next sendMessage / sendPhoto / sendDocument (any chat, any status). */
    public RecordedCall awaitSend(Duration timeout) {
        return await(RecordedCall::isSend, timeout, "any send*");
    }

    public RecordedCall awaitSend() {
        return awaitSend(DEFAULT_TIMEOUT);
    }

    /** Next call of one method. */
    public RecordedCall awaitMethod(String method, Duration timeout) {
        return await(c -> c.method.equalsIgnoreCase(method), timeout, method);
    }

    /** Next sendMessage whose text contains {@code fragment}. */
    public RecordedCall awaitText(String fragment, Duration timeout) {
        return await(c -> c.method.equalsIgnoreCase("sendMessage") && c.text() != null && c.text().contains(fragment),
                timeout, "sendMessage containing \"" + fragment + "\"");
    }

    public RecordedCall awaitText(String fragment) {
        return awaitText(fragment, DEFAULT_TIMEOUT);
    }

    /** Next successful send that carries an inline keyboard. */
    public RecordedCall awaitKeyboard(Duration timeout) {
        return await(c -> c.isSend() && c.isOk() && c.hasKeyboard(), timeout, "a message with an inline keyboard");
    }

    public RecordedCall awaitKeyboard() {
        return awaitKeyboard(DEFAULT_TIMEOUT);
    }

    /** All calls after the cursor that are not getUpdates (does not move the cursor). */
    public List<RecordedCall> callsSinceCursor() {
        List<RecordedCall> all = fake.calls();
        return all.subList(Math.min(cursor, all.size()), all.size()).stream()
                .filter(c -> !c.method.equals("getUpdates")).toList();
    }

    /**
     * Waits until no new (non-getUpdates) call has arrived for {@code quiet},
     * giving up after {@code max}. Returns true when it went quiet.
     */
    public boolean awaitQuiet(Duration quiet, Duration max) {
        long end = System.nanoTime() + max.toNanos();
        long lastCount = countNonPoll();
        long lastChange = System.nanoTime();
        while (System.nanoTime() < end) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            long n = countNonPoll();
            if (n != lastCount) {
                lastCount = n;
                lastChange = System.nanoTime();
            } else if (System.nanoTime() - lastChange >= quiet.toNanos()) {
                return true;
            }
        }
        return false;
    }

    private long countNonPoll() {
        return fake.calls().stream().filter(c -> !c.method.equals("getUpdates")).count();
    }
}
