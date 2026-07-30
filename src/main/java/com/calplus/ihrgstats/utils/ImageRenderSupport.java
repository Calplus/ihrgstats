package com.calplus.ihrgstats.utils;

import java.awt.AlphaComposite;
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
