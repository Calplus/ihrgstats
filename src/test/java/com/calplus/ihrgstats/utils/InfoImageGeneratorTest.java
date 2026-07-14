package com.calplus.ihrgstats.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the match-info image header (hallIdentifier == null):
 * calculateHeaderHeight() used to skip subtitle height entirely in that
 * branch, and drawHeaderSection() never advanced yOffset after drawing the
 * "Last Round" line in that same branch - so a subtitle would have been
 * drawn on top of it, and the canvas wouldn't have been tall enough to fit
 * it even if it weren't. No current caller sets a subtitle on a null-hall
 * image, so this was latent rather than visibly broken, but any future
 * caller that did would have hit it.
 */
public class InfoImageGeneratorTest {

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
    void matchInfoImage_withSubtitle_doesNotOverlapTheLastRoundLine() throws Exception {
        InfoImageGenerator.ImageMetadata metadata = new InfoImageGenerator.ImageMetadata();
        metadata.title = "Match Information";
        metadata.lastRound = "Round 1";
        metadata.subtitle = "Test Subtitle";

        Path imagePath = InfoImageGenerator.generateInfoImage(
                metadata, new ArrayList<>(), null, "Test", "a");
        BufferedImage image = ImageIO.read(imagePath.toFile());

        // Title, "Generated: ...", "Last Round: ...", and the subtitle are 4
        // separate lines. If the subtitle were drawn at the same yOffset as
        // "Last Round" (the pre-fix bug), their text would merge into one
        // band instead of two, leaving only 3 bands total.
        int bandCount = countHeaderTextBands(image);
        assertEquals(4, bandCount,
                "expected 4 separate header text lines (title, generated date, last round, subtitle), found "
                        + bandCount + " - the subtitle must not be drawn on top of the last-round line");
    }

    @Test
    void matchInfoImage_withSubtitle_growsTheHeaderToFitIt() throws Exception {
        InfoImageGenerator.ImageMetadata withoutSubtitle = new InfoImageGenerator.ImageMetadata();
        withoutSubtitle.title = "Match Information";
        withoutSubtitle.lastRound = "Round 1";

        InfoImageGenerator.ImageMetadata withSubtitle = new InfoImageGenerator.ImageMetadata();
        withSubtitle.title = "Match Information";
        withSubtitle.lastRound = "Round 1";
        withSubtitle.subtitle = "Test Subtitle";

        Path noSubtitlePath = InfoImageGenerator.generateInfoImage(
                withoutSubtitle, new ArrayList<>(), null, "Test", "b");
        Path subtitlePath = InfoImageGenerator.generateInfoImage(
                withSubtitle, new ArrayList<>(), null, "Test", "c");

        BufferedImage noSubtitleImage = ImageIO.read(noSubtitlePath.toFile());
        BufferedImage subtitleImage = ImageIO.read(subtitlePath.toFile());

        assertTrue(subtitleImage.getHeight() > noSubtitleImage.getHeight(),
                "adding a subtitle to a match-info image (hallIdentifier == null) must grow the canvas to fit it, "
                        + "was " + noSubtitleImage.getHeight() + " without vs " + subtitleImage.getHeight() + " with");
    }

    /**
     * Counts contiguous vertical bands of dark (text) pixels within the
     * image's header region. The header is drawn on a solid white
     * background, while everything below it uses a light-yellow tiled
     * background - so the header's lower bound is found as the first row
     * with no pure-white pixel at all.
     */
    private static int countHeaderTextBands(BufferedImage image) {
        int white = 0xFFFFFF;
        int headerEndY = image.getHeight();
        for (int y = 0; y < image.getHeight(); y++) {
            boolean rowHasWhite = false;
            for (int x = 0; x < image.getWidth(); x += 3) {
                if ((image.getRGB(x, y) & 0xFFFFFF) == white) {
                    rowHasWhite = true;
                    break;
                }
            }
            if (!rowHasWhite) {
                headerEndY = y;
                break;
            }
        }

        int bandCount = 0;
        boolean inBand = false;
        for (int y = 0; y < headerEndY; y++) {
            boolean rowHasDarkPixel = false;
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y) & 0xFFFFFF;
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r + g + b < 300) {
                    rowHasDarkPixel = true;
                    break;
                }
            }
            if (rowHasDarkPixel && !inBand) {
                bandCount++;
                inBand = true;
            } else if (!rowHasDarkPixel) {
                inBand = false;
            }
        }
        return bandCount;
    }
}
