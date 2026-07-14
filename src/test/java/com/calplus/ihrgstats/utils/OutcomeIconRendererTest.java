package com.calplus.ihrgstats.utils;

import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests: getOutcomeIconWidth used to always report a fixed
 * DEFAULT_ICON_SIZE, even for an outcome whose icon fails to resolve -
 * drawOutcomeIcon's own fallback-to-emoji branch draws at the emoji's real
 * (different) width in that case, so a caller reserving column space from
 * getOutcomeIconWidth alone could reserve too little/much room. An
 * unrecognized outcome value takes the exact same "icon == null" fallback
 * branch a genuinely missing icon resource would, so it's used here to
 * exercise that path deterministically.
 */
public class OutcomeIconRendererTest {

    private static Graphics2D newGraphics() {
        BufferedImage image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB);
        return image.createGraphics();
    }

    @Test
    void getOutcomeIconWidth_matchesRealIconSize_forEveryKnownOutcome() {
        Graphics2D g2d = newGraphics();
        Font font = FontManager.getSansFont(14);

        for (int outcome : new int[]{1, 0, -1}) {
            assertEquals(24, OutcomeIconRenderer.getOutcomeIconWidth(g2d, outcome, font),
                    "known outcome " + outcome + " should report the real icon size");
        }
    }

    @Test
    void getOutcomeIconWidth_matchesTheActualFallbackEmojiWidth_whenNoIconResolves() {
        Graphics2D g2d = newGraphics();
        Font font = FontManager.getSansFont(14);
        int unrecognizedOutcome = 5; // takes the same icon==null fallback branch as a missing resource

        int reportedWidth = OutcomeIconRenderer.getOutcomeIconWidth(g2d, unrecognizedOutcome, font);

        FontMetrics fm = g2d.getFontMetrics(font);
        int actualFallbackEmojiWidth = fm.stringWidth(VictoryRecordCalculator.getOutcomeEmoji(unrecognizedOutcome));

        assertEquals(actualFallbackEmojiWidth, reportedWidth,
                "reported width must match what drawOutcomeIcon actually draws in the fallback case");
        assertNotEquals(24, reportedWidth,
                "the fallback emoji's real width should not coincidentally equal the icon size, or this test proves nothing");
    }

    @Test
    void getOutcomeIconWidth_unknownOutcome_returnsQuestionMarkWidth() {
        Graphics2D g2d = newGraphics();
        Font font = FontManager.getSansFont(14);

        int width = OutcomeIconRenderer.getOutcomeIconWidth(g2d, null, font);

        FontMetrics fm = g2d.getFontMetrics(font);
        assertEquals(fm.stringWidth("?"), width);
    }
}
