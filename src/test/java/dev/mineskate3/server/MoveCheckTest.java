package dev.mineskate3.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MoveCheckTest {
    private static final double MAX = 1.5; // 30 blocks per second
    private static final int AIR = 200;

    @Test
    void steadySkatingAtTopSpeedPasses() {
        MoveCheck check = new MoveCheck();
        for (int i = 0; i < 200; i++) {
            assertEquals(MoveCheck.Verdict.OK, check.step(MAX, 0, 0, MAX, false, false, true, AIR));
        }
    }

    @Test
    void aLagBurstPasses() {
        MoveCheck check = new MoveCheck();
        for (int i = 0; i < 10; i++) {
            check.step(0, 0, 0, MAX, false, false, true, AIR);
        }
        // Three ticks of movement arriving in one.
        assertEquals(MoveCheck.Verdict.OK, check.step(3 * MAX, 0, 0, MAX, false, false, true, AIR));
    }

    @Test
    void sustainedSpeedingStops() {
        MoveCheck check = new MoveCheck();
        MoveCheck.Verdict verdict = MoveCheck.Verdict.OK;
        for (int i = 0; i < 10 && verdict != MoveCheck.Verdict.STOP; i++) {
            verdict = check.step(3 * MAX, 0, 0, MAX, false, false, true, AIR);
        }
        assertEquals(MoveCheck.Verdict.STOP, verdict);
        assertEquals("moved too fast", check.reason);
    }

    @Test
    void fallingIsNotSpeeding() {
        MoveCheck check = new MoveCheck();
        for (int i = 0; i < 20; i++) {
            assertEquals(MoveCheck.Verdict.OK, check.step(0.2, -4, 0, MAX, false, false, false, AIR));
        }
    }

    @Test
    void oneBrushWithAWallIsForgiven() {
        MoveCheck check = new MoveCheck();
        assertEquals(MoveCheck.Verdict.SUSPECT, check.step(0.5, 0, 0, MAX, false, true, true, AIR));
        for (int i = 0; i < 40; i++) {
            assertEquals(MoveCheck.Verdict.OK, check.step(0.5, 0, 0, MAX, false, false, true, AIR));
        }
    }

    @Test
    void walkingThroughWallsStops() {
        MoveCheck check = new MoveCheck();
        MoveCheck.Verdict verdict = MoveCheck.Verdict.OK;
        int ticks = 0;
        while (verdict != MoveCheck.Verdict.STOP && ticks < 20) {
            verdict = check.step(0.5, 0, 0, MAX, true, false, true, AIR);
            ticks++;
        }
        assertEquals(MoveCheck.Verdict.STOP, verdict);
        assertEquals("moved through a wall", check.reason);
    }

    @Test
    void hoveringStopsButGroundResetsTheClock() {
        MoveCheck check = new MoveCheck();
        for (int i = 0; i < AIR; i++) {
            assertEquals(MoveCheck.Verdict.OK, check.step(0.5, 0, 0, MAX, false, false, false, AIR));
        }
        check.step(0.5, 0, 0, MAX, false, false, true, AIR);
        for (int i = 0; i < AIR; i++) {
            assertEquals(MoveCheck.Verdict.OK, check.step(0.5, 0, 0, MAX, false, false, false, AIR));
        }
        assertEquals(MoveCheck.Verdict.STOP, check.step(0.5, 0, 0, MAX, false, false, false, AIR));
        assertEquals("stayed in the air", check.reason);
    }
}
