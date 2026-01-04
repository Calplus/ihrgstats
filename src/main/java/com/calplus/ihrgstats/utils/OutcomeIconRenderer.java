package com.calplus.ihrgstats.utils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility class for rendering outcome icons (win/lose/draw) in generated images.
 * Replaces emoji characters with actual PNG images for consistent rendering
 * across different platforms, especially Linux servers.
 */
public class OutcomeIconRenderer {
    
    private static final String ICON_PATH = "/icons/";
    private static final int DEFAULT_ICON_SIZE = 20; // Default size for outcome icons
    
    // Cache for loaded icons at different sizes
    private static final Map<String, BufferedImage> iconCache = new HashMap<>();
    
    /**
     * Loads an outcome icon from resources
     * @param outcome Outcome value (1=win, 0=draw, -1=loss, null=unknown)
     * @param size Desired icon size in pixels
     * @return BufferedImage icon, or null if outcome is unknown
     */
    public static BufferedImage loadOutcomeIcon(Integer outcome, int size) {
        if (outcome == null) {
            return null; // No icon for unknown outcome, will render "?" text instead
        }
        
        String iconName = getIconFileName(outcome);
        if (iconName == null) {
            return null;
        }
        
        String cacheKey = iconName + "_" + size;
        if (iconCache.containsKey(cacheKey)) {
            return iconCache.get(cacheKey);
        }
        
        try (InputStream is = OutcomeIconRenderer.class.getResourceAsStream(ICON_PATH + iconName)) {
            if (is == null) {
                System.err.println("Icon not found: " + ICON_PATH + iconName);
                return null;
            }
            
            BufferedImage original = ImageIO.read(is);
            BufferedImage resized = resizeIcon(original, size);
            iconCache.put(cacheKey, resized);
            return resized;
            
        } catch (Exception e) {
            System.err.println("Failed to load icon: " + iconName);
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * Gets the icon filename for an outcome
     */
    private static String getIconFileName(Integer outcome) {
        return switch (outcome) {
            case 1 -> "win.png";
            case 0 -> "draw.png";
            case -1 -> "lose.png";
            default -> null;
        };
    }
    
    /**
     * Resizes an icon to the specified size
     */
    private static BufferedImage resizeIcon(BufferedImage original, int size) {
        if (original.getWidth() == size && original.getHeight() == size) {
            return original;
        }
        
        BufferedImage resized = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = resized.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.drawImage(original, 0, 0, size, size, null);
        g2d.dispose();
        return resized;
    }
    
    /**
     * Draws an outcome icon or "?" text at the specified position
     * @param g2d Graphics context
     * @param outcome Outcome value (1=win, 0=draw, -1=loss, null=unknown)
     * @param x X coordinate
     * @param y Y coordinate (baseline for text, top for image)
     * @param font Font to use for "?" text if outcome is unknown
     * @return Width of the drawn icon/text in pixels
     */
    public static int drawOutcomeIcon(Graphics2D g2d, Integer outcome, int x, int y, Font font) {
        if (outcome == null) {
            // Draw "?" text for unknown outcome
            g2d.setFont(font);
            FontMetrics fm = g2d.getFontMetrics();
            g2d.drawString("?", x, y);
            return fm.stringWidth("?");
        }
        
        // Try to load and draw icon
        BufferedImage icon = loadOutcomeIcon(outcome, DEFAULT_ICON_SIZE);
        if (icon != null) {
            // Adjust y to align icon with text baseline
            FontMetrics fm = g2d.getFontMetrics(font);
            int iconY = y - fm.getAscent() + (fm.getAscent() - DEFAULT_ICON_SIZE) / 2;
            g2d.drawImage(icon, x, iconY, null);
            return DEFAULT_ICON_SIZE;
        } else {
            // Fallback to emoji if icon fails to load
            String emoji = VictoryRecordCalculator.getOutcomeEmoji(outcome);
            g2d.setFont(font);
            FontMetrics fm = g2d.getFontMetrics();
            g2d.drawString(emoji, x, y);
            return fm.stringWidth(emoji);
        }
    }
    
    /**
     * Calculates the width an outcome icon/text would occupy
     * @param g2d Graphics context
     * @param outcome Outcome value
     * @param font Font for "?" text if outcome is unknown
     * @return Width in pixels
     */
    public static int getOutcomeIconWidth(Graphics2D g2d, Integer outcome, Font font) {
        if (outcome == null) {
            FontMetrics fm = g2d.getFontMetrics(font);
            return fm.stringWidth("?");
        }
        
        // Icons are always DEFAULT_ICON_SIZE width
        return DEFAULT_ICON_SIZE;
    }
}
