package dev.mineskate3.server;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server rules for skate mode, in config/mineskate3-server.toml (a world's serverconfig/ copy overrides it). */
public final class SkateConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ALLOW_SKATING;
    public static final ModConfigSpec.IntValue PERMISSION_LEVEL;
    public static final ModConfigSpec.BooleanValue MOVEMENT_CHECKS;
    public static final ModConfigSpec.DoubleValue MAX_SPEED;
    public static final ModConfigSpec.DoubleValue MAX_AIR_SECONDS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        ALLOW_SKATING = b.comment("Whether players may use skate mode at all.")
                .define("allowSkating", true);
        PERMISSION_LEVEL = b.comment("Operator level needed to skate: 0 everyone, 2 command blocks and up, 4 owners.")
                .defineInRange("permissionLevel", 0, 0, 4);
        b.comment("Skaters move by their own client's physics, so the server's usual movement",
                "corrections are relaxed for them. These checks stand in for them.").push("movementChecks");
        MOVEMENT_CHECKS = b.comment("Stop skate mode for moves no skater can make: through walls, too fast, or",
                "hanging in the air. Turn off only on servers where every player is trusted.")
                .define("enabled", true);
        MAX_SPEED = b.comment("Fastest sustained skating speed allowed, in blocks per second.")
                .defineInRange("maxSpeed", 30.0, 5.0, 200.0);
        MAX_AIR_SECONDS = b.comment("Longest a skater may stay off the ground without falling, in seconds.")
                .defineInRange("maxAirSeconds", 10.0, 1.0, 120.0);
        b.pop();
        SPEC = b.build();
    }

    private SkateConfig() {}

    /** Config values throw before the server loads them; fall back to the defaults. */
    static <T> T get(ModConfigSpec.ConfigValue<T> value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }
}
