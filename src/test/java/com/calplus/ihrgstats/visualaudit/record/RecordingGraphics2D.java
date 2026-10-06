package com.calplus.ihrgstats.visualaudit.record;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.ImageObserver;
import java.awt.image.RenderedImage;
import java.awt.image.renderable.RenderableImage;
import java.text.AttributedCharacterIterator;
import java.util.Map;

/**
 * A Graphics2D that forwards every call, unchanged and in order, to the
 * real Graphics2D of a report canvas and additionally reports text runs,
 * images and filled rectangles to a {@link DrawRecorder.Session}. Recording
 * only reads state (font, colour, transform, clip, render context) - it
 * never sets anything on the delegate, so the pixels are identical with the
 * recorder on or off (proved by RecorderPixelIdentityTest).
 */
final class RecordingGraphics2D extends Graphics2D {

    private final Graphics2D g;
    private final DrawRecorder.Session session;

    RecordingGraphics2D(Graphics2D delegate, DrawRecorder.Session session) {
        this.g = delegate;
        this.session = session;
    }

    // --- recorded calls ------------------------------------------------------

    @Override
    public void drawString(String str, int x, int y) {
        g.drawString(str, x, y);
        session.text(str, x, y, g);
    }

    @Override
    public void drawString(String str, float x, float y) {
        g.drawString(str, x, y);
        session.text(str, x, y, g);
    }

    @Override
    public void drawString(AttributedCharacterIterator iterator, int x, int y) {
        String s = text(iterator);
        g.drawString(iterator, x, y);
        session.text(s, x, y, g);
    }

    @Override
    public void drawString(AttributedCharacterIterator iterator, float x, float y) {
        String s = text(iterator);
        g.drawString(iterator, x, y);
        session.text(s, x, y, g);
    }

    @Override
    public void drawChars(char[] data, int offset, int length, int x, int y) {
        g.drawChars(data, offset, length, x, y);
        session.text(new String(data, offset, length), x, y, g);
    }

    @Override
    public void drawBytes(byte[] data, int offset, int length, int x, int y) {
        g.drawBytes(data, offset, length, x, y);
        session.text(new String(data, offset, length, java.nio.charset.StandardCharsets.ISO_8859_1), x, y, g);
    }

    @Override
    public void drawGlyphVector(GlyphVector gv, float x, float y) {
        g.drawGlyphVector(gv, x, y);
        session.other("drawGlyphVector@" + x + "," + y, g);
    }

    @Override
    public boolean drawImage(Image img, int x, int y, ImageObserver observer) {
        boolean r = g.drawImage(img, x, y, observer);
        session.image(img, x, y, img.getWidth(null), img.getHeight(null), g);
        return r;
    }

    @Override
    public boolean drawImage(Image img, int x, int y, int width, int height, ImageObserver observer) {
        boolean r = g.drawImage(img, x, y, width, height, observer);
        session.image(img, x, y, width, height, g);
        return r;
    }

    @Override
    public boolean drawImage(Image img, int x, int y, Color bgcolor, ImageObserver observer) {
        boolean r = g.drawImage(img, x, y, bgcolor, observer);
        session.image(img, x, y, img.getWidth(null), img.getHeight(null), g);
        return r;
    }

    @Override
    public boolean drawImage(Image img, int x, int y, int width, int height, Color bgcolor, ImageObserver observer) {
        boolean r = g.drawImage(img, x, y, width, height, bgcolor, observer);
        session.image(img, x, y, width, height, g);
        return r;
    }

