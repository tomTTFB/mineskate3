package dev.mineskate3.block;

import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A skate ramp: a smooth surface rising from the front edge of the block to
 * its back edge, which faces FACING (the way the player looked when placing
 * it, so you ride up it away from where you stood). Skate gets the smooth
 * surface itself (see BlockCollision); Minecraft collides with a staircase of
 * thin slices just under it, which players walk up like slabs.
 */
public class RampBlock extends Block {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** Slices of the walking collision under the surface. */
    private static final int SLICES = 8;

    /** A ramp's side view: heights over the block's depth, front (u 0) to back (u 1). */
    public enum Profile {
        /** One block up over one block: a 45 degree kicker. */
        RAMP(line(0, 1)),
        /** The lower half of a two block long ramp, also a small kicker on its own. */
        LONG_RAMP_LOW(line(0, 0.5)),
        /** The upper half of a two block long ramp. */
        LONG_RAMP_HIGH(line(0.5, 1)),
        /** A curved transition up to vertical, with a deck behind the coping. */
        QUARTER_PIPE(quarterPipe());

        /** Width of the quarter pipe's flat deck, in blocks. */
        public static final double DECK = 0.125;
        /** Segments of the quarter pipe's curve. */
        public static final int CURVE = 8;

        /** (u, height) points, u rising from 0 to 1. */
        public final double[][] points;

        Profile(double[][] points) {
            this.points = points;
        }

        private static double[][] line(double front, double back) {
            return new double[][] {{0, front}, {1, back}};
        }

        /** A quarter ellipse from flat at the front to vertical at the deck, one block high. */
        private static double[][] quarterPipe() {
            double[][] p = new double[CURVE + 2][];
            double reach = 1 - DECK;
            for (int i = 0; i <= CURVE; i++) {
                double angle = Math.PI / 2 * i / CURVE;
                p[i] = new double[] {reach * Math.sin(angle), 1 - Math.cos(angle)};
            }
            p[CURVE + 1] = new double[] {1, 1};
            return p;
        }

        public double front() {
            return points[0][1];
        }

        public double back() {
            return points[points.length - 1][1];
        }

        /** Surface height at depth `u` (0 to 1). */
        public double height(double u) {
            for (int i = 1; i < points.length; i++) {
                double[] a = points[i - 1];
                double[] b = points[i];
                if (u <= b[0]) {
                    double span = b[0] - a[0];
                    return span <= 0 ? b[1] : a[1] + (b[1] - a[1]) * (u - a[0]) / span;
                }
            }
            return back();
        }
    }

    public final Profile profile;
    private final Map<Direction, VoxelShape> collision = new EnumMap<>(Direction.class);
    private final Map<Direction, VoxelShape> outline = new EnumMap<>(Direction.class);

    public RampBlock(Profile profile, Properties properties) {
        super(properties);
        this.profile = profile;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            collision.put(facing, slices(facing, false));
            outline.put(facing, slices(facing, true));
        }
    }

    /**
     * Thin slices across the ramp, each as high as the surface at its front
     * (under the surface) or at its back (covering it, for the outline).
     */
    private VoxelShape slices(Direction facing, boolean cover) {
        VoxelShape shape = Shapes.empty();
        for (int i = 0; i < SLICES; i++) {
            double u0 = (double) i / SLICES;
            double u1 = (double) (i + 1) / SLICES;
            double h = profile.height(cover ? u1 : u0);
            if (h <= 0) {
                continue;
            }
            shape = Shapes.or(shape, box(facing, u0, u1, h));
        }
        return shape.optimize();
    }

    /** The box spanning depths u0 to u1, full width, `h` high, turned to `facing`. */
    private static VoxelShape box(Direction facing, double u0, double u1, double h) {
        double[] a = toBlock(facing, u0, 0);
        double[] b = toBlock(facing, u1, 1);
        return Shapes.box(Math.min(a[0], b[0]), 0, Math.min(a[1], b[1]), Math.max(a[0], b[0]), h,
                Math.max(a[1], b[1]));
    }

    /** Block-relative x, z of depth `u` and width `v` on a ramp facing `facing`. */
    public static double[] toBlock(Direction facing, double u, double v) {
        Direction right = facing.getClockWise();
        return new double[] {
            0.5 + (u - 0.5) * facing.getStepX() + (v - 0.5) * right.getStepX(),
            0.5 + (u - 0.5) * facing.getStepZ() + (v - 0.5) * right.getStepZ()};
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return outline.get(state.getValue(FACING));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
            CollisionContext context) {
        return collision.get(state.getValue(FACING));
    }

    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }
}
