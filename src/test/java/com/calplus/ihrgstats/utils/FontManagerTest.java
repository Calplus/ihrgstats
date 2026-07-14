package com.calplus.ihrgstats.utils;

import org.junit.jupiter.api.Test;

import java.awt.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test: loadFontFromResource's fallback (used when a bundled TTF
 * fails to load) was always constructed as Font.PLAIN regardless of which
 * FontType was being loaded - so if the bold TTF specifically failed to
 * load, getSansBoldFont/getFont(SANS_BOLD, ...) would silently hand back a
 * PLAIN-weight font (deriveFont(size) preserves the base font's own style),
 * with no visible error beyond a stderr line. The fallback now takes an
 * explicit style parameter matching the font type it stands in for.
 */
public class FontManagerTest {

    @Test
    void loadFontFromResource_fallback_honorsTheRequestedStyle_whenResourceIsMissing() {
        Font boldFallback = FontManager.loadFontFromResource("does-not-exist.ttf", "SansSerif", Font.BOLD);
        Font plainFallback = FontManager.loadFontFromResource("does-not-exist.ttf", "SansSerif", Font.PLAIN);

        assertEquals(Font.BOLD, boldFallback.getStyle());
        assertEquals(Font.PLAIN, plainFallback.getStyle());
    }
}
