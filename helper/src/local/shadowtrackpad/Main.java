package local.shadowtrackpad;

import android.net.LocalServerSocket;
import android.net.LocalSocket;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Shadow Trackpad Fix daemon. Runs as the adb shell user via app_process (like scrcpy's server).
 *
 * While Shadow PC holds pointer capture, it grabs the trackpad exclusively (EVIOCGRAB), interprets
 * the raw fingers itself and injects events that Shadow handles correctly:
 *  - cursor motion and clicks as captured relative-mouse events,
 *  - two-finger scroll (with momentum) as a synthetic two-finger captured-touchpad drag, which
 *    Shadow turns into fine-grained wheel deltas,
 *  - pinch as Ctrl + wheel, three/four-finger swipes as Windows shortcuts.
 * When Shadow is not capturing the pointer, the trackpad is released and Android behaves normally.
 * If this process dies, the kernel drops the grab automatically.
 *
 * Control: a local socket "shadowtrackpad" accepting "status", "stop", "pause", "resume".
 */
public final class Main {
    static final String SOCKET = "shadowtrackpad";
    static final SimpleDateFormat TS = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    static volatile boolean verbose;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("selftest")) {
            SelfTest.run();
            return;
        }
        if (args.length > 0 && !args[0].equals("run")) {
            System.out.println(sendCommand(String.join(" ", args)));
            return;
        }
        LocalServerSocket server;
        try {
            server = new LocalServerSocket(SOCKET);
        } catch (IOException e) {
            System.out.println("already running");
            return;
        }
        startFileLog();
        ensureToken();
        Config config = new Config();
        Injector injector = new Injector();
        Daemon daemon = new Daemon(config, injector);
        Runtime.getRuntime().addShutdownHook(new Thread(daemon::shutdown));
        Thread t = new Thread(daemon::run, "daemon");
        t.start();
        Thread st = new Thread(() -> statusServer(daemon), "status-server");
        st.setDaemon(true);
        st.start();
        log("started (pid " + android.os.Process.myPid() + ")");
        while (true) {
            try (LocalSocket s = server.accept()) {
                s.setSoTimeout(2000); // a silent client can't block the other commands
                PrintWriter out = new PrintWriter(s.getOutputStream(), true);
                String cmd = readLine(s.getInputStream(), 512, 2000);
                if (cmd == null) continue;
                cmd = cmd.trim();
                // Every command must carry the secret token (the shell tools read it from the
                // shell-only token file) - nothing else can drive the helper.
                String token = readToken();
                if (token == null || !cmd.startsWith("token " + token + " ")) {
                    out.println("denied");
                    continue;
                }
                cmd = cmd.substring(("token " + token + " ").length()).trim();
                switch (cmd) {
                    case "stop":
                        out.println("stopping");
                        out.flush();
                        daemon.shutdown();
                        log("stopped by request");
                        System.exit(0);
                        break;
                    case "pause": daemon.setPaused(true); out.println("paused"); break;
                    case "resume": daemon.setPaused(false); out.println("resumed"); break;
                    case "verbose": verbose = !verbose; out.println("verbose=" + verbose); break;
                    case "reload": config.load(); out.println("reloaded: " + config); break;
                    case "test-stall": daemon.stallTest = true; out.println("main loop will freeze for 4 s (watchdog test)"); break;
                    default: out.println(daemon.status()); break;
                }
            } catch (Throwable e) {
                logLimited("control", "control socket: " + e);
            }
        }
    }

    /**
     * Reads one line of at most maxLen bytes within timeoutMs overall (null if there is none), so
     * no client can make the helper buffer without limit or wait forever.
     */
    static String readLine(java.io.InputStream in, int maxLen, long timeoutMs) throws IOException {
        long deadline = android.os.SystemClock.uptimeMillis() + timeoutMs;
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        while (b.size() < maxLen && android.os.SystemClock.uptimeMillis() < deadline) {
            int c = in.read();
            if (c < 0 || c == '\n') break;
            b.write(c);
        }
        if (b.size() == 0 && android.os.SystemClock.uptimeMillis() >= deadline) return null;
        return b.toString("UTF-8");
    }

    /** Ports the app's health check asks for status (read-only; nothing can be controlled here). */
    static final int[] STATUS_PORTS = {51742, 51743};

    /**
     * Lets the Shadow Trackpad Fix app see whether the helper is alive and healthy without adb
     * (so Wireless debugging is only needed to (re)start it). Only "status" is answered.
     */
    private static void statusServer(Daemon daemon) {
        java.net.ServerSocket server = null;
        for (int port : STATUS_PORTS) {
            try {
                server = new java.net.ServerSocket(port, 4, java.net.InetAddress.getByName("127.0.0.1"));
                log("status port " + port);
                break;
            } catch (IOException e) {
                log("status port " + port + " unavailable: " + e);
            }
        }
        if (server == null) return;
        while (true) {
            try (java.net.Socket s = server.accept()) {
                s.setSoTimeout(500);
                String cmd = readLine(s.getInputStream(), 200, 1000);
                String[] parts = (cmd == null ? "" : cmd.trim()).split(" ");
                if (parts[0].equals("status") && parts.length > 1 && parts[1].matches("[0-9a-f]{16,64}")) {
                    // Signed reply: HMAC-SHA256(secret token shared with the app, nonce + reply), so
                    // another app squatting or relaying this port can neither impersonate a healthy
                    // helper nor alter what it says. Nothing about other apps is included.
                    String body = daemon.status(false);
                    String reply = "auth " + sign(parts[1] + "\n" + body) + "\n" + body + "\n";
                    s.getOutputStream().write(reply.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {
                // never let one bad request end the status service
            }
        }
    }

    static final java.io.File TOKEN = new java.io.File("/data/local/tmp/shadowtrackpad/token");

    /** The shared secret (null = unreadable: then nothing is accepted). */
    static String readToken() {
        try {
            String t = new String(java.nio.file.Files.readAllBytes(TOKEN.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
            return t.length() >= 16 ? t : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Without a token every control command would be refused, so a helper started before the app
     * ever ran creates one itself (the app replaces it with its own when it next starts the helper).
     */
    private static void ensureToken() {
        if (readToken() != null) return;
        try {
            byte[] b = new byte[24];
            new java.security.SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            java.io.File tmp = new java.io.File(TOKEN.getPath() + ".new");
            java.nio.file.Files.write(tmp.toPath(), (sb + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            android.system.Os.chmod(tmp.getPath(), 0600);
            if (!tmp.renameTo(TOKEN)) throw new IOException("rename failed");
            log("created a control token");
        } catch (Exception e) {
            log("could not create a control token: " + e);
        }
    }

    static String sign(String msg) {
        String token = readToken();
        if (token == null) return "none";
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(token.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder sb = new StringBuilder();
            for (byte b : mac.doFinal(msg.getBytes(java.nio.charset.StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "none";
        }
    }

    static String sendCommand(String cmd) {
        try (LocalSocket s = new LocalSocket()) {
            s.connect(new android.net.LocalSocketAddress(SOCKET));
            PrintWriter out = new PrintWriter(s.getOutputStream(), true);
            String token = readToken();
            if (token == null) return "cannot read the helper's token file (" + TOKEN + ")";
            out.println("token " + token + " " + cmd);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
            StringBuilder sb = new StringBuilder();
            for (String l; (l = in.readLine()) != null; ) sb.append(l).append('\n');
            return sb.toString().trim();
        } catch (IOException e) {
            return "not running";
        }
    }

    // The helper rotates its own log at 2 MB while running (the supervisor's between-runs rotation
    // alone isn't enough: the helper normally runs for days). stdout and stderr (file descriptors
    // 1 and 2, so Java and native output alike) are pointed at the current log file each time.
    private static final java.io.File LOG = new java.io.File("/data/local/tmp/shadowtrackpad/daemon.log");
    private static final long LOG_MAX = 2_000_000;
    private static boolean logToFile;
    private static long logBytes;

    private static void pointStdioAt(java.io.File f) throws Exception {
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true)) {
            android.system.Os.dup2(fos.getFD(), 1);
            android.system.Os.dup2(fos.getFD(), 2);
        }
    }

    private static synchronized void startFileLog() {
        try {
            pointStdioAt(LOG);
            logBytes = LOG.length();
            logToFile = true;
        } catch (Exception e) {
            System.out.println("log file: " + e);
        }
    }

    private static void rotateLog() {
        logBytes = 0; // on failure: keep the current file, try again after another 2 MB
        try {
            System.out.flush();
            java.io.File old = new java.io.File(LOG.getPath() + ".1");
            old.delete();
            if (LOG.renameTo(old)) pointStdioAt(LOG);
        } catch (Exception ignored) {
        }
    }

    static synchronized void log(String s) {
        String line = TS.format(new Date()) + " " + s;
        System.out.println(line);
        System.out.flush();
        if (logToFile && (logBytes += line.length() + 1) > LOG_MAX) rotateLog();
    }

    private static final java.util.Map<String, long[]> LIMITED = new java.util.HashMap<>();

    /** Logs a recurring problem at most once a minute (with a count of suppressed repeats). */
    static void logLimited(String key, String s) {
        long now = android.os.SystemClock.uptimeMillis();
        synchronized (LIMITED) {
            long[] st = LIMITED.get(key);
            if (st == null) {
                st = new long[]{-60_000, 0};
                LIMITED.put(key, st);
            }
            if (now - st[0] < 60_000) {
                st[1]++;
                return;
            }
            if (st[1] > 0) s += " (repeated " + st[1] + "x in the last minute)";
            st[0] = now;
            st[1] = 0;
        }
        log(s);
    }

    static void debug(String s) {
        if (verbose) log(s);
    }
}
