package com.calplus.ihrgstats.visualaudit.record;

import com.calplus.ihrgstats.utils.TimezoneHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the draw-call recorder is observation only: every generator's PNG
 * is pixel-identical with the recorder off and on, and the recorder's
 * measured ink bounds match the pixels actually painted.
 */
public class RecorderPixelIdentityTest {

    private String originalUserDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        DrawRecorder.disable();
        System.setProperty("user.dir", originalUserDir);
    }

    @Test
    void tableImage_isPixelIdenticalWithRecorderOnAndOff() throws Exception {
        assertIdentical(() -> GeneratorFixtures.hallTable(12, "pix"), "table");
        assertIdentical(() -> GeneratorFixtures.playerTable(11, "pix"), "table");
    }

    @Test
    void infoImage_isPixelIdenticalWithRecorderOnAndOff() throws Exception {
        assertIdentical(() -> GeneratorFixtures.infoImage("tanjong", "pix", "2026-01-01 00:00:00"), "info");
        assertIdentical(() -> GeneratorFixtures.infoImage(null, "pix2", "2026-01-01 00:00:00"), "info");
    }

    @Test
    void comparisonImage_isPixelIdenticalWithRecorderOnAndOff() throws Exception {
        assertIdentical(() -> GeneratorFixtures.comparisonImage("binjai", "tanjong", "pix"), "comparison");
    }

    /**
     * Renders off, then on, inside the same wall-clock second (the generators
     * stamp "Generated: yyyy-MM-dd HH:mm:ss" and name files by second), and
     * compares every pixel.
     */
    private void assertIdentical(Callable<Path> render, String generator) throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            String before = TimezoneHelper.formatNow("yyyy-MM-dd HH:mm:ss");
            DrawRecorder.disable();
            BufferedImage off = ImageIO.read(render.call().toFile());
            DrawRecorder recorder = DrawRecorder.enable();
            recorder.drain();
            BufferedImage on = ImageIO.read(render.call().toFile());
            List<DrawRecord> records = recorder.drain();
            DrawRecorder.disable();
            String after = TimezoneHelper.formatNow("yyyy-MM-dd HH:mm:ss");
            if (!before.equals(after)) {
                continue; // straddled a second boundary - the timestamp text legitimately differs
            }
            assertEquals(off.getWidth(), on.getWidth(), "width");
            assertEquals(off.getHeight(), on.getHeight(), "height");
            int w = off.getWidth(), h = off.getHeight();
            int[] a = off.getRGB(0, 0, w, h, null, 0, w);
            int[] b = on.getRGB(0, 0, w, h, null, 0, w);
            int diff = 0;
            for (int i = 0; i < a.length; i++) if (a[i] != b[i]) diff++;
            assertEquals(0, diff, "pixels differing between recorder off and on");

            assertEquals(1, records.size(), "one record per image");
            DrawRecord r = records.get(0);
            assertEquals(generator, r.generator);
            assertEquals(w, r.width);
            assertEquals(h, r.height);
            assertFalse(r.texts.isEmpty(), "texts recorded");
            assertFalse(r.rects.isEmpty(), "rects recorded");
            assertTrue(r.otherCalls.isEmpty(), "unmodelled draw calls: " + r.otherCalls);
            System.out.println("pixel-identical: " + r.file + " " + w + "x" + h + " texts=" + r.texts.size()
                    + " icons=" + r.icons.size() + " rects=" + r.rects.size() + " origin=" + r.originX + "," + r.originY);
            return;
        }
        fail("could not render both images inside one wall-clock second in 10 attempts");
    }

    /** The recorded ink box of a string equals the bounding box of the pixels it actually painted (+-1 px). */
    @Test
    void recordedInkBounds_matchPaintedPixels() {
        DrawRecorder recorder = DrawRecorder.enable();
        recorder.drain();
        Font[] fonts = {
                com.calplus.ihrgstats.utils.FontManager.getMonoFont(24),
                com.calplus.ihrgstats.utils.FontManager.getSansBoldFont(48),
                com.calplus.ihrgstats.utils.FontManager.getSansFont(20)};
        String[] samples = {"Round 10", "ΔRank", "Zara Z. Q. B.", "Hall Comparison", "jgpqy|"};
        for (Font f : fonts) {
            for (String s : samples) {
                BufferedImage canvas = new BufferedImage(900, 120, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = com.calplus.ihrgstats.utils.ImageRenderSupport.createGraphics(canvas, "probe");
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, 900, 120);
                g.setFont(f);
                g.setColor(Color.BLACK);
                g.drawString(s, 37, 80);
                g.dispose();
                // Close the canvas session through the real write seam.
                recorderWrite(recorder, canvas);
                DrawRecord r = recorder.drain().get(0);
                DrawRecord.Text t = r.texts.get(0);
                Rectangle painted = paintedBounds(canvas, Color.WHITE.getRGB());
                assertNotNull(painted);
                assertTrue(Math.abs(painted.x - t.ink.x) <= 1 && Math.abs(painted.y - t.ink.y) <= 1
                                && Math.abs(painted.x + painted.width - t.ink.x - t.ink.width) <= 1
                                && Math.abs(painted.y + painted.height - t.ink.y - t.ink.height) <= 1,
                        "ink " + t.ink + " vs painted " + painted + " for '" + s + "' in " + t.fontDescription());
                assertEquals(f.getStringBounds(s, t.frc).getWidth(), t.advance, 0.001);
            }
        }
    }

    private static void recorderWrite(DrawRecorder recorder, BufferedImage canvas) {
        try {
            Path tmp = Path.of(System.getProperty("user.dir"), "probe.png");
            com.calplus.ihrgstats.utils.ImageRenderSupport.writePng(canvas, tmp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static Rectangle paintedBounds(BufferedImage img, int background) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (img.getRGB(x, y) != background) {
                    minX = Math.min(minX, x); minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
                }
            }
        }
        return maxX < 0 ? null : new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }
}
