package com.calplus.ihrgstats.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test for the comparison-image canvas height budget:
 * calculateContentHeight() used to advance headerFm.getHeight() + 5 per
 * section title while drawSide() actually advances headerFm.getHeight() + 10
 * - a 5px-per-section undercount that silently clipped the bottom of the
 * image once enough sections were stacked.
 */
public class ComparisonImageGeneratorTest {

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
    void manySections_everyRowIsFullyDrawnWithinTheCanvasBounds() throws Exception {
        // 30 one-line sections: the old +5-per-title budget undercounts the
        // canvas by 5px per section (150px total) against a fixed ~30-60px
        // slack - far more than one row's height, enough to push the last
        // row(s) partly or entirely off the bottom of the canvas if the
        // +5/+10 drift were reintroduced.
        int sectionCount = 30;
        List<ComparisonImageGenerator.Section> sections = new ArrayList<>();
        for (int i = 0; i < sectionCount; i++) {
            sections.add(new ComparisonImageGenerator.Section("Section " + i,
                    Collections.singletonList("X")));
        }
        ComparisonImageGenerator.ComparisonData leftData =
                new ComparisonImageGenerator.ComparisonData("Left Entity", "1", sections);
        ComparisonImageGenerator.ComparisonData rightData =
                new ComparisonImageGenerator.ComparisonData("Right Entity", "2", sections);
        ComparisonImageGenerator.ImageMetadata metadata =
                new ComparisonImageGenerator.ImageMetadata("Test Title", "", "");

        Path imagePath = ComparisonImageGenerator.generateComparisonImage(
                "Test Title", leftData, rightData, metadata, "Test", "Left Entity", "Right Entity");
        BufferedImage image = ImageIO.read(imagePath.toFile());

        // Every row's fillRect (30px tall) is drawn in one of two alternating
        // colors on the left side, distinct from the plain background beneath
        // it. Scanning a fixed column - far enough right to clear the short
        // "X" line text - and counting contiguous colored bands tells us
        // exactly how many rows actually rendered, and whether the last one
        // was clipped short by a too-small canvas.
        int rowColorA = new Color(224, 255, 255).getRGB(); // BLUE_LIGHTER (even rows)
        int rowColorB = new Color(173, 216, 230).getRGB(); // BLUE_LIGHT (odd rows)
        int scanX = 150;

        List<Integer> bandHeights = new ArrayList<>();
        boolean inBand = false;
        int bandStart = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            int rgb = image.getRGB(scanX, y);
            boolean isRow = rgb == rowColorA || rgb == rowColorB;
            if (isRow && !inBand) {
                inBand = true;
                bandStart = y;
            } else if (!isRow && inBand) {
                inBand = false;
                bandHeights.add(y - bandStart);
            }
        }
        if (inBand) {
            bandHeights.add(image.getHeight() - bandStart);
        }

        assertEquals(sectionCount, bandHeights.size(),
                "expected " + sectionCount + " rows rendered, found " + bandHeights.size()
                        + " - the canvas was sized too short and clipped some rows entirely");
        assertEquals(30, bandHeights.get(bandHeights.size() - 1).intValue(),
                "the last row must be fully drawn (30px tall), not clipped short by the canvas edge");
    }
}
