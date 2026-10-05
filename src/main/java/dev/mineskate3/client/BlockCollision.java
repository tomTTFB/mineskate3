package dev.mineskate3.client;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.WallSide;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Minecraft's block collision around the skater as triangles for Skate, in
 * session space (world minus the session origin). Faces buried against a full
 * neighbouring block are skipped. Triangles wind counterclockwise seen from
 * outside, which Skate treats as the solid side.
 *
 * <p>Thin connecting blocks (fences, walls, panes, bars, chains) collide by
 * their visible shape, so the board meets the top you can see, and bring a
 * rail down their middle in place of the twin lips their edges would make.
 * Straight staircases get a rail down each open side.
 */
public final class BlockCollision {
    public static final int RADIUS = 24;
    public static final int BELOW = 12;
    public static final int ABOVE = 12;

    public final float[] triangles;
    public final int count;
    /** One per triangle: 1 where its edges must never become grind lips. */
    public final byte[] noLip;
    /** Extra rails: 3 floats per point, `railPoints[i]` points for rail i. */
    public final float[] rails;
    public final int[] railPoints;
    /** Rails are only trusted inside this x/z box (session space). */
    public final float minX;
    public final float minZ;
    public final float maxX;
    public final float maxZ;
    public final BlockPos centre;

    private BlockCollision(float[] triangles, int count, byte[] noLip, List<float[]> rails, BlockPos centre,
            float minX, float minZ, float maxX, float maxZ) {
        this.triangles = triangles;
        this.count = count;
        this.noLip = noLip;
        int floats = 0;
        for (float[] rail : rails) {
            floats += rail.length;
        }
        this.rails = new float[floats];
        this.railPoints = new int[rails.size()];
        int at = 0;
        for (int i = 0; i < rails.size(); i++) {
            float[] rail = rails.get(i);
            System.arraycopy(rail, 0, this.rails, at, rail.length);
            at += rail.length;
            railPoints[i] = rail.length / 3;
        }
        this.centre = centre;
        this.minX = minX;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxZ = maxZ;
    }

    private static final class Builder {
        float[] data = new float[9 * 4096];
        byte[] flags = new byte[4096];
        int floats;
        /** Flag for the triangles added next. */
        byte noLip;

        void tri(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz) {
            if (floats + 9 > data.length) {
                data = java.util.Arrays.copyOf(data, data.length * 2);
                flags = java.util.Arrays.copyOf(flags, flags.length * 2);
            }
            flags[floats / 9] = noLip;
            data[floats++] = (float) ax;
            data[floats++] = (float) ay;
            data[floats++] = (float) az;
            data[floats++] = (float) bx;
            data[floats++] = (float) by;
            data[floats++] = (float) bz;
            data[floats++] = (float) cx;
            data[floats++] = (float) cy;
            data[floats++] = (float) cz;
        }

        void quad(double ax, double ay, double az, double bx, double by, double bz,
                double cx, double cy, double cz, double dx, double dy, double dz) {
            tri(ax, ay, az, bx, by, bz, cx, cy, cz);
            tri(ax, ay, az, cx, cy, cz, dx, dy, dz);
        }
    }

