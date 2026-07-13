package com.calplus.ihrgstats.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for A26: generated exports/images now land in a single,
 * dedicated output directory instead of scattering across the OS temp dir.
 */
public class OutputPathsTest {

    private String originalUserDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    @Test
    void getOutputDirectory_resolvesUnderUserDir_andCreatesIt() throws Exception {
        Path dir = OutputPaths.getOutputDirectory();

        assertTrue(Files.isDirectory(dir), "The output directory must exist after calling getOutputDirectory()");
        assertEquals(Path.of(System.getProperty("user.dir"), "output").toAbsolutePath(), dir.toAbsolutePath(),
                "The output directory must be a dedicated '<user.dir>/output' folder, not the OS temp directory");
    }

    @Test
    void getOutputDirectory_isIdempotent_safeToCallRepeatedly() throws Exception {
        Path first = OutputPaths.getOutputDirectory();
        Path second = OutputPaths.getOutputDirectory();

        assertEquals(first, second, "Repeated calls must resolve to the same directory");
        assertTrue(Files.isDirectory(second), "The directory must still exist on a second call");
    }
}
