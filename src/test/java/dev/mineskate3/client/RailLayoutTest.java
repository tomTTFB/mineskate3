package dev.mineskate3.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RailLayoutTest {
    private static final int S = 10;

    private static int index(int x, int y, int z) {
        return (x * S + y) * S + z;
    }

    @Test
    void aFenceRunIsOneRailAtItsTop() {
        byte[] links = new byte[S * S * S];
        float[] tops = new float[S * S * S];
        // Fence posts at x = 2..5, y = 3, z = 4, each linked east to the next.
        for (int x = 2; x <= 5; x++) {
            tops[index(x, 3, 4)] = 1f;
            if (x < 5) {
                links[index(x, 3, 4)] = RailLayout.EAST;
            }
        }
        RailLayout layout = new RailLayout(S, S, S, 100, 0, 0);
        layout.thinRuns(links, tops);
        assertEquals(1, layout.rails.size());
        float e = RailLayout.END;
        assertArrayEquals(new float[] {100 + 2.5f - e, 4f, 4.5f, 100 + 5.5f + e, 4f, 4.5f}, layout.rails.get(0),
                1e-5f);
    }

    @Test
    void lonePostsHaveNoRail() {
        byte[] links = new byte[S * S * S];
        float[] tops = new float[S * S * S];
        tops[index(3, 3, 3)] = 1f;
        RailLayout layout = new RailLayout(S, S, S, 0, 0, 0);
        layout.thinRuns(links, tops);
        assertTrue(layout.rails.isEmpty());
    }

    @Test
    void anOpenStaircaseGetsARailOnEachSide() {
        byte[] stairs = new byte[S * S * S];
        boolean[] solid = new boolean[S * S * S];
        // Climbing north (-z): steps at (4, 2, 7), (4, 3, 6), (4, 4, 5), (4, 5, 4).
        for (int k = 0; k < 4; k++) {
            stairs[index(4, 2 + k, 7 - k)] = 1;
            solid[index(4, 2 + k, 7 - k)] = true;
        }
        RailLayout layout = new RailLayout(S, S, S, 0, 0, 0);
        layout.staircases(stairs, solid);
        assertEquals(2, layout.rails.size());
        for (float[] rail : layout.rails) {
            // 45 degrees: rise equals run, from half a block up to the top step's nosing.
            assertEquals(2.5f, rail[1], 1e-5f);
            assertEquals(6f, rail[4], 1e-5f);
            assertEquals(rail[4] - rail[1], rail[2] - rail[5], 1e-5f);
            assertTrue(rail[0] == 4f || rail[0] == 5f, "on a side edge: " + rail[0]);
        }
    }

    @Test
    void aWallBesideTheStairsBlocksThatSide() {
        byte[] stairs = new byte[S * S * S];
        boolean[] solid = new boolean[S * S * S];
        for (int k = 0; k < 3; k++) {
            stairs[index(4, 2 + k, 7 - k)] = 1;
            solid[index(4, 2 + k, 7 - k)] = true;
            solid[index(5, 2 + k, 7 - k)] = true; // east side walled
        }
        RailLayout layout = new RailLayout(S, S, S, 0, 0, 0);
        layout.staircases(stairs, solid);
        assertEquals(1, layout.rails.size());
        assertEquals(4f, layout.rails.get(0)[0], 1e-5f);
    }

    @Test
    void twoStepsAreNotAStaircase() {
        byte[] stairs = new byte[S * S * S];
        boolean[] solid = new boolean[S * S * S];
        stairs[index(4, 2, 7)] = 1;
        stairs[index(4, 3, 6)] = 1;
        RailLayout layout = new RailLayout(S, S, S, 0, 0, 0);
        layout.staircases(stairs, solid);
        assertTrue(layout.rails.isEmpty());
    }
}
