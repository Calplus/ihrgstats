package com.calplus.ihrgstats.visualaudit.record;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class ImageTilerTest {

    /** Tiles cover every pixel exactly as rendered and never exceed 800x600; the preview's long edge is 1280. */
    @Test
    void tilesCoverEveryPixel_andPreviewIs1280(@TempDir Path tmp) throws Exception {
        BufferedImage img = new BufferedImage(3048, 1927, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) img.setRGB(x, y, (x * 7919 + y * 104729) & 0xFFFFFF);
        Path png = tmp.resolve("probe.png");
        ImageIO.write(img, "PNG", png.toFile());
        List<Path> tiles = ImageTiler.tile(png, tmp.resolve("tiles"));
        boolean[][] seen = new boolean[img.getHeight()][img.getWidth()];
        for (Path t : tiles) {
            BufferedImage ti = ImageIO.read(t.toFile());
            assertTrue(ti.getWidth() <= ImageTiler.TILE_W && ti.getHeight() <= ImageTiler.TILE_H);
            String[] parts = t.getFileName().toString().replace(".png", "").split("_");
            int x0 = Integer.parseInt(parts[parts.length - 2].substring(1)), y0 = Integer.parseInt(parts[parts.length - 1].substring(1));
            for (int y = 0; y < ti.getHeight(); y++) {
                for (int x = 0; x < ti.getWidth(); x++) {
                    assertEquals(img.getRGB(x0 + x, y0 + y), ti.getRGB(x, y));
                    seen[y0 + y][x0 + x] = true;
                }
            }
        }
        for (boolean[] row : seen) for (boolean b : row) assertTrue(b, "pixel not covered by any tile");
        BufferedImage prev = ImageIO.read(ImageTiler.phonePreview(png, tmp.resolve("preview")).toFile());
        assertEquals(1280, Math.max(prev.getWidth(), prev.getHeight()));
    }

    /**
     * Gated post-processing of a render folder: -Dvisual.post=<folder with PNGs>.
     * Writes <folder>/tiles, <folder>/preview and <folder>/preview_legibility.txt.
     */
    @Test
    @EnabledIfSystemProperty(named = "visual.post", matches = ".+")
    void tileAndPreviewRenderFolder() throws Exception {
        Path dir = Path.of(System.getProperty("visual.post"));
        List<Path> pngs;
        try (Stream<Path> s = Files.list(dir)) {
            pngs = s.filter(p -> p.getFileName().toString().endsWith(".png")).sorted().toList();
        }
        assertFalse(pngs.isEmpty(), "no PNGs in " + dir);
        List<String> report = new ArrayList<>();
        report.add("Simulated Telegram photo preview (long edge 1280 px). Table text = NotoSansMono 24pt, digit ink height 17 px in the render;");
        report.add("'digit px' is that height after Telegram's downscale. Below ~9 px digits are hard to read on a phone without zooming.");
        report.add("");
        int tiles = 0, small = 0;
        for (Path p : pngs) {
            tiles += ImageTiler.tile(p, dir.resolve("tiles")).size();
            ImageTiler.phonePreview(p, dir.resolve("preview"));
            BufferedImage img = ImageIO.read(p.toFile());
            double scale = ImageTiler.previewScale(img.getWidth(), img.getHeight());
            double digit = 17 * scale;
            if (digit < 9) small++;
            report.add(String.format(Locale.ROOT, "%-60s %5dx%-5d scale %.3f  digit %.1f px%s", p.getFileName(), img.getWidth(), img.getHeight(),
                    scale, digit, digit < 9 ? "  < 9 px" : ""));
        }
        report.add("");
        report.add("images " + pngs.size() + ", tiles " + tiles + ", images whose table digits fall below 9 px in the preview: " + small);
        Files.write(dir.resolve("preview_legibility.txt"), report, StandardCharsets.UTF_8);
        System.out.println("post-processed " + pngs.size() + " images into " + tiles + " tiles");
    }
}
