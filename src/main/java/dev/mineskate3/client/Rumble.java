package dev.mineskate3.client;

/**
 * Controller vibration from what the skater is doing: a faint buzz while
 * rolling, a steady grind rumble on rails, kicks for pops, landings (by impact
 * speed) and lock-ins, and a long shake on a bail. Only XInput pads (Windows)
 * can be driven; GLFW has no rumble call, so other pads stay still.
 */
final class Rumble {
    /** Seconds for a kick to fade to about a third. */
    private static final float DECAY = 0.12f;

    private int lastState = -1;
    /** Fading kicks, low (left, heavy) and high (right, light) motor, 0..1. */
    private float kickLow;
    private float kickHigh;
    private float steadyLow;
    private float steadyHigh;
    private int slot = -1;
    private int sentLeft = -1;
    private int sentRight = -1;

    /** Once per rendered frame; `pose` is null when no new pose arrived. */
    void update(float[] pose, float dt, PadInput pad) {
        if (!SkateSettings.rumble() || pad.xinputSlot < 0) {
            stop();
            return;
        }
        if (slot != pad.xinputSlot) {
            stop();
            slot = pad.xinputSlot;
        }
        float fade = (float) Math.exp(-dt / DECAY);
        kickLow *= fade;
        kickHigh *= fade;
        if (pose != null) {
            read(pose);
        }
        send(steadyLow + kickLow, steadyHigh + kickHigh);
    }

    private void read(float[] pose) {
        int st = NativeSkate.STATUS;
        int state = (int) pose[st];
        int wheels = (int) pose[st + 1];
        float impact = pose[st + 2];
        boolean wipe = state == 300 || pose[st + 3] != 0f;
        float speed = SkaterRenderer.speed(pose);
        int previous = lastState;
        lastState = state;

        boolean rolling = ground(state) && wheels >= 2 && !wipe;
        boolean onRail = grinding(state) && !wipe;
        steadyLow = rolling ? Math.min(speed / 12f, 1f) * 0.08f : 0f;
        steadyHigh = onRail ? 0.18f + Math.min(speed / 10f, 1f) * 0.2f : 0f;

        if (previous < 0 || previous == state) {
            return;
        }
        if (wipe && previous != 300) {
            kick(1f, 0.7f);
        } else if (air(state) && (ground(previous) || grinding(previous))) {
            kick(0.15f, 0.3f);
        } else if (air(previous) && (ground(state) || grinding(state))) {
            float hit = Math.min(impact / 8f, 1f);
            kick(0.3f + hit * 0.7f, 0.15f + hit * 0.3f);
        }
        if (grinding(state) && !grinding(previous)) {
            kick(0.25f, 0.5f);
        }
    }

    private void kick(float low, float high) {
        kickLow = Math.max(kickLow, low);
        kickHigh = Math.max(kickHigh, high);
    }

    private void send(float low, float high) {
        int left = motor(low);
        int right = motor(high);
        // Small changes are not worth a driver call every frame.
        if (Math.abs(left - sentLeft) < 650 && Math.abs(right - sentRight) < 650 && (left != 0 || sentLeft == 0)
                && (right != 0 || sentRight == 0)) {
            return;
        }
        if (slot >= 0 && NativeSkate.xinputRumble(slot, left, right)) {
            sentLeft = left;
            sentRight = right;
        }
    }

    private static int motor(float v) {
        return Math.round(Math.max(0f, Math.min(1f, v)) * 65535f);
    }

    /** Motors off; safe to call repeatedly. */
    void stop() {
        if (slot >= 0 && (sentLeft > 0 || sentRight > 0)) {
            NativeSkate.xinputRumble(slot, 0, 0);
        }
        slot = -1;
        sentLeft = sentRight = -1;
        kickLow = kickHigh = steadyLow = steadyHigh = 0f;
        lastState = -1;
    }

    // Physical state ids, as SkateAudio reads them.
    private static boolean ground(int s) {
        return s >= 100 && s <= 103;
    }

    private static boolean air(int s) {
        return s >= 200 && s <= 202;
    }

    private static boolean grinding(int s) {
        return s >= 400 && s <= 405;
    }
}
