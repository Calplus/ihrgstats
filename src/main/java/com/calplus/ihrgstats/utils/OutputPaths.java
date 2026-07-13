package com.calplus.ihrgstats.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves the shared output directory for generated artifacts (ranking/
 * info/comparison images, database exports) - a dedicated, inspectable
 * {@code <user.dir>/output/} folder instead of scattering across the OS
 * temp directory ({@code java.io.tmpdir}) or ad-hoc
 * {@code Files.createTempDirectory(...)} calls, each with their own
 * lifecycle nobody was tracking.
 *
 * Files placed here are NOT automatically deleted (owner decision) - this
 * only fixes WHERE generated files land, not how long they stick around.
 */
public final class OutputPaths {

    private OutputPaths() {}

    private static final String OUTPUT_DIR_NAME = "output";

    /** Returns the shared output directory, creating it if it doesn't exist yet. */
    public static Path getOutputDirectory() throws IOException {
        Path dir = Paths.get(System.getProperty("user.dir"), OUTPUT_DIR_NAME);
        Files.createDirectories(dir);
        return dir;
    }
}
