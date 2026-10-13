package local.shadowtrackpadfix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Base64;
import android.util.Log;

import java.io.FileOutputStream;

/**
 * Setup channel for the Linux-side script. Guarded by android.permission.DUMP in the manifest, so
 * only the adb shell / system can send to it - no other app can install keys or trigger it.
 *   PROVISION: stores the paired adb key (PKCS#12, base64) in this app's private storage.
 *   KICK:      runs a start/health check now (what a reboot would do).
 */
public class ControlReceiver extends BroadcastReceiver {
    /** Nonces delivered by PROVE: proof that an adb connection reached the real shell (see Starter.proveShell). */
    static final java.util.Set<String> PROVEN = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if ("local.shadowtrackpadfix.PROVE".equals(action)) {
            String nonce = intent.getStringExtra("nonce");
            if (nonce != null && nonce.matches("[0-9a-f]{32}")) {
                if (PROVEN.size() > 50) PROVEN.clear();
                PROVEN.add(nonce);
            }
            setResultData("ok");
        } else if ("local.shadowtrackpadfix.PROVISION".equals(action)) {
            String p12 = intent.getStringExtra("p12");
            String pass = intent.getStringExtra("pass");
            if (p12 == null || pass == null) {
                setResultData("missing extras");
                return;
            }
            try {
                // Decode and write to a temporary file first, so a malformed request can't wipe a working key.
                byte[] key = Base64.decode(p12, Base64.DEFAULT);
                java.security.KeyStore.getInstance("PKCS12").load(new java.io.ByteArrayInputStream(key), pass.toCharArray());
                java.io.File tmp = new java.io.File(context.getFilesDir(), "adb.p12.new");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(key);
                }
                if (!tmp.renameTo(Starter.keyFile(context))) throw new java.io.IOException("rename failed");
                Starter.prefs(context).edit().putString("p12pass", pass).remove("port").apply();
                setResultData("provisioned");
                Log.i(Starter.TAG, "adb key provisioned");
            } catch (Exception e) {
                setResultData("error: " + e);
                return;
            }
            HealthJob.schedule(context, false, true);
        } else if ("local.shadowtrackpadfix.CONFIG".equals(action)) {
            if (intent.hasExtra("stopped")) {
                // From "shadow-trackpad stop/start": the app respects it like its own Stop button.
                Starter.prefs(context).edit().putBoolean("stopped", intent.getBooleanExtra("stopped", false)).commit();
            }
            if (intent.hasExtra("usb_debugging")) {
                Starter.prefs(context).edit().putBoolean("keepUsbDebugging", intent.getBooleanExtra("usb_debugging", false)).apply();
            }
            // challenge: the Linux script checks that it talks to this app on this Googlebook (only it
            // and this app hold the paired key the proof is derived from) before sending the key.
            String challenge = intent.getStringExtra("challenge");
            String proof = challenge != null && challenge.matches("[0-9a-f]{32}") ? " proof=" + Starter.keyMac(context, "challenge:" + challenge) : "";
            setResultData("keepUsbDebugging=" + Starter.prefs(context).getBoolean("keepUsbDebugging", false)
                    + " stopped=" + Starter.prefs(context).getBoolean("stopped", false) + proof);
        } else if ("local.shadowtrackpadfix.KICK".equals(action)) {
            // restart=true: also restart the helper (e.g. after install-app, to load the new code).
            HealthJob.schedule(context, intent.getBooleanExtra("restart", false), true);
            setResultData("kicked");
        }
    }
}
