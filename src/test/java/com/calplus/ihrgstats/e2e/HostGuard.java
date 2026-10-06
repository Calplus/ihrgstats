package com.calplus.ihrgstats.e2e;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Catches any HTTP request the bot attempts to a host other than the fake.
 *
 * Installed as the JVM-wide default {@link ProxySelector}: java.net.http clients
 * built while it is installed (the listener, the log channels, the file
 * downloader and /about all build theirs per object) consult it for every
 * request. Allowed authorities get a direct connection; anything else is
 * recorded as a violation and routed to a local "sinkhole" proxy that accepts
 * and immediately closes the connection - so the request fails fast and no
 * packet ever leaves the machine (the client never resolves or contacts the
 * real host). HttpURLConnection consults the same selector. Raw sockets do not;
 * the bot has none.
 */
public class HostGuard extends ProxySelector implements AutoCloseable {

    private final ProxySelector previous;
    private final Set<String> allowed = ConcurrentHashMap.newKeySet();
    private final List<URI> seen = new CopyOnWriteArrayList<>();
    private final List<URI> violations = new CopyOnWriteArrayList<>();
    private final ServerSocket sinkhole;
    private final Thread sinkholeThread;
    private volatile boolean closed;

    private HostGuard(Set<String> allowedAuthorities) throws IOException {
        this.previous = ProxySelector.getDefault();
        for (String a : allowedAuthorities) {
            allowed.add(a.toLowerCase(Locale.ROOT));
        }
        this.sinkhole = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.sinkholeThread = new Thread(() -> {
            while (!closed) {
                try (Socket s = sinkhole.accept()) {
                    s.setSoLinger(true, 0); // reset, not a graceful close
                } catch (IOException e) {
                    if (closed) {
                        return;
                    }
                }
            }
        }, "host-guard-sinkhole");
        sinkholeThread.setDaemon(true);
        sinkholeThread.start();
    }

    /** Installs a guard allowing only the given "host:port" authorities. */
    public static HostGuard install(String... allowedAuthorities) throws IOException {
        HostGuard guard = new HostGuard(Set.of(allowedAuthorities));
        ProxySelector.setDefault(guard);
        return guard;
    }

    public void allow(String authority) {
        allowed.add(authority.toLowerCase(Locale.ROOT));
    }

    @Override
    public List<Proxy> select(URI uri) {
        seen.add(uri);
        if (isAllowed(uri)) {
            return List.of(Proxy.NO_PROXY);
        }
        violations.add(uri);
        System.err.println("[HostGuard] BLOCKED request to " + redact(uri));
        return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(InetAddress.getLoopbackAddress(), sinkhole.getLocalPort())));
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        // expected for blocked requests
    }

    private boolean isAllowed(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        int port = uri.getPort();
        if (port < 0) {
            port = "https".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }
        return allowed.contains((host + ":" + port).toLowerCase(Locale.ROOT));
    }

    /** Every URI any guarded client asked to reach (allowed or not), token redacted on output only. */
    public List<URI> seen() {
        return new ArrayList<>(seen);
    }

    /** URIs to hosts other than the allowed ones. */
    public List<URI> violations() {
        return new ArrayList<>(violations);
    }

    /** Throws if any request went (or tried to go) anywhere but the fake. */
    public void assertNoViolations() {
        if (!violations.isEmpty()) {
            StringBuilder sb = new StringBuilder("requests attempted to hosts other than the fake server:");
            for (URI u : violations) {
                sb.append("\n  ").append(redact(u));
            }
            throw new AssertionError(sb.toString());
        }
    }

    /** URI text with any bot token removed. */
    public static String redact(URI uri) {
        return TranscriptWriter.redact(uri.toString());
    }

    /**
     * Uninstalls this guard. The JVM default does NOT go back to the platform
     * selector: it becomes {@link #lockdown()}, which keeps blocking every
     * non-loopback host for the rest of the test JVM - so a thread left over from
     * a scenario, or a client built after it, can never reach a real API host.
     */
    @Override
    public void close() {
        if (ProxySelector.getDefault() == this) {
            ProxySelector.setDefault(lockdown());
        }
        closed = true;
        try {
            sinkhole.close();
        } catch (IOException ignored) {
            // closing
        }
    }

    private static volatile ProxySelector lockdown;

    /** A permanent selector: loopback hosts direct, everything else to a reset-on-accept sinkhole. */
    public static synchronized ProxySelector lockdown() {
        if (lockdown == null) {
            try {
                HostGuard l = new HostGuard(Set.of()) {
                    @Override
                    public List<Proxy> select(URI uri) {
                        String h = uri.getHost();
                        if (h != null && (h.equals("127.0.0.1") || h.equals("localhost") || h.equals("[::1]"))) {
                            return List.of(Proxy.NO_PROXY);
                        }
                        return super.select(uri);
                    }

                    @Override
                    public void close() {
                        // never closed
                    }
                };
                lockdown = l;
            } catch (IOException e) {
                throw new IllegalStateException("cannot create network lockdown", e);
            }
        }
        return lockdown;
    }
}
