package local.trackpadprobe;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.lang.reflect.Method;
import java.util.ArrayDeque;

/**
 * Diagnostic: shows exactly which MotionEvents the trackpad delivers to an app, both normally
 * and after a legacy View.requestPointerCapture() (what Shadow PC does). Every event is also
 * logged to logcat under tag "TrackpadProbe".
 */
public class MainActivity extends Activity {
    static final String TAG = "TrackpadProbe";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ProbeView v = new ProbeView(this);
        setContentView(v);
        v.requestFocus();
        v.autoCapture = getIntent().getBooleanExtra("capture", false);
        for (Method m : View.class.getMethods()) {
            if (m.getName().toLowerCase().contains("pointercapture")) {
                Log.i(TAG, "API: " + m);
            }
        }
    }

    static class ProbeView extends View {
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        final ArrayDeque<String> lines = new ArrayDeque<>();
        boolean captured, autoCapture;
        float lostScroll, sentScroll;
        float shadowSum, allSum;
        int events, batched;
        long latN, latSum, latMax, latOver8; // what Shadow's (int)axis*120 would send vs. lose

        ProbeView(Context c) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(true);
            text.setColor(Color.WHITE);
            text.setTextSize(30);
            text.setTypeface(android.graphics.Typeface.MONOSPACE);
            setOnCapturedPointerListener((view, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_MOVE || e.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE) {
                    // What Shadow uses (latest sample only) vs. everything that was sent (incl. batched history).
                    shadowSum += e.getX();
                    float all = e.getX();
                    for (int h = 0; h < e.getHistorySize(); h++) all += e.getHistoricalX(h);
                    allSum += all;
                    events++;
                    batched += e.getHistorySize();
                    if (events % 50 == 0) Log.i(TAG, String.format("MOVESTATS events=%d batchedSamples=%d shadowSum=%.0f allSum=%.0f", events, batched, shadowSum, allSum));
                }
                long lat = android.os.SystemClock.uptimeMillis() - e.getEventTime();
                latN++; latSum += lat; latMax = Math.max(latMax, lat);
                if (lat >= 8) latOver8++;
                if (latN % 100 == 0) Log.i(TAG, String.format("LATSTATS n=%d avg=%.2fms max=%dms over8ms=%d | MOVESTATS events=%d batchedSamples=%d shadowSum=%.0f allSum=%.0f", latN, latSum / (double) latN, latMax, latOver8, events, batched, shadowSum, allSum));
                log("CAPTURED", e);
                return true;
            });
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            super.onWindowFocusChanged(hasFocus);
            if (hasFocus && autoCapture) { autoCapture = false; requestPointerCapture(); }
        }

        @Override
        public void onPointerCaptureChange(boolean hasCapture) {
            captured = hasCapture;
            Log.i(TAG, "pointer capture " + hasCapture);
            add("Pointer capture is " + (hasCapture ? "ON. Press Esc to release it." : "OFF."));
        }

        @Override
        public boolean onKeyUp(int keyCode, KeyEvent e) {
            add("key UP   " + KeyEvent.keyCodeToString(keyCode) + " meta=0x" + Integer.toHexString(e.getMetaState()));
            Log.i(TAG, "key UP " + KeyEvent.keyCodeToString(keyCode) + " dev=" + e.getDeviceId());
            return true;
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent e) {
            add("key DOWN " + KeyEvent.keyCodeToString(keyCode) + " meta=0x" + Integer.toHexString(e.getMetaState()));
            Log.i(TAG, "key DOWN " + KeyEvent.keyCodeToString(keyCode) + " dev=" + e.getDeviceId());
            if (keyCode == KeyEvent.KEYCODE_ESCAPE && captured) { releasePointerCapture(); return true; }
            if (keyCode == KeyEvent.KEYCODE_C && !captured) { requestPointerCapture(); return true; }
            return super.onKeyDown(keyCode, e);
        }

        @Override
        public boolean onGenericMotionEvent(MotionEvent e) {
            if (e.getActionMasked() != MotionEvent.ACTION_HOVER_MOVE) log("generic", e);
            return true;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            log("touch", e);
            return true;
        }

        void log(String path, MotionEvent e) {
            int i = e.getActionIndex();
            float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL, i);
            float h = e.getAxisValue(MotionEvent.AXIS_HSCROLL, i);
            StringBuilder s = new StringBuilder();
            s.append(path).append(' ').append(MotionEvent.actionToString(e.getActionMasked()))
                    .append(" src=").append(sourceName(e.getSource()))
                    .append(" tool=").append(toolName(e.getToolType(i)))
                    .append(" ptrs=").append(e.getPointerCount())
                    .append(" cls=").append(e.getClassification())
                    .append(" dev=").append(e.getDeviceId());
            if (e.getPointerCount() > 1 || e.isFromSource(InputDevice.SOURCE_TOUCHPAD)) {
                for (int p = 0; p < e.getPointerCount(); p++)
                    s.append(String.format(" p%d=(%.0f,%.0f)", e.getPointerId(p), e.getX(p), e.getY(p)));
            }
            if (v != 0 || h != 0) {
                int shadowV = (int) v * 120, shadowH = (int) h * 120;
                s.append(String.format(" VSCROLL=%.3f HSCROLL=%.3f, so Shadow sends wheel v=%d h=%d", v, h, shadowV, shadowH));
                if (shadowV == 0 && v != 0) lostScroll += Math.abs(v); else sentScroll += Math.abs(v);
            }
            float rx = e.getAxisValue(MotionEvent.AXIS_RELATIVE_X, i), ry = e.getAxisValue(MotionEvent.AXIS_RELATIVE_Y, i);
            if (rx != 0 || ry != 0) s.append(String.format(" rel=(%.1f,%.1f)", rx, ry));
            float gx = e.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE, i);
            float gy = e.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE, i);
            if (gx != 0 || gy != 0) s.append(String.format(" gestScroll=(%.1f,%.1f)", gx, gy));
            float pinch = e.getAxisValue(MotionEvent.AXIS_GESTURE_PINCH_SCALE_FACTOR, i);
            if (pinch != 0) s.append(String.format(" pinch=%.3f", pinch));
            s.append(String.format(" xy=(%.0f,%.0f) btn=0x%x lat=%d hist=%d", e.getX(i), e.getY(i), e.getButtonState(),
                    android.os.SystemClock.uptimeMillis() - e.getEventTime(), e.getHistorySize()));
            Log.i(TAG, s.toString());
            add(s.toString());
        }

        void add(String s) {
            lines.addFirst(s);
            while (lines.size() > 40) lines.removeLast();
            invalidate();
        }

        static String toolName(int t) {
            switch (t) {
                case MotionEvent.TOOL_TYPE_FINGER: return "FINGER";
                case MotionEvent.TOOL_TYPE_MOUSE: return "MOUSE";
                case MotionEvent.TOOL_TYPE_STYLUS: return "STYLUS";
                case MotionEvent.TOOL_TYPE_ERASER: return "ERASER";
                default: return "UNKNOWN(" + t + ")";
            }
        }

        static String sourceName(int src) {
            switch (src) {
                case InputDevice.SOURCE_MOUSE: return "MOUSE";
                case InputDevice.SOURCE_MOUSE_RELATIVE: return "MOUSE_RELATIVE";
                case InputDevice.SOURCE_TOUCHPAD: return "TOUCHPAD";
                case InputDevice.SOURCE_TOUCHSCREEN: return "TOUCHSCREEN";
                default: return "0x" + Integer.toHexString(src);
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(captured ? Color.rgb(0, 60, 0) : Color.rgb(30, 30, 30));
            float y = 40;
            c.drawText(captured
                    ? "CAPTURED like Shadow. Scroll, pinch or swipe now. Press Esc to release."
                    : "NOT captured. Press C to capture the pointer like Shadow does.", 20, y, text);
            y += 40;
            c.drawText(String.format("scroll amount Shadow would LOSE: %.2f   would send: %.2f", lostScroll, sentScroll), 20, y, text);
            y += 50;
            for (String l : lines) { c.drawText(l, 20, y, text); y += 36; if (y > getHeight()) break; }
        }
    }
}
