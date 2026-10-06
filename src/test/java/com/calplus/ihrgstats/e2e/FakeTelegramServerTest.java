package com.calplus.ihrgstats.e2e;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Self-tests of the rig itself (no bot involved): every validator catches its
 * planted defect and passes the value just inside the limit; failure injection
 * does what it says; a held long poll never blocks a send; the host guard
 * blocks other hosts; and what java.net.http's HTTP/2 upgrade attempt looks
 * like against this HTTP/1.1 server.
 */
public class FakeTelegramServerTest {

    private FakeTelegramServer fake;
    private HostGuard guard;
    private HttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        fake = FakeTelegramServer.start();
        guard = HostGuard.install(fake.authority());
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(); // default version: HTTP_2
    }

    @AfterEach
    void tearDown() {
        guard.close();
        fake.close();
    }

    private String url(String method) {
        return fake.baseUrl() + "/bot" + FakeTelegramServer.FAKE_TOKEN + "/" + method;
    }

    private HttpResponse<String> post(String method, JsonObject body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url(method))).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String methodAndQuery) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url(methodAndQuery))).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject msg(String text) {
        JsonObject o = new JsonObject();
        o.addProperty("chat_id", "-1009990001");
        o.addProperty("text", text);
        return o;
    }

    private static JsonObject keyboard(int buttons, String data) {
        JsonArray rows = new JsonArray();
        JsonArray row = new JsonArray();
        for (int i = 0; i < buttons; i++) {
            JsonObject b = new JsonObject();
            b.addProperty("text", "B" + i);
            b.addProperty("callback_data", data == null ? "d" + i : data);
            row.add(b);
            if (row.size() == 4) {
                rows.add(row);
                row = new JsonArray();
            }
        }
        if (row.size() > 0) {
            rows.add(row);
        }
        JsonObject markup = new JsonObject();
        markup.add("inline_keyboard", rows);
        return markup;
    }

    private static JsonObject update(String text) {
        JsonObject m = new JsonObject();
        m.addProperty("message_id", 1);
        m.addProperty("text", text);
        JsonObject u = new JsonObject();
        u.add("message", m);
        return u;
    }

    // ------------------------------------------------------------------ long polling

    @Test
    void heldLongPoll_doesNotBlockASend_andAnEnqueueWakesIt() throws Exception {
        CompletableFuture<HttpResponse<String>> poll = client.sendAsync(
                HttpRequest.newBuilder(URI.create(url("getUpdates?offset=0&timeout=30"))).timeout(Duration.ofSeconds(40)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(fake.awaitPollHeld(Duration.ofSeconds(5)), "the 30 s poll is being held");

        long t0 = System.nanoTime();
        HttpResponse<String> sent = post("sendMessage", msg("while a poll is held"));
        long sendMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertEquals(200, sent.statusCode());
        assertTrue(sendMs < 2000, "send took " + sendMs + " ms while a poll was held");
        assertFalse(poll.isDone(), "the poll is still held");

        long id = fake.enqueueUpdate(update("/help"));
        HttpResponse<String> polled = poll.get(5, TimeUnit.SECONDS);
        assertTrue(polled.body().contains("\"update_id\":" + id), polled.body());
    }

    @Test
    void getUpdates_followsTelegramOffsetSemantics() throws Exception {
        long a = fake.enqueueUpdate(update("a"));
        fake.enqueueUpdate(update("b"));
        long c = fake.enqueueUpdate(update("c"));
        assertEquals(3, JsonParser.parseString(get("getUpdates?offset=0").body()).getAsJsonObject().getAsJsonArray("result").size());
        assertEquals(2, JsonParser.parseString(get("getUpdates?offset=" + (a + 1)).body()).getAsJsonObject().getAsJsonArray("result").size(),
                "offset confirms (drops) earlier updates");
        JsonArray last = JsonParser.parseString(get("getUpdates?offset=-1&limit=1").body()).getAsJsonObject().getAsJsonArray("result");
        assertEquals(1, last.size());
        assertEquals(c, last.get(0).getAsJsonObject().get("update_id").getAsLong(), "offset=-1 returns the last update");
        assertEquals(1, fake.pendingUpdates(), "offset=-1 forgets everything before the last update");
    }

    @Test
    void overlappingPolls_endTheOlderOneWith409_likeASecondBotInstance() throws Exception {
        CompletableFuture<HttpResponse<String>> first = client.sendAsync(
                HttpRequest.newBuilder(URI.create(url("getUpdates?offset=0&timeout=30"))).timeout(Duration.ofSeconds(40)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(fake.awaitPollHeld(Duration.ofSeconds(5)));
        CompletableFuture<HttpResponse<String>> second = client.sendAsync(
                HttpRequest.newBuilder(URI.create(url("getUpdates?offset=0&timeout=1"))).timeout(Duration.ofSeconds(40)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(409, first.get(5, TimeUnit.SECONDS).statusCode());
        assertEquals(200, second.get(5, TimeUnit.SECONDS).statusCode());
    }

    // ------------------------------------------------------------------ validators

    @Test
    void textLength_isCheckedAfterHtmlParsing() throws Exception {
        assertEquals(200, post("sendMessage", msg("x".repeat(4096))).statusCode());
        HttpResponse<String> tooLong = post("sendMessage", msg("x".repeat(4097)));
        assertEquals(400, tooLong.statusCode());
        assertTrue(tooLong.body().contains("message is too long"), tooLong.body());

        JsonObject html = msg("<b>" + "x".repeat(4096) + "</b>");
        html.addProperty("parse_mode", "HTML");
        assertEquals(200, post("sendMessage", html).statusCode(), "tags do not count toward the limit");
        JsonObject entities = msg("&amp;".repeat(4097));
        entities.addProperty("parse_mode", "HTML");
        assertEquals(400, post("sendMessage", entities).statusCode(), "each entity counts as one character");
        assertEquals(400, post("sendMessage", msg("   ")).statusCode(), "blank text is rejected");
    }

    @Test
    void html_unclosedUnsupportedOrStrayLessThan_isRejected_andValidHtmlPasses() throws Exception {
        for (String bad : new String[]{"<b>unclosed", "<b>x</i>", "<script>x</script>", "a < b", "<span>x</span>", "x</b>"}) {
            JsonObject m = msg(bad);
            m.addProperty("parse_mode", "HTML");
            HttpResponse<String> r = post("sendMessage", m);
            assertEquals(400, r.statusCode(), "should reject: " + bad);
            assertTrue(r.body().contains("can't parse entities"), r.body());
        }
        JsonObject good = msg("<b>bold</b> <i>it</i> <code>c &lt; d</code> <pre><code class=\"language-java\">x</code></pre> "
                + "<a href=\"https://example.invalid\">l</a> <tg-spoiler>s</tg-spoiler> &amp; a & b &foo; &#65; > ok");
        good.addProperty("parse_mode", "HTML");
        assertEquals(200, post("sendMessage", good).statusCode(), fake.violations().toString());
        JsonObject plain = msg("a < b and <b> are literal without parse_mode");
        assertEquals(200, post("sendMessage", plain).statusCode());
    }

    @Test
    void keyboard_buttonCountAndCallbackDataBytes_areLimited() throws Exception {
        JsonObject ok = msg("100 buttons");
        ok.add("reply_markup", keyboard(100, null));
        assertEquals(200, post("sendMessage", ok).statusCode());
        JsonObject tooMany = msg("101 buttons");
        tooMany.add("reply_markup", keyboard(101, null));
        assertEquals(400, post("sendMessage", tooMany).statusCode());

        JsonObject data64 = msg("64 bytes");
        data64.add("reply_markup", keyboard(1, "x".repeat(64)));
        assertEquals(200, post("sendMessage", data64).statusCode());
        JsonObject data65 = msg("65 bytes");
        data65.add("reply_markup", keyboard(1, "x".repeat(65)));
        HttpResponse<String> r = post("sendMessage", data65);
        assertEquals(400, r.statusCode());
        assertTrue(r.body().contains("BUTTON_DATA_INVALID"), r.body());
        JsonObject multibyte = msg("22 euro signs = 66 bytes, 22 chars");
        multibyte.add("reply_markup", keyboard(1, "€".repeat(22)));
        assertEquals(400, post("sendMessage", multibyte).statusCode(), "the limit is UTF-8 bytes, not characters");
    }

    @Test
    void threadId_mustBeNumeric_aJsonStringOfDigitsIsOnlyANote() throws Exception {
        JsonObject number = msg("n");
        number.addProperty("message_thread_id", 678);
        assertEquals(200, post("sendMessage", number).statusCode());
        JsonObject digits = msg("s");
        digits.addProperty("message_thread_id", "678");
        assertEquals(200, post("sendMessage", digits).statusCode());
        assertTrue(fake.violations().stream().anyMatch(v -> !v.rejected() && v.rule().equals("message_thread_id")));
        JsonObject word = msg("w");
        word.addProperty("message_thread_id", "general");
        assertEquals(400, post("sendMessage", word).statusCode());
    }

    @Test
    void photo_sizeDimensionsRatioAndCaption_areValidated() throws Exception {
        byte[] okPng = png(1200, 900);
        assertEquals(200, multipart("sendPhoto", "photo", "ok.png", okPng, null).statusCode());

        HttpResponse<String> ratio = multipart("sendPhoto", "photo", "wide.png", png(2100, 100), null);
        assertEquals(400, ratio.statusCode(), "ratio 21 > 20");
        assertTrue(ratio.body().contains("PHOTO_INVALID_DIMENSIONS"), ratio.body());
        assertEquals(400, multipart("sendPhoto", "photo", "big.png", png(6000, 4001), null).statusCode(), "w+h 10001 > 10000");
        assertEquals(200, multipart("sendPhoto", "photo", "edge.png", png(6000, 4000), null).statusCode(), "w+h 10000 is allowed");

        byte[] heavy = Arrays.copyOf(okPng, 10 * 1024 * 1024 + 1); // readable header, 10 MB + 1 byte
        assertEquals(400, multipart("sendPhoto", "photo", "heavy.png", heavy, null).statusCode(), "photo > 10 MB");
        assertEquals(400, multipart("sendPhoto", "photo", "junk.png", "not an image".getBytes(StandardCharsets.UTF_8), null).statusCode());

        assertEquals(200, multipart("sendDocument", "document", "doc.png", okPng, "c".repeat(1024)).statusCode());
        assertEquals(400, multipart("sendDocument", "document", "doc.png", okPng, "c".repeat(1025)).statusCode(), "caption > 1024");

        RecordedCall last = fake.calls().stream().filter(c -> c.method.equals("sendPhoto") && c.isOk()).findFirst().orElseThrow();
        assertEquals(1200, last.file().width());
        assertEquals(900, last.file().height());
    }

    @Test
    void unknownMethods_areRecordedNotRejected_unlessAskedTo() throws Exception {
        assertEquals(200, post("sendDice", msg("x")).statusCode());
        assertEquals(1, fake.unknownMethodCalls().size());
        fake.setRejectUnknownMethods(true);
        assertEquals(404, post("sendDice", msg("x")).statusCode());
    }

    @Test
    void wrongToken_getsUnauthorized() throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(fake.baseUrl() + "/bot111:WRONG/getMe")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, r.statusCode());
        assertTrue(fake.calls().get(0).wrongToken);
        assertFalse(fake.calls().get(0).path.contains("WRONG"), "the token is redacted in the record");
    }

    @Test
    void editAnswerAndFile_flows() throws Exception {
        JsonObject withKb = msg("pick");
        withKb.add("reply_markup", keyboard(2, null));
        long messageId = JsonParser.parseString(post("sendMessage", withKb).body()).getAsJsonObject()
                .getAsJsonObject("result").get("message_id").getAsLong();
        JsonObject edit = new JsonObject();
        edit.addProperty("chat_id", "-1009990001");
        edit.addProperty("message_id", messageId);
        edit.add("reply_markup", new JsonObject());
        assertEquals(200, post("editMessageReplyMarkup", edit).statusCode(), "removing a keyboard");
        assertEquals(400, post("editMessageReplyMarkup", edit).statusCode(), "removing it again: message is not modified");
        edit.addProperty("message_id", 999999);
        assertEquals(400, post("editMessageReplyMarkup", edit).statusCode(), "unknown message");

        fake.registerCallbackId("cbq-x");
        JsonObject answer = new JsonObject();
        answer.addProperty("callback_query_id", "cbq-x");
        assertEquals(200, post("answerCallbackQuery", answer).statusCode());
        assertEquals(400, post("answerCallbackQuery", answer).statusCode(), "a query can be answered once");

        byte[] content = "name1,hall1\n".getBytes(StandardCharsets.UTF_8);
        String fileId = fake.registerFile("2001_round_1.csv", content);
        JsonObject info = JsonParser.parseString(get("getFile?file_id=" + fileId).body()).getAsJsonObject().getAsJsonObject("result");
        HttpResponse<byte[]> download = client.send(HttpRequest.newBuilder(URI.create(
                fake.baseUrl() + "/file/bot" + FakeTelegramServer.FAKE_TOKEN + "/" + info.get("file_path").getAsString())).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertArrayEquals(content, download.body());
        assertEquals(400, get("getFile?file_id=nope").statusCode());
    }

    // ------------------------------------------------------------------ failure injection

    @Test
    void faults_respondDelayDropAndHang_asScripted() throws Exception {
        fake.failNext("getMe", Fault.tooManyRequests(7));
        HttpResponse<String> limited = get("getMe");
        assertEquals(429, limited.statusCode());
        assertEquals(7, JsonParser.parseString(limited.body()).getAsJsonObject().getAsJsonObject("parameters").get("retry_after").getAsInt());
        assertEquals(200, get("getMe").statusCode(), "failNext is one-shot");

        fake.failTimes("getMe", 2, Fault.serverError());
        assertEquals(500, get("getMe").statusCode());
        assertEquals(500, get("getMe").statusCode());
        assertEquals(200, get("getMe").statusCode());

        fake.failWhen("sendMessage", c -> "boom".equals(c.text()), Fault.badRequest("can't parse entities: test"), -1);
        assertEquals(200, post("sendMessage", msg("fine")).statusCode());
        assertEquals(400, post("sendMessage", msg("boom")).statusCode());
        fake.clearFaults();
        assertEquals(200, post("sendMessage", msg("boom")).statusCode());

        fake.failNext("getMe", Fault.delay(Duration.ofMillis(300)));
        long t0 = System.nanoTime();
        assertEquals(200, get("getMe").statusCode());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) >= 290);

        // java.net.http silently retries an idempotent GET once when the connection
        // closes before any response, so a one-shot drop on a GET is invisible...
        int before = fake.callCount();
        fake.failNext("getMe", Fault.dropConnection());
        assertEquals(200, get("getMe").statusCode(), "GET retried transparently by the client");
        assertEquals(2, fake.callCount() - before, "the fake saw the dropped attempt and the retry");
        // ...two drops in a row, or a POST, surface as an IOException.
        fake.failTimes("getMe", 2, Fault.dropConnection());
        assertThrows(IOException.class, () -> get("getMe"));
        fake.failNext("sendMessage", Fault.dropConnection());
        assertThrows(IOException.class, () -> post("sendMessage", msg("dropped")));

        fake.failNext("getMe", Fault.hang());
        HttpRequest shortTimeout = HttpRequest.newBuilder(URI.create(url("getMe"))).timeout(Duration.ofMillis(400)).GET().build();
        assertThrows(HttpTimeoutException.class, () -> client.send(shortTimeout, HttpResponse.BodyHandlers.ofString()));

        fake.failNext("getUpdates", Fault.unauthorized().after(Duration.ofMillis(100)));
        assertEquals(401, get("getUpdates?offset=0").statusCode());
    }

    // ------------------------------------------------------------------ transport facts

    /**
     * java.net.http defaults to HTTP/2; against a cleartext http:// URL it tries an
     * h2c upgrade. This records what the client sends and that the HTTP/1.1
     * server's plain answer is accepted (the client falls back to HTTP/1.1).
     */
    @Test
    void http2UpgradeAttempt_isIgnoredByTheServer_andTheClientFallsBackToHttp11() throws Exception {
        HttpResponse<String> first = get("getMe");
        HttpResponse<String> postReply = post("sendMessage", msg("after upgrade attempt"));
        HttpResponse<String> second = get("getMe");
        assertEquals(200, first.statusCode());
        assertEquals(200, postReply.statusCode());
        assertEquals(200, second.statusCode());
        assertEquals(HttpClient.Version.HTTP_1_1, first.version());
        assertEquals(HttpClient.Version.HTTP_1_1, second.version());
        var calls = fake.calls();
        for (RecordedCall c : calls) {
            System.out.println("[HTTP2-CHECK] " + c.httpMethod + " " + c.method + " Upgrade=" + c.headers.get("Upgrade")
                    + " Connection=" + c.headers.get("Connection") + " HTTP2-Settings=" + (c.headers.get("HTTP2-Settings") != null));
        }
        assertEquals("h2c", calls.get(0).headers.get("Upgrade"), "the first GET carries the h2c upgrade offer");
    }

    @Test
    void hostGuard_blocksAnyOtherHost_withoutReachingIt() {
        long t0 = System.nanoTime();
        HttpRequest elsewhere = HttpRequest.newBuilder(URI.create("http://unreachable.invalid/bot000000:TEST/getMe"))
                .timeout(Duration.ofSeconds(5)).GET().build();
        assertThrows(IOException.class, () -> client.send(elsewhere, HttpResponse.BodyHandlers.ofString()));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 4000, "blocked fast");
        assertEquals(1, guard.violations().size());
        assertThrows(AssertionError.class, guard::assertNoViolations);
        assertTrue(guard.violations().get(0).getHost().endsWith(".invalid"));
    }

    @Test
    void transcriptRedaction_removesTokens() {
        String s = TranscriptWriter.redact("/bot123456789:AAEhBP0av28_x-Yz/getMe and /bot000000:TEST/x");
        assertFalse(s.contains("AAEhBP0av28"), s);
        assertFalse(s.contains("000000:TEST"), s);
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] png(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private HttpResponse<String> multipart(String method, String field, String fileName, byte[] bytes, String caption) throws Exception {
        String boundary = "----fakeboundary" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n-1009990001\r\n").getBytes(StandardCharsets.UTF_8));
        if (caption != null) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"caption\"\r\n\r\n" + caption + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field + "\"; filename=\"" + fileName
                + "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(bytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return client.send(HttpRequest.newBuilder(URI.create(url(method))).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
    }
}
