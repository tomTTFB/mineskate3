package dev.mineskate3.client.setup;

import java.nio.file.Files;
import java.nio.file.Path;
import net.neoforged.fml.loading.FMLPaths;

/** Where converted Skate 3 data and unpacked helpers live: <game dir>/mineskate3. */
public final class SkateData {
    private SkateData() {}

    public static Path root() {
        return FMLPaths.GAMEDIR.get().resolve("mineskate3");
    }

    /** The converter writes <out>/assets; the engine reads from there. */
    public static Path output() {
        return root().resolve("skate-data");
    }

    public static Path assets() {
        return output().resolve("assets");
    }

    public static boolean ready() {
        Path assets = assets();
        return Files.isRegularFile(assets.resolve("private/game.json"))
                && Files.isRegularFile(assets.resolve("private/skater.glb"))
                && Files.isRegularFile(assets.resolve("private/stock/skater-collections.json"))
                && Files.isRegularFile(assets.resolve("private/stock/physics-skeletons.json"));
    }
}
