package com.calplus.ihrgstats.utils;

import java.awt.*;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

/**
 * Utility class for loading and managing fonts from JAR resources.
 * Loads TrueType fonts bundled in the JAR to ensure consistent rendering
 * across different platforms, especially headless Linux servers.
 */
public class FontManager {
    
    /**
     * Font types available in the application
     */
    public enum FontType {
        MONO,           // Monospaced font for tables
        SANS_REGULAR,   // Sans-serif regular weight
        SANS_BOLD       // Sans-serif bold weight
    }
    
    // Cache loaded fonts
    private static final Map<FontType, Font> fontCache = new EnumMap<>(FontType.class);
    
    // Font resource paths
    private static final String FONT_RESOURCE_PATH = "/fonts/";
    private static final String MONO_FONT = "NotoSansMono-Regular.ttf";
    private static final String SANS_REGULAR_FONT = "NotoSans-Regular.ttf";
    private static final String SANS_BOLD_FONT = "NotoSans-Bold.ttf";
    
    static {
        // Preload all fonts on class initialization
        loadAllFonts();
    }
    
    /**
     * Loads all fonts from resources into cache
     */
    private static void loadAllFonts() {
        try {
            fontCache.put(FontType.MONO, loadFontFromResource(MONO_FONT, "Monospaced", Font.PLAIN));
            fontCache.put(FontType.SANS_REGULAR, loadFontFromResource(SANS_REGULAR_FONT, "SansSerif", Font.PLAIN));
            fontCache.put(FontType.SANS_BOLD, loadFontFromResource(SANS_BOLD_FONT, "SansSerif", Font.BOLD));
        } catch (Exception e) {
            System.err.println("Failed to preload fonts: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Loads a TrueType font from JAR resources
     * @param fontFileName Name of the font file in resources/fonts/
     * @param fallbackName Logical font name to use if resource loading fails
     * @param fallbackStyle Font.PLAIN/BOLD/ITALIC to use for the fallback -
     *   must match the FontType being loaded (e.g. Font.BOLD for
     *   SANS_BOLD_FONT), otherwise getFont(type, size)'s deriveFont(size)
     *   (which preserves the base font's own style) would silently carry
     *   PLAIN through for a caller that specifically asked for bold.
     * @return Loaded Font object
     */
    static Font loadFontFromResource(String fontFileName, String fallbackName, int fallbackStyle) {
        String resourcePath = FONT_RESOURCE_PATH + fontFileName;
        try (InputStream fontStream = FontManager.class.getResourceAsStream(resourcePath)) {
            if (fontStream == null) {
                System.err.println("Font resource not found: " + resourcePath + ", using fallback: " + fallbackName);
                return new Font(fallbackName, fallbackStyle, 12);
            }

            Font font = Font.createFont(Font.TRUETYPE_FONT, fontStream);
            System.out.println("Successfully loaded font: " + fontFileName);
            return font;

        } catch (Exception e) {
            System.err.println("Failed to load font: " + fontFileName + ", using fallback: " + fallbackName);
            e.printStackTrace();
            return new Font(fallbackName, fallbackStyle, 12);
        }
    }
    
    /**
     * Gets a font of the specified type and size
     * @param type Font type (MONO, SANS_REGULAR, SANS_BOLD)
     * @param size Font size in points
     * @return Font object ready to use
     */
    public static Font getFont(FontType type, float size) {
        Font baseFont = fontCache.get(type);
        if (baseFont == null) {
            System.err.println("Font type not found in cache: " + type + ", using default");
            return new Font("SansSerif", Font.PLAIN, (int) size);
        }
        return baseFont.deriveFont(size);
    }
    
    /**
     * Gets a font of the specified type, style, and size
     * @param type Font type (MONO, SANS_REGULAR, SANS_BOLD)
     * @param style Font style (Font.PLAIN, Font.BOLD, Font.ITALIC, etc.)
     * @param size Font size in points
     * @return Font object ready to use
     */
    public static Font getFont(FontType type, int style, float size) {
        Font baseFont = fontCache.get(type);
        if (baseFont == null) {
            System.err.println("Font type not found in cache: " + type + ", using default");
            return new Font("SansSerif", style, (int) size);
        }
        return baseFont.deriveFont(style, size);
    }
    
    /**
     * Gets monospaced font for table rendering
     * @param size Font size in points
     * @return Monospaced font
     */
    public static Font getMonoFont(float size) {
        return getFont(FontType.MONO, size);
    }
    
    /**
     * Gets sans-serif regular font
     * @param size Font size in points
     * @return Sans-serif regular font
     */
    public static Font getSansFont(float size) {
        return getFont(FontType.SANS_REGULAR, size);
    }
    
    /**
     * Gets sans-serif bold font
     * @param size Font size in points
     * @return Sans-serif bold font
     */
    public static Font getSansBoldFont(float size) {
        return getFont(FontType.SANS_BOLD, size);
    }
}
