package local.shadowtrackpad;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Test build only: attacks the running helper's control socket and status port the way another
 * process could, and checks it refuses, survives and stays responsive.
 */
final class SecurityTest {
    static int failures;

    public static void main(String[] a) throws Exception {
        String token = Main.readToken();
        check(token != null, "token file readable by the shell user");

        check(control("status").equals("denied"), "control: command without token is denied");
        check(control("token 0123456789abcdef0123 status").equals("denied"), "control: wrong token is denied");
        check(control("token " + token + " status").startsWith("running"), "control: correct token works");

        // Flood: 100 KB without a newline, then the helper must still answer normally.
        try (LocalSocket s = new LocalSocket()) {
            s.connect(new LocalSocketAddress(Main.SOCKET));
            OutputStream o = s.getOutputStream();
            byte[] junk = new byte[100_000];
            java.util.Arrays.fill(junk, (byte) 'x');
            try { o.write(junk); } catch (Exception ignored) { }
        }
        check(control("token " + token + " status").startsWith("running"), "control: still answers after a 100 KB flood");

        // A silent client must not block the others for long.
        LocalSocket silent = new LocalSocket();
        silent.connect(new LocalSocketAddress(Main.SOCKET));
        long t0 = System.currentTimeMillis();
        String r = control("token " + token + " status");
        long waited = System.currentTimeMillis() - t0;
        silent.close();
        check(r.startsWith("running") && waited < 3500, "control: a silent client delays others at most ~2 s (" + waited + " ms)");

        // Status port.
        check(status("status").isEmpty(), "status: request without nonce gets no reply");
        check(status("status nothex!!").isEmpty(), "status: malformed nonce gets no reply");
        try (java.net.Socket s = new java.net.Socket("127.0.0.1", 51742)) {
            byte[] junk = new byte[100_000];
            java.util.Arrays.fill(junk, (byte) 'y');
            try { s.getOutputStream().write(junk); } catch (Exception ignored) { }
        }
        String nonce = "0123456789abcdef0123456789abcdef";
        String reply = status("status " + nonce);
        int nl = reply.indexOf('\n');
        String body = nl > 0 ? reply.substring(nl + 1) : "";
        if (body.endsWith("\n")) body = body.substring(0, body.length() - 1);
        check(nl > 0 && reply.substring(0, nl).equals("auth " + Main.sign(nonce + "\n" + body)) && body.startsWith("running"),
                "status: answers after a flood, signature covers nonce + whole reply");
        String tampered = body.replace("HEALTH: ok", "HEALTH: tampered");
        check(!Main.sign(nonce + "\n" + tampered).equals(Main.sign(nonce + "\n" + body)) || !body.contains("HEALTH: ok"),
                "status: an altered reply no longer matches the signature");
        check(!body.contains("capture: ") || !body.contains("focused"), "status: no information about other apps");

        System.out.println(failures == 0 ? "SECURITY TEST: all passed" : "SECURITY TEST: " + failures + " FAILED");
    }

    static void check(boolean ok, String what) {
        if (!ok) failures++;
        System.out.println((ok ? "  [PASS] " : "  [FAIL] ") + what);
    }

    static String control(String line) throws Exception {
        try (LocalSocket s = new LocalSocket()) {
            s.connect(new LocalSocketAddress(Main.SOCKET));
            s.setSoTimeout(5000);
            s.getOutputStream().write((line + "\n").getBytes(StandardCharsets.UTF_8));
            return new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    static String status(String line) throws Exception {
        try (java.net.Socket s = new java.net.Socket("127.0.0.1", 51742)) {
            s.setSoTimeout(3000);
            s.getOutputStream().write((line + "\n").getBytes(StandardCharsets.UTF_8));
            InputStream in = s.getInputStream();
            try {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (java.net.SocketTimeoutException e) {
                return "";
            }
        }
    }
}
