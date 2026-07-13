package com.calplus.ihrgstats.utils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared loader for hall watermark/header icons (resources/halls/*.png),
 * caching the decoded image per hall identifier so each icon is only ever
 * read from the classpath and decoded once, and closing its InputStream
 * every time - the pattern {@link OutcomeIconRenderer} already used
 * correctly for outcome icons, applied here for hall icons across
 * {@code TableImageGenerator}/{@code InfoImageGenerator}/
 * {@code ComparisonImageGenerator}, which previously each re-opened and
 * re-decoded a hall's icon on every row/image and never closed the stream.
 */
public final class HallIconLoader {

    private static final String ICON_BASE_PATH = "/halls/";
    private static final String UNKNOWN_ICON_RESOURCE = ICON_BASE_PATH + "unknown.png";

    private static final Map<String, BufferedImage> iconCache = new ConcurrentHashMap<>();
    private static final BufferedImage NOT_FOUND = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

    private HallIconLoader() {}

    /**
     * Loads (and caches) the raw hall icon for the given hall identifier,
     * falling back to unknown.png if no icon exists for it. Returns null if
     * even the fallback can't be found/decoded.
     */
    public static BufferedImage loadRawIcon(String hallIdentifier) {
        String key = hallIdentifier == null ? "" : hallIdentifier.toLowerCase();
        BufferedImage cached = iconCache.computeIfAbsent(key, HallIconLoader::readIconWithFallback);
        return cached == NOT_FOUND ? null : cached;
    }

    private static BufferedImage readIconWithFallback(String key) {
        BufferedImage icon = readIcon(ICON_BASE_PATH + key + ".png");
        if (icon == null) {
            icon = readIcon(UNKNOWN_ICON_RESOURCE);
        }
        return icon != null ? icon : NOT_FOUND;
    }

    private static BufferedImage readIcon(String resourcePath) {
        try (InputStream is = HallIconLoader.class.getResourceAsStream(resourcePath)) {
            return is != null ? ImageIO.read(is) : null;
        } catch (IOException e) {
            System.err.println("Failed to load hall icon: " + resourcePath + " - " + e.getMessage());
            return null;
        }
    }
}
