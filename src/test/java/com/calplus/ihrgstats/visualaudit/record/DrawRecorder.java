package com.calplus.ihrgstats.visualaudit.record;

import com.calplus.ihrgstats.utils.ImageRenderSupport;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Paint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Test-only draw-call recorder. {@link #enable()} installs it behind the
 * {@code ImageRenderSupport} seam; from then on every report image rendered
 * on any thread produces one {@link DrawRecord} (texts, icons, filled
 * rectangles, in the written PNG's coordinates). {@link #disable()} restores
 * production behaviour (no observer).
 */
public final class DrawRecorder implements ImageRenderSupport.DrawSurfaceObserver {

    private static final DrawRecorder INSTANCE = new DrawRecorder();

    private final ThreadLocal<Session> current = new ThreadLocal<>();
    private final List<DrawRecord> completed = Collections.synchronizedList(new ArrayList<>());

    private DrawRecorder() {}

    public static DrawRecorder enable() {
        ImageRenderSupport.setDrawSurfaceObserver(INSTANCE);
        return INSTANCE;
    }

    public static void disable() {
        ImageRenderSupport.setDrawSurfaceObserver(null);
    }

    /** Returns and clears every record completed so far. */
    public List<DrawRecord> drain() {
        synchronized (completed) {
            List<DrawRecord> out = new ArrayList<>(completed);
            completed.clear();
            return out;
        }
    }

    @Override
    public Graphics2D wrap(BufferedImage canvas, Graphics2D real, String generator) {
        Session s = new Session(canvas, generator);
        current.set(s);
        return new RecordingGraphics2D(real, s);
    }

    @Override
    public void imageWritten(BufferedImage written, Path file) {
        Session s = current.get();
        if (s == null) {
            throw new IllegalStateException("PNG written without a recorded canvas on this thread: " + file);
        }
        current.remove();
        if (written.getRaster().getDataBuffer() != s.canvas.getRaster().getDataBuffer()) {
            throw new IllegalStateException("written image is not the recorded canvas or a sub-image of it: " + file);
        }
        int ox = s.canvas.getRaster().getSampleModelTranslateX() - written.getRaster().getSampleModelTranslateX();
        int oy = s.canvas.getRaster().getSampleModelTranslateY() - written.getRaster().getSampleModelTranslateY();
        // Self-check of the origin arithmetic against the shared pixels.
        int[][] probes = {{0, 0}, {written.getWidth() - 1, written.getHeight() - 1}, {written.getWidth() / 2, written.getHeight() / 3}};
        for (int[] p : probes) {
            if (written.getRGB(p[0], p[1]) != s.canvas.getRGB(p[0] + ox, p[1] + oy)) {
                throw new IllegalStateException("crop origin self-check failed for " + file);
            }
        }
        DrawRecord r = s.record;
        r.file = file.getFileName().toString();
        try {
            r.fileBytes = Files.size(file);
        } catch (Exception e) {
            r.fileBytes = -1;
        }
        r.canvasWidth = s.canvas.getWidth();
        r.canvasHeight = s.canvas.getHeight();
        r.originX = ox;
        r.originY = oy;
        r.width = written.getWidth();
        r.height = written.getHeight();
        if (ox != 0 || oy != 0) {
            r.texts.forEach(t -> t.translate(-ox, -oy));
            r.icons.forEach(i -> i.translate(-ox, -oy));
            r.rects.forEach(x -> x.bounds.translate(-ox, -oy));
        }
        completed.add(r);
    }

    /** One canvas being drawn. */
    static final class Session {
        final BufferedImage canvas;
        final DrawRecord record = new DrawRecord();
        private int seq;

        Session(BufferedImage canvas, String generator) {
            this.canvas = canvas;
            record.generator = generator;
        }

        void text(String s, float x, float y, Graphics2D g) {
            AffineTransform tx = g.getTransform();
            DrawRecord.Text t = new DrawRecord.Text();
            t.seq = seq++;
            t.s = s == null ? "" : s;
            t.font = g.getFont();
            t.frc = g.getFontRenderContext();
            t.color = hex(g.getPaint());
            t.transformed = !translationOnly(tx);
            t.x = (float) (x + tx.getTranslateX());
            t.y = (float) (y + tx.getTranslateY());
            t.remeasure();
            t.intAdvance = g.getFontMetrics(t.font).stringWidth(t.s);
            record.texts.add(t);
        }

        void image(Image img, int x, int y, int w, int h, Graphics2D g) {
            AffineTransform tx = g.getTransform();
            DrawRecord.Icon icon = new DrawRecord.Icon();
            icon.seq = seq++;
            icon.transformed = !translationOnly(tx);
            Rectangle dest = tx.createTransformedShape(new Rectangle(x, y, w, h)).getBounds();
            icon.dest = dest;
            icon.drawW = w;
            icon.drawH = h;
            icon.srcW = img.getWidth(null);
            icon.srcH = img.getHeight(null);
            ImageInfo info = IconRegistry.info(img);
            icon.source = info.source;
            icon.sourceW = info.sourceW > 0 ? info.sourceW : icon.srcW;
            icon.sourceH = info.sourceH > 0 ? info.sourceH : icon.srcH;
            icon.translucent = info.maxAlpha < 128;
            if (info.opaque != null && !icon.transformed) {
                double sx = (double) w / icon.srcW;
                double sy = (double) h / icon.srcH;
                int ox0 = (int) Math.floor(info.opaque.x * sx);
                int oy0 = (int) Math.floor(info.opaque.y * sy);
                int ox1 = (int) Math.ceil((info.opaque.x + info.opaque.width) * sx);
                int oy1 = (int) Math.ceil((info.opaque.y + info.opaque.height) * sy);
                icon.opaque = new Rectangle(dest.x + ox0, dest.y + oy0, ox1 - ox0, oy1 - oy0);
            }
            record.icons.add(icon);
        }

        void rect(Rectangle bounds, Graphics2D g) {
            AffineTransform tx = g.getTransform();
            DrawRecord.Rect r = new DrawRecord.Rect();
            r.seq = seq++;
            r.transformed = !translationOnly(tx);
            r.bounds = tx.createTransformedShape(bounds).getBounds();
            r.color = hex(g.getPaint());
            record.rects.add(r);
        }

        void other(String what, Graphics2D g) {
            record.otherCalls.add(seq++ + ":" + what);
        }
    }

    static boolean translationOnly(AffineTransform tx) {
        return (tx.getType() & ~AffineTransform.TYPE_TRANSLATION) == 0;
    }

    static String hex(Paint p) {
        if (p instanceof Color c) {
            return String.format("#%02x%02x%02x%s", c.getRed(), c.getGreen(), c.getBlue(),
                    c.getAlpha() == 255 ? "" : String.format("/%d", c.getAlpha()));
        }
        return p == null ? "null" : p.getClass().getSimpleName();
    }

    /** What we know about a drawn image: recognised resource, its own size, opaque bounds. */
    record ImageInfo(String source, int sourceW, int sourceH, Rectangle opaque, int maxAlpha) {}

    /**
     * Recognises the images the generators draw by content hash: the raw
     * hall/outcome icons and the derived copies the product makes before
     * drawing (TableImageGenerator's 30x30 resize, OutcomeIconRenderer's
     * 24x24 resize, the 20%-alpha watermark copy) - so an icon resized off
     * canvas still reports the aspect ratio of the resource it came from.
     */
    static final class IconRegistry {
        private static final Map<Long, ImageInfo> byHash = new HashMap<>();
        private static final Map<Image, ImageInfo> byIdentity = Collections.synchronizedMap(new IdentityHashMap<>());
        private static boolean loaded;

        static synchronized ImageInfo info(Image img) {
            ImageInfo cached = byIdentity.get(img);
            if (cached != null) return cached;
            load();
            ImageInfo info;
            if (img instanceof BufferedImage bi) {
                long h = hash(bi);
                ImageInfo known = byHash.get(h);
                Rectangle opaque = opaqueBounds(bi);
                int maxAlpha = maxAlpha(bi);
                info = known != null
                        ? new ImageInfo(known.source, known.sourceW, known.sourceH, opaque, maxAlpha)
                        : new ImageInfo(null, 0, 0, opaque, maxAlpha);
            } else {
                info = new ImageInfo(null, 0, 0, null, 255);
            }
            if (byIdentity.size() > 5000) byIdentity.clear();
            byIdentity.put(img, info);
            return info;
        }

        private static void load() {
            if (loaded) return;
            loaded = true;
            for (String dir : new String[]{"halls", "icons"}) {
                URL url = DrawRecorder.class.getResource("/" + dir);
                if (url == null || !"file".equals(url.getProtocol())) continue;
                File[] files;
                try {
                    files = new File(url.toURI()).listFiles((d, n) -> n.endsWith(".png"));
                } catch (Exception e) {
                    continue;
                }
                if (files == null) continue;
                for (File f : files) {
                    try (InputStream in = Files.newInputStream(f.toPath())) {
                        BufferedImage raw = ImageIO.read(in);
                        if (raw == null) continue;
                        String name = dir + "/" + f.getName();
                        register(raw, name, raw.getWidth(), raw.getHeight());
                        if (dir.equals("halls")) {
                            register(resize(raw, 30, false), name, raw.getWidth(), raw.getHeight());
                            register(alphaCopy(raw), name, raw.getWidth(), raw.getHeight());
                        } else {
                            register(resize(raw, 24, true), name, raw.getWidth(), raw.getHeight());
                        }
                    } catch (Exception ignored) {
                        // unrecognised resources just stay unrecognised
                    }
                }
            }
        }

        private static void register(BufferedImage img, String name, int w, int h) {
            byHash.putIfAbsent(hash(img), new ImageInfo(name, w, h, null, 255));
        }

        /** Mirrors TableImageGenerator.loadHallIcon (30) and OutcomeIconRenderer.resizeIcon (24, +AA hint). */
        private static BufferedImage resize(BufferedImage src, int size, boolean antialias) {
            if (antialias && src.getWidth() == size && src.getHeight() == size) return src;
            BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            if (antialias) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, size, size, null);
            g.dispose();
            return out;
        }

        /** Mirrors ImageRenderSupport.tileIconWatermark's 20%-alpha copy. */
        private static BufferedImage alphaCopy(BufferedImage src) {
            BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.2f));
            g.drawImage(src, 0, 0, null);
            g.dispose();
            return out;
        }

        static long hash(BufferedImage img) {
            int w = img.getWidth(), h = img.getHeight();
            int[] px = img.getRGB(0, 0, w, h, null, 0, w);
            CRC32 crc = new CRC32();
            crc.update(w);
            crc.update(h);
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(px.length * 4);
            bb.asIntBuffer().put(px);
            crc.update(bb.array());
            return crc.getValue() ^ ((long) w << 40) ^ ((long) h << 52);
        }

        static Rectangle opaqueBounds(BufferedImage img) {
            int w = img.getWidth(), h = img.getHeight();
            boolean hasAlpha = img.getColorModel().hasAlpha();
            if (!hasAlpha) return new Rectangle(0, 0, w, h);
            int minX = w, minY = h, maxX = -1, maxY = -1;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if ((img.getRGB(x, y) >>> 24) >= 64) {
                        if (x < minX) minX = x;
                        if (y < minY) minY = y;
                        if (x > maxX) maxX = x;
                        if (y > maxY) maxY = y;
                    }
                }
            }
            return maxX < 0 ? null : new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
        }

        static int maxAlpha(BufferedImage img) {
            if (!img.getColorModel().hasAlpha()) return 255;
            int max = 0;
            int w = img.getWidth(), h = img.getHeight();
            for (int y = 0; y < h; y += 1) {
                for (int x = 0; x < w; x += 1) {
                    max = Math.max(max, img.getRGB(x, y) >>> 24);
                    if (max == 255) return 255;
                }
            }
            return max;
        }
    }
}
