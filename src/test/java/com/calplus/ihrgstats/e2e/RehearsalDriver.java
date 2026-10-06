package com.calplus.ihrgstats.e2e;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lane a3 production rehearsal: runs the fake Telegram server for a bot that
 * runs as a SEPARATE process ({@code java -jar}, the production start
 * command) - normally in another container that shares this one's network
 * namespace (loopback only, {@code --network none}) and PID namespace.
 * <p>
 * Not a test: a {@code main} the rehearsal scripts start. The fake listens on
 * 127.0.0.1:0 and its port is written to {@code <ctl>/fake.port}; a plain
 * HTTP/1.1 control port on 127.0.0.1:{@code --ctl-port} lets the host script
 * drive the conversation one step at a time ({@code curl}):
 * <pre>
 *   /waitpoll?ms=60000              wait until the bot holds a getUpdates poll
 *   /text?t=/help&amp;quiet=1500       send a text as the admin in the group, wait for quiet
 *   /click?data=X | /click?label=X  click on the newest keyboard message
 *   /upload?file=/data/x.csv[&amp;name=n][&amp;kill=TERM|KILL&amp;pid=N&amp;at=delay|dialog|download|success&amp;ms=N]
 *   /kill?pid=N&amp;sig=TERM|KILL     signal a process in the shared PID namespace
 *   /stats  /calls?since=N  /transcript?name=n  /quit
 * </pre>
 * Every answer is plain text: one "key=value" line per fact plus one line per
 * bot call. Uses only the fake's public API; the rig is not modified.
 */
public final class RehearsalDriver {

    private static final List<String[]> SCRIPT = List.of(
            // needle, answer index - copied from CorpusIngestionTest.DIALOG_SCRIPT (same corpus, same answers)
            new String[]{"'Paul Murphy' may match existing player 'Paul Morphy'.", "1"},
            new String[]{"'Bobby Fischer' may match existing player 'Bob'.", "1"},
            new String[]{"'Teddy Rosevelt' may match existing player 'Teddy Roosevelt'.", "0"},
            new String[]{"Player: Joyce Byers", "1"},
            new String[]{"'Jessie Pinkman' may match existing player 'Jesse Pinkman'.", "0"},
            new String[]{"'Margarey Tyrell' may match existing player 'Margaery Tyrell'.", "0"},
            new String[]{"Player: Jim Hopper", "1"},
            new String[]{"Player: Coral Reeves", "2"},
            new String[]{"'Elven' may match existing player 'Eleven'.", "0"},
            new String[]{"'Dominique' may match existing player 'Dominique DiPierro'.", "0"},
            new String[]{"'Aniya Forger' may match existing player 'Anya Forger'.", "0"},
            new String[]{"'Hermoine Granger' may match existing player 'Hermione Granger'.", "0"},
            new String[]{"'Baracuda' may match existing player 'Barracuda'.", "0"},
            new String[]{"'Tigran Petrosyan' may match existing player 'Tigran Petrosian'.", "0"});

    private final FakeTelegramServer fake;
    private final ConversationDriver admin;
    private final Path ctl;
    private final Instant started = Instant.now();
    private volatile boolean quit;

    private RehearsalDriver(FakeTelegramServer fake, Path ctl) {
        this.fake = fake;
        this.ctl = ctl;
        this.admin = new ConversationDriver(fake, ConversationDriver.ADMIN, ConversationDriver.GROUP);
    }