    public static BlockCollision gather(Level level, BlockPos centre, double originX, double originY, double originZ) {
        int size = 2 * RADIUS + 1;
        int height = BELOW + ABOVE + 1;
        // Full-cube flags with a one block margin, for face culling.
        int sx = size + 2;
        int sy = height + 2;
        int sz = size + 2;
        boolean[] full = new boolean[sx * sy * sz];
        VoxelShape[] shapes = new VoxelShape[sx * sy * sz];
        boolean[] thin = new boolean[sx * sy * sz];
        byte[] links = new byte[sx * sy * sz];
        float[] tops = new float[sx * sy * sz];
        byte[] stairs = new byte[sx * sy * sz];
        boolean[] solid = new boolean[sx * sy * sz];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int x0 = centre.getX() - RADIUS - 1;
        int y0 = centre.getY() - BELOW - 1;
        int z0 = centre.getZ() - RADIUS - 1;
        for (int x = 0; x < sx; x++) {
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    pos.set(x0 + x, y0 + y, z0 + z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    VoxelShape shape = state.getCollisionShape(level, pos, CollisionContext.empty());
                    if (shape.isEmpty()) {
                        continue;
                    }
                    int i = (x * sy + y) * sz + z;
                    solid[i] = true;
                    if (isThin(state)) {
                        // Fences collide 1.5 high; skate on the top you can see.
                        VoxelShape visible = state.getShape(level, pos);
                        shape = visible.isEmpty() ? shape : visible;
                        thin[i] = true;
                        tops[i] = (float) shape.max(Direction.Axis.Y);
                    }
                    shapes[i] = shape;
                    full[i] = Block.isShapeFullBlock(shape);
                    if (!thin[i] && !full[i]) {
                        stairs[i] = stairCode(shape);
                    }
                }
            }
        }
        // A thin block links to its +x/+z neighbour when both connect that way.
        for (int x = 0; x < sx - 1; x++) {
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz - 1; z++) {
                    int i = (x * sy + y) * sz + z;
                    if (!thin[i]) {
                        continue;
                    }
                    pos.set(x0 + x, y0 + y, z0 + z);
                    BlockState state = level.getBlockState(pos);
                    int east = ((x + 1) * sy + y) * sz + z;
                    if (thin[east] && connects(state, Direction.EAST)
                            && connects(level.getBlockState(pos.move(Direction.EAST)), Direction.WEST)) {
                        links[i] |= RailLayout.EAST;
                    }
                    pos.set(x0 + x, y0 + y, z0 + z);
                    int south = i + 1;
                    if (thin[south] && connects(state, Direction.SOUTH)
                            && connects(level.getBlockState(pos.move(Direction.SOUTH)), Direction.NORTH)) {
                        links[i] |= RailLayout.SOUTH;
                    }
                }
            }
        }

        Builder out = new Builder();
        for (int x = 1; x < sx - 1; x++) {
            for (int y = 1; y < sy - 1; y++) {
                for (int z = 1; z < sz - 1; z++) {
                    int i = (x * sy + y) * sz + z;
                    VoxelShape shape = shapes[i];
                    if (shape == null) {
                        continue;
                    }
                    double bx = x0 + x - originX;
                    double by = y0 + y - originY;
                    double bz = z0 + z - originZ;
                    boolean upFull = full[(x * sy + y + 1) * sz + z];
                    boolean downFull = full[(x * sy + y - 1) * sz + z];
                    boolean northFull = full[(x * sy + y) * sz + z - 1];
                    boolean southFull = full[(x * sy + y) * sz + z + 1];
                    boolean westFull = full[((x - 1) * sy + y) * sz + z];
                    boolean eastFull = full[((x + 1) * sy + y) * sz + z];
                    if (full[i] && upFull && downFull && northFull && southFull && westFull && eastFull) {
                        continue;
                    }
                    out.noLip = thin[i] ? (byte) 1 : (byte) 0;
                    for (AABB box : shape.toAabbs()) {
                        double ax = bx + box.minX, ay = by + box.minY, az = bz + box.minZ;
                        double cx = bx + box.maxX, cy = by + box.maxY, cz = bz + box.maxZ;
                        // A face on the block boundary against a full block is buried.
                        if (!(box.maxY >= 1 && upFull)) {
                            out.quad(ax, cy, az, ax, cy, cz, cx, cy, cz, cx, cy, az);
                        }
                        if (!(box.minY <= 0 && downFull)) {
                            out.quad(ax, ay, az, cx, ay, az, cx, ay, cz, ax, ay, cz);
                        }
                        if (!(box.minZ <= 0 && northFull)) {
                            out.quad(ax, ay, az, ax, cy, az, cx, cy, az, cx, ay, az);
                        }
                        if (!(box.maxZ >= 1 && southFull)) {
                            out.quad(ax, ay, cz, cx, ay, cz, cx, cy, cz, ax, cy, cz);
                        }
                        if (!(box.minX <= 0 && westFull)) {
                            out.quad(ax, ay, az, ax, ay, cz, ax, cy, cz, ax, cy, az);
                        }
                        if (!(box.maxX >= 1 && eastFull)) {
                            out.quad(cx, ay, az, cx, cy, az, cx, cy, cz, cx, ay, cz);
                        }
                    }
                }
            }
        }
        float minX = (float) (centre.getX() - RADIUS + 1 - originX);
        float minZ = (float) (centre.getZ() - RADIUS + 1 - originZ);
        float maxX = (float) (centre.getX() + RADIUS - originX);
        float maxZ = (float) (centre.getZ() + RADIUS - originZ);
        RailLayout rails = new RailLayout(sx, sy, sz, x0 - originX, y0 - originY, z0 - originZ);
        rails.thinRuns(links, tops);
        rails.staircases(stairs, solid);
        int count = out.floats / 9;
        return new BlockCollision(out.data, count, java.util.Arrays.copyOf(out.flags, count), rails.rails,
                centre.immutable(), minX, minZ, maxX, maxZ);
    }

    /** Blocks whose rail runs down their middle: fences, panes, bars, walls, lying chains. */
    static boolean isThin(BlockState state) {
        Block block = state.getBlock();
        return block instanceof CrossCollisionBlock || block instanceof WallBlock
                || block instanceof ChainBlock && state.getValue(ChainBlock.AXIS).isHorizontal();
    }

    private static boolean connects(BlockState state, Direction side) {
        Block block = state.getBlock();
        if (block instanceof CrossCollisionBlock) {
            return state.getValue(net.minecraft.world.level.block.PipeBlock.PROPERTY_BY_DIRECTION.get(side));
        }
        if (block instanceof WallBlock) {
            var property = switch (side) {
                case NORTH -> WallBlock.NORTH_WALL;
                case SOUTH -> WallBlock.SOUTH_WALL;
                case EAST -> WallBlock.EAST_WALL;
                default -> WallBlock.WEST_WALL;
            };
            return state.getValue(property) != WallSide.NONE;
        }
        return block instanceof ChainBlock && state.getValue(BlockStateProperties.AXIS) == side.getAxis();
    }

    private static final double[][] QUADRANTS = {{0.25, 0.25}, {0.75, 0.25}, {0.25, 0.75}, {0.75, 0.75}};

    /**
     * For a straight bottom-half stair shape, its tall side as a RailLayout
     * code (1 north, 2 east, 3 south, 4 west); 0 for anything else. Read from
     * the shape, so modded stairs count too.
     */
    static byte stairCode(VoxelShape shape) {
        if (shape.max(Direction.Axis.Y) < 1.0 || shape.min(Direction.Axis.Y) > 0.0) {
            return 0;
        }
        List<AABB> boxes = shape.toAabbs();
        int upper = 0;
        for (int q = 0; q < 4; q++) {
            double qx = QUADRANTS[q][0], qz = QUADRANTS[q][1];
            if (!inside(boxes, qx, 0.25, qz)) {
                return 0;
            }
            if (inside(boxes, qx, 0.75, qz)) {
                upper |= 1 << q;
            }
        }
        return switch (upper) {
            case 0b0011 -> 1; // -z half
            case 0b1010 -> 2; // +x half
            case 0b1100 -> 3; // +z half
            case 0b0101 -> 4; // -x half
            default -> 0;
        };
    }

    private static boolean inside(List<AABB> boxes, double x, double y, double z) {
        for (AABB box : boxes) {
            if (box.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }
}
