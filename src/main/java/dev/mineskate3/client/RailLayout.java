package dev.mineskate3.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Grind rails that Minecraft blocks imply but the lip finder cannot see:
 * along the middle of runs of thin connected blocks (fences, walls, panes,
 * bars, chains) and down the open sides of staircases, as a hubba ledge.
 * Works on the block grid BlockCollision gathers: index (x * sy + y) * sz + z,
 * with a one block margin that runs never start in.
 */
final class RailLayout {
    /** Link bits: this block's rail continues into its +x or +z neighbour. */
    static final byte EAST = 1;
    static final byte SOUTH = 2;
    /** How far a run's rail reaches past the centre of its end blocks. */
    static final float END = 0.25f;
    /** Fewest steps that make a staircase worth grinding. */
    static final int MIN_STAIRS = 3;

    /** Tall-side directions of straight bottom-half stairs: x and z steps per code. */
    static final int[] STAIR_DX = {0, 0, 1, 0, -1};
    static final int[] STAIR_DZ = {0, -1, 0, 1, 0};

    private final int sx;
    private final int sy;
    private final int sz;
    /** Block (0, 0, 0) of the grid in session space. */
    private final double ox;
    private final double oy;
    private final double oz;
    final List<float[]> rails = new ArrayList<>();

    RailLayout(int sx, int sy, int sz, double ox, double oy, double oz) {
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
    }

    private int index(int x, int y, int z) {
        return (x * sy + y) * sz + z;
    }

    private boolean inside(int x, int y, int z) {
        return x >= 1 && x < sx - 1 && y >= 1 && y < sy - 1 && z >= 1 && z < sz - 1;
    }

    private void add(double ax, double ay, double az, double bx, double by, double bz) {
        rails.add(new float[] {(float) (ax + ox), (float) (ay + oy), (float) (az + oz),
                (float) (bx + ox), (float) (by + oy), (float) (bz + oz)});
    }

    /**
     * One straight rail per maximal run of linked blocks along x and along z,
     * at the lowest top along the run (`tops`, block-relative height).
     */
    void thinRuns(byte[] links, float[] tops) {
        for (int x = 1; x < sx - 1; x++) {
            for (int y = 1; y < sy - 1; y++) {
                for (int z = 1; z < sz - 1; z++) {
                    run(links, tops, x, y, z, true);
                    run(links, tops, x, y, z, false);
                }
            }
        }
    }

    private void run(byte[] links, float[] tops, int x, int y, int z, boolean alongX) {
        byte bit = alongX ? EAST : SOUTH;
        int dx = alongX ? 1 : 0;
        int dz = alongX ? 0 : 1;
        int i = index(x, y, z);
        if ((links[i] & bit) == 0) {
            return;
        }
        if (inside(x - dx, y, z - dz) && (links[index(x - dx, y, z - dz)] & bit) != 0) {
            return; // not the start of the run
        }
        float top = tops[i];
        int ex = x;
        int ez = z;
        while (inside(ex + dx, y, ez + dz) && (links[index(ex, y, ez)] & bit) != 0) {
            ex += dx;
            ez += dz;
            top = Math.min(top, tops[index(ex, y, ez)]);
        }
        if (ex == x && ez == z) {
            return;
        }
        add(x + 0.5 - dx * END, y + top, z + 0.5 - dz * END, ex + 0.5 + dx * END, y + top, ez + 0.5 + dz * END);
    }

    /**
     * Rails down each open side of every straight staircase of at least
     * MIN_STAIRS steps, over the step nosings. `stairs` holds a tall-side code
     * (index into STAIR_DX/DZ, 0 for none); `solid` marks any collision.
     */
    void staircases(byte[] stairs, boolean[] solid) {
        for (int x = 1; x < sx - 1; x++) {
            for (int y = 1; y < sy - 1; y++) {
                for (int z = 1; z < sz - 1; z++) {
                    int code = stairs[index(x, y, z)];
                    if (code != 0) {
                        staircase(stairs, solid, x, y, z, code);
                    }
                }
            }
        }
    }

    private boolean stairAt(byte[] stairs, int x, int y, int z, int code) {
        return inside(x, y, z) && stairs[index(x, y, z)] == code;
    }

    private void staircase(byte[] stairs, boolean[] solid, int x, int y, int z, int code) {
        int dx = STAIR_DX[code];
        int dz = STAIR_DZ[code];
        if (stairAt(stairs, x - dx, y - 1, z - dz, code)) {
            return; // not the bottom step
        }
        int n = 1;
        while (stairAt(stairs, x + dx * n, y + n, z + dz * n, code)) {
            n++;
        }
        if (n < MIN_STAIRS) {
            return;
        }
        for (int side = -1; side <= 1; side += 2) {
            int px = -dz * side;
            int pz = dx * side;
            boolean open = true;
            for (int k = 0; k < n && open; k++) {
                int nx = x + dx * k + px;
                int ny = y + k;
                int nz = z + dz * k + pz;
                open = inside(nx, ny, nz) && !solid[index(nx, ny, nz)];
            }
            if (!open) {
                continue;
            }
            // Bottom step's front edge (half height) to the top step's middle
            // nosing (full height): one straight 45 degree line on the side.
            int t = n - 1;
            add(x + 0.5 + px * 0.5 - dx * 0.5, y + 0.5, z + 0.5 + pz * 0.5 - dz * 0.5,
                    x + dx * t + 0.5 + px * 0.5, y + t + 1.0, z + dz * t + 0.5 + pz * 0.5);
        }
    }
}
