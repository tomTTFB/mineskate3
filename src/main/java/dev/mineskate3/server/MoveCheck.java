package dev.mineskate3.server;

/**
 * One skater's movement record for {@link SkateGuard}, kept free of
 * Minecraft types. Each server tick feeds in how the player moved and what
 * the world says about it; suspicious ticks add to a score that drains one
 * point a tick, so a moment of jank (a bail into a wall corner, a lag burst)
 * passes and only a pattern stops skating.
 */
final class MoveCheck {
    /** Score at which skating stops. */
    static final int STOP_SCORE = 20;
    static final int TOO_FAST = 10;
    static final int THROUGH_WALL = 8;
    static final int IN_WALL = 3;
    /** Ticks of speed a lag burst may deliver at once. */
    static final int BURST_TICKS = 4;
    /** Distance always allowed on top of the speed budget, in blocks. */
    static final double SLACK = 0.3;
    /** A fall this fast (blocks per tick) is gravity, not hovering. */
    static final double FALLING = 1.0;

    enum Verdict {
        /** Nothing odd this tick: its position is a safe one to return to. */
        OK,
        /** Odd, but not yet enough to act on. */
        SUSPECT,
        STOP
    }

    private double allowance;
    private int score;
    private int airTicks;
    String reason = "";

    /**
     * @param dx dy dz this tick's movement, in blocks
     * @param maxPerTick fastest allowed movement per tick, in blocks
     * @param throughWall the path since last tick crossed a solid block
     * @param inWall the body is inside a solid block now
     * @param grounded anything solid under or beside the skater
     * @param maxAirTicks longest allowed time off the ground without falling
     */
    Verdict step(double dx, double dy, double dz, double maxPerTick, boolean throughWall, boolean inWall,
            boolean grounded, int maxAirTicks) {
        boolean odd = false;
        allowance = Math.min(allowance + maxPerTick, maxPerTick * BURST_TICKS);
        // Falling is free; up and across spend the budget.
        double distance = Math.sqrt(dx * dx + dz * dz + Math.max(dy, 0) * Math.max(dy, 0));
        if (distance > allowance + SLACK) {
            odd = flag(TOO_FAST, "moved too fast");
        }
        allowance = Math.max(0, allowance - distance);
        if (throughWall) {
            odd = flag(THROUGH_WALL, "moved through a wall");
        } else if (inWall) {
            odd = flag(IN_WALL, "inside a wall");
        }
        airTicks = grounded || dy < -FALLING ? 0 : airTicks + 1;
        if (airTicks > maxAirTicks) {
            reason = "stayed in the air";
            return Verdict.STOP;
        }
        if (score >= STOP_SCORE) {
            return Verdict.STOP;
        }
        score = Math.max(0, score - 1);
        return odd ? Verdict.SUSPECT : Verdict.OK;
    }

    private boolean flag(int points, String why) {
        score += points;
        reason = why;
        return true;
    }

    int score() {
        return score;
    }
}
