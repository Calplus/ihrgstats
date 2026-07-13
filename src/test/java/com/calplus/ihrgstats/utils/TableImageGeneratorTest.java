package com.calplus.ihrgstats.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for A37: a narrow table (few columns/rows) used to get
 * its final image cropped down to just the table's own width, while the
 * title/description was centered on the WIDER pre-crop canvas - clipping the
 * header text at both edges. The canvas and the final crop bounds now both
 * account for the header content's own width too, not just the table's.
 */
public class TableImageGeneratorTest {

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

    private static TableImageGenerator.ImageMetadata longDescriptionMetadata() {
        return new TableImageGenerator.ImageMetadata(
                "Player Rankings",
                "This is a deliberately long description meant to be much wider than a narrow, few-column table",
                "Round 1");
    }

    @Test
    void generatePlayerTable_withANarrowTableAndALongDescription_isWideEnoughForTheHeader() throws Exception {
        String[] headers = {"Rk", "Elo"};
        List<String[]> rows = java.util.Collections.singletonList(new String[]{"1", "1000"});
        TableFormatter.Alignment[] alignments = {TableFormatter.Alignment.RIGHT, TableFormatter.Alignment.RIGHT};
        int[] columnWidths = {2, 4};
        TableImageGenerator.ImageMetadata metadata = longDescriptionMetadata();

        Path imagePath = TableImageGenerator.generatePlayerTable(headers, rows, alignments, columnWidths, metadata);
        BufferedImage image = ImageIO.read(imagePath.toFile());

        int requiredWidth = TableImageGenerator.calculateHeaderContentWidth(metadata);
        assertTrue(image.getWidth() >= requiredWidth,
                "Image width " + image.getWidth() + " must be at least the header's own required width " + requiredWidth
                        + " - otherwise the title/description gets clipped by the crop");
    }

    @Test
    void generateHallTable_withANarrowTableAndALongDescription_isWideEnoughForTheHeader() throws Exception {
        String[] headers = {"Rk", "Elo"};
        List<String[]> rows = java.util.Collections.singletonList(new String[]{"1", "1000"});
        List<String> hallNames = List.of("1");
        TableFormatter.Alignment[] alignments = {TableFormatter.Alignment.RIGHT, TableFormatter.Alignment.RIGHT};
        int[] columnWidths = {2, 4};
        TableImageGenerator.ImageMetadata metadata = longDescriptionMetadata();

        Path imagePath = TableImageGenerator.generateHallTable(headers, rows, hallNames, alignments, columnWidths, metadata);
        BufferedImage image = ImageIO.read(imagePath.toFile());

        int requiredWidth = TableImageGenerator.calculateHeaderContentWidth(metadata);
        assertTrue(image.getWidth() >= requiredWidth,
                "Image width " + image.getWidth() + " must be at least the header's own required width " + requiredWidth
                        + " - otherwise the title/description gets clipped by the crop");
    }
}
