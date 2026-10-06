package com.calplus.ihrgstats.visualaudit.record;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Test utilities for image inspection:
 * <ul>
 *   <li>{@link #tile} cuts a render into overlapping tiles no larger than
 *       {@link #TILE_W} x {@link #TILE_H} - small enough that an inspector
 *       (human or model) sees them at 1:1 without any downscaling;</li>
 *   <li>{@link #phonePreview} simulates what Telegram delivers for a photo:
 *       the long edge scaled down to 1280 px (never up), area-averaged, as
 *       a JPEG-free PNG so only the scaling loss is shown.</li>
 * </ul>
 */
public final class ImageTiler {

    public static final int TILE_W = 800;
    public static final int TILE_H = 600;
    public static final int OVERLAP = 48;
    public static final int TELEGRAM_LONG_EDGE = 1280;

    private ImageTiler() {}

    /** Writes {@code <name>_r<row>c<col>.png} tiles and returns their paths (row-major). */
    public static List<Path> tile(Path png, Path outDir) throws Exception {
        BufferedImage img = ImageIO.read(png.toFile());
        String base = png.getFileName().toString().replaceFirst("\\.png$", "");
        Files.createDirectories(outDir);
        List<Path> out = new ArrayList<>();
        int stepX = TILE_W - OVERLAP, stepY = TILE_H - OVERLAP;
        int rows = img.getHeight() <= TILE_H ? 1 : (int) Math.ceil((double) (img.getHeight() - OVERLAP) / stepY);
        int cols = img.getWidth() <= TILE_W ? 1 : (int) Math.ceil((double) (img.getWidth() - OVERLAP) / stepX);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int x = Math.min(c * stepX, Math.max(0, img.getWidth() - TILE_W));
                int y = Math.min(r * stepY, Math.max(0, img.getHeight() - TILE_H));
                int w = Math.min(TILE_W, img.getWidth() - x);
                int h = Math.min(TILE_H, img.getHeight() - y);
                BufferedImage t = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = t.createGraphics();
                g.drawImage(img.getSubimage(x, y, w, h), 0, 0, null);
                g.dispose();
                Path p = outDir.resolve(String.format("%s_r%02dc%02d_x%d_y%d.png", base, r, c, x, y));
                ImageIO.write(t, "PNG", p.toFile());
                out.add(p);
            }
        }
        return out;
    }

    /** Writes {@code <name>_preview1280.png}: long edge 1280 px if larger (Telegram's photo size), else unchanged. */
    public static Path phonePreview(Path png, Path outDir) throws Exception {
        BufferedImage img = ImageIO.read(png.toFile());
        int longEdge = Math.max(img.getWidth(), img.getHeight());
        double scale = longEdge > TELEGRAM_LONG_EDGE ? (double) TELEGRAM_LONG_EDGE / longEdge : 1.0;
        int w = Math.max(1, (int) Math.round(img.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(img.getHeight() * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(img.getScaledInstance(w, h, Image.SCALE_AREA_AVERAGING), 0, 0, null);
        g.dispose();
        Files.createDirectories(outDir);
        Path p = outDir.resolve(png.getFileName().toString().replaceFirst("\\.png$", "") + "_preview1280.png");
        ImageIO.write(out, "PNG", p.toFile());
        return p;
    }

    /** Effective text height in the preview for a glyph of {@code px} pixels in the render. */
    public static double previewScale(int width, int height) {
        int longEdge = Math.max(width, height);
        return longEdge > TELEGRAM_LONG_EDGE ? (double) TELEGRAM_LONG_EDGE / longEdge : 1.0;
    }
}
