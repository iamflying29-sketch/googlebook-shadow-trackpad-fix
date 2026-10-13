package local.shadowtrackpad;

import java.util.Arrays;

/**
 * Rebuilds the trackpad's fingers from raw evdev events (multitouch protocol B).
 *
 * The kernel only sends what changed: a slot number only when it differs from the last one sent,
 * and a coordinate only when it differs from that slot's previous value. So right after the
 * trackpad is grabbed (or after the kernel dropped events) neither the current slot nor the
 * fingers already on the pad are known. A finger is therefore only handed to the engine once both
 * of its coordinates are known for certain; contacts that started before that ("adopted") never
 * make taps, and everything learned before the first slot number arrives is re-learned after it.
 * This keeps a finger's position from ever being mixed up with another finger's (no cursor jumps).
 */
final class MtParser {
    static final int EV_SYN = 0, EV_KEY = 1, EV_ABS = 3;
    static final int SYN_REPORT = 0, SYN_DROPPED = 3;
    static final int BTN_LEFT = 0x110;
    static final int ABS_MT_SLOT = 0x2f, ABS_MT_POSITION_X = 0x35, ABS_MT_POSITION_Y = 0x36,
            ABS_MT_TOOL_TYPE = 0x37, ABS_MT_TRACKING_ID = 0x39;
    static final int MT_TOOL_PALM = 2;

    /** onEvent results. */
    static final int NONE = 0, FRAME = 1, DROPPED = 2;

    private static final int S_UNKNOWN = 0, S_LIFTED = 1, S_CONTACT = 2;

    final Engine.Frame frame = new Engine.Frame(); // valid after onEvent returned FRAME
    float resX = 31f, resY = 31f;

    private final int[] state = new int[Engine.MAX_SLOTS], ids = new int[Engine.MAX_SLOTS];
    private final int[] rawX = new int[Engine.MAX_SLOTS], rawY = new int[Engine.MAX_SLOTS];
    private final boolean[] gotX = new boolean[Engine.MAX_SLOTS], gotY = new boolean[Engine.MAX_SLOTS];
    private final boolean[] palm = new boolean[Engine.MAX_SLOTS];
    private boolean button, synced, dropping;
    private int slot, adoptSeq;

    MtParser() {
        resetUnknown();
    }

    /** Forget everything (after a grab or SYN_DROPPED): the pad's current state is unknown. */
    void resetUnknown() {
        Arrays.fill(state, S_UNKNOWN);
        Arrays.fill(ids, -1);
        Arrays.fill(gotX, false);
        Arrays.fill(gotY, false);
        Arrays.fill(palm, false);
        button = false;
        synced = false;
        dropping = false;
        slot = 0;
        Arrays.fill(frame.id, -1);
        frame.button = false;
    }

    private int adoptedId() {
        adoptSeq = (adoptSeq + 1) & 0xffff;
        return Engine.ADOPTED_ID + adoptSeq * Engine.MAX_SLOTS + slot;
    }

    int onEvent(int type, int code, int value) {
        if (type == EV_SYN) {
            if (code == SYN_DROPPED) {
                resetUnknown();
                dropping = true; // the rest of this packet is incomplete: skip through its SYN_REPORT
                return DROPPED;
            }
            if (code != SYN_REPORT) return NONE;
            if (dropping) {
                dropping = false;
                return NONE;
            }
            buildFrame();
            return FRAME;
        }
        if (dropping) return NONE;
        if (type == EV_KEY) {
            if (code == BTN_LEFT) button = value != 0;
            return NONE;
        }
        if (type != EV_ABS) return NONE;
        if (code == ABS_MT_SLOT) {
            if (!synced) {
                // From now on every event's slot is known. What was learned before (attributed to
                // a guessed slot) is dropped and re-learned.
                synced = true;
                Arrays.fill(state, S_UNKNOWN);
                Arrays.fill(gotX, false);
                Arrays.fill(gotY, false);
                Arrays.fill(palm, false);
            }
            slot = value;
            return NONE;
        }
        if (slot < 0 || slot >= Engine.MAX_SLOTS) return NONE;
        switch (code) {
            case ABS_MT_TRACKING_ID:
                if (value < 0) {
                    state[slot] = S_LIFTED;
                } else {
                    state[slot] = S_CONTACT;
                    ids[slot] = synced ? value : adoptedId();
                }
                break;
            case ABS_MT_POSITION_X:
            case ABS_MT_POSITION_Y:
                if (code == ABS_MT_POSITION_X) { rawX[slot] = value; gotX[slot] = true; }
                else { rawY[slot] = value; gotY[slot] = true; }
                if (state[slot] == S_UNKNOWN) {
                    // A finger that was already down: it gets an "adopted" id.
                    state[slot] = S_CONTACT;
                    ids[slot] = adoptedId();
                }
                break;
            case ABS_MT_TOOL_TYPE:
                // Kept across lifts: the kernel only reports a change of tool type.
                palm[slot] = value == MT_TOOL_PALM;
                break;
            default:
                break;
        }
        return NONE;
    }

    private void buildFrame() {
        for (int i = 0; i < Engine.MAX_SLOTS; i++) {
            boolean known = state[i] == S_CONTACT && gotX[i] && gotY[i];
            frame.id[i] = known ? ids[i] : -1;
            frame.x[i] = rawX[i] / resX;
            frame.y[i] = rawY[i] / resY;
            frame.palm[i] = palm[i];
        }
        frame.button = button;
    }
}
