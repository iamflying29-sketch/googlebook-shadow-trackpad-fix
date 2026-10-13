import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import java.lang.reflect.Method;

/** Injects key chords to see which ones Android lets through to the focused window. */
public class KeyTest {
    static Object im; static Method inject; static int meta;
    static void key(int code, boolean down) throws Exception {
        int f = code == KeyEvent.KEYCODE_META_LEFT ? 0x30000 : code == KeyEvent.KEYCODE_CTRL_LEFT ? 0x3000
              : code == KeyEvent.KEYCODE_ALT_LEFT ? 0x12 : code == KeyEvent.KEYCODE_SHIFT_LEFT ? 0x41 : 0;
        if (down) meta |= f;
        long t = SystemClock.uptimeMillis();
        inject.invoke(im, new KeyEvent(t, t, down ? 0 : 1, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), 0);
        if (!down) meta &= ~f;
    }
    public static void main(String[] a) throws Exception {
        Class<?> g = Class.forName("android.hardware.input.InputManagerGlobal");
        im = g.getMethod("getInstance").invoke(null);
        inject = g.getMethod("injectInputEvent", InputEvent.class, int.class);
        for (String chord : a) {
            System.out.println("chord " + chord);
            String[] ks = chord.split("\\+");
            int[] codes = new int[ks.length];
            for (int i = 0; i < ks.length; i++) codes[i] = KeyEvent.keyCodeFromString("KEYCODE_" + ks[i]);
            for (int c : codes) { key(c, true); Thread.sleep(30); }
            for (int i = codes.length - 1; i >= 0; i--) { key(codes[i], false); Thread.sleep(30); }
            Thread.sleep(700);
        }
    }
}
