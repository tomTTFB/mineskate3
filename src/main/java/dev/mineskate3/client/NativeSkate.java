package dev.mineskate3.client;

import dev.mineskate3.MineSkate3;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * The Skate 3 Rust engine, loaded from the jar for this platform.
 * Implemented in native/crates/skate-mc/src/lib.rs.
 */
public final class NativeSkate {
    public static final int ABI_VERSION = 5;

    public static final int STATUS_LOADING = 0;
    public static final int STATUS_READY = 1;
    public static final int STATUS_ACTIVE = 2;
    public static final int STATUS_FAILED = -1;

    // Pose layout, matching worker::layout.
    public static final int ROOT = 0;
    public static final int CAMERA_POSITION = 16;
    public static final int CAMERA_BASIS = 19;
    public static final int CAMERA_FOV = 28;
    public static final int CAMERA_VALID = 29;
    public static final int VELOCITY = 30;
    public static final int PARTS = 33;
    public static final int BOARD = PARTS + 6 * 16;
    /** State id, wheel contacts, impact speed, wiping out, marker visible, can place, can return,
     *  return progress, markers placed, markers returned. */
    public static final int STATUS = BOARD + 16;
    public static final int POSE_LENGTH = STATUS + 10;

    public static final int MESH_BOARD = 0;
    public static final int MESH_SKATER = 1;

    private static Path library;
    private static Throwable failure;

    private NativeSkate() {}

    public static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String cpu = arch.equals("aarch64") || arch.equals("arm64") ? "aarch64" : "x86_64";
        if (os.contains("win")) {
            return "windows-" + cpu;
        }
        if (os.contains("mac")) {
            return "macos-" + cpu;
        }
        return "linux-" + cpu;
    }

    private static String libraryName() {
        String platform = platform();
        if (platform.startsWith("windows")) {
            return "skate_mc.dll";
        }
        if (platform.startsWith("macos")) {
            return "libskate_mc.dylib";
        }
        return "libskate_mc.so";
    }

    /** Unpacks and loads the library once; later calls report the first outcome. */
    public static synchronized void load(Path directory) throws IOException {
        if (library != null) {
            return;
        }
        if (failure != null) {
            throw new IOException("The Skate engine library failed to load earlier", failure);
        }
        try {
            String resource = "/natives/" + platform() + "/" + libraryName();
            try (InputStream in = NativeSkate.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException("This build has no Skate engine library for " + platform());
                }
                byte[] bytes = in.readAllBytes();
                // One folder per distinct library, so a running game never
                // has its loaded file overwritten by a newer jar.
                Path target = directory.resolve(Integer.toHexString(java.util.Arrays.hashCode(bytes)))
                        .resolve(libraryName());
                if (!Files.isRegularFile(target) || Files.size(target) != bytes.length) {
                    Files.createDirectories(target.getParent());
                    Path partial = target.resolveSibling(libraryName() + ".partial");
                    Files.write(partial, bytes);
                    Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
                }
                System.load(target.toAbsolutePath().toString());
                int abi = abiVersion();
                if (abi != ABI_VERSION) {
                    throw new IOException("Skate engine library ABI " + abi + ", expected " + ABI_VERSION);
                }
                library = target;
                MineSkate3.LOGGER.info("Loaded Skate engine library {}", target);
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            failure = e;
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
    }

    /** The loaded library file, also used by the converter for RefPack decoding. */
    public static synchronized Path library() {
        return library;
    }

    static native int abiVersion();

    static native long create(String assetsRoot);

    static native void destroy(long handle);

    static native int status(long handle);

    static native String error(long handle);

    static native String state(long handle);

    /** `noLip`: one byte per triangle, nonzero where its edges are never grind lips.
     *  `rails`: 3 floats per point, `railPoints[i]` points for rail i. */
    static native void collision(long handle, float[] triangles, int count, byte[] noLip,
            float[] rails, int[] railPoints, float minX, float minZ, float maxX, float maxZ);

    static native void activate(long handle, float x, float y, float z, float heading, float aspect);

    /** `drag`, `glide` and `bounce` describe the block under the board (see {@link Surfaces}). */
    static native void step(long handle, float dt, boolean connected, int buttons,
            int leftTrigger, int rightTrigger, int lx, int ly, int rx, int ry,
            float drag, float glide, float bounce, float aspect);

    static native void suspend(long handle);

    static native long pose(long handle, float[] out);

    static native int poseLength();

    /** Loads the board and skater meshes: 2 both, 1 board only, 0 failed. */
    static native int meshLoad(String assetsRoot);

    static native void meshUse(String assetsRoot);

    static native int meshBoneCount();

    static native int meshLayoutHash();

    static native int[] meshLayout(int which);

    static native int[] meshIndices(int which);

    static native byte[] meshTexture(int which, int index);

    static native int meshSkin(int which, float[] bones, float[] out);

    static native int poseBones(long handle, float[] out);

    static native int rails(long handle);

    /** 1 the original trick HUD runs, 0 its data is missing, -1 it failed. */
    static native int hudStatus(long handle);

    static native String hudError(long handle);

    /** HUD texture files relative to assets/private/hud, as "path|width|height". */
    static native String[] hudTextures(long handle);

    /** The HUD draw list; returns its length, or minus the length needed. */
    static native int hudDraws(long handle, float[] out);

    /** Skate 3's own XInput reader (Windows): see PadInput. */
    static native int pollXInput(int[] out);

    /** XInput pad `slot`'s low and high frequency motors, 0..65535 (Windows only). */
    static native boolean xinputRumble(int slot, int left, int right);
}
