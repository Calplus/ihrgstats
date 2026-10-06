package com.calplus.ihrgstats.e2e;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Lane a1b helpers on top of the a1a rig (new file; the rig's API is unchanged):
 * a step runner that waits for "a keyboard or the bot went quiet", a picker
 * crawler, a cumulative matrix log and a cumulative limits ledger. Both logs
 * are rewritten under target/e2e-transcripts/ after every scenario so a
 * partial run still leaves evidence.
 */
final class A1bSupport {

    private A1bSupport() {
    }

    /** Where every a1b artefact goes (inside the worktree's target folder). */
    static Path outDir() {
        return BotHarness.projectDir().resolve("target").resolve("e2e-transcripts");
    }

    // ------------------------------------------------------------------ call slices

    /** Non-poll calls with index in [from, to). */
    static List<RecordedCall> slice(FakeTelegramServer fake, int from, int to) {
        List<RecordedCall> all = fake.calls();
        List<RecordedCall> out = new ArrayList<>();
        for (int i = Math.max(0, from); i < Math.min(to, all.size()); i++) {
            RecordedCall c = all.get(i);
            if (!c.method.equals("getUpdates")) {
                out.add(c);
            }
        }
        return out;
    }

    static List<RecordedCall> sliceFrom(FakeTelegramServer fake, int from) {
        return slice(fake, from, Integer.MAX_VALUE);
    }

    /** True for remote-log traffic (Telegram dev chat or Discord), which is not a user-facing reply. */
    static boolean isLogTraffic(RecordedCall c, String devChatId) {
        if (c.method.equals(FakeTelegramServer.DISCORD_CREATE_MESSAGE)) {
            return true;
        }
        return devChatId != null && !devChatId.isEmpty() && devChatId.equals(c.chatId());
    }

    /** User-facing sends (send* not to the dev chat). */
    static List<RecordedCall> userSends(List<RecordedCall> calls, String devChatId) {
        return calls.stream().filter(c -> c.isSend() && !isLogTraffic(c, devChatId)).toList();
    }

    /** A one-line description of what the bot did. */
    static String summarize(List<RecordedCall> calls, String devChatId) {
        List<String> parts = new ArrayList<>();
        int log = 0;
        for (RecordedCall c : calls) {
            if (isLogTraffic(c, devChatId)) {
                log++;
                continue;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(c.method);
            if (c.status != 200) {
                sb.append("!").append(c.status);
            }
            if (c.isSend()) {
                sb.append("->").append(c.chatId());
                if (c.threadId() != null) {
                    sb.append("#").append(c.threadId());
                }
                if (c.hasKeyboard()) {
                    sb.append("[kb ").append(c.buttons().size()).append("]");
                }
                if (c.file() != null) {
                    RecordedCall.FilePart f = c.file();
                    sb.append("[").append(f.fileName()).append(" ").append(f.width()).append("x").append(f.height())
                            .append(" ").append(f.size()).append("B]");
                }
                String t = c.text() != null ? c.text() : c.param("caption");
                if (t != null) {
                    String one = t.replace("\r", "").replace("\n", "/");
                    sb.append(" \"").append(one.length() > 70 ? one.substring(0, 70) + "..." : one).append("\"");
                }
            }
            if (!c.violations.isEmpty()) {
                sb.append(" {").append(c.violations.size()).append(" violation(s)}");
            }
            parts.add(sb.toString());
        }
        if (log > 0) {
            parts.add("+" + log + " log call(s)");
        }
        return parts.isEmpty() ? "SILENT" : String.join(" | ", parts);
    }

    // ------------------------------------------------------------------ steps

    /** What one injected action produced. */
    record Step(int fromIndex, int toIndex, RecordedCall keyboard, List<RecordedCall> calls, boolean silent, long firstReplyMs) {
        String summary(String devChatId) {
            return summarize(calls, devChatId);
        }
    }

    /**
     * Waits after an action injected at call index {@code from}: returns as soon
     * as a successful send with a keyboard to {@code chatId} appears, otherwise
     * once at least one user-facing call has arrived and nothing new came for
     * {@code quiet}, otherwise after {@code firstReplyMax} with nothing (silent).
     */
    static Step awaitStep(FakeTelegramServer fake, int from, String chatId, String devChatId,
                          Duration firstReplyMax, Duration quiet) {
        long start = System.nanoTime();
        long firstDeadline = start + firstReplyMax.toNanos();
        long hardDeadline = start + firstReplyMax.toNanos() + Duration.ofSeconds(60).toNanos();
        int lastCount = -1;
        long lastChange = System.nanoTime();
        long firstReply = -1;
        while (true) {
            List<RecordedCall> calls = sliceFrom(fake, from);
            for (RecordedCall c : calls) {
                if (c.isSend() && c.isOk() && c.hasKeyboard() && !isLogTraffic(c, devChatId)
                        && (chatId == null || chatId.equals(c.chatId()))) {
                    int to = fake.indexOf(c) + 1;
                    long ms = (System.nanoTime() - start) / 1_000_000;
                    return new Step(from, to, c, slice(fake, from, to), false, firstReply >= 0 ? firstReply : ms);
                }
            }
            boolean anyUser = calls.stream().anyMatch(c -> !isLogTraffic(c, devChatId)
                    && (c.isSend() || c.method.equalsIgnoreCase("editMessageText")));
            if (anyUser && firstReply < 0) {
                firstReply = (System.nanoTime() - start) / 1_000_000;
            }
            if (calls.size() != lastCount) {
                lastCount = calls.size();
                lastChange = System.nanoTime();
            }
            long now = System.nanoTime();
            if (anyUser && now - lastChange >= quiet.toNanos()) {
                int to = fake.callCount();
                return new Step(from, to, null, slice(fake, from, to), false, firstReply);
            }
            if (!anyUser && now >= firstDeadline && now - lastChange >= quiet.toNanos()) {
                int to = fake.callCount();
                return new Step(from, to, null, slice(fake, from, to), true, -1);
            }
            if (now >= hardDeadline) {
                int to = fake.callCount();
                return new Step(from, to, null, slice(fake, from, to), !anyUser, firstReply);
            }
            sleep(40);
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted");
        }
    }

    /** Waits until the listener is idle in a held poll and no update is pending. */
    static void awaitIdle(FakeTelegramServer fake, Duration max) {
        long end = System.nanoTime() + max.toNanos();
        while (System.nanoTime() < end) {
            if (fake.pendingUpdates() == 0 && fake.heldPolls() > 0) {
                return;
            }
            sleep(20);
        }
    }

    // ------------------------------------------------------------------ crawler

    /** One explored path: the clicks, what the last action produced, and whether it ended in a keyboard. */
    record PathResult(String command, List<String> clicks, Step last, boolean diverged, String note) {
        String pathString() {
            return clicks.isEmpty() ? "(command only)" : String.join(" > ", clicks);
        }
    }

    /** Groups callback data by replacing digit runs with '#'. */
    static String kind(String data) {
        return data == null ? "<no callback_data>" : data.replaceAll("[0-9]+", "#");
    }

    /**
     * Breadth-first crawl of a picker command: every distinct button kind at every
     * depth is clicked once (first button of the kind, replaying the path from the
     * command each time so wizard state is genuine). Paths stop at maxDepth or when
     * a step returns no keyboard (terminal).
     */
    static final class Crawler {
        final FakeTelegramServer fake;
        final ConversationDriver driver;
        final String command;
        final String devChatId;
        int maxPaths = 30;
        int maxDepth = 6;
        Duration firstReplyMax = Duration.ofSeconds(12);
        Duration quiet = Duration.ofMillis(1500);
        Predicate<RecordedCall.Button> skip = b -> false;
        boolean alsoLast = true;
        /** Fixture halls 1-8 and HallA-C have matches; 9-16 have none. Prefer those as kind representatives. */
        Predicate<RecordedCall.Button> prefer = b -> b.text() != null && b.text().matches("^([1-8]|Hall ?[1-8]|Hall[ABC])$");
        final List<PathResult> results = new ArrayList<>();
        final Set<String> seenKinds = new LinkedHashSet<>();
        int replays;

        Crawler(FakeTelegramServer fake, ConversationDriver driver, String command, String devChatId) {
            this.fake = fake;
            this.driver = driver;
            this.command = command;
            this.devChatId = devChatId;
        }

        List<PathResult> crawl() {
            Deque<List<RecordedCall.Button>> queue = new ArrayDeque<>();
            queue.add(List.of());
            while (!queue.isEmpty() && results.size() < maxPaths) {
                List<RecordedCall.Button> path = queue.poll();
                PathResult r = replay(path);
                results.add(r);
                if (r.last.keyboard() != null && !r.diverged && path.size() < maxDepth) {
                    Map<String, List<RecordedCall.Button>> byKind = new LinkedHashMap<>();
                    for (RecordedCall.Button b : r.last.keyboard().buttons()) {
                        if (b.callbackData() == null || skip.test(b)) {
                            continue;
                        }
                        byKind.computeIfAbsent(stableKind(b.callbackData()), k -> new ArrayList<>()).add(b);
                    }
                    for (Map.Entry<String, List<RecordedCall.Button>> e : byKind.entrySet()) {
                        List<RecordedCall.Button> group = e.getValue();
                        // Representative: the first button the scenario prefers (e.g. a hall that has data), else the first.
                        RecordedCall.Button rep = group.stream().filter(prefer).findFirst().orElse(group.get(0));
                        if (seenKinds.add(path.size() + "|" + e.getKey())) {
                            List<RecordedCall.Button> next = new ArrayList<>(path);
                            next.add(rep);
                            queue.add(next);
                        }
                        // Boundary: the last button of every kind with 3+ buttons (in the fixtures the last hall
                        // is one with no players), once per depth and kind.
                        RecordedCall.Button last = group.get(group.size() - 1);
                        if (alsoLast && group.size() >= 3 && last != rep && seenKinds.add(path.size() + "|" + e.getKey() + "|last")) {
                            List<RecordedCall.Button> next = new ArrayList<>(path);
                            next.add(last);
                            queue.add(next);
                        }
                    }
                }
            }
            return results;
        }

        /** Runs the command and then the given clicks; returns what the last action produced. */
        PathResult replay(List<RecordedCall.Button> clicks) {
            replays++;
            List<String> labels = clicks.stream().map(b -> b.text() + "{" + b.callbackData() + "}").toList();
            awaitIdle(fake, Duration.ofSeconds(10));
            int from = fake.callCount();
            driver.sendText(command);
            Step step = awaitStep(fake, from, driver.chat().idString(), devChatId, firstReplyMax, quiet);
            for (int i = 0; i < clicks.size(); i++) {
                if (step.keyboard() == null) {
                    return new PathResult(command, labels, step, true, "no keyboard before click " + (i + 1));
                }
                RecordedCall.Button want = clicks.get(i);
                RecordedCall kb = step.keyboard();
                // Same data, else same label and same stable kind (dialog nonces change per run).
                RecordedCall.Button found = kb.buttons().stream().filter(b -> want.callbackData().equals(b.callbackData())).findFirst()
                        .orElseGet(() -> kb.buttons().stream().filter(b -> b.callbackData() != null && want.text().equals(b.text())
                                && stableKind(want.callbackData()).equals(stableKind(b.callbackData()))).findFirst().orElse(null));
                if (found == null) {
                    return new PathResult(command, labels, step, true, "button " + want.callbackData() + " absent on replay");
                }
                awaitIdle(fake, Duration.ofSeconds(10));
                int f = fake.callCount();
                driver.clickData(kb, found.callbackData());
                step = awaitStep(fake, f, driver.chat().idString(), devChatId, firstReplyMax, quiet);
            }
            return new PathResult(command, labels, step, false, "");
        }
    }

    /** kind() with UUID-like nonces folded too, so a dialog's buttons keep their kind across runs. */
    static String stableKind(String data) {
        if (data == null) {
            return kind(null);
        }
        return kind(data.replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "<uuid>")
                .replaceAll("_[0-9a-fA-F]{12,}$", "_<nonce>"));
    }

    // ------------------------------------------------------------------ matrix log

    private static final List<String> MATRIX = new ArrayList<>();
    private static boolean matrixLoaded;

    /** Rows written by earlier Maven runs are kept (delete target/e2e-transcripts/a1b-matrix.tsv for a clean run). */
    private static void loadMatrix() {
        if (matrixLoaded) {
            return;
        }
        matrixLoaded = true;
        Path f = outDir().resolve("a1b-matrix.tsv");
        try {
            if (Files.exists(f)) {
                List<String> rows = Files.readAllLines(f, StandardCharsets.UTF_8);
                MATRIX.addAll(rows.subList(Math.min(1, rows.size()), rows.size()).stream().filter(r -> !r.isBlank()).toList());
            }
        } catch (IOException e) {
            System.err.println("[a1b] cannot reload matrix: " + e);
        }
    }

    /** Adds one matrix cell and rewrites target/e2e-transcripts/a1b-matrix.tsv. */
    static synchronized void cell(String group, String command, String path, String actor, String chat, String outcome) {
        loadMatrix();
        MATRIX.add(String.join("\t", group, command, path, actor, chat, outcome.replace("\t", " ")));
        writeQuietly(outDir().resolve("a1b-matrix.tsv"),
                "group\tcommand\tpath\tactor\tchat\toutcome\n" + String.join("\n", MATRIX) + "\n");
    }

    static synchronized int cellCount() {
        return MATRIX.size();
    }

    // ------------------------------------------------------------------ limits ledger

    /** Per command: how many sends, the biggest of each kind and every validator verdict. */
    static final class Stats implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        int calls;
        int sends;
        int rejected;
        int noted;
        int maxText;
        String maxTextWhere = "";
        int maxCaption;
        int maxButtons;
        int maxCallbackBytes;
        String maxCallbackData = "";
        int maxImageW;
        int maxImageH;
        long maxImageBytes;
        int maxLineCodePoints;
        final Map<String, Integer> verdicts = new TreeMap<>();
    }

    private static TreeMap<String, Stats> LEDGER = new TreeMap<>();
    private static boolean ledgerLoaded;

    /** Ledger state of earlier Maven runs is kept in a1b-ledger.ser (delete it for a clean run). */
    @SuppressWarnings("unchecked")
    private static void loadLedger() {
        if (ledgerLoaded) {
            return;
        }
        ledgerLoaded = true;
        Path f = outDir().resolve("a1b-ledger.ser");
        if (Files.exists(f)) {
            try (java.io.ObjectInputStream in = new java.io.ObjectInputStream(Files.newInputStream(f))) {
                LEDGER = (TreeMap<String, Stats>) in.readObject();
            } catch (Exception e) {
                System.err.println("[a1b] cannot reload ledger: " + e);
            }
        }
    }

    private static void saveLedger() {
        try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(Files.newOutputStream(outDir().resolve("a1b-ledger.ser")))) {
            out.writeObject(LEDGER);
        } catch (IOException e) {
            System.err.println("[a1b] cannot save ledger: " + e);
        }
    }

    /** Records every call of a scenario slice under {@code command}; rewrites target/e2e-transcripts/a1b-limits.txt. */
    static synchronized void ledger(String command, List<RecordedCall> calls) {
        loadLedger();
        Stats s = LEDGER.computeIfAbsent(command, k -> new Stats());
        for (RecordedCall c : calls) {
            if (c.method.equals("getUpdates")) {
                continue;
            }
            s.calls++;
            for (RecordedCall.Violation v : c.violations) {
                if (v.rejected()) {
                    s.rejected++;
                } else {
                    s.noted++;
                }
                s.verdicts.merge(v.toString().length() > 140 ? v.toString().substring(0, 140) : v.toString(), 1, Integer::sum);
            }
            if (!c.isSend() && !c.method.equalsIgnoreCase("editMessageText") && !c.method.equalsIgnoreCase("editMessageReplyMarkup")) {
                continue;
            }
            if (c.isSend()) {
                s.sends++;
            }
            String t = c.text();
            if (t != null) {
                if (t.length() > s.maxText) {
                    s.maxText = t.length();
                    s.maxTextWhere = c.method + " #" + c.seq;
                }
                for (String line : t.split("\n", -1)) {
                    s.maxLineCodePoints = Math.max(s.maxLineCodePoints, line.codePointCount(0, line.length()));
                }
            }
            String cap = c.param("caption");
            if (cap != null) {
                s.maxCaption = Math.max(s.maxCaption, cap.length());
            }
            List<RecordedCall.Button> buttons = c.buttons();
            s.maxButtons = Math.max(s.maxButtons, buttons.size());
            for (RecordedCall.Button b : buttons) {
                if (b.callbackData() != null) {
                    int bytes = b.callbackData().getBytes(StandardCharsets.UTF_8).length;
                    if (bytes > s.maxCallbackBytes) {
                        s.maxCallbackBytes = bytes;
                        s.maxCallbackData = b.callbackData();
                    }
                }
            }
            for (RecordedCall.FilePart f : c.files) {
                s.maxImageW = Math.max(s.maxImageW, f.width());
                s.maxImageH = Math.max(s.maxImageH, f.height());
                s.maxImageBytes = Math.max(s.maxImageBytes, f.size());
            }
        }
        writeLedger();
        saveLedger();
    }

    private static void writeLedger() {
        StringBuilder sb = new StringBuilder();
        sb.append("# a1b limits ledger - every non-poll call the bot made in the a1b scenarios, grouped by command.\n");
        sb.append("# text = UTF-16 units of the raw text parameter (Telegram limit 4096 after entity parsing),\n");
        sb.append("# caption limit 1024, keyboard limit 100 buttons, callback_data 64 bytes, photo <=10 MB and w+h <= 10000.\n");
        sb.append("# verdicts = the fake's BotApiRules violations (REJECT = Telegram would answer 400; NOTE = tolerated).\n\n");
        sb.append(String.format("%-34s %6s %6s %5s %5s %9s %8s %5s %4s %8s %11s %8s%n", "command", "calls", "sends", "rej", "note",
                "maxText", "maxLine", "maxKb", "cbB", "maxCap", "maxImage", "imgKB"));
        for (Map.Entry<String, Stats> e : LEDGER.entrySet()) {
            Stats s = e.getValue();
            sb.append(String.format("%-34s %6d %6d %5d %5d %9d %8d %5d %4d %8d %11s %8d%n", e.getKey(), s.calls, s.sends, s.rejected,
                    s.noted, s.maxText, s.maxLineCodePoints, s.maxButtons, s.maxCallbackBytes, s.maxCaption,
                    s.maxImageW + "x" + s.maxImageH, s.maxImageBytes / 1024));
        }
        sb.append("\n## verdicts per command\n");
        for (Map.Entry<String, Stats> e : LEDGER.entrySet()) {
            if (e.getValue().verdicts.isEmpty()) {
                continue;
            }
            sb.append(e.getKey()).append(":\n");
            for (Map.Entry<String, Integer> v : e.getValue().verdicts.entrySet()) {
                sb.append("  ").append(v.getValue()).append("x ").append(v.getKey()).append('\n');
            }
        }
        sb.append("\n## longest callback_data per command\n");
        for (Map.Entry<String, Stats> e : LEDGER.entrySet()) {
            if (!e.getValue().maxCallbackData.isEmpty()) {
                sb.append(String.format("%-34s %3d bytes  %s%n", e.getKey(), e.getValue().maxCallbackBytes, e.getValue().maxCallbackData));
            }
        }
        writeQuietly(outDir().resolve("a1b-limits.txt"), sb.toString());
    }

    // ------------------------------------------------------------------ files

    static void writeQuietly(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, TranscriptWriter.redact(content), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[a1b] could not write " + file + ": " + e);
        }
    }

    /** Appends a free-form section to target/e2e-transcripts/a1b-notes.txt. */
    static synchronized void note(String title, String body) {
        Path f = outDir().resolve("a1b-notes.txt");
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, TranscriptWriter.redact("== " + title + "\n" + body + "\n\n"), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[a1b] could not append note: " + e);
        }
        System.out.println("[A1B] " + title + "\n" + body);
    }

    /** Counts calls of a method in a slice. */
    static long count(List<RecordedCall> calls, String method) {
        return calls.stream().filter(c -> c.method.equalsIgnoreCase(method)).count();
    }

    /** Pretty multi-line form of a slice for notes. */
    static String lines(List<RecordedCall> calls) {
        StringBuilder sb = new StringBuilder();
        for (RecordedCall c : calls) {
            sb.append("  ").append(c).append(c.violations.isEmpty() ? "" : " " + c.violations).append('\n');
        }
        return sb.toString();
    }

    /** Ordered map helper for small fixed tables. */
    static <K, V> Map<K, V> ordered() {
        return new LinkedHashMap<>();
    }
}
