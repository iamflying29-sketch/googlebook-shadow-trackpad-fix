package local.shadowtrackpadfix;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Starts / checks the trackpad helper on this device through Wireless debugging (adb over TLS
 * on 127.0.0.1) with the key that was paired once. Used at boot, by the periodic health job and
 * by the app's buttons. Never runs anything but the fixed supervisor commands below.
 */
final class Starter {
    static final String TAG = "ShadowTrackpadFix";
    static final String DIR = "/data/local/tmp/shadowtrackpad";
    static final String SHADOW = "com.blade.shadowcloudgaming";
    private static final String CH_PROBLEMS = "problems";
    private static final int NOTIF_PROBLEM = 1, NOTIF_UPDATE = 2;
    private static final Object LOCK = new Object();

    enum Action { ENSURE, RESTART, STOP }

    static final class Result {
        boolean ok;
        String summary = "";
        String status = "";
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("state", Context.MODE_PRIVATE);
    }

    static File keyFile(Context c) {
        return new File(c.getFilesDir(), "adb.p12");
    }

    static boolean provisioned(Context c) {
        return keyFile(c).exists() && prefs(c).contains("p12pass");
    }

    static String hex(int bytes) {
        byte[] b = new byte[bytes];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Secret shared with the helper (written to its shell-only token file when it is started). */
    static synchronized String token(Context ctx) {
        String t = prefs(ctx).getString("token", null);
        if (t == null) {
            t = hex(24);
            prefs(ctx).edit().putString("token", t).commit();
        }
        return t;
    }

    /**
     * Secret shared with the Linux terminal's script, derived from the paired private key (which
     * only this app and that script hold): SHA-256 of a deterministic RSA signature of a fixed
     * label. Lets the script check that it is really talking to this app on this Googlebook before
     * it sends the key, and lets it trust only port files this app wrote. Null if not set up.
     */
    static String keySecret(Context ctx) {
        try {
            KeyStoreHolder k = loadKey(ctx);
            java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
            sig.initSign(k.key);
            sig.update("shadowtrackpadfix-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(sig.sign())) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static final class KeyStoreHolder {
        java.security.PrivateKey key;
    }

    private static KeyStoreHolder loadKey(Context ctx) throws Exception {
        char[] pass = prefs(ctx).getString("p12pass", "").toCharArray();
        java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
        try (InputStream in = new java.io.FileInputStream(keyFile(ctx))) {
            ks.load(in, pass);
        }
        KeyStoreHolder h = new KeyStoreHolder();
        h.key = (java.security.PrivateKey) ks.getKey(ks.aliases().nextElement(), pass);
        return h;
    }

    /** HMAC-SHA256 with the key secret (hex), or "none". */
    static String keyMac(Context ctx, String msg) {
        String k = keySecret(ctx);
        if (k == null) return "none";
        byte[] kb = new byte[k.length() / 2];
        for (int i = 0; i < kb.length; i++) kb[i] = (byte) Integer.parseInt(k.substring(2 * i, 2 * i + 2), 16);
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(kb, "HmacSHA256"));
            StringBuilder sb = new StringBuilder();
            for (byte b : mac.doFinal(msg.getBytes(java.nio.charset.StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "none";
        }
    }

    /**
     * Asks the running helper for its status over its localhost port. The reply must carry
     * HMAC(token, our random nonce), so another app squatting the port can't fake a healthy
     * helper. Returns null if no genuine helper answered.
     */
    static String queryHelper(Context ctx) {
        String nonce = hex(16);
        String token = token(ctx);
        for (int port : new int[]{51742, 51743}) {
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 1000);
                s.setSoTimeout(1500);
                s.getOutputStream().write(("status " + nonce + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String text = readLimited(s.getInputStream(), 16384, 3000);
                int nl = text.indexOf('\n');
                if (nl <= 0) continue;
                // The signature covers the nonce and the whole reply, so nothing can be altered.
                String body = text.substring(nl + 1);
                if (body.endsWith("\n")) body = body.substring(0, body.length() - 1);
                if (text.substring(0, nl).trim().equals("auth " + hmac(token, nonce + "\n" + body)) && body.startsWith("running")) {
                    return body.trim();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** Reads at most max bytes within timeoutMs overall (so no other app can stall or flood us). */
    private static String readLimited(InputStream in, int max, long timeoutMs) throws java.io.IOException {
        long deadline = android.os.SystemClock.uptimeMillis() + timeoutMs;
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        while (b.size() < max && android.os.SystemClock.uptimeMillis() < deadline) {
            int n;
            try {
                n = in.read(buf, 0, Math.min(buf.length, max - b.size()));
            } catch (java.net.SocketTimeoutException e) {
                break;
            }
            if (n < 0) break;
            b.write(buf, 0, n);
        }
        return b.toString("UTF-8");
    }

    private static String hmac(String key, String msg) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder sb = new StringBuilder();
            for (byte b : mac.doFinal(msg.getBytes(java.nio.charset.StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * Routine check (boot, every 15 min, Wireless/USB debugging switched): a genuine, healthy
     * helper is left alone; a missing one is started; a running-but-unhealthy one is restarted
     * (at most once an hour) and the user is told if that doesn't fix it.
     */
    static Result check(Context ctx) {
        if (prefs(ctx).getBoolean("stopped", false)) return stoppedResult();
        String st = queryHelper(ctx);
        if (st != null && st.contains("HEALTH: ok")) {
            refreshPublishedPort(ctx);
            Result r = new Result();
            r.ok = true;
            r.status = st;
            r.summary = "Helper is running.";
            prefs(ctx).edit().putLong("lastCheck", System.currentTimeMillis()).putString("lastSummary", r.summary)
                    .putString("lastStatus", r.status).putBoolean("lastOk", true).apply();
            notifyResult(ctx, Action.ENSURE, r);
            return r;
        }
        long lastRepair = prefs(ctx).getLong("lastRepair", 0);
        if (st != null && System.currentTimeMillis() - lastRepair > 3_600_000) {
            prefs(ctx).edit().putLong("lastRepair", System.currentTimeMillis()).apply();
            return run(ctx, Action.RESTART, true);
        }
        return run(ctx, Action.ENSURE, true);
    }

    private static Result stoppedResult() {
        Result r = new Result();
        r.ok = true;
        r.summary = "Stopped by you (press Check / start to turn it back on).";
        return r;
    }

    /**
     * Keeps Documents/Shadow Trackpad Fix/adb-port.txt current even when adb itself isn't needed.
     * A new port is only published after it proved to be Android's own adb service.
     */
    private static void refreshPublishedPort(Context ctx) {
        try {
            if (adbWifi(ctx) != 1 || !provisioned(ctx)) return;
            List<int[]> ports = discoverOwnPorts(ctx, 4000);
            if (ports.isEmpty() || ports.get(0)[0] == prefs(ctx).getInt("publishedPort", -1)) return;
            byte[] p12 = Files.readAllBytes(keyFile(ctx).toPath());
            char[] pass = prefs(ctx).getString("p12pass", "").toCharArray();
            synchronized (LOCK) {
                for (int[] hp : ports) {
                    AdbClient adb = tryConnect(hp[0], p12, pass);
                    if (adb == null) continue;
                    try {
                        if (!proveShell(ctx, adb)) continue;
                        publishPort(ctx, hp[0]);
                        prefs(ctx).edit().putInt("port", hp[0]).putInt("publishedPort", hp[0]).apply();
                        return;
                    } finally {
                        adb.close();
                    }
                }
            }
        } catch (Exception e) {
            Log.i(TAG, "refreshPublishedPort: " + e);
        }
    }

    static int adbWifi(Context ctx) {
        return Settings.Global.getInt(ctx.getContentResolver(), "adb_wifi_enabled", 0);
    }

    /**
     * Proves that an adb connection really reaches Android's adb service (the shell user): only
     * the shell or the system can deliver a broadcast to ControlReceiver (it requires the DUMP
     * permission), so another app imitating Wireless debugging on a local port can't pass. Nothing
     * secret is sent over a connection before this check.
     */
    static boolean proveShell(Context ctx, AdbClient adb) {
        String nonce = hex(16);
        try {
            int user = android.os.Process.myUid() / 100000;
            adb.exec("am broadcast --user " + user + " -n " + ctx.getPackageName()
                    + "/.ControlReceiver -a local.shadowtrackpadfix.PROVE --es nonce " + nonce);
        } catch (Exception e) {
            return false;
        }
        long until = android.os.SystemClock.uptimeMillis() + 3000; // "am broadcast" waits for the receiver
        while (android.os.SystemClock.uptimeMillis() < until) {
            if (ControlReceiver.PROVEN.remove(nonce)) return true;
            android.os.SystemClock.sleep(50);
        }
        Log.w(TAG, "an adb port did not prove to be Android's adb service - ignored");
        return false;
    }

    /** A user action (app buttons / shadow-trackpad start|stop): synchronous, call off the UI thread. */
    static Result run(Context ctx, Action action) {
        return run(ctx, action, false);
    }

    /**
     * auto=true: an automatic check. It re-reads the user's "stopped" choice under the lock, so a
     * check racing the user's Stop button can never start the helper again.
     */
    static Result run(Context ctx, Action action, boolean auto) {
        synchronized (LOCK) {
            if (auto && prefs(ctx).getBoolean("stopped", false)) return stoppedResult();
            if (!auto) prefs(ctx).edit().putBoolean("stopped", action == Action.STOP).commit();
            Result r = new Result();
            try {
                r = runLocked(ctx, action, auto);
            } catch (Exception e) {
                r.ok = false;
                r.summary = "Error: " + e;
                Log.w(TAG, "run failed", e);
            }
            SharedPreferences.Editor ed = prefs(ctx).edit();
            ed.putLong("lastCheck", System.currentTimeMillis()).putString("lastSummary", r.summary)
                    .putString("lastStatus", r.status).putBoolean("lastOk", r.ok).apply();
            notifyResult(ctx, action, r);
            return r;
        }
    }

    private static Result runLocked(Context ctx, Action action, boolean auto) throws Exception {
        Result r = new Result();
        if (!provisioned(ctx)) {
            r.summary = "Not set up yet: the pairing key has not been installed.";
            return r;
        }
        byte[] p12 = Files.readAllBytes(keyFile(ctx).toPath());
        char[] pass = prefs(ctx).getString("p12pass", "").toCharArray();

        ensureWirelessDebugging(ctx, r);
        if (adbWifi(ctx) != 1) {
            r.summary = "Wireless debugging is off and could not be switched on. Is Wi-Fi connected, on a network "
                    + "where Wireless debugging is allowed? It can be switched on in Developer options.";
            return r;
        }
        AdbClient adb = null;
        boolean sawOwnPort = false, sawImpostor = false;
        int port = prefs(ctx).getInt("port", -1);
        if (port > 0) {
            adb = tryConnect(port, p12, pass);
            if (adb != null && !proveShell(ctx, adb)) {
                adb.close();
                adb = null;
                sawImpostor = true;
                prefs(ctx).edit().remove("port").apply();
            }
        }
        if (adb == null) {
            for (int attempt = 0; attempt < 4 && adb == null; attempt++) {
                for (int[] hp : discoverOwnPorts(ctx, 15000)) {
                    sawOwnPort = true;
                    AdbClient c = tryConnect(hp[0], p12, pass);
                    if (c == null) continue;
                    if (!proveShell(ctx, c)) {
                        c.close();
                        sawImpostor = true;
                        continue;
                    }
                    adb = c;
                    port = hp[0];
                    break;
                }
                if (adb == null) Thread.sleep(5000);
            }
        }
        if (adb == null) {
            r.summary = sawImpostor
                    ? "Something on this Googlebook imitates Wireless debugging (it couldn't prove it is Android's adb "
                    + "service), so nothing was sent to it. If this persists, check recently installed apps."
                    : sawOwnPort
                    ? "Wireless debugging is on but no longer accepts this app's key (the pairing \"ShadowTrackpadFix\" "
                    + "was probably removed). Re-pair it from the Linux terminal: shadow-trackpad pair"
                    : "Could not reach Wireless debugging on this Googlebook. Is Wi-Fi on, and is this a network "
                    + "where Wireless debugging is allowed? It can be switched on in Developer options.";
            return r;
        }
        prefs(ctx).edit().putInt("port", port).apply();
        try {
            installScript(ctx, adb);
            adb.exec("umask 077; echo " + token(ctx) + " > " + DIR + "/token");
            publishPort(ctx, port);
            prefs(ctx).edit().putInt("publishedPort", port).apply();
            String out;
            switch (action) {
                case STOP:
                    out = adb.exec("sh " + DIR + "/start.sh stop");
                    break;
                case RESTART:
                    if (auto) {
                        out = adb.exec("sh " + DIR + "/start.sh restart"); // does nothing if the user stopped it
                    } else {
                        adb.exec("sh " + DIR + "/start.sh stop");
                        Thread.sleep(1500);
                        out = adb.exec("sh " + DIR + "/start.sh start");
                    }
                    break;
                default:
                    out = adb.exec("sh " + DIR + "/start.sh " + (auto ? "ensure" : "start"));
            }
            if (out.contains("stopped by user")) {
                // Stopped from the Linux terminal: automatic checks respect that like the Stop button.
                prefs(ctx).edit().putBoolean("stopped", true).commit();
                return stoppedResult();
            }
            Thread.sleep(action == Action.STOP ? 300 : 1500);
            r.status = adb.exec("sh " + DIR + "/start.sh status").trim();
            if (action != Action.STOP && r.status.startsWith("running") && queryHelper(ctx) == null) {
                Thread.sleep(2000); // the status port may need a moment after a fresh start
                if (queryHelper(ctx) == null) r.status += "\nHEALTH-NOTE: status port not verified (another app may be using port 51742)";
            }
            boolean running = r.status.startsWith("running");
            boolean healthy = r.status.contains("HEALTH: ok") && !r.status.contains("HEALTH-NOTE");
            if (action == Action.STOP) {
                r.ok = !running;
                r.summary = "Helper stopped (" + out.trim() + ").";
            } else {
                r.ok = running && healthy;
                r.summary = running ? (healthy ? "Helper is running." : "Helper is running with warnings.")
                        : "Helper did not start: " + out.trim();
            }
        } finally {
            adb.close();
        }
        return r;
    }

    private static void ensureWirelessDebugging(Context ctx, Result r) throws InterruptedException {
        try {
            // With the user's consent, USB debugging stays on: then adbd (and the helper, which lives
            // in adbd's process group) keeps running even when Wireless debugging goes off.
            // Android 17 hides adb_enabled from apps (reads as 0 even when on), so it can't be checked;
            // with the user's consent it is simply (re)asserted - a no-op when it is already on.
            if (prefs(ctx).getBoolean("keepUsbDebugging", false)) {
                Settings.Global.putInt(ctx.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
                Log.i(TAG, "USB debugging asserted on");
            }
            if (adbWifi(ctx) == 1) return;
            // Android switches Wireless debugging straight back off without Wi-Fi or on a network it
            // isn't allowed for (and may ask the user each time), and every switch wakes this app's
            // settings trigger: so only try with Wi-Fi connected, and at most every 10 minutes.
            if (!wifiConnected(ctx)) {
                Log.i(TAG, "Wireless debugging is off and Wi-Fi isn't connected - not switching it on now");
                return;
            }
            long now = System.currentTimeMillis(), last = prefs(ctx).getLong("wifiAssertAt", 0);
            if (now - last < 600_000 && now >= last) return;
            prefs(ctx).edit().putLong("wifiAssertAt", now).commit();
            Settings.Global.putInt(ctx.getContentResolver(), "adb_wifi_enabled", 1);
            Log.i(TAG, "turned Wireless debugging on");
            Thread.sleep(3000);
        } catch (SecurityException e) {
            Log.w(TAG, "cannot change Wireless debugging (WRITE_SECURE_SETTINGS missing)", e);
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean wifiConnected(Context ctx) {
        android.net.ConnectivityManager cm = ctx.getSystemService(android.net.ConnectivityManager.class);
        if (cm == null) return true; // can't tell: try anyway
        for (android.net.Network n : cm.getAllNetworks()) {
            android.net.NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return true;
        }
        return false;
    }

    private static AdbClient tryConnect(int port, byte[] p12, char[] pass) {
        try {
            return AdbClient.connect("127.0.0.1", port, p12, pass, 5000);
        } catch (Exception e) {
            Log.i(TAG, "port " + port + ": " + e);
            return null;
        }
    }

    /** Finds Wireless debugging ports advertised by this device itself (not phones on the Wi-Fi). */
    private static List<int[]> discoverOwnPorts(Context ctx, long timeoutMs) throws InterruptedException {
        Set<String> own = ownAddresses();
        NsdManager nsd = (NsdManager) ctx.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        final boolean[] closed = {false}; // guarded by callbacks: no registration after cleanup
        List<int[]> found = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch gotOne = new CountDownLatch(1);
        // The callbacks only record a port, so they run directly on Android's NSD thread: no extra
        // thread to leak, and nothing that could reject Android's late "unregistered" callbacks.
        java.util.concurrent.Executor ex = Runnable::run;
        List<NsdManager.ServiceInfoCallback> callbacks = Collections.synchronizedList(new ArrayList<>());
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            public void onStartDiscoveryFailed(String t, int e) { Log.w(TAG, "discovery failed " + e); gotOne.countDown(); }
            public void onStopDiscoveryFailed(String t, int e) { }
            public void onDiscoveryStarted(String t) { }
            public void onDiscoveryStopped(String t) { }
            public void onServiceLost(NsdServiceInfo i) { }
            public void onServiceFound(NsdServiceInfo info) {
                NsdManager.ServiceInfoCallback cb = new NsdManager.ServiceInfoCallback() {
                    public void onServiceInfoCallbackRegistrationFailed(int e) { }
                    public void onServiceLost() { }
                    public void onServiceInfoCallbackUnregistered() { }
                    public void onServiceUpdated(NsdServiceInfo s) {
                        boolean mine = false;
                        for (InetAddress a : s.getHostAddresses()) if (own.contains(a.getHostAddress())) mine = true;
                        Log.i(TAG, "adb service " + s.getServiceName() + " port " + s.getPort() + (mine ? " (this device)" : ""));
                        if (mine && s.getPort() > 0) {
                            found.add(new int[]{s.getPort()});
                            gotOne.countDown();
                        }
                    }
                };
                synchronized (callbacks) {
                    if (closed[0]) return; // found after discovery was stopped: don't leave a callback behind
                    try {
                        nsd.registerServiceInfoCallback(info, ex, cb);
                        callbacks.add(cb);
                    } catch (RuntimeException e) {
                        Log.w(TAG, "resolve failed", e);
                    }
                }
            }
        };
        nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, listener);
        gotOne.await(timeoutMs, TimeUnit.MILLISECONDS);
        Thread.sleep(500);
        try { nsd.stopServiceDiscovery(listener); } catch (RuntimeException ignored) { }
        synchronized (callbacks) {
            closed[0] = true;
            for (NsdManager.ServiceInfoCallback cb : callbacks) {
                try { nsd.unregisterServiceInfoCallback(cb); } catch (RuntimeException ignored) { }
            }
        }
        LinkedHashSet<Integer> unique = new LinkedHashSet<>();
        synchronized (found) {
            for (int[] p : found) unique.add(p[0]);
        }
        List<int[]> out = new ArrayList<>();
        for (int p : unique) out.add(new int[]{p});
        return out;
    }

    private static Set<String> ownAddresses() {
        Set<String> s = new java.util.HashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (InetAddress a : Collections.list(ni.getInetAddresses())) s.add(a.getHostAddress());
        } catch (Exception ignored) {
        }
        return s;
    }

    /** Writes the bundled supervisor script (kept in sync with the app version) to the device. */
    private static void installScript(Context ctx, AdbClient adb) throws Exception {
        byte[] script;
        try (InputStream in = ctx.getAssets().open("start.sh")) {
            script = in.readAllBytes();
        }
        String b64 = Base64.encodeToString(script, Base64.NO_WRAP);
        String out = adb.exec("mkdir -p " + DIR + " && echo " + b64 + " | base64 -d > " + DIR + "/start.sh.new && mv "
                + DIR + "/start.sh.new " + DIR + "/start.sh && echo ok");
        if (!out.trim().endsWith("ok")) throw new Exception("could not install start.sh: " + out);
    }

    /**
     * Leaves "IP:port" of Wireless debugging in Documents/Shadow Trackpad Fix/adb-port.txt (via
     * MediaStore, no storage permission needed), where the Linux terminal's script finds it to
     * reconnect after a reboot without asking for the port.
     */
    private static void publishPort(Context ctx, int port) {
        try {
            String ip = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || !ni.getName().startsWith("wlan")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof java.net.Inet4Address) ip = a.getHostAddress();
            }
            String addr = (ip == null ? "" : ip) + ":" + port;
            // Signed with the secret only this app and the Linux script share, so the script never
            // follows a port file another app planted.
            String text = addr + " " + keyMac(ctx, "port:" + addr) + "\n";
            android.content.ContentResolver cr = ctx.getContentResolver();
            android.net.Uri files = android.provider.MediaStore.Files.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
            String rel = android.os.Environment.DIRECTORY_DOCUMENTS + "/Shadow Trackpad Fix/";
            android.net.Uri uri = null;
            String saved = prefs(ctx).getString("portUri", null);
            if (saved != null) {
                try (java.io.OutputStream out = cr.openOutputStream(android.net.Uri.parse(saved), "wt")) {
                    if (out != null) {
                        out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        return;
                    }
                } catch (Exception e) {
                    prefs(ctx).edit().remove("portUri").apply(); // deleted meanwhile: find or create it again
                }
            }
            // Only this app's own files are visible here. If another file already has the name,
            // Android gives ours a numbered name ("adb-port (1).txt"); the script checks all of them.
            try (android.database.Cursor c = cr.query(files, new String[]{android.provider.MediaStore.MediaColumns._ID},
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + android.provider.MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                    new String[]{rel, "adb-port%.txt"}, null)) {
                if (c != null && c.moveToFirst()) uri = android.content.ContentUris.withAppendedId(files, c.getLong(0));
            }
            if (uri == null) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "adb-port.txt");
                v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, rel);
                uri = cr.insert(files, v);
            }
            if (uri == null) return;
            try (java.io.OutputStream out = cr.openOutputStream(uri, "wt")) {
                out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            prefs(ctx).edit().putString("portUri", uri.toString()).apply();
        } catch (Exception e) {
            Log.i(TAG, "publishPort: " + e);
        }
    }

    // ---- notifications --------------------------------------------------------------------------

    private static void notifyResult(Context ctx, Action action, Result r) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_PROBLEMS, "Problems and updates", NotificationManager.IMPORTANCE_DEFAULT));
        if (action == Action.STOP) {
            nm.cancel(NOTIF_PROBLEM);
            return;
        }
        if (r.ok) {
            nm.cancel(NOTIF_PROBLEM);
            prefs(ctx).edit().remove("lastProblem").apply();
        } else {
            String text = r.summary + (r.status.contains("HEALTH:") ? "\n" + line(r.status, "HEALTH:") : "");
            // Only alert when the problem is new or changed (or once a day while it persists).
            long lastAt = prefs(ctx).getLong("lastProblemAt", 0);
            if (!text.equals(prefs(ctx).getString("lastProblem", "")) || System.currentTimeMillis() - lastAt > 86_400_000) {
                prefs(ctx).edit().putString("lastProblem", text).putLong("lastProblemAt", System.currentTimeMillis()).apply();
                post(ctx, nm, NOTIF_PROBLEM, "Shadow trackpad fix needs attention", text);
            }
        }
        checkUpdates(ctx, nm, r.status);
    }

    /** Tells the user when Shadow or Android changed since the helper was last verified. */
    private static void checkUpdates(Context ctx, NotificationManager nm, String status) {
        String verified = line(status, "verified:");
        String shadow = shadowVersion(ctx);
        String fp = Build.FINGERPRINT;
        // Never verified (or the record was cleared): there is nothing to compare against.
        if (verified.isEmpty() || verified.endsWith(" none") || shadow.isEmpty()) return;
        List<String> changed = new ArrayList<>();
        if (!verified.contains("shadow=" + shadow)) changed.add("Shadow PC (now " + shadow + ")");
        if (!verified.contains("android=" + fp)) changed.add("Android");
        String key = shadow + "|" + fp;
        if (changed.isEmpty()) {
            nm.cancel(NOTIF_UPDATE);
        } else if (!key.equals(prefs(ctx).getString("notifiedUpdate", ""))) {
            prefs(ctx).edit().putString("notifiedUpdate", key).apply();
            post(ctx, nm, NOTIF_UPDATE, "Shadow trackpad fix: " + String.join(" and ", changed) + " updated",
                    "The fix is still running and checks itself continuously. To re-verify it against the update, run "
                            + "'shadow-trackpad doctor' in the Linux terminal.");
        }
    }

    static String shadowVersion(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(SHADOW, 0);
            return pi.versionName + "/" + pi.getLongVersionCode();
        } catch (Exception e) {
            return "";
        }
    }

    private static void post(Context ctx, NotificationManager nm, int id, String title, String text) {
        PendingIntent open = PendingIntent.getActivity(ctx, 0, new Intent(ctx, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(ctx, CH_PROBLEMS)
                .setSmallIcon(R.drawable.ic_trackpad)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build();
        nm.notify(id, n);
    }

    static String line(String text, String prefix) {
        for (String l : text.split("\n")) if (l.trim().startsWith(prefix)) return l.trim();
        return "";
    }
}
