package com.calplus.ihrgstats.utils;

import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for A36: shortenNameWithInitials/truncateToPixelWidth
 * previously ignored their availableWidth argument entirely (shortening only
 * ever targeted a hardcoded 20-character count), so a long name could
 * overdraw into the neighboring score column. These tests exercise both
 * near-identical copies (InfoImageGenerator/ComparisonImageGenerator) against
 * a real FontMetrics from the actual app font - per the owner's explicit
 * "test it yourself" instruction for anything pixel-width related, this is a
 * real rendered FontMetrics, not a mocked/assumed one. A real end-to-end
 * rendering pass (real PNGs, visually inspected) is the other half of this
 * verification and is not something a unit test can stand in for.
 */
public class NameShorteningTest {

    private static FontMetrics realTableFontMetrics() {
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = img.createGraphics();
        g2d.setFont(FontManager.getMonoFont(24));
        return g2d.getFontMetrics();
    }

    // --- InfoImageGenerator ---

    @Test
    void infoImage_nameThatAlreadyFits_isReturnedUnchanged() {
        FontMetrics fm = realTableFontMetrics();
        String result = ImageRenderSupport.shortenNameWithInitials("Finn Vale", 500, fm);
        assertEquals("Finn Vale", result);
    }

    @Test
    void infoImage_longMultiWordName_shortensToFitWithinSafetyMargin() {
        FontMetrics fm = realTableFontMetrics();
        int availableWidth = 150; // deliberately tight
        String result = ImageRenderSupport.shortenNameWithInitials("Barnaby Puck Vo Lin", availableWidth, fm);
        assertTrue(fm.stringWidth(result) <= availableWidth,
                "Result '" + result + "' (width " + fm.stringWidth(result) + ") must fit within " + availableWidth);
    }

    @Test
    void infoImage_commaContainingName_neverOverflowsATightBudget() {
        // A comma-containing name, the exact shape the sample corpus
        // carries - checked mathematically here and visually in the audit.
        FontMetrics fm = realTableFontMetrics();
        int availableWidth = 120;
        String result = ImageRenderSupport.shortenNameWithInitials("Nightingale, Florence", availableWidth, fm);
        assertTrue(fm.stringWidth(result) <= availableWidth,
                "Result '" + result + "' (width " + fm.stringWidth(result) + ") must fit within " + availableWidth);
    }

    @Test
    void infoImage_longSingleWordName_hardTruncatesWithEllipsisAndFits() {
        FontMetrics fm = realTableFontMetrics();
        int availableWidth = 100;
        String result = ImageRenderSupport.shortenNameWithInitials("Supercalifragilisticexpialidocious", availableWidth, fm);
        assertTrue(result.endsWith("..."), "A truncated single word should end with an ellipsis: " + result);
        assertTrue(fm.stringWidth(result) <= availableWidth,
                "Result '" + result + "' (width " + fm.stringWidth(result) + ") must fit within " + availableWidth);
    }

    @Test
    void infoImage_extremelyCrampedBudget_returnsEmptyRatherThanOverflowing() {
        FontMetrics fm = realTableFontMetrics();
        // Not even room for "..." itself.
        String result = ImageRenderSupport.truncateToPixelWidth("Some Long Name", 10, fm);
        assertEquals("", result);
    }

    @Test
    void infoImage_allWordsAlreadyInitials_stillFallsBackToPixelTruncation() {
        FontMetrics fm = realTableFontMetrics();
        int availableWidth = 40;
        // Every "word" is already a single letter - the initials-shortening
        // loop can't reduce this further, so it must fall back to
        // truncateToPixelWidth instead of returning an oversized string.
        String result = ImageRenderSupport.shortenNameWithInitials("A B C D E F G H I J K L M", availableWidth, fm);
        assertTrue(fm.stringWidth(result) <= availableWidth,
                "Result '" + result + "' (width " + fm.stringWidth(result) + ") must fit within " + availableWidth);
    }

    @Test
    void infoImage_safetyMarginIsActuallyApplied_notJustAnExactFit() {
        FontMetrics fm = realTableFontMetrics();
        int availableWidth = 300;
        String result = ImageRenderSupport.shortenNameWithInitials("Bartholomew Krieger Vandersloot", availableWidth, fm);
        // The result must land comfortably under the hard limit, not just barely under it.
        assertTrue(fm.stringWidth(result) <= availableWidth * 0.9 + 1,
                "Result '" + result + "' (width " + fm.stringWidth(result) + ") should respect the ~90% safety margin of " + availableWidth);
    }

    // --- ComparisonImageGenerator (identical duplicate logic) ---

    @Test
    void comparisonImage_longMultiWordName_shortensToFitWithinSafetyMargin() {
        FontMetrics fm = realTableFontMetrics();
        int availableWidth = 150;
        String result = ImageRenderSupport.shortenNameWithInitials("Barnaby Puck Vo Lin", availableWidth, fm);
        assertTrue(fm.stringWidth(result) <= availableWidth,
                "Result '" + result + "' (width " + fm.stringWidth(result) + ") must fit within " + availableWidth);
    }

    @Test
    void comparisonImage_commaContainingName_neverOverflowsATightBudget() {
        FontMetrics fm = realTableFontMetrics();
        int availableWidth = 120;
        String result = ImageRenderSupport.shortenNameWithInitials("Nightingale, Florence", availableWidth, fm);
        assertTrue(fm.stringWidth(result) <= availableWidth,
                "Result '" + result + "' (width " + fm.stringWidth(result) + ") must fit within " + availableWidth);
    }

    @Test
    void comparisonImage_extremelyCrampedBudget_returnsEmptyRatherThanOverflowing() {
        FontMetrics fm = realTableFontMetrics();
        String result = ImageRenderSupport.truncateToPixelWidth("Some Long Name", 10, fm);
        assertEquals("", result);
    }
}
