package dev.mineskate3.client.setup;

import dev.mineskate3.MineSkate3;
import dev.mineskate3.client.NativeSkate;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Runs the Skate 3 Rust engine's own asset converters (bundled in the jar) on
 * the player's extracted default.xex, using their installed Python. Only
 * what skating needs is written: animation banks, state graphs, input and
 * physics settings and the skater model.
 */
public final class ConverterRunner {

    /** A usable Python: the command to start it and its version. */
    public record Python(List<String> command, String version, boolean hasPackages) {}

    private ConverterRunner() {}

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static List<List<String>> candidates() {
        List<List<String>> list = new ArrayList<>();
        if (windows()) {
            list.add(List.of("py", "-3.13"));
            list.add(List.of("py", "-3"));
            list.add(List.of("python"));
            list.add(List.of("python3"));
        } else {
            list.add(List.of("python3.13"));
            list.add(List.of("python3"));
            list.add(List.of("python"));
        }
        return list;
    }

    private static String run(List<String> command, int seconds) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out;
            try (InputStream in = process.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Finds Python 3.11 or newer, noting whether numpy and Pillow are installed. */
    public static Python findPython() {
        for (List<String> base : candidates()) {
            List<String> probe = new ArrayList<>(base);
            probe.addAll(List.of("-c", "import sys; print('%d.%d' % sys.version_info[:2])"));
            String version = run(probe, 20);
            if (version == null || !version.matches("\\d+\\.\\d+")) {
                continue;
            }
            String[] parts = version.split("\\.");
            if (Integer.parseInt(parts[0]) < 3 || (Integer.parseInt(parts[0]) == 3 && Integer.parseInt(parts[1]) < 11)) {
                continue;
            }
            List<String> packages = new ArrayList<>(base);
            packages.addAll(List.of("-c", "import numpy, PIL"));
            return new Python(base, version, run(packages, 60) != null);
        }
        return null;
    }

    /** Installs numpy and Pillow for the user. */
    public static boolean installPackages(Python python, Consumer<String> log) {
        List<String> command = new ArrayList<>(python.command());
        command.addAll(List.of("-m", "pip", "install", "--user", "numpy", "Pillow"));
        return stream(command, null, log) == 0;
    }

    /**
     * Unpacks the bundled converter scripts. The folder is named after the
     * archive's contents, so a mod update never runs a stale converter.
     */
    static Path unpack() throws IOException {
        byte[] archive;
        try (InputStream raw = ConverterRunner.class.getResourceAsStream("/mineskate3/converter.zip")) {
            if (raw == null) {
                throw new IOException("This build does not include the Skate 3 converter");
            }
            archive = raw.readAllBytes();
        }
        String version;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(archive);
            version = java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        Path target = SkateData.root().resolve("converter").resolve(version);
        Path marker = target.resolve(".complete");
        if (Files.isRegularFile(marker)) {
            return target;
        }
        {
            try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    Path out = target.resolve(entry.getName()).normalize();
                    if (!out.startsWith(target)) {
                        throw new IOException("Unsafe converter archive entry " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(out);
                    } else {
                        Files.createDirectories(out.getParent());
                        Files.copy(zip, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        Files.writeString(marker, version);
        return target;
    }

    private static int stream(List<String> command, Path directory, Consumer<String> log) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            if (directory != null) {
                builder.directory(directory.toFile());
                builder.environment().put("PYTHONPATH", directory.toAbsolutePath().toString());
            }
            builder.environment().put("PYTHONUNBUFFERED", "1");
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            Path library = NativeSkate.library();
            if (library != null) {
                builder.environment().put("MINESKATE_REFPACK", library.toAbsolutePath().toString());
            }
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.accept(line);
                }
            }
            return process.waitFor();
        } catch (IOException e) {
            log.accept("Could not start " + command.get(0) + ": " + e.getMessage());
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /**
     * Converts `xex` into SkateData.output(), or with `hudOnly` adds just the
     * trick HUD to an existing conversion. Returns true on success.
     */
    public static boolean convert(Python python, Path xex, boolean hudOnly, Consumer<String> log) {
        try {
            try {
                NativeSkate.load(SkateData.root().resolve("natives"));
            } catch (IOException e) {
                // Optional here: the converter falls back to Python RefPack decoding.
                MineSkate3.LOGGER.warn("Converting without the native RefPack decoder: {}", e.getMessage());
            }
            Path converter = unpack();
            List<String> command = new ArrayList<>(python.command());
            command.addAll(List.of("-u", converter.resolve("mineskate_convert.py").toString(),
                    "--xex", xex.toAbsolutePath().toString(),
                    "--out", SkateData.output().toAbsolutePath().toString()));
            if (hudOnly) {
                command.add("--hud-only");
            }
            Files.createDirectories(SkateData.root());
            int exit = stream(command, converter, log);
            return exit == 0 && SkateData.ready() && (!hudOnly || SkateData.hudReady());
        } catch (IOException e) {
            log.accept(e.getMessage());
            return false;
        }
    }
}