    @Override
    public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2, ImageObserver observer) {
        boolean r = g.drawImage(img, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, observer);
        session.image(img, Math.min(dx1, dx2), Math.min(dy1, dy2), Math.abs(dx2 - dx1), Math.abs(dy2 - dy1), g);
        return r;
    }

    @Override
    public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2, Color bgcolor, ImageObserver observer) {
        boolean r = g.drawImage(img, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, bgcolor, observer);
        session.image(img, Math.min(dx1, dx2), Math.min(dy1, dy2), Math.abs(dx2 - dx1), Math.abs(dy2 - dy1), g);
        return r;
    }

    @Override
    public boolean drawImage(Image img, AffineTransform xform, ImageObserver obs) {
        boolean r = g.drawImage(img, xform, obs);
        session.other("drawImage(xform)", g);
        return r;
    }

    @Override
    public void drawImage(BufferedImage img, BufferedImageOp op, int x, int y) {
        g.drawImage(img, op, x, y);
        session.other("drawImage(op)", g);
    }

    @Override
    public void drawRenderedImage(RenderedImage img, AffineTransform xform) {
        g.drawRenderedImage(img, xform);
        session.other("drawRenderedImage", g);
    }

    @Override
    public void drawRenderableImage(RenderableImage img, AffineTransform xform) {
        g.drawRenderableImage(img, xform);
        session.other("drawRenderableImage", g);
    }

    @Override
    public void fillRect(int x, int y, int width, int height) {
        g.fillRect(x, y, width, height);
        session.rect(new Rectangle(x, y, width, height), g);
    }

    @Override
    public void fill(Shape s) {
        g.fill(s);
        session.rect(s.getBounds(), g);
    }

    @Override
    public void clearRect(int x, int y, int width, int height) {
        g.clearRect(x, y, width, height);
        session.other("clearRect", g);
    }

    @Override public void draw(Shape s) { g.draw(s); session.other("draw(Shape)", g); }
    @Override public void drawLine(int x1, int y1, int x2, int y2) { g.drawLine(x1, y1, x2, y2); session.other("drawLine", g); }
    @Override public void drawRect(int x, int y, int width, int height) { g.drawRect(x, y, width, height); session.other("drawRect", g); }
    @Override public void draw3DRect(int x, int y, int width, int height, boolean raised) { g.draw3DRect(x, y, width, height, raised); session.other("draw3DRect", g); }
    @Override public void fill3DRect(int x, int y, int width, int height, boolean raised) { g.fill3DRect(x, y, width, height, raised); session.other("fill3DRect", g); }
    @Override public void drawRoundRect(int x, int y, int w, int h, int aw, int ah) { g.drawRoundRect(x, y, w, h, aw, ah); session.other("drawRoundRect", g); }
    @Override public void fillRoundRect(int x, int y, int w, int h, int aw, int ah) { g.fillRoundRect(x, y, w, h, aw, ah); session.other("fillRoundRect", g); }
    @Override public void drawOval(int x, int y, int w, int h) { g.drawOval(x, y, w, h); session.other("drawOval", g); }
    @Override public void fillOval(int x, int y, int w, int h) { g.fillOval(x, y, w, h); session.other("fillOval", g); }
    @Override public void drawArc(int x, int y, int w, int h, int sa, int aa) { g.drawArc(x, y, w, h, sa, aa); session.other("drawArc", g); }
    @Override public void fillArc(int x, int y, int w, int h, int sa, int aa) { g.fillArc(x, y, w, h, sa, aa); session.other("fillArc", g); }
    @Override public void drawPolyline(int[] xs, int[] ys, int n) { g.drawPolyline(xs, ys, n); session.other("drawPolyline", g); }
    @Override public void drawPolygon(int[] xs, int[] ys, int n) { g.drawPolygon(xs, ys, n); session.other("drawPolygon", g); }
    @Override public void drawPolygon(Polygon p) { g.drawPolygon(p); session.other("drawPolygon", g); }
    @Override public void fillPolygon(int[] xs, int[] ys, int n) { g.fillPolygon(xs, ys, n); session.other("fillPolygon", g); }
    @Override public void fillPolygon(Polygon p) { g.fillPolygon(p); session.other("fillPolygon", g); }
    @Override public void copyArea(int x, int y, int w, int h, int dx, int dy) { g.copyArea(x, y, w, h, dx, dy); session.other("copyArea", g); }

    // --- pure delegation -----------------------------------------------------

    @Override public Graphics create() { return new RecordingGraphics2D((Graphics2D) g.create(), session); }
    @Override public Graphics create(int x, int y, int width, int height) { return new RecordingGraphics2D((Graphics2D) g.create(x, y, width, height), session); }
    @Override public boolean hit(Rectangle rect, Shape s, boolean onStroke) { return g.hit(rect, s, onStroke); }
    @Override public GraphicsConfiguration getDeviceConfiguration() { return g.getDeviceConfiguration(); }
    @Override public void setComposite(Composite comp) { g.setComposite(comp); }
    @Override public void setPaint(Paint paint) { g.setPaint(paint); }
    @Override public void setStroke(Stroke s) { g.setStroke(s); }
    @Override public void setRenderingHint(RenderingHints.Key hintKey, Object hintValue) { g.setRenderingHint(hintKey, hintValue); }
    @Override public Object getRenderingHint(RenderingHints.Key hintKey) { return g.getRenderingHint(hintKey); }
    @Override public void setRenderingHints(Map<?, ?> hints) { g.setRenderingHints(hints); }
    @Override public void addRenderingHints(Map<?, ?> hints) { g.addRenderingHints(hints); }
    @Override public RenderingHints getRenderingHints() { return g.getRenderingHints(); }
    @Override public void translate(int x, int y) { g.translate(x, y); }
    @Override public void translate(double tx, double ty) { g.translate(tx, ty); }
    @Override public void rotate(double theta) { g.rotate(theta); }
    @Override public void rotate(double theta, double x, double y) { g.rotate(theta, x, y); }
    @Override public void scale(double sx, double sy) { g.scale(sx, sy); }
    @Override public void shear(double shx, double shy) { g.shear(shx, shy); }
    @Override public void transform(AffineTransform tx) { g.transform(tx); }
    @Override public void setTransform(AffineTransform tx) { g.setTransform(tx); }
    @Override public AffineTransform getTransform() { return g.getTransform(); }
    @Override public Paint getPaint() { return g.getPaint(); }
    @Override public Composite getComposite() { return g.getComposite(); }
    @Override public void setBackground(Color color) { g.setBackground(color); }
    @Override public Color getBackground() { return g.getBackground(); }
    @Override public Stroke getStroke() { return g.getStroke(); }
    @Override public void clip(Shape s) { g.clip(s); }
    @Override public FontRenderContext getFontRenderContext() { return g.getFontRenderContext(); }
    @Override public Color getColor() { return g.getColor(); }
    @Override public void setColor(Color c) { g.setColor(c); }
    @Override public void setPaintMode() { g.setPaintMode(); }
    @Override public void setXORMode(Color c1) { g.setXORMode(c1); }
    @Override public Font getFont() { return g.getFont(); }
    @Override public void setFont(Font font) { g.setFont(font); }
    @Override public FontMetrics getFontMetrics() { return g.getFontMetrics(); }
    @Override public FontMetrics getFontMetrics(Font f) { return g.getFontMetrics(f); }
    @Override public Rectangle getClipBounds() { return g.getClipBounds(); }
    @Override public Rectangle getClipBounds(Rectangle r) { return g.getClipBounds(r); }
    @Override public boolean hitClip(int x, int y, int width, int height) { return g.hitClip(x, y, width, height); }
    @Override public void clipRect(int x, int y, int width, int height) { g.clipRect(x, y, width, height); }
    @Override public void setClip(int x, int y, int width, int height) { g.setClip(x, y, width, height); }
    @Override public Shape getClip() { return g.getClip(); }
    @Override public void setClip(Shape clip) { g.setClip(clip); }
    @Override public void dispose() { g.dispose(); }
    @Override public String toString() { return "Recording[" + g + "]"; }

    private static String text(AttributedCharacterIterator it) {
        StringBuilder sb = new StringBuilder();
        for (char c = it.first(); c != AttributedCharacterIterator.DONE; c = it.next()) {
            sb.append(c);
        }
        it.first();
        return sb.toString();
    }
}