    public static void main(String[] args) throws Exception {
        int ctlPort = 18080;
        Path ctl = Path.of("/ctl");
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--ctl-port")) {
                ctlPort = Integer.parseInt(args[i + 1]);
            } else if (args[i].equals("--ctl-dir")) {
                ctl = Path.of(args[i + 1]);
            }
        }
        FakeTelegramServer fake = FakeTelegramServer.start();
        RehearsalDriver d = new RehearsalDriver(fake, ctl);
        HttpServer control = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), ctlPort), 0);
        control.setExecutor(Executors.newCachedThreadPool());
        control.createContext("/", d::handle);
        control.start();
        Files.createDirectories(ctl);
        Files.writeString(ctl.resolve("fake.port"), String.valueOf(fake.port()));
        System.out.println("[rehearsal] fake=" + fake.baseUrl() + " control=127.0.0.1:" + ctlPort);
        while (!d.quit) {
            Thread.sleep(200);
        }
        control.stop(0);
        fake.close();
        System.exit(0);
    }

    // ------------------------------------------------------------------ HTTP control

    private void handle(HttpExchange ex) {
        String body;
        int status = 200;
        try {
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            String path = ex.getRequestURI().getPath();
            body = switch (path) {
                case "/waitpoll" -> "held=" + fake.awaitPollHeld(Duration.ofMillis(num(q, "ms", 60000))) + "\n";
                case "/text" -> text(q);
                case "/click" -> click(q);
                case "/upload" -> upload(q);
                case "/kill" -> kill(Long.parseLong(q.get("pid")), q.getOrDefault("sig", "TERM")) + "\n";
                case "/stats" -> stats();
                case "/calls" -> calls((int) num(q, "since", 0));
                case "/count" -> "next_index=" + fake.callCount() + "\n";
                case "/save" -> save((int) num(q, "since", 0), q.getOrDefault("dir", "files"), q.getOrDefault("prefix", ""));
                case "/quiet" -> "quiet=" + admin.awaitQuiet(Duration.ofMillis(num(q, "quiet", 1500)),
                        Duration.ofMillis(num(q, "max", 60000))) + "\n";
                case "/transcript" -> "written=" + TranscriptWriter.write(ctl.resolve("transcripts"),
                        q.getOrDefault("name", "rehearsal"), fake) + "\n";
                case "/quit" -> {
                    quit = true;
                    yield "bye\n";
                }
                default -> {
                    status = 404;
                    yield "unknown " + path + "\n";
                }
            };
        } catch (Throwable t) {
            status = 500;
            body = "error=" + t.toString().replace("\n", "\\n") + "\n";
        }
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException ignored) {
            // the script went away
        } finally {
            ex.close();
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return m;
        }
        for (String kv : raw.split("&")) {
            int eq = kv.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? kv : kv.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8);
            m.put(k, v);
        }
        return m;
    }

    private static long num(Map<String, String> q, String k, long def) {
        String v = q.get(k);
        return v == null || v.isBlank() ? def : Long.parseLong(v);
    }

    // ------------------------------------------------------------------ steps

    private String text(Map<String, String> q) {
        int from = fake.callCount();
        Instant t0 = Instant.now();
        admin.sendText(q.get("t"));
        String until = awaitUntil(q, from, t0);
        boolean quiet = admin.awaitQuiet(Duration.ofMillis(num(q, "quiet", 1500)), Duration.ofMillis(num(q, "max", 60000)));
        return "quiet=" + quiet + "\n" + until + describe(from, t0);
    }

    /**
     * Optional "until=<method>" / "untilText=<fragment>": first waits (up to max) for
     * such a call - or for any send starting with the red error emoji - so a slow
     * step is not mistaken for a quiet one. Returns "until_ms=..." or "".
     */
    private String awaitUntil(Map<String, String> q, int from, Instant t0) {
        String m = q.get("until");
        String frag = q.get("untilText");
        if (m == null && frag == null) {
            return "";
        }
        try {
            RecordedCall hit = fake.awaitCall(from, c -> (m != null && c.method.equalsIgnoreCase(m))
                            || (frag != null && c.isSend() && c.text() != null && c.text().contains(frag))
                            || (c.isSend() && c.text() != null && c.text().startsWith("🔴")),
                    Duration.ofMillis(num(q, "max", 60000)), "until");
            return "until_ms=" + Duration.between(t0, hit.at).toMillis() + " until_hit=" + hit.method + "\n";
        } catch (AssertionError timeout) {
            return "until_ms=-1 until_hit=timeout\n";
        }
    }

    private String click(Map<String, String> q) {
        int from = fake.callCount();
        RecordedCall kb = lastKeyboard().orElseThrow(() -> new IllegalStateException("no keyboard message yet"));
        Instant t0 = Instant.now();
        AtomicReference<String> killed = new AtomicReference<>("");
        if (q.containsKey("kill")) {
            // signal the bot a fixed delay after the click is queued (mid-/settings kill tests)
            long pid = num(q, "pid", -1);
            long delay = num(q, "ms", 0);
            String sig = q.get("kill");
            Thread k = new Thread(() -> {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                killed.set(ms(t0) + "ms " + kill(pid, sig));
            });
            k.setDaemon(true);
            k.start();
        }
        if (q.containsKey("data")) {
            admin.clickData(kb, q.get("data"));
        } else {
            admin.click(kb, q.get("label"));
        }
        if (q.containsKey("kill")) {
            boolean quiet = admin.awaitQuiet(Duration.ofMillis(num(q, "quiet", 1500)), Duration.ofMillis(num(q, "max", 10000)));
            return "quiet=" + quiet + "\nkilled=" + killed.get() + "\n" + describe(from, t0);
        }
        String until = awaitUntil(q, from, t0);
        boolean quiet = admin.awaitQuiet(Duration.ofMillis(num(q, "quiet", 1500)), Duration.ofMillis(num(q, "max", 60000)));
        return "quiet=" + quiet + "\n" + until + describe(from, t0);
    }

    private Optional<RecordedCall> lastKeyboard() {
        List<RecordedCall> all = fake.calls();
        for (int i = all.size() - 1; i >= 0; i--) {
            RecordedCall c = all.get(i);
            if (c.isSend() && c.isOk() && c.hasKeyboard()) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    /**
     * Uploads one file as the admin and answers its dialogs. Ends when the
     * bot has said "processed successfully" (or an error) and then stayed
     * quiet for {@code quiet} ms. Times are measured from the moment the
     * update was queued (the bot's own poll pick-up latency is included).
     */
    private String upload(Map<String, String> q) throws Exception {
        Path file = Path.of(q.get("file"));
        String name = q.getOrDefault("name", file.getFileName().toString());
        byte[] bytes = Files.readAllBytes(file);
        long quietMs = num(q, "quiet", 4000);
        long maxMs = num(q, "max", 900_000);
        String killSig = q.get("kill");
        long pid = num(q, "pid", -1);
        String killAt = q.getOrDefault("at", "delay");
        long killMs = num(q, "ms", 0);
        boolean answer = !"0".equals(q.get("answer"));

        int from = fake.callCount();
        Instant t0 = Instant.now();
        admin.upload(name, bytes);
        StringBuilder log = new StringBuilder();
        AtomicReference<String> killed = new AtomicReference<>("");
        if (killSig != null && killAt.equals("delay")) {
            Thread k = new Thread(() -> {
                try {
                    Thread.sleep(killMs);
                    killed.set(ms(t0) + "ms " + kill(pid, killSig));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            k.setDaemon(true);
            k.start();
        }
        long deadline = System.nanoTime() + Duration.ofMillis(maxMs).toNanos();
        int cursor = from;
        String outcome = "timeout";
        long firstReplyMs = -1;
        long successMs = -1;
        int dialogs = 0;
        boolean downloadSeen = false;
        while (System.nanoTime() < deadline) {
            if (killSig != null && killAt.equals("download") && !downloadSeen
                    && fake.findCall(from, c -> c.method.equals(FakeTelegramServer.FILE_DOWNLOAD)).isPresent()) {
                downloadSeen = true;
                killed.set(ms(t0) + "ms " + kill(pid, killSig));
                outcome = "killed-after-download";
                break;
            }
            RecordedCall c;
            try {
                int idx = cursor;
                c = fake.awaitCall(idx, x -> x.isSend(), Duration.ofMillis(250), "send");
            } catch (AssertionError timeout) {
                if (!killed.get().isEmpty() && !processAlive(pid)) {
                    outcome = "bot-gone";
                    break;
                }
                continue;
            }
            cursor = fake.indexOf(c) + 1;
            long at = Duration.between(t0, c.at).toMillis();
            if (firstReplyMs < 0) {
                firstReplyMs = at;
            }
            String text = c.text() == null ? String.valueOf(c.param("caption")) : c.text();
            List<RecordedCall.Button> choices = c.buttons().stream()
                    .filter(b -> b.callbackData() != null && b.callbackData().startsWith("choice_")).toList();
            log.append("  +").append(at).append("ms ").append(c.method).append(' ').append(c.status).append(' ')
                    .append(oneLine(text, 160)).append('\n');
            if (!choices.isEmpty()) {
                dialogs++;
                if (killSig != null && killAt.equals("dialog")) {
                    killed.set(ms(t0) + "ms " + kill(pid, killSig));
                    outcome = "killed-at-dialog";
                    break;
                }
                if (!answer) {
                    outcome = "left-waiting-at-dialog";
                    break;
                }
                String[] labels = choices.stream().map(RecordedCall.Button::text).toArray(String[]::new);
                int pick = pick(text, labels);
                log.append("    -> answer [").append(pick).append("] ").append(labels[pick]).append('\n');
                admin.clickData(c, choices.get(pick).callbackData());
            } else if (text != null && text.contains("processed successfully")) {
                successMs = at;
                outcome = "success";
                if (killSig != null && killAt.equals("success")) {
                    killed.set(ms(t0) + "ms " + kill(pid, killSig));
                    outcome = "killed-after-success";
                    break;
                }
            } else if (text != null && (text.contains("ERROR") || text.contains("Failed") || text.startsWith("🔴")
                    || text.contains("cancelled"))) {
                outcome = "error";
            }
            if (outcome.equals("success") || outcome.equals("error")) {
                // drain: everything the upload still says (recalc, retrain, distil) until quiet
                long lastAt = at;
                while (true) {
                    try {
                        int idx = cursor;
                        RecordedCall more = fake.awaitCall(idx, x -> !x.method.equals("getUpdates"),
                                Duration.ofMillis(quietMs), "more");
                        cursor = fake.indexOf(more) + 1;
                        lastAt = Duration.between(t0, more.at).toMillis();
                        if (more.isSend()) {
                            String mt = more.text() == null ? String.valueOf(more.param("caption")) : more.text();
                            log.append("  +").append(lastAt).append("ms ").append(more.method).append(' ')
                                    .append(more.status).append(' ').append(oneLine(mt, 160)).append('\n');
                        }
                    } catch (AssertionError quiet) {
                        break;
                    }
                }
                StringBuilder out = new StringBuilder();
                out.append("file=").append(name).append('\n')
                        .append("outcome=").append(outcome).append('\n')
                        .append("bytes=").append(bytes.length).append('\n')
                        .append("dialogs=").append(dialogs).append('\n')
                        .append("first_reply_ms=").append(firstReplyMs).append('\n')
                        .append("success_ms=").append(successMs).append('\n')
                        .append("last_message_ms=").append(lastAt).append('\n')
                        .append("killed=").append(killed.get()).append('\n')
                        .append("rejections=").append(fake.rejections().size()).append('\n')
                        .append(log);
                return out.toString();
            }
        }
        return "file=" + name + "\noutcome=" + outcome + "\nbytes=" + bytes.length + "\ndialogs=" + dialogs
                + "\nfirst_reply_ms=" + firstReplyMs + "\nsuccess_ms=" + successMs + "\nlast_message_ms=-1\nkilled="
                + killed.get() + "\nrejections=" + fake.rejections().size() + "\n" + log;
    }

    private static int pick(String text, String[] labels) {
        if (text != null && text.startsWith("⚠️ This round contains a WALKOVER")) {
            return 0;
        }
        if (text != null) {
            for (String[] s : SCRIPT) {
                if (text.contains(s[0])) {
                    return Integer.parseInt(s[1]);
                }
            }
        }
        return BotHarness.autoAnswer(text == null ? "" : text, labels);
    }

    private static boolean processAlive(long pid) {
        return pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** TERM = ProcessHandle.destroy() (SIGTERM on Linux), KILL = destroyForcibly() (SIGKILL). */
    private static String kill(long pid, String sig) {
        Optional<ProcessHandle> h = ProcessHandle.of(pid);
        if (h.isEmpty()) {
            return "kill " + sig + " pid=" + pid + " NOT-FOUND";
        }
        boolean sent = sig.equalsIgnoreCase("KILL") ? h.get().destroyForcibly() : h.get().destroy();
        return "kill " + sig + " pid=" + pid + " sent=" + sent;
    }

    private static long ms(Instant t0) {
        return Duration.between(t0, Instant.now()).toMillis();
    }

    private String stats() {
        List<RecordedCall> all = fake.calls();
        Map<String, Integer> byMethod = new LinkedHashMap<>();
        for (RecordedCall c : all) {
            byMethod.merge(c.method, 1, Integer::sum);
        }
        return "uptime_ms=" + ms(started) + "\ncalls=" + all.size() + "\nby_method=" + byMethod
                + "\nviolations=" + fake.violations().size() + "\nrejections=" + fake.rejections().size()
                + "\nrejection_list=" + fake.rejections() + "\nunknown_methods=" + fake.unknownMethodCalls().size()
                + "\nheld_polls=" + fake.heldPolls() + "\npending_updates=" + fake.pendingUpdates() + "\n";
    }

    /** Writes every photo/document the bot sent since call index {@code since} to {@code <ctl>/<dir>/}. */
    private String save(int since, String dir, String prefix) throws IOException {
        Path out = ctl.resolve(dir);
        Files.createDirectories(out);
        StringBuilder sb = new StringBuilder();
        List<RecordedCall> all = fake.calls();
        for (int i = Math.max(0, since); i < all.size(); i++) {
            RecordedCall c = all.get(i);
            if (c.file() == null || c.file().bytes() == null) {
                continue;
            }
            String kind = c.method.equalsIgnoreCase("sendPhoto") ? "photo" : "doc";
            Path f = out.resolve(prefix + String.format("%05d", c.seq) + "_" + kind + "_" + c.file().fileName());
            Files.write(f, c.file().bytes());
            sb.append(f.getFileName()).append(" bytes=").append(c.file().size());
            if (c.file().width() > 0) {
                sb.append(" px=").append(c.file().width()).append('x').append(c.file().height());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String calls(int since) {
        return describe(since, started);
    }

    private String describe(int from, Instant t0) {
        StringBuilder sb = new StringBuilder();
        List<RecordedCall> all = fake.calls();
        List<RecordedCall> mine = new ArrayList<>();
        for (int i = Math.max(0, from); i < all.size(); i++) {
            if (!all.get(i).method.equals("getUpdates")) {
                mine.add(all.get(i));
            }
        }
        sb.append("next_index=").append(all.size()).append('\n');
        for (RecordedCall c : mine) {
            sb.append("  +").append(Duration.between(t0, c.at).toMillis()).append("ms ").append(c.method).append(' ')
                    .append(c.status).append(" chat=").append(c.chatId());
            if (c.file() != null) {
                sb.append(" file=").append(c.file().fileName()).append(" bytes=").append(c.file().size());
                if (c.file().width() > 0) {
                    sb.append(" px=").append(c.file().width()).append('x').append(c.file().height());
                }
            }
            String t = c.text() != null ? c.text() : c.param("caption");
            if (t != null) {
                sb.append(" text=").append(oneLine(t, 200));
            }
            if (c.hasKeyboard()) {
                sb.append(" buttons=");
                for (RecordedCall.Button b : c.buttons()) {
                    sb.append('[').append(oneLine(b.text(), 40)).append('|').append(b.callbackData()).append(']');
                }
            }
            if (!c.isOk()) {
                sb.append(" response=").append(oneLine(c.responseBody, 160));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String oneLine(String s, int max) {
        if (s == null) {
            return "null";
        }
        String t = s.replace("\r", "").replace("\n", "\\n");
        return t.length() > max ? t.substring(0, max) + "..." : t;
    }
}
