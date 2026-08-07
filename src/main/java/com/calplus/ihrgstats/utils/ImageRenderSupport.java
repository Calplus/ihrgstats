package com.calplus.ihrgstats.utils;

import java.awt.AlphaComposite;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * Rendering primitives shared by the image generators (table, info,
 * comparison) - previously private copies in each. Header layouts stay
 * per-generator on purpose: they are genuinely different designs, not
 * duplicated code.
 */
public final class ImageRenderSupport {

    private ImageRenderSupport() {}

    /** Sanitizes a name for use in a filename by removing invalid characters. */
    public static String sanitizeName(String name) {
        if (name == null || name.isEmpty()) return "";
        return name.replaceAll("[^a-zA-Z0-9_-]", "_").replaceAll("_{2,}", "_").trim();
    }

    /**
     * Names get a small safety margin under their nominal column width so a
     * string measuring exactly at the boundary can't visually bleed into
     * overlapping the next element (A36).
     */
    private static final double NAME_WIDTH_SAFETY_MARGIN = 0.9;

    /**
     * Shortens a name by converting words to initials until it fits within
     * availableWidth (measured in real pixels via fm, with a safety margin -
     * see NAME_WIDTH_SAFETY_MARGIN), falling back to hard pixel-based
     * truncation with an ellipsis if even every word reduced to an initial
     * still doesn't fit.
     * Always shortens the longest word first.
     * Example: "Thisisa Verylongfake Name" -> "Thisisa V. Name" -> "T. V. Name"
     */
    public static String shortenNameWithInitials(String name, int availableWidth, FontMetrics fm) {
        int targetWidth = (int) (availableWidth * NAME_WIDTH_SAFETY_MARGIN);

        if (fm.stringWidth(name) <= targetWidth) {
            return name;
        }

        // Split name into words
        String[] words = name.split("\\s+");
        if (words.length == 1) {
            return truncateToPixelWidth(name, targetWidth, fm);
        }

        // Track which words are already initials
        boolean[] isInitial = new boolean[words.length];

        while (true) {
            // Build current name
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < words.length; i++) {
                if (i > 0) current.append(" ");
                current.append(words[i]);
            }

            String currentName = current.toString();

            if (fm.stringWidth(currentName) <= targetWidth) {
                return currentName;
            }

            // Find longest non-initial word to shorten
            int longestIndex = -1;
            int longestLength = 0;
            for (int i = 0; i < words.length; i++) {
                if (!isInitial[i] && words[i].length() > longestLength) {
                    longestLength = words[i].length();
                    longestIndex = i;
                }
            }

            if (longestIndex == -1) {
                // All words are already initials and it still doesn't fit -
                // fall back to hard pixel truncation rather than overflowing.
                return truncateToPixelWidth(currentName, targetWidth, fm);
            }

            // Shorten the longest word to initial
            words[longestIndex] = words[longestIndex].substring(0, 1) + ".";
            isInitial[longestIndex] = true;
        }
    }

    /**
     * Truncates text to the longest prefix (plus an ellipsis) whose real
     * rendered width fits within targetWidth, binary-searching on actual
     * pixel width rather than assuming a fixed character count. Returns an
     * empty string in the (very cramped) case where even the ellipsis alone
     * doesn't fit - drawing nothing is safer than drawing something that
     * still overflows.
     */
    public static String truncateToPixelWidth(String text, int targetWidth, FontMetrics fm) {
        String ellipsis = "...";
        int ellipsisWidth = fm.stringWidth(ellipsis);
        if (ellipsisWidth > targetWidth) {
            return "";
        }

        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (fm.stringWidth(text.substring(0, mid)) + ellipsisWidth <= targetWidth) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return text.substring(0, lo) + ellipsis;
    }

    /**
     * Tiles {@code icon} across the given region as a semi-transparent
     * (20% alpha), 15°-rotated watermark, clipped to the region. The
     * caller paints the background first and decides how the icon is
     * loaded (and what to do when it's missing).
     */
    public static void tileIconWatermark(Graphics2D g2d, BufferedImage icon, int x, int y, int width, int height) {
        // Create semi-transparent version
        BufferedImage tiledIcon = new BufferedImage(icon.getWidth(), icon.getHeight(),
                                                    BufferedImage.TYPE_INT_ARGB);
        Graphics2D iconG2d = tiledIcon.createGraphics();
        iconG2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.2f));
        iconG2d.drawImage(icon, 0, 0, null);
        iconG2d.dispose();

        // Save current transform and clip
        AffineTransform oldTransform = g2d.getTransform();
        Shape oldClip = g2d.getClip();

        // Set clip to constrain drawing to this region only
        g2d.setClip(x, y, width, height);

        // Rotate 15 degrees around center of this region
        g2d.translate(x + width / 2, y + height / 2);
        g2d.rotate(Math.toRadians(-15));
        g2d.translate(-(x + width / 2), -(y + height / 2));

        // Tile the icon (with extra tiles to cover rotation)
        int iconSize = 100;
        int tilesX = (int) Math.ceil((double) width / iconSize) + 4;
        int tilesY = (int) Math.ceil((double) height / iconSize) + 4;

        for (int i = -2; i < tilesX; i++) {
            for (int j = -2; j < tilesY; j++) {
                g2d.drawImage(tiledIcon, x + i * iconSize, y + j * iconSize, iconSize, iconSize, null);
            }
        }

        // Restore transform and clip
        g2d.setTransform(oldTransform);
        g2d.setClip(oldClip);
    }
}
